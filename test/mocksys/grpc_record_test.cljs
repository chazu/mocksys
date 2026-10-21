(ns mocksys.grpc-record-test
  "Recording a gRPC upstream: a grpc imposter whose stub is a proxyOnce proxy
   forwards each call upstream with grpc-js, answers with what came back, and saves
   it as a stub that replays the same answer. The upstream here is another grpc
   imposter on the same daemon."
  (:require [clojure.test :refer [deftest testing is async]]
            [clojure.string :as str]
            [promesa.core :as p]
            ["node:path" :as path]
            ["@grpc/grpc-js" :as grpc]
            ["@grpc/proto-loader" :as loader]
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

(def ^:private hanging
  "Handlers whose Get never answers and whose Watch sends one message and never ends."
  {:Get   (fn [_ _])
   :Watch (fn [^js call] (.write call #js {:key "w1"}))})

(defn- raw-upstream
  "A grpc-js server for kv.Store on `port`, its methods answered by `handlers`
   ({:Method fn}). Resolves {:server :calls}, where `calls` is an atom of {:rpc
   :deadline :cancelled (a promise of the cancel)} for each call."
  [dir port handlers]
  (let [def    (.loadSync loader (path/join dir "kv.proto") #js {:keepCase true :includeDirs #js [dir]})
        svc    (.. (.loadPackageDefinition grpc def) -kv -Store -service)
        calls  (atom [])
        note!  (fn [rpc ^js call]
                 (swap! calls conj {:rpc rpc :deadline (js/Number (.getDeadline call))
                                    :cancelled (js/Promise. (fn [resolve] (.on call "cancelled" #(resolve true))))}))
        server (grpc/Server.)]
    (.addService server svc (clj->js (into {} (for [[k f] handlers]
                                                [k (fn [call & more] (note! (name k) call) (apply f call more))]))))
    (js/Promise.
     (fn [resolve reject]
       (.bindAsync server (str "127.0.0.1:" port) (.createInsecure grpc/ServerCredentials)
                   (fn [err _] (if err (reject err) (resolve {:server server :calls calls}))))))))

(defn- within [ms pr] (js/Promise.race #js [pr (p/delay ms :timeout)]))

(defn- nth-call
  "Resolve the `n`th call `calls` notes (from 0), polling, or :timeout after 2s."
  [calls n]
  (within 2000 (p/loop [] (if (> (count @calls) n) (nth @calls n) (p/do (p/delay 10) (p/recur))))))

(deftest a-call-the-client-gives-up-on-is-cancelled-upstream
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [dir   (fx/kv-dir)
                desc  (:descriptor (proto/read-proto (path/join dir "kv.proto")))
                up    (fx/free-port)
                port  (fx/free-port)
                {:keys [server calls]} (raw-upstream dir up hanging)
                _     (fx/admin-call (str admin "/imposters") "POST" (recorder desc up port))
                ^js c (fx/connect (path/join dir "kv.proto") "kv.Store" port)
                ^js w (.Watch c #js {:key "x"})
                _     (.on w "error" (fn [_]))
                watch (nth-call calls 0)
                _     (.cancel w)
                wgone (within 2000 (:cancelled watch))
                late  (fx/unary c "Get" {:key "a"} {} {:deadline-ms 300})
                get1  (nth-call calls 1)
                ggone (within 500 (:cancelled get1))
                saved (fx/admin-call (str admin "/imposters/" port "?replayable=true&removeProxies=true") "GET" nil)]
          (fx/close c)
          (.forceShutdown ^js server)
          (is (= true wgone) "a stream the client cancels is cancelled upstream")
          (is (= (codes "DEADLINE_EXCEEDED") (:code late)))
          (is (< (:deadline get1) (+ (js/Date.now) 2000)) "the client's deadline goes upstream")
          (is (= true ggone) "and a call past it is cancelled upstream at once, not at the upstream's own deadline")
          (is (= [] (get-in saved [:json "stubs"])) "and neither is saved"))))))

(deftest a-stream-that-fails-partway-is-recorded-with-what-it-sent
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [dir    (fx/kv-dir)
                desc   (:descriptor (proto/read-proto (path/join dir "kv.proto")))
                up     (fx/free-port)
                port   (fx/free-port)
                {:keys [server]} (raw-upstream dir up {:Watch (fn [^js call]
                                                                (.write call #js {:key "w1"})
                                                                (.write call #js {:key "w2"})
                                                                (.emit call "error" #js {:code (codes "NOT_FOUND") :details "gone"}))})
                _      (fx/admin-call (str admin "/imposters") "POST" (recorder desc up port))
                c      (fx/connect (path/join dir "kv.proto") "kv.Store" port)
                live   (fx/server-stream c "Watch" {:key "x"})
                _      (fx/close c)
                _      (.forceShutdown ^js server)
                saved  (fx/admin-call (str admin "/imposters/" port "?replayable=true&removeProxies=true") "GET" nil)
                replay (fx/free-port)
                _      (fx/admin-call (str admin "/imposters") "POST" (assoc (:json saved) "port" replay "name" "t/replay"))
                r      (fx/connect (path/join dir "kv.proto") "kv.Store" replay)
                again  (fx/server-stream r "Watch" {:key "x"})]
          (fx/close r)
          (let [shape (juxt #(mapv (fn [m] (get m "key")) (:messages %)) :code :details)]
            (is (= [["w1" "w2"] (codes "NOT_FOUND") "gone"] (shape live)) "the client gets the messages, then the error")
            (is (= {"statusCode" 404 "body" {"grpcCode" "NOT_FOUND" "message" "gone" "messages" [{"key" "w1"} {"key" "w2"}]}}
                   (-> (get-in saved [:json "stubs" 0 "responses" 0 "is"])
                       (select-keys ["statusCode" "body"])
                       (update-in ["body" "messages"] #(mapv (fn [m] (select-keys m ["key"])) %)))))
            (is (= (shape live) (shape again)) "and the replay sends the same")))))))

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
