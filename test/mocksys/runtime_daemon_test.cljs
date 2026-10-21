(ns mocksys.runtime-daemon-test
  "The daemon over real sockets: the admin routes mocksys.mb calls, imposters
   answering on both loopback addresses, faults on the wire, wait, proxyOnce
   recording against a live upstream, and the admin API's refusal of browser and
   non-local callers."
  (:require [clojure.test :refer [deftest testing is async]]
            [clojure.string :as str]
            [promesa.core :as p]
            ["node:http" :as http]
            ["node:net" :as net]
            [mocksys.runtime :as runtime]))

(defn- free-port []
  (js/Promise.
   (fn [resolve]
     (let [srv (.createServer net)]
       (.listen srv 0 (fn [] (let [port (.-port (.address srv))] (.close srv #(resolve port)))))))))

(defn- call
  "An admin or imposter call; resolves {:status :headers :text :json}."
  ([url] (call url {}))
  ([url {:keys [method body headers]}]
   (p/let [res  (js/fetch url (clj->js (cond-> {:method (or method "GET")
                                                 :headers (merge {"Content-Type" "application/json"} headers)}
                                          body (assoc :body (if (string? body) body (js/JSON.stringify (clj->js body)))))))
           text (.text res)]
     {:status  (.-status res)
      :headers (js->clj (js/Object.fromEntries (.entries (.-headers res))))
      :text    text
      :json    (try (js->clj (js/JSON.parse text)) (catch :default _ nil))})))

(defn- with-daemon
  "Run `f` against a fresh daemon on an ephemeral admin port; stop it afterwards."
  [done f]
  (p/let [d (runtime/start! {:host "127.0.0.1" :port 0})
          admin (str "http://127.0.0.1:" (:port d))]
    (-> (p/do (f admin))
        (p/catch (fn [e] (is (nil? e) (str "unexpected rejection: " (ex-message e)))))
        (p/finally (fn [] (p/do ((:stop! d)) (done)))))))

(defn- imposter [port stubs & {:as extra}]
  (merge {"protocol" "http" "port" port "name" "t/one" "recordRequests" true "stubs" stubs} extra))

(defn- raw-get
  "A GET with full control of headers (fetch forbids setting Host)."
  [port headers]
  (js/Promise.
   (fn [resolve]
     (let [rq (.request http #js {:host "127.0.0.1" :port port :path "/" :headers (clj->js headers)}
                        (fn [res] (.resume res) (resolve (.-statusCode res))))]
       (.end rq)))))

(deftest admin-lifecycle
  (async done
    (with-daemon done
      (fn [admin]
        (p/let [port   (free-port)
                ident  (call (str admin "/"))
                bad    (call (str admin "/imposters") {:method "POST" :body (imposter port [{"responses" [{"is" {} "repeat" 2}]}])})
                made   (call (str admin "/imposters") {:method "POST"
                                                       :body (imposter port [{"predicates" [{"deepEquals" {"path" "/hi"}}]
                                                                              "responses" [{"is" {"body" "hello"}}]}])})
                dup    (call (str admin "/imposters") {:method "POST" :body (imposter port [])})
                listed (call (str admin "/imposters"))
                v4     (call (str "http://127.0.0.1:" port "/hi?x=1"))
                v6     (call (str "http://[::1]:" port "/hi"))
                full   (call (str admin "/imposters/" port))
                _      (call (str admin "/imposters/" port "/savedRequests") {:method "DELETE"})
                after  (call (str admin "/imposters/" port))
                del    (call (str admin "/imposters/" port) {:method "DELETE"})
                gone   (-> (js/fetch (str "http://127.0.0.1:" port "/hi")) (p/then (constantly :served)) (p/catch (constantly :refused)))
                again  (call (str admin "/imposters/" port) {:method "DELETE"})
                miss   (call (str admin "/imposters/" port))]
          (testing "GET / identifies the daemon"
            (is (= "mocksys" (get-in ident [:json "name"])))
            (is (number? (get-in ident [:json "pid"]))))
          (testing "an imposter outside the inventory is refused with the field named"
            (is (= 400 (:status bad)))
            (is (str/includes? (get-in bad [:json "errors" 0 "message"]) "repeat")))
          (is (= 201 (:status made)))
          (testing "a second imposter on a taken port is a conflict"
            (is (= 400 (:status dup)))
            (is (= "resource conflict" (get-in dup [:json "errors" 0 "code"]))))
          (is (= [{"protocol" "http" "port" port "name" "t/one" "numberOfRequests" 0}]
                 (get-in listed [:json "imposters"])))
          (testing "the imposter answers on both loopback addresses"
            (is (= "hello" (:text v4)))
            (is (= "hello" (:text v6))))
          (testing "recorded requests, then cleared"
            (is (= [["GET" "/hi" {"x" "1"}] ["GET" "/hi" {}]]
                   (mapv (juxt #(get % "method") #(get % "path") #(get % "query")) (get-in full [:json "requests"]))))
            (is (= [] (get-in after [:json "requests"]))))
          (testing "delete stops the server and is idempotent"
            (is (= 200 (:status del)))
            (is (= :refused gone))
            (is (= 200 (:status again)))
            (is (= 404 (:status miss)))))))))

(deftest faults-and-wait-on-the-wire
  (async done
    (with-daemon done
      (fn [admin]
        (p/let [port (free-port)
                _    (call (str admin "/imposters")
                           {:method "POST"
                            :body (imposter port [{"predicates" [{"deepEquals" {"path" "/reset"}}]
                                                   "responses" [{"fault" "CONNECTION_RESET_BY_PEER"}]}
                                                  {"predicates" [{"deepEquals" {"path" "/garbage"}}]
                                                   "responses" [{"fault" "RANDOM_DATA_THEN_CLOSE"}]}
                                                  {"predicates" [{"deepEquals" {"path" "/slow"}}]
                                                   "responses" [{"is" {"body" "late"} "behaviors" [{"wait" 150}]}]}])})
                outcome (fn [path] (-> (js/fetch (str "http://127.0.0.1:" port path))
                                       (p/then (fn [r] (.text r)))
                                       (p/catch (constantly :failed))))
                reset   (outcome "/reset")
                garbage (outcome "/garbage")
                t0      (js/Date.now)
                slow    (outcome "/slow")
                elapsed (- (js/Date.now) t0)]
          (is (= :failed reset) "the connection is cut with no response")
          (is (= :failed garbage) "bytes that are not HTTP, then a close")
          (is (= "late" slow))
          (is (>= elapsed 150) "the response waited"))))))

(deftest injection-errors-are-500s
  (async done
    (with-daemon done
      (fn [admin]
        (p/let [port (free-port)
                _    (call (str admin "/imposters")
                           {:method "POST" :body (imposter port [{"responses" [{"inject" "function () { throw new Error('boom'); }"}]}])})
                r    (call (str "http://127.0.0.1:" port "/"))]
          (is (= 500 (:status r)))
          (is (str/includes? (get-in r [:json "errors" 0 "message"]) "boom")))))))

(deftest proxy-once-against-a-live-upstream
  (async done
    (let [hits     (atom [])
          upstream (.createServer http
                                  (fn [^js rq ^js rs]
                                    (swap! hits conj [(.-method rq) (.-url rq) (aget (.-headers rq) "host")])
                                    (.writeHead rs 200 #js {"Content-Type" "application/json" "X-Upstream" "yes"})
                                    (.end rs (js/JSON.stringify #js {:url (.-url rq)}))))]
      (.listen upstream 0 "127.0.0.1"
               (fn []
                 (let [up (str "http://127.0.0.1:" (.-port (.address upstream)))]
                   (with-daemon
                     (fn [] (.close upstream) (done))
                     (fn [admin]
                       (p/let [port (free-port)
                               _    (call (str admin "/imposters")
                                          {:method "POST"
                                           :body {"protocol" "http" "port" port "name" "svc/rec"
                                                  "stubs" [{"responses" [{"proxy" {"to" up "mode" "proxyOnce"
                                                                                   "predicateGenerators" [{"matches" {"method" true "path" true}}]}}]}]}})
                               a    (call (str "http://127.0.0.1:" port "/v1/things?page=2"))
                               b    (call (str "http://127.0.0.1:" port "/v1/things"))
                               rec  (call (str admin "/imposters/" port "?replayable=true&removeProxies=true"))]
                         (testing "the first call goes upstream with its query and the upstream Host"
                           (is (= [["GET" "/v1/things?page=2" (subs up 7)]] @hits))
                           (is (= "/v1/things?page=2" (get (:json a) "url")))
                           (is (= "yes" (get-in a [:headers "x-upstream"]))))
                         (testing "the repeat replays without going upstream"
                           (is (= 1 (count @hits)))
                           (is (= "/v1/things?page=2" (get (:json b) "url"))))
                         (testing "freeze sees one stub with the parsed JSON body"
                           (let [stubs (get-in rec [:json "stubs"])]
                             (is (= 1 (count stubs)))
                             (is (= [{"deepEquals" {"method" "GET"}} {"deepEquals" {"path" "/v1/things"}}]
                                    (get-in stubs [0 "predicates"])))
                             (is (= {"url" "/v1/things?page=2"} (get-in stubs [0 "responses" 0 "is" "body"])))
                             (is (= "text" (get-in stubs [0 "responses" 0 "is" "_mode"]))))))))))))))

(deftest admin-refuses-browsers-and-foreign-hosts
  (async done
    (p/let [d    (runtime/start! {:host "127.0.0.1" :port 0})
            ok   (raw-get (:port d) {"Host" "127.0.0.1"})
            orig (raw-get (:port d) {"Host" "127.0.0.1" "Origin" "https://evil.example"})
            host (raw-get (:port d) {"Host" "evil.example"})]
      (is (= 200 ok))
      (is (= 403 orig) "a browser request carries an Origin")
      (is (= 403 host) "a DNS-rebound request names a foreign host")
      (p/do ((:stop! d)) (done)))))
