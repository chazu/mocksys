(ns mocksys.runtime-imposter-test
  "One request through a native imposter: validation of the posted spec, stub
   selection, `is`/`effect`/`clock`/`proxy`/`fault` responses, behaviors, request recording
   and proxyOnce recording. Asserts the outcome the server would write."
  (:require [clojure.test :refer [deftest testing is async]]
            [clojure.string :as str]
            [promesa.core :as p]
            [mocksys.contract :as contract]
            [mocksys.kits :as kits]
            [mocksys.runtime.imposter :as imp]))

(defn- spec [stubs & {:as extra}]
  (merge {"protocol" "http" "port" 4545 "stubs" stubs} extra))

(defn- req [& {:as r}]
  (merge {"method" "GET" "path" "/" "query" {} "headers" {} "body" ""} r))

(defn- rejects [s]
  (try (imp/create s) nil (catch :default e (ex-message e))))

(defn- run-all
  "Send `reqs` through `i` in order (the server handles one at a time per
   connection, but sequencing here keeps cursor assertions deterministic)."
  [i reqs opts]
  (p/loop [rs reqs acc []]
    (if (empty? rs)
      acc
      (p/let [o (imp/respond i (first rs) opts)]
        (p/recur (rest rs) (conj acc o))))))

(defn- done-after [done pr]
  (-> pr
      (p/catch (fn [e] (is (nil? e) (str "unexpected rejection: " (ex-message e)))))
      (p/finally (fn [] (done)))))

;; --- validation --------------------------------------------------------------

(deftest every-imposter-mocksys-emits-validates
  (testing "the okta twin, which uses every emitted feature but proxy"
    (is (some? (imp/create (assoc (contract/lower (kits/okta "okta/okta" nil {})) "port" 4545)))))
  (testing "a recording proxy"
    (is (some? (imp/create {"protocol" "http" "port" 4545 "name" "r"
                            "stubs" [{"responses" [{"proxy" {"to" "https://api.example.com"
                                                             "mode" "proxyOnce"
                                                             "predicateGenerators" [{"matches" {"method" true "path" true}}]}}]}]})))))

(deftest anything-outside-the-inventory-is-rejected
  (is (str/includes? (rejects (spec [] "protocol" "tcp")) "protocol"))
  (is (str/includes? (rejects (dissoc (spec []) "port")) "port"))
  (is (str/includes? (rejects (spec [{"predicates" [{"contains" {"path" "/x"}}]}])) "stubs[0].predicates[0]"))
  (is (str/includes? (rejects (spec [{"predicates" [{"equals" {"path" "/x"} "caseSensitive" true}]}])) "caseSensitive"))
  (is (str/includes? (rejects (spec [{"predicates" [{"equals" {"body" "x"} "jsonpath" {"selector" "$..a[?(@.b)]"}}]}])) "selector"))
  (is (str/includes? (rejects (spec [{"predicates" [{"matches" {"path" "(["}}]}])) "regex"))
  (is (str/includes? (rejects (spec [{"responses" [{"is" {} "inject" "function(){}"}]}])) "stubs[0].responses[0]"))
  (is (str/includes? (rejects (spec [{"responses" [{"is" {} "behaviors" [{"decorate" "x"}]}]}])) "decorate"))
  (is (str/includes? (rejects (spec [{"responses" [{"is" {} "repeat" 2}]}])) "repeat"))
  (is (str/includes? (rejects (spec [{"responses" [{"fault" "SLOW_LORIS"}]}])) "fault"))
  (is (str/includes? (rejects (spec [{"responses" [{"proxy" {"to" "https://x" "mode" "proxyAlways"}}]}])) "proxyOnce")))

(deftest an-is-response-that-cannot-be-written-is-rejected
  (let [is-of (fn [is] (rejects (spec [{"responses" [{"is" is}]}])))]
    (testing "headers"
      (is (str/includes? (is-of {"headers" "Content-Type: text/plain"}) "stubs[0].responses[0]: is.headers must be an object"))
      (is (str/includes? (is-of {"headers" {"X-A" {"nested" 1}}}) "is.headers[\"X-A\"]"))
      (is (str/includes? (is-of {"headers" {"X-A" nil}}) "is.headers[\"X-A\"]"))
      (is (nil? (is-of {"headers" {"X-A" "1" "X-B" 2 "Set-Cookie" ["a=1" "b=2"]}}))))
    (testing "statusCode"
      (is (str/includes? (is-of {"statusCode" "abc"}) "stubs[0].responses[0]: is.statusCode"))
      (is (str/includes? (is-of {"statusCode" 42}) "is.statusCode"))
      (is (str/includes? (is-of {"statusCode" 200.5}) "is.statusCode"))
      (is (nil? (is-of {"statusCode" 201})))
      (is (nil? (is-of {"statusCode" "404"}))))))

(deftest a-planted-inject-is-an-unknown-field
  (is (= "stubs[0].responses[0]: unsupported field(s) inject"
         (rejects (spec [{"responses" [{"inject" "function () { return {statusCode: 299}; }"}]}]))))
  (is (= "stubs[1].responses[0]: unsupported field(s) inject"
         (rejects (spec [{"responses" [{"is" {}}]} {"responses" [{"is" {} "inject" "function () {}"}]}])))))

(deftest an-effect-is-checked-on-post
  (is (str/includes? (rejects (spec [{"responses" [{"is" {}}]} {"responses" [{"effect" {"respond" {"status" "200;x()"}}}]}]))
                     "stubs[1].responses[0].effect: respond.status must be an HTTP status"))
  (is (str/includes? (rejects (spec [{"responses" [{"effect" {"respond" {"status" 200} "teleport" {}}}]}]))
                     "stubs[0].responses[0].effect"))
  (is (= "stubs[0].responses[0]: a clock response needs the imposter's state.clock"
         (rejects (spec [{"responses" [{"clock" true}]}]))))
  (is (nil? (rejects (spec [{"responses" [{"effect" {"respond" {"status" 201}}}]}]))))
  (is (= "stubs[0].responses[0].effect: create.collection names users, which state.collections does not declare"
         (rejects (spec [{"responses" [{"effect" {"create" {"collection" "users"}}}]}]))))
  (is (nil? (rejects (spec [{"responses" [{"effect" {"create" {"collection" "users"}}}]}]
                           "state" {"collections" {"users" {}}})))))

(deftest a-stream-answer-is-checked-on-post
  (is (= "stubs[0].responses[0].effect: sse: unknown field replay (use channel, events, headers, keepAlive, retry)"
         (rejects (spec [{"responses" [{"effect" {"sse" {"channel" "c" "replay" true}}}]}]))))
  (is (= "stubs[0].responses[0].effect: websocket needs protocol http"
         (rejects (spec [{"responses" [{"effect" {"websocket" {"channel" "c"}}}]}] "protocol" "grpc" "descriptor" {}))))
  (is (nil? (rejects (spec [{"responses" [{"effect" {"sse" {"channel" "c"}}}]}
                            {"responses" [{"effect" {"publish" {"channel" "c" "data" "x"} "respond" {}}}]}])))))

(deftest a-stream-answer-is-an-outcome
  (async done
    (done-after done
      (p/let [i (imp/create (spec [{"responses" [{"effect" {"sse" {"channel" "c" "events" [{"data" "x"}]}}}]}]))
              o (imp/respond i (req) {})]
        (is (= 200 (:status o)))
        (is (= {"kind" "sse" "channel" "c" "events" [{"data" "x"}] "keepAlive" 15000} (:stream o)))))))

(deftest a-proxy-target-is-a-bare-origin
  (let [proxy-to (fn [to] (rejects (spec [{"responses" [{"proxy" {"to" to "mode" "proxyOnce"}}]}])))]
    (testing "a path, query or fragment would be dropped on the way upstream, so it is refused"
      (is (str/includes? (proxy-to "https://api.example.com/v1") "stubs[0].responses[0]: proxy.to must not contain a path"))
      (is (str/includes? (proxy-to "https://api.example.com/?page=2") "proxy.to must not contain a query"))
      (is (str/includes? (proxy-to "https://api.example.com#top") "proxy.to must not contain a fragment")))
    (testing "an origin, with or without the root slash, is accepted"
      (is (nil? (proxy-to "https://api.example.com")))
      (is (nil? (proxy-to "http://127.0.0.1:8080/"))))
    (is (str/includes? (proxy-to "not a url") "proxy.to must be an http(s) URL"))))

;; --- is responses ------------------------------------------------------------

(deftest is-response-and-defaults
  (async done
    (let [i (imp/create (spec [{"predicates" [{"deepEquals" {"path" "/json"}}]
                                "responses" [{"is" {"statusCode" 201 "headers" {"Content-Type" "application/json"}
                                                    "body" {"a" [1 2]}}}]}
                               {"predicates" [{"deepEquals" {"path" "/len"}}]
                                "responses" [{"is" {"headers" {"Content-Length" "1"} "body" "héllo"}}]}]))]
      (done-after done
        (p/let [[j miss len] (run-all i [(req "path" "/json") (req "path" "/nope") (req "path" "/len")] {})]
          (testing "an object body is served as indented JSON"
            (is (= 201 (:status j)))
            (is (= "{\n    \"a\": [\n        1,\n        2\n    ]\n}" (:body j)))
            (is (= "application/json" (get-in j [:headers "Content-Type"]))))
          (testing "every response closes its connection unless it says otherwise"
            (is (= "close" (get-in j [:headers "Connection"]))))
          (testing "no matching stub serves an empty 200"
            (is (= 200 (:status miss)))
            (is (= "" (:body miss)))
            (is (= {"Connection" "close"} (:headers miss))))
          (testing "a declared Content-Length is recomputed from the body"
            (is (= 6 (get-in len [:headers "Content-Length"])))))))))

(deftest first-matching-stub-wins-and-responses-cycle
  (async done
    (let [i (imp/create (spec [{"predicates" [{"deepEquals" {"path" "/a"}}]
                                "responses" [{"is" {"body" "one"}} {"is" {"body" "two"}}]}
                               {"responses" [{"is" {"body" "fallback"}}]}]))]
      (done-after done
        (p/let [os (run-all i (map #(req "path" %) ["/a" "/a" "/a" "/b"]) {})]
          (is (= ["one" "two" "one" "fallback"] (mapv :body os))))))))

;; --- behaviors ---------------------------------------------------------------

(deftest copy-and-wait-behaviors
  (async done
    (let [lowered (contract/lower {"scenario" "t/copy"
                                   "operations" [{"id" "cb" "request" {"method" "GET" "path" "/cb/{id}" "match" ["path"]}
                                                  "responses" [{"status" 302
                                                                "headers" {"Location" "https://app/cb?state={{request.query.state}}&h={{request.headers.X-Trace}}"}
                                                                "examples" {"e" {"select" true
                                                                                 "body" "{{request.method}} {{request.path}} {{request.body.user.name}}"}}}]}]
                                   "fault" {"latency" "40"}})
          i (imp/create (assoc lowered "port" 4545))]
      (done-after done
        (p/let [[o missing] (run-all i [(req "path" "/cb/7" "method" "POST" "query" {"state" "xyz"}
                                             "headers" {"x-trace" "t1"} "body" "{\"user\":{\"name\":\"ann\"}}")
                                        (req "path" "/cb/7")]
                                     {})]
          (testing "copy splices request values into headers and body"
            (is (= 302 (:status o)))
            (is (= "https://app/cb?state=xyz&h=t1" (get-in o [:headers "Location"])))
            (is (= "POST /cb/7 ann" (:body o))))
          (testing "a missing source yields Mountebank's literal undefined; a non-JSON body leaves the token"
            (is (= "https://app/cb?state=undefined&h=undefined" (get-in missing [:headers "Location"])))
            (is (str/ends-with? (:body missing) "${body_user_name}")))
          (testing "wait is returned as a delay for the server"
            (is (= 40 (:wait o)))))))))

(deftest faults-are-outcomes
  (async done
    (let [i (imp/create (spec [{"responses" [{"fault" "CONNECTION_RESET_BY_PEER"}]}]))]
      (done-after done
        (p/let [o (imp/respond i (req) {})]
          (is (= {:fault "CONNECTION_RESET_BY_PEER"} o)))))))

;; --- effects -----------------------------------------------------------------

(def ^:private remember
  {"predicates" [{"deepEquals" {"path" "/a"}}]
   "responses"  [{"effect" {"bind" {"v" {"query" "v"}} "store" {"bucket" "b" "key" "k" "value" "{v}"} "respond" {"status" 201}}}]})

(def ^:private recall
  {"predicates" [{"deepEquals" {"path" "/b"}}]
   "responses"  [{"effect" {"lookup" {"bucket" "b" "key" "k" "as" "v" "miss" {"status" 404}} "respond" {"text" "{v}"}}}]})

(deftest effect-state-is-shared-and-persists
  (async done
    (let [i (imp/create (spec [remember recall]))]
      (done-after done
        (p/let [os (run-all i [(req "path" "/b") (req "path" "/a" "query" {"v" "1"}) (req "path" "/b")
                               (req "path" "/a" "query" {"v" "2"}) (req "path" "/b")] {})]
          (is (= [404 201 200 201 200] (mapv :status os)))
          (is (= ["1" "2"] [(:body (nth os 2)) (:body (nth os 4))]) "one stub reads what another wrote"))))))

;; --- request recording -------------------------------------------------------

(deftest requests-are-recorded-only-when-asked
  (async done
    (let [on  (imp/create (spec [] "recordRequests" true))
          off (imp/create (spec []))]
      (done-after done
        (p/let [_ (run-all on [(req "path" "/x" "query" {"a" "1"}) (req "path" "/y" "method" "POST" "body" "b")] {})
                _ (imp/respond off (req "path" "/x") {})]
          (let [rs (get (imp/to-json on {}) "requests")]
            (is (= [["GET" "/x" {"a" "1"} ""] ["POST" "/y" {} "b"]]
                   (mapv (juxt #(get % "method") #(get % "path") #(get % "query") #(get % "body")) rs)))
            (is (every? #(string? (get % "timestamp")) rs)))
          (is (= 2 (get (imp/to-json on {}) "numberOfRequests")))
          (is (= [] (get (imp/to-json off {}) "requests")))
          (imp/clear-requests! on)
          (is (= [] (get (imp/to-json on {}) "requests"))))))))

;; --- the request-log cap -----------------------------------------------------

(defn- paths [i] (mapv #(get % "path") (get (imp/to-json i {}) "requests")))

(deftest the-request-log-keeps-the-newest
  (async done
    (let [i (imp/create (spec [] "recordRequests" true) {:max-requests 2})]
      (done-after done
        (p/let [_ (run-all i (for [n (range 1 4)] (req "path" (str "/" n))) {})]
          (is (= ["/2" "/3"] (paths i)) "the oldest request goes first")
          (is (= [3 1] ((juxt #(get % "numberOfRequests") #(get % "droppedRequests")) (imp/to-json i {})))
              "every request counts, and the view says how many were dropped")
          (is (not (contains? (imp/to-json i {:list? true}) "droppedRequests")) "the summary row stays as it was")
          (imp/clear-requests! i)
          (p/let [_ (imp/respond i (req "path" "/4") {})]
            (is (= ["/4"] (paths i)))
            (is (= [4 0] ((juxt #(get % "numberOfRequests") #(get % "droppedRequests")) (imp/to-json i {})))
                "clearing forgets the dropped ones too")))))))

(deftest the-request-log-holds-10000-by-default
  (async done
    (let [i (imp/create (spec [] "recordRequests" true))]
      (done-after done
        (p/let [_ (run-all i (repeat 10001 (req)) {})]
          (is (= 10000 (count (get (imp/to-json i {}) "requests"))))
          (is (= 1 (get (imp/to-json i {}) "droppedRequests"))))))))

(deftest a-zero-cap-records-nothing
  (async done
    (let [i (imp/create (spec [] "recordRequests" true) {:max-requests 0})]
      (done-after done
        (p/let [_ (run-all i [(req) (req)] {})]
          (is (= [[] 2 2] ((juxt #(get % "requests") #(get % "numberOfRequests") #(get % "droppedRequests"))
                           (imp/to-json i {})))))))))

;; --- proxyOnce recording -----------------------------------------------------

(deftest proxy-once-records-then-replays
  (async done
    (let [calls  (atom [])
          proxy! (fn [cfg request]
                   (swap! calls conj [(get cfg "to") (get request "path")])
                   (p/resolved {"statusCode" 200 "headers" {"Content-Type" "application/json" "X-Up" "1"}
                                "body" {"path" (get request "path")} "_mode" "text"}))
          i (imp/create {"protocol" "http" "port" 4545 "name" "github/rec" "recordRequests" true
                         "stubs" [{"responses" [{"proxy" {"to" "https://api.example.com" "mode" "proxyOnce"
                                                          "predicateGenerators" [{"matches" {"method" true "path" true}}]}}]}]})]
      (done-after done
        (p/let [os (run-all i [(req "path" "/repos") (req "path" "/repos" "query" {"page" "2"})
                               (req "path" "/user") (req "method" "POST" "path" "/repos")]
                            {:proxy! proxy!})]
          (testing "each new method+path goes upstream once; a repeat replays the recording"
            (is (= [["https://api.example.com" "/repos"] ["https://api.example.com" "/user"]
                    ["https://api.example.com" "/repos"]]
                   @calls))
            (is (= (repeat 4 200) (map :status os)))
            (is (str/includes? (:body (second os)) "/repos")))
          (testing "replayable with proxies removed is the recorded stubs, in recording order"
            (let [r (imp/to-json i {:replayable? true :remove-proxies? true})]
              (is (= "github/rec" (get r "name")))
              (is (nil? (get r "requests")))
              (is (= [[{"deepEquals" {"method" "GET"}} {"deepEquals" {"path" "/repos"}}]
                      [{"deepEquals" {"method" "GET"}} {"deepEquals" {"path" "/user"}}]
                      [{"deepEquals" {"method" "POST"}} {"deepEquals" {"path" "/repos"}}]]
                     (mapv #(get % "predicates") (get r "stubs"))))
              (is (= {"statusCode" 200 "headers" {"Content-Type" "application/json" "X-Up" "1"}
                      "body" {"path" "/repos"} "_mode" "text"}
                     (get-in r ["stubs" 0 "responses" 0 "is"]))
                  "no _proxyResponseTime in a replayable recording")))
          (testing "without removeProxies the proxy stub stays last"
            (is (contains? (get-in (imp/to-json i {}) ["stubs" 3 "responses" 0]) "proxy"))))))))
