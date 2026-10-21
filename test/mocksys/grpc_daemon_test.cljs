(ns mocksys.grpc-daemon-test
  "gRPC imposters on the real daemon, called with a real grpc-js client: a call is
   matched as POST /pkg.Service/Method with metadata as headers and the message as
   a JSON body; replies, status codes, response metadata, UNIMPLEMENTED for what
   is not served, and a stateful contract over a collection."
  (:require [clojure.test :refer [deftest testing is async]]
            [clojure.string :as str]
            [promesa.core :as p]
            ["node:net" :as net]
            ["node:path" :as path]
            [mocksys.runtime :as runtime]
            [mocksys.contract :as contract]
            [mocksys.proto :as proto]
            [mocksys.grpc-fixture :as fx]))

(defn- free-port []
  (js/Promise.
   (fn [resolve]
     (let [srv (.createServer net)]
       (.listen srv 0 (fn [] (let [port (.-port (.address srv))] (.close srv #(resolve port)))))))))

(defn- admin-call [url method body]
  (p/let [res  (js/fetch url (clj->js (cond-> {:method method :headers {"Content-Type" "application/json"}}
                                         body (assoc :body (js/JSON.stringify (clj->js body))))))
          text (.text res)]
    {:status (.-status res) :json (try (js->clj (js/JSON.parse text)) (catch :default _ nil))}))

(defn- with-daemon [done f]
  (p/let [d (runtime/start! {:host "127.0.0.1" :port 0})]
    (-> (p/do (f (str "http://127.0.0.1:" (:port d))))
        (p/catch (fn [e] (is (nil? e) (str "unexpected rejection: " (ex-message e)))))
        (p/finally (fn [] (p/do ((:stop! d)) (done)))))))

(defn- kv-imposter [dir port stubs]
  {"protocol" "grpc" "port" port "name" "t/kv" "recordRequests" true
   "descriptor" (:descriptor (proto/read-proto (path/join dir "kv.proto")))
   "stubs" stubs})

(defn- stub [rpc preds is] {"predicates" (into [{"deepEquals" {"path" (str "/" rpc)}}] preds)
                            "responses" [{"is" is}]})

(def ^:private codes fx/status)

(deftest a-unary-call-is-matched-and-answered
  (async done
    (with-daemon done
      (fn [admin]
        (p/let [dir  (fx/kv-dir)
                port (free-port)
                stubs [(stub "kv.Store/Get" [{"equals" {"body" "a"} "jsonpath" {"selector" "$.key"}}]
                             {"statusCode" 200 "headers" {"x-trace" "t-1"}
                              "body" {"key" "a" "value" "apple" "version" "7" "kind" "SECRET"}})
                       (stub "kv.Store/Put" [{"equals" {"headers" {"authorization" "Bearer t"}}}]
                             {"statusCode" 200 "body" {"key" "authorized"}})
                       (stub "kv.Store/List" [] {"statusCode" 200 "body" [{"key" "a"} {"key" "b"}]})
                       (stub "kv.Store/Get" [{"equals" {"body" "teapot"} "jsonpath" {"selector" "$.key"}}]
                             {"statusCode" 200 "body" "not json"})]
                made (admin-call (str admin "/imposters") "POST" (kv-imposter dir port stubs))
                c    (fx/connect (path/join dir "kv.proto") "kv.Store" port)
                hit  (fx/unary c "Get" {:key "a"} {"authorization" "Bearer t"})
                miss (fx/unary c "Get" {:key "zzz"})
                put  (fx/unary c "Put" {:key "x"} {"authorization" "Bearer t"})
                anon (fx/unary c "Put" {:key "x"})
                lst  (fx/unary c "List" {})
                bad  (fx/unary c "Get" {:key "teapot"})
                full (admin-call (str admin "/imposters/" port) "GET" nil)]
          (fx/close c)
          (is (= 201 (:status made)) (pr-str (:json made)))
          (testing "the reply encodes the stub's JSON body: int64 as a string, enums by name"
            (is (= {"key" "a" "value" "apple" "version" "7" "kind" "SECRET"} (:ok hit))))
          (testing "response headers arrive as metadata"
            (is (= "t-1" (get-in hit [:metadata "x-trace"]))))
          (testing "an unmatched call gets the empty reply an unmatched HTTP request gets"
            (is (= {"key" "" "value" "" "version" "0" "kind" "PLAIN"} (:ok miss))))
          (testing "metadata is matched as headers"
            (is (= "authorized" (get-in put [:ok "key"])))
            (is (= "" (get-in anon [:ok "key"]))))
          (testing "a JSON array body fills the reply's one repeated field"
            (is (= ["a" "b"] (map #(get % "key") (get-in lst [:ok "items"])))))
          (testing "a 2xx body that is not a JSON object is INTERNAL"
            (is (= (codes "INTERNAL") (:code bad)))
            (is (str/includes? (str (:details bad)) "not a JSON object")))
          (testing "requests are recorded in the shape the stubs match on"
            (let [r (first (get-in full [:json "requests"]))]
              (is (= "POST" (get r "method")))
              (is (= "/kv.Store/Get" (get r "path")))
              (is (= "a" (get (js->clj (js/JSON.parse (get r "body"))) "key")))
              (is (= "Bearer t" (get-in r ["headers" "authorization"]))))))))))

(deftest non-2xx-statuses-become-grpc-statuses
  (async done
    (with-daemon done
      (fn [admin]
        (p/let [dir  (fx/kv-dir)
                port (free-port)
                by-key (fn [k is] (stub "kv.Store/Get" [{"equals" {"body" k} "jsonpath" {"selector" "$.key"}}] is))
                stubs [(by-key "named"   {"statusCode" 400 "body" {"grpcCode" "FAILED_PRECONDITION" "message" "not yet"}})
                       (by-key "numbered" {"statusCode" 500 "body" {"grpcCode" 10 "message" "try again"}})
                       (by-key "missing" {"statusCode" 404 "body" {"message" "no such key"}})
                       (by-key "limited" {"statusCode" 429})
                       (by-key "teapot"  {"statusCode" 418 "body" "short and stout"})]
                _    (admin-call (str admin "/imposters") "POST" (kv-imposter dir port stubs))
                c    (fx/connect (path/join dir "kv.proto") "kv.Store" port)
                rs   (p/all (map #(fx/unary c "Get" {:key %}) ["named" "numbered" "missing" "limited" "teapot"]))]
          (fx/close c)
          (let [[named numbered missing limited teapot] rs]
            (testing "{grpcCode, message} picks the code by name or number"
              (is (= [(codes "FAILED_PRECONDITION") "not yet"] [(:code named) (:details named)]))
              (is (= [(codes "ABORTED") "try again"] [(:code numbered) (:details numbered)])))
            (testing "otherwise the HTTP status maps through the table"
              (is (= [(codes "NOT_FOUND") "no such key"] [(:code missing) (:details missing)]))
              (is (= (codes "RESOURCE_EXHAUSTED") (:code limited)))
              (is (= "HTTP 429" (:details limited)))
              (is (= [(codes "UNKNOWN") "short and stout"] [(:code teapot) (:details teapot)])))))))))

(deftest what-is-not-served-is-unimplemented
  (async done
    (with-daemon done
      (fn [admin]
        (p/let [dir    (fx/kv-dir)
                port   (free-port)
                _      (admin-call (str admin "/imposters") "POST" (kv-imposter dir port []))
                c      (fx/connect (path/join dir "kv.proto") "kv.Store" port)
                nope   (fx/raw-unary port "/kv.Store/Nope")
                stream (fx/streaming c "Watch" {:key "a"})]
          (fx/close c)
          (is (= (codes "UNIMPLEMENTED") (:code nope)) "a method the proto does not declare")
          (is (= (codes "UNIMPLEMENTED") (:code stream)) "a streaming method"))))))

(deftest faults-and-deletion
  (async done
    (with-daemon done
      (fn [admin]
        (p/let [dir   (fx/kv-dir)
                port  (free-port)
                _     (admin-call (str admin "/imposters") "POST"
                                  (kv-imposter dir port [{"predicates" [] "responses" [{"fault" "CONNECTION_RESET_BY_PEER"}]}]))
                c     (fx/connect (path/join dir "kv.proto") "kv.Store" port)
                fault (fx/unary c "Get" {:key "a"})
                del   (admin-call (str admin "/imposters/" port) "DELETE" nil)
                gone  (fx/raw-unary port "/kv.Store/Get")]
          (fx/close c)
          (is (= (codes "UNAVAILABLE") (:code fault)) "a fault drops the call as UNAVAILABLE")
          (is (= 200 (:status del)))
          (is (= "grpc" (get-in del [:json "protocol"])))
          (is (= (codes "UNAVAILABLE") (:code gone)) "a deleted imposter no longer listens"))))))

(deftest grpc-imposters-are-validated
  (async done
    (with-daemon done
      (fn [admin]
        (p/let [dir     (fx/kv-dir)
                port    (free-port)
                no-desc (admin-call (str admin "/imposters") "POST" (dissoc (kv-imposter dir port []) "descriptor"))
                junk    (admin-call (str admin "/imposters") "POST" (assoc (kv-imposter dir port []) "descriptor"
                                                                    {"nested" {"S" {"methods" {"M" {"requestType" "Gone" "responseType" "Gone"}}}}}))
                on-http (admin-call (str admin "/imposters") "POST" (assoc (kv-imposter dir port []) "protocol" "http"))
                listed  (admin-call (str admin "/imposters") "GET" nil)]
          (is (= [400 "protocol grpc needs a descriptor"]
                 [(:status no-desc) (get-in no-desc [:json "errors" 0 "message"])]))
          (is (= 400 (:status junk)))
          (is (str/starts-with? (str (get-in junk [:json "errors" 0 "message"])) "descriptor: "))
          (is (= [400 "unsupported field(s) descriptor"]
                 [(:status on-http) (get-in on-http [:json "errors" 0 "message"])]))
          (is (= [] (get-in listed [:json "imposters"])) "no rejected imposter is left registered"))))))

(def ^:private kv-contract
  {"scenario" "t/kv" "protocol" "grpc" "proto" "./kv.proto"
   "state" {"collections" {"items" {"idField" "key"}}}
   "operations"
   [{"id" "put" "request" {"rpc" "kv.Store/Put"}
     "effect" {"bind" {"incoming" {"bodyJson" true}}
               "create" {"collection" "items" "merge" ["incoming"] "body" {} "status" 200}}}
    {"id" "get" "request" {"rpc" "kv.Store/Get"}
     "effect" {"bind" {"k" {"body" "key"}}
               "get" {"collection" "items" "key" "{k}"
                      "miss" {"status" 404 "json" {"message" "no item {k}"}}}}}
    {"id" "list" "request" {"rpc" "kv.Store/List"}
     "effect" {"list" {"collection" "items"}}}]})

(deftest a-stateful-contract-serves-grpc
  (async done
    (with-daemon done
      (fn [admin]
        (p/let [dir  (fx/kv-dir)
                port (free-port)
                imp  (assoc (contract/lower kv-contract port)
                            "descriptor" (:descriptor (proto/read-proto (path/join dir "kv.proto"))))
                made (admin-call (str admin "/imposters") "POST" imp)
                c    (fx/connect (path/join dir "kv.proto") "kv.Store" port)
                put  (fx/unary c "Put" {:key "a" :value "apple" :version "3"})
                got  (fx/unary c "Get" {:key "a"})
                miss (fx/unary c "Get" {:key "b"})
                lst  (fx/unary c "List" {})]
          (fx/close c)
          (is (= 201 (:status made)) (pr-str (:json made)))
          (is (= "apple" (get-in put [:ok "value"])))
          (is (= {"key" "a" "value" "apple" "version" "3" "kind" "PLAIN"} (:ok got)) "create then get")
          (is (= [(codes "NOT_FOUND") "no item b"] [(:code miss) (:details miss)]) "a miss is NOT_FOUND")
          (is (= ["a"] (map #(get % "key") (get-in lst [:ok "items"]))) "list"))))))
