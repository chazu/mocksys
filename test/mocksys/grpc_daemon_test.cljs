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
            [mocksys.contract :as contract]
            [mocksys.proto :as proto]
            [mocksys.runtime.grpc :as grpc]
            [mocksys.grpc-fixture :as fx]))

(def ^:private free-port fx/free-port)
(def ^:private admin-call fx/admin-call)
(def ^:private with-daemon fx/with-daemon)

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

(defn- answers
  "Serve kv.Store/Get answering request key k with response `is`, for each [k is] of
   `by-key`, and call it with every key; resolves {k result}."
  [admin by-key]
  (p/let [dir  (fx/kv-dir)
          port (free-port)
          stubs (vec (for [[k is] by-key]
                       (stub "kv.Store/Get" [{"equals" {"body" k} "jsonpath" {"selector" "$.key"}}] is)))
          _    (admin-call (str admin "/imposters") "POST" (kv-imposter dir port stubs))
          c    (fx/connect (path/join dir "kv.proto") "kv.Store" port)
          rs   (p/all (map #(fx/unary c "Get" {:key %}) (keys by-key)))]
    (fx/close c)
    (zipmap (keys by-key) rs)))

(deftest the-http-status-table-follows-google-rpc-code
  (async done
    (with-daemon done
      (fn [admin]
        (p/let [table {408 "DEADLINE_EXCEEDED" 412 "FAILED_PRECONDITION" 422 "INVALID_ARGUMENT"
                       502 "UNAVAILABLE" 504 "DEADLINE_EXCEEDED"}
                rs    (answers admin (into {} (for [st (keys table)] [(str st) {"statusCode" st}])))]
          (doseq [[st code] table]
            (is (= (codes code) (:code (get rs (str st)))) (str st " is " code))))))))

(deftest an-errors-details-are-a-message-not-a-body
  (async done
    (with-daemon done
      (fn [admin]
        (p/let [rs (answers admin {"both"   {"statusCode" 400 "body" {"message" "m" "error" "e"}}
                                   "oauth"  {"statusCode" 400 "body" {"error" "invalid_grant" "error_description" "expired"}}
                                   "okta"   {"statusCode" 404 "body" {"errorCode" "E0000007" "errorSummary" "Not found"}}
                                   "nested" {"statusCode" 400 "body" {"message" {"text" "x"}}}
                                   "array"  {"statusCode" 500 "body" [1 2]}
                                   "text"   {"statusCode" 418 "body" "short and stout"}})]
          (is (= {"both" "m" "oauth" "invalid_grant" "okta" "HTTP 404" "nested" "HTTP 400" "array" "HTTP 500"
                  "text" "short and stout"}
                 (update-vals rs :details))))))))

(deftest metadata-round-trips-and-what-grpc-refuses-is-logged
  (async done
    (with-daemon done
      (fn [admin]
        (p/let [dir   (fx/kv-dir)
                port  (free-port)
                _     (admin-call (str admin "/imposters") "POST"
                                  (kv-imposter dir port [(stub "kv.Store/Get" []
                                                               {"statusCode" 200 "body" {}
                                                                "headers" {"x-ok" "1" "x-blob-bin" "AQID"
                                                                           "x-city" "Zürich" "x/slash" "v"}})]))
                c     (fx/connect (path/join dir "kv.proto") "kv.Store" port)
                [r log] (fx/capturing-stderr #(fx/unary c "Get" {:key "a"} {"x-tag-bin" (js/Buffer.from #js [9 8])}))
                full  (admin-call (str admin "/imposters/" port) "GET" nil)]
          (fx/close c)
          (testing "a -bin request value is base64 in the request headers"
            (is (= "CQg=" (get-in full [:json "requests" 0 "headers" "x-tag-bin"]))))
          (testing "a -bin response header is base64, and reaches the client as bytes"
            (is (= [1 2 3] (vec (js/Array.from (get-in r [:metadata "x-blob-bin"])))) (pr-str r))
            (is (= "1" (get-in r [:metadata "x-ok"]))))
          (testing "a header grpc-js refuses as metadata is named in the daemon log"
            (is (not (contains? (:metadata r) "x-city")))
            (is (str/includes? log "x-city") log)
            (is (str/includes? log "x/slash") log)))))))

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
                proxied (admin-call (str admin "/imposters") "POST"
                                    (kv-imposter dir port [{"responses" [{"proxy" {"to" "http://127.0.0.1:1"}}]}]))
                listed  (admin-call (str admin "/imposters") "GET" nil)]
          (is (= [400 "protocol grpc needs a descriptor"]
                 [(:status no-desc) (get-in no-desc [:json "errors" 0 "message"])]))
          (is (= 400 (:status junk)))
          (is (str/starts-with? (str (get-in junk [:json "errors" 0 "message"])) "descriptor: "))
          (is (= [400 "unsupported field(s) descriptor"]
                 [(:status on-http) (get-in on-http [:json "errors" 0 "message"])]))
          (is (= [400 "stubs[0]: proxy responses are not supported for protocol grpc"]
                 [(:status proxied) (get-in proxied [:json "errors" 0 "message"])]))
          (is (= [] (get-in listed [:json "imposters"])) "no rejected imposter is left registered"))))))

(defn- hold!
  "Listen on `host`:`port` with a plain TCP server; resolves a fn that closes it."
  [host port]
  (js/Promise.
   (fn [resolve reject]
     (let [srv (.createServer net)]
       (.once srv "error" reject)
       (.listen srv port host (fn [] (resolve (fn [] (js/Promise. (fn [r] (.close srv r)))))))))))

(defn- bindable?
  "Whether `host`:`port` is free to listen on now."
  [host port]
  (-> (p/let [release (hold! host port)] (release) true)
      (p/catch (fn [_] false))))

(deftest a-port-in-use-is-refused-on-either-loopback-address
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/doseq [host ["127.0.0.1" "::1"]]
          (p/let [dir     (fx/kv-dir)
                  port    (free-port)
                  release (hold! host port)
                  made    (admin-call (str admin "/imposters") "POST" (kv-imposter dir port []))
                  listed  (admin-call (str admin "/imposters") "GET" nil)
                  _       (release)
                  v4-free (bindable? "127.0.0.1" port)]
            (testing (str "with " host " taken")
              (is (= [400 (str "Port " port " is already in use")]
                     [(:status made) (get-in made [:json "errors" 0 "message"])]))
              (is (= [] (get-in listed [:json "imposters"])) "nothing is left registered")
              (is v4-free "nothing is left listening"))))))))

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

(deftest a-grpc-twins-virtual-clock-is-reachable
  (async done
    (with-daemon done
      (fn [admin]
        (p/let [dir    (fx/kv-dir)
                port   (free-port)
                twin   (assoc-in kv-contract ["state" "clock"] {"start" 1000})
                imp    (assoc (contract/lower twin port)
                              "descriptor" (:descriptor (proto/read-proto (path/join dir "kv.proto"))))
                _      (admin-call (str admin "/imposters") "POST" imp)
                before (grpc/clock-call port {})
                after  (grpc/clock-call port {"advance" 60})]
          (is (= 1000 (get before "now")))
          (is (= 1060 (get after "now"))))))))
