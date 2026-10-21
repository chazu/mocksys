(ns mocksys.grpc-record-test
  "Recording a gRPC upstream: a grpc imposter whose stub is a proxyOnce proxy
   forwards each call upstream with grpc-js, answers with what came back, and saves
   it as a stub that replays the same answer. The upstream here is another grpc
   imposter on the same daemon."
  (:require [clojure.test :refer [deftest testing is async]]
            [clojure.string :as str]
            [promesa.core :as p]
            ["node:path" :as path]
            [mocksys.contract :as contract]
            [mocksys.proto :as proto]
            [mocksys.grpc-fixture :as fx]))

(def ^:private codes fx/status)

(defn- on [rpc preds & responses]
  {"predicates" (into [{"deepEquals" {"path" (str "/" rpc)}}] preds)
   "responses"  (vec responses)})

(defn- by-key [k] {"equals" {"body" k} "jsonpath" {"selector" "$.key"}})

(def ^:private upstream-stubs
  [(on "kv.Store/Get" [(by-key "a")]
       {"is" {"statusCode" 200 "headers" {"x-upstream" "yes"} "body" {"key" "a" "value" "apple" "version" "9007199254740993"}}})
   (on "kv.Store/Watch" []
       {"is" {"statusCode" 200 "body" [{"key" "w1"} {"key" "w2"}]}})
   (on "kv.Store/PutMany" []
       {"is" {"statusCode" 200 "body" [{"key" "stored"}]}})])

(defn- recorder [descriptor upstream-port port]
  {"protocol" "grpc" "port" port "name" "t/rec" "descriptor" descriptor
   "stubs" [{"responses" [{"proxy" {"to" (str "http://127.0.0.1:" upstream-port) "mode" "proxyOnce"
                                    "predicateGenerators" [{"matches" {"method" true "path" true}}]}}]}]})

(defn- upstream-paths [admin port]
  (p/let [full (fx/admin-call (str admin "/imposters/" port) "GET" nil)]
    (mapv #(get % "path") (get-in full [:json "requests"]))))

(deftest a-grpc-proxy-records-what-the-upstream-answers
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [dir   (fx/kv-dir)
                desc  (:descriptor (proto/read-proto (path/join dir "kv.proto")))
                up    (fx/free-port)
                port  (fx/free-port)
                _     (fx/admin-call (str admin "/imposters") "POST"
                                     {"protocol" "grpc" "port" up "name" "t/up" "recordRequests" true
                                      "descriptor" desc "stubs" upstream-stubs})
                made  (fx/admin-call (str admin "/imposters") "POST" (recorder desc up port))
                c     (fx/connect (path/join dir "kv.proto") "kv.Store" port)
                hit   (fx/unary c "Get" {:key "a"} {"authorization" "Bearer t"})
                watch (fx/server-stream c "Watch" {:key "x"})
                many  (fx/client-stream c "PutMany" [{:key "p"} {:key "q"}])
                sync  (let [{:keys [call done]} (fx/bidi c "Sync")] (fx/send! call {:key "s"}) (.end call) done)
                seen  (fx/admin-call (str admin "/imposters/" up) "GET" nil)
                again (fx/unary c "Get" {:key "a"})
                after (upstream-paths admin up)
                saved (fx/admin-call (str admin "/imposters/" port "?replayable=true&removeProxies=true") "GET" nil)]
          (fx/close c)
          (is (= 201 (:status made)) (pr-str (:json made)))
          (testing "each call is answered with what the upstream answered"
            (is (= {"key" "a" "value" "apple" "version" "9007199254740993"}
                   (select-keys (:ok hit) ["key" "value" "version"])))
            (is (= "yes" (get-in hit [:metadata "x-upstream"])) "and its metadata")
            (is (= ["w1" "w2"] (mapv #(get % "key") (:messages watch))))
            (is (= ["stored"] (mapv #(get % "key") (get-in many [:ok "items"])))))
          (testing "the upstream gets the call's message and metadata"
            (let [first-get (first (get-in seen [:json "requests"]))]
              (is (= {"key" "a"} (js->clj (js/JSON.parse (get first-get "body")))))
              (is (= "Bearer t" (get-in first-get ["headers" "authorization"])))))
          (testing "a bidirectional stream is not recorded, and says so"
            (is (= (codes "UNIMPLEMENTED") (:code sync)))
            (is (str/includes? (str (:details sync)) "bidirectional") (:details sync)))
          (testing "a repeat replays without going upstream"
            (is (= "apple" (get-in again [:ok "value"])))
            (is (= ["/kv.Store/Get" "/kv.Store/Watch" "/kv.Store/PutMany"] after)))
          (testing "the saved stubs carry the status as HTTP and the code as grpcCode"
            (let [stubs (get-in saved [:json "stubs"])
                  is-of (fn [rpc] (some #(when (= {"deepEquals" {"path" (str "/" rpc)}} (second (get % "predicates")))
                                           (get-in % ["responses" 0 "is"]))
                                        stubs))]
              (is (= 3 (count stubs)) "one per rpc called: proxyOnce keys on method and path")
              (is (= [200 {"key" "a" "value" "apple" "version" "9007199254740993"}]
                     ((juxt #(get % "statusCode") #(select-keys (get % "body") ["key" "value" "version"])) (is-of "kv.Store/Get"))))
              (is (= [{"key" "w1"} {"key" "w2"}] (mapv #(select-keys % ["key"]) (get (is-of "kv.Store/Watch") "body")))
                  "a server stream's messages are the body's array"))))))))

(deftest a-recorded-grpc-error-replays-its-code
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [dir  (fx/kv-dir)
                desc (:descriptor (proto/read-proto (path/join dir "kv.proto")))
                up   (fx/free-port)
                port (fx/free-port)
                _    (fx/admin-call (str admin "/imposters") "POST"
                                    {"protocol" "grpc" "port" up "name" "t/up" "descriptor" desc
                                     "stubs" [(on "kv.Store/Get" []
                                                  {"is" {"statusCode" 400 "body" {"grpcCode" "FAILED_PRECONDITION" "message" "not yet"}}})]})
                _    (fx/admin-call (str admin "/imposters") "POST" (recorder desc up port))
                c    (fx/connect (path/join dir "kv.proto") "kv.Store" port)
                _    (fx/unary c "Get" {:key "a"})
                _    (fx/close c)
                saved  (fx/admin-call (str admin "/imposters/" port "?replayable=true&removeProxies=true") "GET" nil)
                replay (fx/free-port)
                _      (fx/admin-call (str admin "/imposters") "POST"
                                      (assoc (:json saved) "port" replay "name" "t/replay"))
                r      (fx/connect (path/join dir "kv.proto") "kv.Store" replay)
                got    (fx/unary r "Get" {:key "a"})]
          (fx/close r)
          (is (= {"statusCode" 400 "body" {"grpcCode" "FAILED_PRECONDITION" "message" "not yet"}}
                 (select-keys (get-in saved [:json "stubs" 0 "responses" 0 "is"]) ["statusCode" "body"])))
          (is (= [(codes "FAILED_PRECONDITION") "not yet"] [(:code got) (:details got)])
              "the saved imposter replays the upstream's code"))))))

(deftest an-upstream-that-does-not-answer-is-not-recorded
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [dir   (fx/kv-dir)
                desc  (:descriptor (proto/read-proto (path/join dir "kv.proto")))
                down  (fx/free-port)
                port  (fx/free-port)
                _     (fx/admin-call (str admin "/imposters") "POST" (recorder desc down port))
                c     (fx/connect (path/join dir "kv.proto") "kv.Store" port)
                start (js/Date.now)
                got   (fx/unary c "Get" {:key "a"})
                took  (- (js/Date.now) start)
                _     (fx/close c)
                saved (fx/admin-call (str admin "/imposters/" port "?replayable=true&removeProxies=true") "GET" nil)]
          (is (= [(codes "UNAVAILABLE") (str "Unable to connect to \"http://127.0.0.1:" down "\"")]
                 [(:code got) (:details got)]))
          (is (< took 2000) "without waiting out a timeout")
          (is (= [] (get-in saved [:json "stubs"])) "and nothing is saved"))))))

(deftest a-recorded-grpc-imposter-lifts-to-a-grpc-contract
  (let [imp {"protocol" "grpc" "descriptor" {}
             "stubs" [{"predicates" [{"deepEquals" {"method" "POST"}} {"deepEquals" {"path" "/kv.Store/Get"}}]
                       "responses"  [{"is" {"statusCode" 200 "body" {"key" "a"}}}]}]}
        c   (contract/lift imp "t/rec" "kv")]
    (is (= "grpc" (get c "protocol")))
    (is (= {"rpc" "kv.Store/Get"} (get-in c ["operations" 0 "request"])))
    (is (= [{"deepEquals" {"method" "POST"}} {"deepEquals" {"path" "/kv.Store/Get"}}]
           (get-in (contract/lower c 1) ["stubs" 0 "predicates"]))
        "and lowers back to the predicates it was recorded with")))
