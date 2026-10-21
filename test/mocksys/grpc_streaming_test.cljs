(ns mocksys.grpc-streaming-test
  "Streaming rpcs on the real daemon, called with a real grpc-js client. A server
   stream answers one request with the body's messages; a client stream's messages
   arrive as one JSON array body; each message of a bidirectional stream is its own
   request, answered with zero or more messages."
  (:require [clojure.test :refer [deftest testing is async]]
            [clojure.string :as str]
            [promesa.core :as p]
            ["node:path" :as path]
            [mocksys.contract :as contract]
            [mocksys.proto :as proto]
            [mocksys.grpc-fixture :as fx]))

(def ^:private codes fx/status)

(defn- serve!
  "POST a kv imposter with `stubs` (or a lowered `imposter`) on a free port;
   resolves [port client admin-response]."
  [admin stubs]
  (p/let [dir  (fx/kv-dir)
          port (fx/free-port)
          imp  {"protocol" "grpc" "port" port "name" "t/kv" "recordRequests" true
                "descriptor" (:descriptor (proto/read-proto (path/join dir "kv.proto")))
                "stubs" stubs}
          made (fx/admin-call (str admin "/imposters") "POST" imp)]
    [port (fx/connect (path/join dir "kv.proto") "kv.Store" port) made]))

(defn- on [rpc preds & responses]
  {"predicates" (into [{"deepEquals" {"path" (str "/" rpc)}}] preds)
   "responses"  (vec responses)})

(defn- by-key [k] {"equals" {"body" k} "jsonpath" {"selector" "$.key"}})
(defn- ok [body] {"is" {"statusCode" 200 "body" body}})

(defn- requests [admin port]
  (p/let [full (fx/admin-call (str admin "/imposters/" port) "GET" nil)]
    (mapv #(js->clj (js/JSON.parse (get % "body"))) (get-in full [:json "requests"]))))

(deftest a-server-stream-sends-the-bodys-messages
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [[port c made]
                (serve! admin
                        [(on "kv.Store/Watch" [(by-key "many")]
                             {"is" {"statusCode" 200 "headers" {"x-trace" "w-1"}
                                    "body" [{"key" "a" "version" "1"} {"key" "b" "version" "2"}]}})
                         (on "kv.Store/Watch" [(by-key "one")] (ok {"key" "solo"}))
                         (on "kv.Store/Watch" [(by-key "none")] (ok []))
                         (on "kv.Store/Watch" [(by-key "gone")]
                             {"is" {"statusCode" 404 "body" {"message" "no key gone"}}})
                         (on "kv.Store/Watch" [(by-key "bad")]
                             (ok [{"key" "fine"} {"key" "b" "version" "not a number"}]))
                         (on "kv.Store/Watch" [(by-key "text")] (ok "not json"))])
                many (fx/server-stream c "Watch" {:key "many"})
                one  (fx/server-stream c "Watch" {:key "one"})
                none (fx/server-stream c "Watch" {:key "none"})
                miss (fx/server-stream c "Watch" {:key "unmatched"})
                gone (fx/server-stream c "Watch" {:key "gone"})
                bad  (fx/server-stream c "Watch" {:key "bad"})
                text (fx/server-stream c "Watch" {:key "text"})
                seen (requests admin port)]
          (fx/close c)
          (is (= 201 (:status made)) (pr-str (:json made)))
          (testing "a JSON array body is the stream's messages, in order"
            (is (= [["a" "1"] ["b" "2"]] (mapv (juxt #(get % "key") #(get % "version")) (:messages many))))
            (is (nil? (:code many)) "the stream ends OK"))
          (testing "response headers arrive as metadata"
            (is (= "w-1" (get-in many [:metadata "x-trace"]))))
          (testing "an object body is one message"
            (is (= ["solo"] (mapv #(get % "key") (:messages one)))))
          (testing "an empty array, or no matching stub, sends no messages and ends OK"
            (is (= [[] nil] [(:messages none) (:code none)]))
            (is (= [[] nil] [(:messages miss) (:code miss)])))
          (testing "an error status ends the stream with that error, after no messages"
            (is (= [[] (codes "NOT_FOUND") "no key gone"] [(:messages gone) (:code gone) (:details gone)])))
          (testing "a message that does not fit the reply type fails the stream before any is sent"
            (is (= [] (:messages bad)))
            (is (= (codes "INTERNAL") (:code bad)))
            (is (str/includes? (str (:details bad)) "[1].version: expected an integer") (:details bad)))
          (testing "a body that is not JSON is INTERNAL"
            (is (= [[] (codes "INTERNAL")] [(:messages text) (:code text)])))
          (testing "the request is the one message, as for unary"
            (is (= {"key" "many"} (first seen)))))))))

(deftest a-client-streams-messages-arrive-as-one-array-body
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [[port c _]
                (serve! admin
                        [(on "kv.Store/PutMany" [{"equals" {"body" "b"} "jsonpath" {"selector" "$[1].key"}}]
                             (ok [{"key" "second is b"}]))
                         (on "kv.Store/PutMany" [] {"is" {"statusCode" 400 "body" {"message" "want b second"}}})])
                hit  (fx/client-stream c "PutMany" [{:key "a" :value "1"} {:key "b"}])
                miss (fx/client-stream c "PutMany" [{:key "a"}])
                none (fx/client-stream c "PutMany" [])
                seen (requests admin port)]
          (fx/close c)
          (testing "the request body is the JSON array of the messages, each with only the fields set"
            (is (= [[{"key" "a" "value" "1"} {"key" "b"}] [{"key" "a"}] []] seen)))
          (testing "a predicate selects into the array"
            (is (= ["second is b"] (mapv #(get % "key") (get-in hit [:ok "items"])))))
          (testing "the reply is as for unary"
            (is (= [(codes "INVALID_ARGUMENT") "want b second"] [(:code miss) (:details miss)]))
            (is (= (codes "INVALID_ARGUMENT") (:code none)))))))))

(deftest each-message-of-a-bidi-stream-is-a-request
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [[port c _]
                (serve! admin
                        [(on "kv.Store/Sync" [(by-key "two")] (ok [{"key" "two-a"} {"key" "two-b"}]))
                         (on "kv.Store/Sync" [(by-key "quiet")] (ok []))
                         (on "kv.Store/Sync" [(by-key "slow")]
                             {"is" {"statusCode" 200 "body" {"key" "slow"}} "behaviors" [{"wait" 150}]})
                         (on "kv.Store/Sync" [] {"is" {"statusCode" 200 "headers" {"x-turn" "1"}
                                                       "body" {"key" "echo"}}})])
                {:keys [call done]} (fx/bidi c "Sync")
                _     (fx/send! call {:key "first"})
                first (fx/next-message call)
                _     (fx/send! call {:key "slow"})
                _     (fx/send! call {:key "two"})
                _     (fx/send! call {:key "quiet"})
                _     (fx/send! call {:key "last"})
                _     (.end call)
                out   done
                seen  (requests admin port)]
          (fx/close c)
          (testing "a reply arrives before the client half-closes"
            (is (= "echo" (get first "key"))))
          (testing "an object reply is one message, an array several, an empty array none; in request order"
            (is (= ["echo" "slow" "two-a" "two-b" "echo"] (mapv #(get % "key") (:messages out)))))
          (testing "the stream ends OK when the client half-closes"
            (is (nil? (:code out)) (pr-str out)))
          (testing "the first reply's headers are the stream's metadata"
            (is (= "1" (get-in out [:metadata "x-turn"]))))
          (testing "each inbound message is recorded as its own request"
            (is (= ["first" "slow" "two" "quiet" "last"] (mapv #(get % "key") seen)))))))))

(deftest an-error-reply-ends-a-bidi-stream
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [[port c _]
                (serve! admin
                        [(on "kv.Store/Sync" [(by-key "stop")]
                             {"is" {"statusCode" 403 "body" {"grpcCode" "PERMISSION_DENIED" "message" "stopped"}}})
                         (on "kv.Store/Sync" [] (ok {"key" "echo"}))])
                {:keys [call done]} (fx/bidi c "Sync")
                _    (fx/send! call {:key "a"})
                _    (fx/send! call {:key "stop"})
                _    (fx/send! call {:key "after"})
                out  done
                seen (requests admin port)]
          (fx/close c)
          (is (= ["echo"] (mapv #(get % "key") (:messages out))) "the replies before the error arrive")
          (is (= [(codes "PERMISSION_DENIED") "stopped"] [(:code out) (:details out)]))
          (is (not (some #{{"key" "after"}} seen)) "a message after the error is not answered"))))))

(def ^:private kv-contract
  {"scenario" "t/kv" "protocol" "grpc" "proto" "./kv.proto"
   "state" {"collections" {"items" {"idField" "key"}}}
   "operations"
   [{"id" "sync" "request" {"rpc" "kv.Store/Sync"}
     "effect" {"bind" {"incoming" {"bodyJson" true}}
               "create" {"collection" "items" "merge" ["incoming"] "body" {} "status" 200}}}
    {"id" "watch" "request" {"rpc" "kv.Store/Watch"}
     "effect" {"list" {"collection" "items"}}}]})

(deftest a-stateful-contract-streams
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [dir  (fx/kv-dir)
                port (fx/free-port)
                imp  (assoc (contract/lower kv-contract port)
                            "descriptor" (:descriptor (proto/read-proto (path/join dir "kv.proto"))))
                made (fx/admin-call (str admin "/imposters") "POST" imp)
                c    (fx/connect (path/join dir "kv.proto") "kv.Store" port)
                {:keys [call done]} (fx/bidi c "Sync")
                _    (doseq [k ["a" "b" "c"]] (fx/send! call {:key k :value (str/upper-case k)}))
                _    (.end call)
                sync done
                lst  (fx/server-stream c "Watch" {})]
          (fx/close c)
          (is (= 201 (:status made)) (pr-str (:json made)))
          (is (= ["A" "B" "C"] (mapv #(get % "value") (:messages sync))) "each message creates a record")
          (is (= ["a" "b" "c"] (mapv #(get % "key") (:messages lst))) "a list effect streams one message per record"))))))
