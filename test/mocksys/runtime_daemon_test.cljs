(ns mocksys.runtime-daemon-test
  "The daemon over real sockets: the admin routes mocksys.mb calls, imposters
   answering on both loopback addresses, faults on the wire, wait, proxyOnce
   recording against a live upstream, and the admin API's refusal of browser and
   non-local callers."
  (:require [clojure.test :refer [deftest testing is async]]
            [clojure.string :as str]
            [promesa.core :as p]
            ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:net" :as net]
            ["node:os" :as os]
            ["node:path" :as path]
            [mocksys.daemon-fixture :as daemon]
            [mocksys.runtime :as runtime]))

(defn- free-port []
  (js/Promise.
   (fn [resolve]
     (let [srv (.createServer net)]
       (.listen srv 0 (fn [] (let [port (.-port (.address srv))] (.close srv #(resolve port)))))))))

(defn- ipv6-loopback?
  "Whether this host can listen on ::1 (some CI containers have no IPv6)."
  []
  (js/Promise.
   (fn [resolve]
     (let [srv (.createServer net)]
       (.once srv "error" (fn [_] (resolve false)))
       (.listen srv 0 "::1" (fn [] (.close srv #(resolve true))))))))

(defn- raw-exchange
  "Send one HTTP/1.1 GET over a bare socket and resolve the bytes that came back
   before the server closed it."
  [port path]
  (js/Promise.
   (fn [resolve]
     (let [^js sock (.connect net #js {:host "127.0.0.1" :port port})
           chunks   #js []]
       (.on sock "data" #(.push chunks %))
       (.on sock "error" (fn [_]))
       (.on sock "close" #(resolve (.toString (js/Buffer.concat chunks) "utf8")))
       (.write sock (str "GET " path " HTTP/1.1\r\nHost: localhost\r\n\r\n"))))))

(defn- call
  "An admin or imposter call; resolves {:status :headers :text :json}."
  ([url] (call url {}))
  ([url {:keys [method body headers]}]
   (p/let [res  (js/fetch url (clj->js (cond-> {:method (or method "GET")
                                                 :headers (merge {"Content-Type" "application/json"} (daemon/auth url) headers)}
                                          body (assoc :body (if (string? body) body (js/JSON.stringify (clj->js body)))))))
           text (.text res)]
     {:status  (.-status res)
      :headers (js->clj (js/Object.fromEntries (.entries (.-headers res))))
      :text    text
      :json    (try (js->clj (js/JSON.parse text)) (catch :default _ nil))})))

(def ^:private with-daemon daemon/with-daemon)

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
                ipv6?  (ipv6-loopback?)
                v6     (if ipv6? (call (str "http://[::1]:" port "/hi")) (call (str "http://127.0.0.1:" port "/hi")))
                full   (call (str admin "/imposters/" port))
                _      (call (str admin "/imposters/" port "/savedRequests") {:method "DELETE"})
                after  (call (str admin "/imposters/" port))
                del    (call (str admin "/imposters/" port) {:method "DELETE"})
                gone   (-> (js/fetch (str "http://127.0.0.1:" port "/hi")) (p/then (constantly :served)) (p/catch (constantly :refused)))
                again  (call (str admin "/imposters/" port) {:method "DELETE"})
                miss   (call (str admin "/imposters/" port))]
          (testing "GET / identifies the daemon and its version"
            (is (= "mocksys" (get-in ident [:json "name"])))
            (is (= (.-version (js/require "../package.json")) (get-in ident [:json "version"])))
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
          (testing "the imposter answers on both loopback addresses (::1 where the host has it)"
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

(deftest a-delete-during-create-leaves-nothing-listening
  ;; create! registers the imposter before its servers listen; a delete! issued in
  ;; that window must win, so the servers that come up afterwards are closed.
  (async done
    (p/let [registry (atom {})
            port     (free-port)
            created  (let [pr (#'runtime/create! registry {} (js/JSON.stringify (clj->js (imposter port []))))]
                       (#'runtime/delete! registry port)
                       (-> pr (p/then (fn [[st _]] st)) (p/catch (fn [e] (ex-message e)))))
            wire     (-> (js/fetch (str "http://127.0.0.1:" port "/"))
                         (p/then (constantly :served)) (p/catch (constantly :refused)))]
      (is (= :refused wire) "no server is left listening on the deleted port")
      (is (= {} @registry) "the registry holds nothing for it")
      (is (string? created) (str "the create reports that it lost to the delete, got " (pr-str created)))
      (p/do (p/all (map #(#'runtime/close! %) (mapcat :servers (vals @registry))))
            (done)))))

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
                reset-bytes   (raw-exchange port "/reset")
                garbage-bytes (raw-exchange port "/garbage")
                t0      (js/Date.now)
                slow    (outcome "/slow")
                elapsed (- (js/Date.now) t0)]
          (is (= :failed reset) "the connection is cut with no response")
          (is (= :failed garbage) "bytes that are not HTTP, then a close")
          (is (= "" reset-bytes) "a reset sends nothing before the close")
          (is (= "Htijy%@tWXJ/hQ#[Q:7G@dH4\"gu[QaX&" garbage-bytes) "random data is Mountebank's fixed garbage")
          (is (= "late" slow))
          (is (>= elapsed 150) "the response waited"))))))

(deftest injection-state-is-per-imposter-and-reset-by-a-repost
  (async done
    (with-daemon done
      (fn [admin]
        (let [counter {"responses" [{"inject" "function (request, state) { state.n = (state.n || 0) + 1; return { body: String(state.n) }; }"}]}
              post!   (fn [port] (call (str admin "/imposters") {:method "POST" :body (imposter port [counter])}))
              hit     (fn [port] (p/let [r (call (str "http://127.0.0.1:" port "/"))] (:text r)))]
          (p/let [a (free-port)
                  _ (post! a)
                  b (free-port)
                  _ (post! b)
                  a1 (hit a) a2 (hit a) b1 (hit b)
                  _ (call (str admin "/imposters/" a) {:method "DELETE"})
                  _ (post! a)
                  a3 (hit a)]
            (is (= ["1" "2"] [a1 a2]) "an imposter's state persists across its requests")
            (is (= "1" b1) "a second imposter does not see the first one's state")
            (is (= "1" a3) "a deleted and reposted imposter starts from empty state")))))))

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

(deftest delete-root-stops-the-daemon
  (async done
    (let [stopped (p/deferred)]
      (p/let [d        (daemon/start! {:on-stop #(p/resolve! stopped :stopped)})
              admin    (:admin d)
              port     (free-port)
              _        (call (str admin "/imposters") {:method "POST" :body (imposter port [])})
              res      (call (str admin "/") {:method "DELETE"})
              signal   (p/race [stopped (p/delay 2000 :timed-out)])
              refused  (fn [url] (-> (js/fetch url) (p/then (constantly :served)) (p/catch (constantly :refused))))
              admin-is (refused (str admin "/"))
              mock-is  (refused (str "http://127.0.0.1:" port "/"))]
        (is (= 200 (:status res)))
        (is (= {"stopped" 1} (:json res)) "the reply counts the imposters it stopped")
        (is (= :stopped signal) "on-stop runs once everything is closed")
        (is (= :refused admin-is) "the admin API no longer listens")
        (is (= :refused mock-is) "nor does the imposter")
        (done)))))

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

;; --- the admin token -----------------------------------------------------------

(defn- settle
  "Report a rejection of `pr` as a failure; call `done` either way."
  [done pr]
  (-> pr
      (p/catch (fn [e] (is (nil? e) (str "unexpected rejection: " (ex-message e)))))
      (p/finally (fn [] (done)))))

(defn- status-with
  "The status of one admin call that sends exactly `headers`."
  [url method headers]
  (p/let [res (js/fetch url (clj->js {:method method :headers headers}))]
    (.-status res)))

(deftest the-admin-api-needs-the-token
  (async done
    (settle done
     (p/let [d       (daemon/start!)
             admin   (:admin d)
             bearer  (fn [t] {"Authorization" (str "Bearer " t)})
             ident   (status-with (str admin "/") "GET" {})
             none    (status-with (str admin "/imposters") "GET" {})
             wrong   (status-with (str admin "/imposters") "GET" (bearer (str/replace (:token d) #"^." #(if (= % "0") "1" "0"))))
             short   (status-with (str admin "/imposters") "GET" (bearer "abc"))
             bare    (status-with (str admin "/imposters") "GET" {"Authorization" (:token d)})
             port    (free-port)
             post    (p/let [res (js/fetch (str admin "/imposters")
                                           (clj->js {:method "POST" :body (js/JSON.stringify (clj->js (imposter port [])))}))
                             text (.text res)]
                       [(.-status res) (js->clj (js/JSON.parse text))])
             stop    (status-with (str admin "/") "DELETE" {})
             listed  (call (str admin "/imposters"))]
       (is (= 200 ident) "GET / identifies the daemon to anyone")
       (is (= [401 401 401 401] [none wrong short bare]) "a missing, wrong or malformed token is refused")
       (is (= 401 (first post)))
       (is (= "unauthorized" (get-in (second post) ["errors" 0 "code"])))
       (is (= 401 stop) "the daemon cannot be stopped without it")
       (is (= 200 (:status listed)) "the token opens the API")
       (is (= [] (get-in listed [:json "imposters"])) "the refused post created nothing")
       (is (re-matches #"[0-9a-f]{64}" (:token d)) "the token is 32 random bytes")
       ((:stop! d))))))

(defn- status-line
  "Send `method` `target` over a bare socket, so the target reaches the server
   unparsed, and resolve the response's status line, or :no-response."
  [port method target]
  (p/race
   [(js/Promise.
     (fn [resolve]
       (let [^js sock (.connect net #js {:host "127.0.0.1" :port port})
             chunks   #js []]
         (.on sock "data" #(.push chunks %))
         (.on sock "error" (fn [_]))
         (.on sock "close" #(resolve (or (first (str/split-lines (.toString (js/Buffer.concat chunks) "utf8")))
                                         :no-response)))
         (.write sock (str method " " target " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")))))
    (p/delay 2000 :no-response)]))

(deftest admin-answers-a-malformed-url
  (async done
    (settle done
     (p/let [d     (runtime/start! {:host "127.0.0.1" :port 0})
             lines (p/all (for [t ["//" "///" "//[" "//a:b"]] (status-line (:port d) "GET" t)))]
       (is (= (repeat 4 "HTTP/1.1 400 Bad Request") lines) "a URL that does not parse is a 400, not a hung request")
       ((:stop! d))))))

(defn- tmp-dir [] (fs/mkdtempSync (path/join (os/tmpdir) "mocksys-token-")))

(defn- mode-of [file] (bit-and (.-mode (fs/statSync file)) 8r777))

(deftest the-daemon-publishes-its-token-to-its-owner-only
  (async done
    (let [file (path/join (tmp-dir) "store" "daemon-1.token")]
      (settle done
       (p/let [d      (runtime/start! {:host "127.0.0.1" :port 0 :token-file file})
               text   (fs/readFileSync file "utf8")
               mode   (mode-of file)
               rival  (-> (runtime/start! {:host "127.0.0.1" :port (:port d) :token-file file})
                          (p/then (constantly :listening))
                          (p/catch (fn [^js e] (.-code e))))
               after  (fs/readFileSync file "utf8")
               again  (runtime/start! {:host "127.0.0.1" :port 0 :token-file file})
               mode2  (mode-of file)]
         (is (= (:token d) text) "the file holds the token, and creates the store if needed")
         (is (= 8r600 mode) "only the daemon's user can read it")
         (is (= "EADDRINUSE" rival))
         (is (= (:token d) after) "a daemon that cannot take the port leaves the running daemon's token alone")
         (is (= (:token again) (fs/readFileSync file "utf8")) "a new daemon replaces a stale file")
         (is (= 8r600 mode2))
         (p/all [((:stop! d)) ((:stop! again))]))))))

;; --- the recorded-request cap ----------------------------------------------------

(deftest the-daemon-caps-each-imposters-request-log
  (async done
    (settle done
     (p/let [d    (daemon/start! {:max-requests 2})
             port (free-port)
             _    (call (str (:admin d) "/imposters") {:method "POST" :body (imposter port [])})
             _    (p/all (for [i (range 3)] (call (str "http://127.0.0.1:" port "/r" i))))
             full (call (str (:admin d) "/imposters/" port))]
       (is (= 2 (count (get-in full [:json "requests"]))) "the log keeps the newest 2")
       (is (= 3 (get-in full [:json "numberOfRequests"])) "every request still counts")
       (is (= 1 (get-in full [:json "droppedRequests"])))
       ((:stop! d))))))

(deftest the-cap-comes-from-the-daemons-environment
  (let [env  (.-env js/process)
        prev (.-MOCKSYS_MAX_RECORDED_REQUESTS env)
        with (fn [v] (if (nil? v) (js-delete env "MOCKSYS_MAX_RECORDED_REQUESTS") (set! (.-MOCKSYS_MAX_RECORDED_REQUESTS env) v))
               (try (runtime/max-recorded-requests) (catch :default e (ex-message e))))]
    (try
      (is (= 10000 (with nil)) "10000 by default")
      (is (= 10000 (with "")))
      (is (= 5 (with "5")))
      (is (= 0 (with "0")) "0 keeps none")
      (doseq [bad ["-1" "1.5" "lots"]]
        (is (= (str "MOCKSYS_MAX_RECORDED_REQUESTS must be a whole number, got " (pr-str bad)) (with bad))))
      (finally (if (nil? prev) (js-delete env "MOCKSYS_MAX_RECORDED_REQUESTS") (set! (.-MOCKSYS_MAX_RECORDED_REQUESTS env) prev))))))
