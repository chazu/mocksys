(ns mocksys.stream-daemon-test
  "SSE and WebSockets over real sockets, against an in-process daemon: scripted
   events and Last-Event-ID, a POST publishing to an open SSE stream and WebSocket, a
   WebSocket round trip through MESSAGE operations, a refused upgrade, and the
   cleanup a closed client leaves behind."
  (:require [clojure.test :refer [deftest testing is async]]
            [clojure.string :as str]
            [promesa.core :as p]
            ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:https" :as https]
            ["node:net" :as net]
            ["node:os" :as os]
            ["node:path" :as path]
            ["ws" :refer [WebSocket]]
            [mocksys.daemon-fixture :as daemon]
            [mocksys.runtime :as runtime]
            [mocksys.runtime.imposter :as imposter]
            [mocksys.store :as store]
            [mocksys.tls :as tls]))

(defn- free-port []
  (js/Promise.
   (fn [resolve]
     (let [srv (.createServer net)]
       (.listen srv 0 (fn [] (let [port (.-port (.address srv))] (.close srv #(resolve port)))))))))

(defn- call
  "A call to the admin API or a mock; resolves {:status :headers :text :json}."
  ([url] (call url {}))
  ([url {:keys [method body headers]}]
   (p/let [res  (js/fetch url (clj->js (cond-> {:method (or method "GET")
                                                 :headers (merge (daemon/auth url) headers)}
                                          body (assoc :body (if (string? body) body (js/JSON.stringify (clj->js body)))))))
           text (.text res)]
     {:status  (.-status res)
      :headers (js->clj (js/Object.fromEntries (.entries (.-headers res))))
      :text    text
      :json    (try (js->clj (js/JSON.parse text)) (catch :default _ nil))})))

(defn- until
  "Resolve once `(ready?)` holds, polling; reject naming `what` after 3s."
  [what ready?]
  (let [deadline (+ (js/Date.now) 3000)]
    (p/loop []
      (cond (ready?)                     true
            (> (js/Date.now) deadline)   (p/rejected (js/Error. (str "timed out waiting for " what)))
            :else                        (p/do (p/delay 10) (p/recur))))))

(defn- sse-open
  "Open an SSE stream; resolves {:status :headers :text (atom of what arrived) :close!}
   once its headers are in."
  [port path headers]
  (js/Promise.
   (fn [resolve reject]
     (let [text   (atom "")
           ^js rq (.get http #js {:host "127.0.0.1" :port port :path path :headers (clj->js headers)})]
       (.on rq "response"
            (fn [^js res]
              (.setEncoding res "utf8")
              (.on res "data" #(swap! text str %))
              (.on res "error" (fn [_]))
              (resolve {:status  (.-statusCode res)
                        :headers (js->clj (.-headers res))
                        :text    text
                        :ended?  #(.-complete res)
                        :close!  #(.destroy rq)})))
       (.on rq "error" reject)))))

(defn- ws-open
  "Open a WebSocket; resolves {:ws :messages (atom) :closed (atom of [code reason])}
   once it is open."
  [url]
  (js/Promise.
   (fn [resolve reject]
     (let [ws       (WebSocket. url)
           messages (atom [])
           closed   (atom nil)]
       (set! (.-onmessage ws) #(swap! messages conj (.-data %)))
       (set! (.-onclose ws) #(reset! closed [(.-code %) (.-reason %)]))
       (set! (.-onerror ws) #(reject (js/Error. "websocket failed")))
       (set! (.-onopen ws) #(resolve {:ws ws :messages messages :closed closed}))))))

(defn- imposter [port stubs]
  {"protocol" "http" "port" port "name" "t/streams" "recordRequests" true "stubs" stubs})

(defn- stub [method path effect & preds]
  {"predicates" (into [{"deepEquals" {"method" method}} {"matches" {"path" path}}] preds)
   "responses"  [{"effect" effect}]})

(def ^:private room {"pathRegex" "/rooms/([^/]+)"})

(def ^:private echo
  (stub "POST" "^/echo$" {"bind" {"t" {"body" "text"}} "respond" {"text" "got {t}"}}))

(def ^:private chat
  [(stub "GET" "^/count$"
         {"sse" {"retry" 500
                 "events" [{"id" "1" "data" "a"}
                           {"id" "2" "event" "tick" "data" "b\nc" "delay" 40}]}})
   (stub "GET" "^/rooms/[^/]+/events$"
         {"bind" {"room" room}
          "sse"  {"channel" "room-{room}" "keepAlive" 30 "events" [{"event" "joined" "data" {"room" "{room}"}}]}})
   (stub "GET" "^/rooms/[^/]+/socket$"
         {"bind"      {"room" room "tok" {"query" "token"}}
          "lookup"    {"bucket" "tokens" "key" "{tok}" "as" "who" "miss" {"status" 401 "text" "who are you"}}
          "websocket" {"channel" "room-{room}" "messages" [{"data" {"type" "welcome" "room" "{room}" "you" "{who}"}}]}})
   (stub "POST" "^/tokens$"
         {"bind" {"tok" {"body" "token"} "who" {"body" "who"}}
          "store" {"bucket" "tokens" "key" "{tok}" "value" "{who}"}
          "respond" {"status" 201}})
   (stub "MESSAGE" "^/rooms/[^/]+/socket$"
         {"bind" {"room" room}
          "respond" {"json" [{"type" "pong" "room" "{room}"} "and text"]}}
         {"equals" {"body" "ping"} "jsonpath" {"selector" "$.type"}})
   (stub "MESSAGE" "^/rooms/[^/]+/socket$"
         {"respond" {"status" 403 "text" "not allowed"}}
         {"equals" {"body" "shout"} "jsonpath" {"selector" "$.type"}})
   (stub "GET" "^/gate$"
         {"bind" {"x" {"query" "x"}} "respond" {"status" 401 "headers" {"X-Realm" "r={x}"}}})
   echo
   {"predicates" [{"deepEquals" {"path" "/late"}}]
    "responses"  [{"effect" {"sse" {"channel" "late"}} "behaviors" [{"wait" 300}]}]}
   (stub "POST" "^/rooms/[^/]+/messages$"
         {"bind"    {"room" room "text" {"body" "text"}}
          "publish" {"channel" "room-{room}" "event" "message" "id" "m1" "data" {"text" "{text}"}}
          "respond" {"status" 202}})])

(defn- with-chat
  "Run `(f port admin)` against the chat imposter on a fresh in-process daemon."
  [done f]
  (daemon/with-daemon done
    (fn [admin]
      (p/let [port (free-port)
              made (call (str admin "/imposters") {:method "POST" :body (imposter port chat)})]
        (is (= 201 (:status made)) (pr-str (:json made)))
        (f port admin)))))

(deftest sse-sends-its-script-then-ends
  (async done
    (with-chat done
      (fn [port _]
        (p/let [start  (js/Date.now)
                all    (call (str "http://127.0.0.1:" port "/count"))
                took   (- (js/Date.now) start)
                resume (call (str "http://127.0.0.1:" port "/count") {:headers {"Last-Event-ID" "1"}})]
          (is (= 200 (:status all)))
          (is (str/starts-with? (get-in all [:headers "content-type"]) "text/event-stream"))
          (is (= "retry: 500\n\nid: 1\ndata: a\n\nevent: tick\nid: 2\ndata: b\ndata: c\n\n" (:text all))
              "retry first, then each event; a line break in data becomes a second data line")
          (is (>= took 35) "the second event waits its delay")
          (is (= "retry: 500\n\nevent: tick\nid: 2\ndata: b\ndata: c\n\n" (:text resume))
              "Last-Event-ID resumes after that event"))))))

(deftest a-post-publishes-to-an-open-sse-stream
  (async done
    (with-chat done
      (fn [port admin]
        (p/let [s     (sse-open port "/rooms/r1/events" {})
                other (sse-open port "/rooms/r2/events" {})
                _     (until "the joined event" #(str/includes? @(:text s) "event: joined"))
                sent  (call (str "http://127.0.0.1:" port "/rooms/r1/messages") {:method "POST" :body {"text" "hi"}})
                _     (until "the published event" #(str/includes? @(:text s) "event: message"))
                _     (until "a keep-alive comment" #(str/includes? @(:text s) ": keep-alive\n\n"))
                full  (call (str admin "/imposters/" port))]
          (is (= 202 (:status sent)))
          (is (str/starts-with? @(:text s) "event: joined\ndata: {\"room\":\"r1\"}\n\n"))
          (is (str/includes? @(:text s) "event: message\nid: m1\ndata: {\"text\":\"hi\"}\n\n"))
          (is (not (str/includes? @(:text other) "message")) "another room's stream hears nothing")
          (is (not ((:ended? s))) "a stream with a channel stays open")
          (is (= [["GET" "/rooms/r1/events"] ["GET" "/rooms/r2/events"] ["POST" "/rooms/r1/messages"]]
                 (mapv (juxt #(get % "method") #(get % "path")) (get-in full [:json "requests"])))
              "the connects and the publishing POST are recorded, not the events")
          ((:close! s))
          ((:close! other)))))))

(deftest a-websocket-round-trip-goes-through-message-operations
  (async done
    (with-chat done
      (fn [port admin]
        (p/let [_     (call (str "http://127.0.0.1:" port "/tokens") {:method "POST" :body {"token" "t1" "who" "ann"}})
                c     (ws-open (str "ws://127.0.0.1:" port "/rooms/r1/socket?token=t1"))
                _     (until "the welcome" #(= 1 (count @(:messages c))))
                _     (.send (:ws c) (js/JSON.stringify #js {:type "ping"}))
                _     (until "the pong" #(= 3 (count @(:messages c))))
                _     (.send (:ws c) "not json")
                _     (call (str "http://127.0.0.1:" port "/rooms/r1/messages") {:method "POST" :body {"text" "hello"}})
                _     (until "the published message" #(= 4 (count @(:messages c))))
                _     (.send (:ws c) (js/JSON.stringify #js {:type "shout"}))
                _     (until "the close" #(some? @(:closed c)))
                full  (call (str admin "/imposters/" port))]
          (is (= ["{\"type\":\"welcome\",\"room\":\"r1\",\"you\":\"ann\"}"
                  "{\"type\":\"pong\",\"room\":\"r1\"}"
                  "and text"
                  "{\"text\":\"hello\"}"]
                 @(:messages c))
              "the scripted welcome, a JSON array reply as one message per element, nothing for an unmatched message, then the publish")
          (is (= [4403 "not allowed"] @(:closed c)) "a 403 reply closes with 4000 + status and the body as reason")
          (is (= [["GET" "/rooms/r1/socket" ""]
                  ["MESSAGE" "/rooms/r1/socket" "{\"type\":\"ping\"}"]
                  ["MESSAGE" "/rooms/r1/socket" "not json"]
                  ["MESSAGE" "/rooms/r1/socket" "{\"type\":\"shout\"}"]]
                 (->> (get-in full [:json "requests"])
                      (remove #(= "POST" (get % "method")))
                      (mapv (juxt #(get % "method") #(get % "path") #(get % "body")))))
              "the upgrade and every inbound message are recorded")
          (is (= "t1" (get-in full [:json "requests" 2 "query" "token"])) "a message carries the upgrade's query"))))))

(defn- raw-upgrade
  "Ask for a WebSocket upgrade with node's http client; resolves the HTTP status of
   a refusal, or :upgraded."
  [port path]
  (js/Promise.
   (fn [resolve reject]
     (let [^js rq (.request http #js {:host "127.0.0.1" :port port :path path
                                      :headers #js {"Connection" "Upgrade" "Upgrade" "websocket"
                                                    "Sec-WebSocket-Version" "13"
                                                    "Sec-WebSocket-Key" "dGhlIHNhbXBsZSBub25jZQ=="}})]
       (.on rq "upgrade" (fn [_ ^js sock _] (.destroy sock) (resolve :upgraded)))
       (.on rq "response" (fn [^js res]
                            (let [body (atom "")]
                              (.setEncoding res "utf8")
                              (.on res "data" #(swap! body str %))
                              (.on res "end" #(resolve [(.-statusCode res) @body])))))
       (.on rq "error" reject)
       (.end rq)))))

(defn- h2c-post
  "POST `body` asking to upgrade to h2c, as curl --http2 does; resolves the answer's
   [status body]."
  [port path body]
  (js/Promise.
   (fn [resolve reject]
     (let [^js rq (.request http #js {:host "127.0.0.1" :port port :path path :method "POST"
                                      :headers #js {"Connection" "Upgrade, HTTP2-Settings" "Upgrade" "h2c"
                                                    "HTTP2-Settings" "AAMAAABkAAQCAAAAAAIAAAAA"
                                                    "Content-Type" "application/json"}})]
       (.on rq "response" (fn [^js res]
                            (let [text (atom "")]
                              (.setEncoding res "utf8")
                              (.on res "data" #(swap! text str %))
                              (.on res "end" #(resolve [(.-statusCode res) @text])))))
       (.on rq "error" reject)
       (.end rq body)))))

(def ^:private filters-upgrades?
  "Whether this node's HTTP server takes shouldUpgradeCallback (node 22.21+, 24.9+)."
  (fn? (.-shouldUpgradeCallback (.createServer http))))

(deftest an-upgrade-other-than-websocket-is-an-ordinary-request
  (async done
    (with-chat done
      (fn [ws-port admin]
        (p/let [port (free-port)
                made (call (str admin "/imposters") {:method "POST" :body (imposter port [echo])})
                r    (h2c-post port "/echo" "{\"text\":\"hello\"}")
                ws-r (h2c-post ws-port "/echo" "{\"text\":\"hello\"}")]
          (is (= 201 (:status made)))
          (is (= [200 "got hello"] r) "an imposter without a websocket answer takes no upgrade, on any node")
          (when filters-upgrades?
            (is (= [200 "got hello"] ws-r) "nor does one with a websocket answer, where node can tell the upgrades apart")))))))

(deftest a-refused-upgrade-cannot-inject-headers
  (async done
    (with-chat done
      (fn [port _]
        (p/let [ok  (raw-upgrade port "/gate?x=abc")
                bad (raw-upgrade port "/gate?x=a%0d%0aSet-Cookie:%20pwned=1")]
          (is (= 401 (first ok)))
          (is (= 500 (first bad)) "a header that would split the response is refused, as for a plain request"))))))

(deftest an-upgrade-is-a-request-like-any-other
  (async done
    (with-chat done
      (fn [port _]
        (p/let [refused  (raw-upgrade port "/rooms/r1/socket?token=nope")
                no-stub  (raw-upgrade port "/nowhere")
                to-sse   (raw-upgrade port "/count")
                _        (call (str "http://127.0.0.1:" port "/tokens") {:method "POST" :body {"token" "t1" "who" "ann"}})
                plain    (call (str "http://127.0.0.1:" port "/rooms/r1/socket?token=t1"))
                accepted (raw-upgrade port "/rooms/r1/socket?token=t1")]
          (is (= [401 "who are you"] refused) "a gate in front of websocket refuses with its own status")
          (is (= [200 ""] no-stub) "an upgrade no stub answers gets the empty default")
          (is (= 200 (first to-sse)) "an answer that is not a websocket is written as plain HTTP")
          (is (= 426 (:status plain)) "a plain GET that reaches a websocket answer is told to upgrade")
          (is (= :upgraded accepted)))))))

(deftest streams-run-over-tls
  (async done
    (daemon/with-daemon done
      (fn [admin]
        (p/let [ca    (let [home (fs/mkdtempSync (path/join (os/tmpdir) "mocksys-stream-tls-"))]
                        (with-redefs [store/root home] (tls/ensure-ca!)))
                port  (free-port)
                stubs [(stub "GET" "^/count$" {"sse" {"events" [{"data" "a"}]}})
                       (stub "GET" "^/socket$" {"websocket" {"messages" [{"data" "welcome"}]}})]
                made  (call (str admin "/imposters") {:method "POST" :body (assoc (imposter port stubs) "tls" (tls/issue ca))})
                sse   (js/Promise.
                       (fn [resolve reject]
                         (-> (.get https #js {:host "127.0.0.1" :port port :path "/count" :servername "localhost" :ca (:cert ca)}
                                   (fn [^js res]
                                     (let [text (atom "")]
                                       (.setEncoding res "utf8")
                                       (.on res "data" #(swap! text str %))
                                       (.on res "end" #(resolve @text)))))
                             (.on "error" reject))))
                hello (js/Promise.
                       (fn [resolve reject]
                         (let [^js c (WebSocket. (str "wss://localhost:" port "/socket") #js {:ca (:cert ca)})]
                           (.on c "message" (fn [data] (.close c) (resolve (.toString data))))
                           (.on c "error" reject))))]
          (is (= 201 (:status made)) (pr-str (:json made)))
          (is (str/includes? sse "data: a") "SSE over https")
          (is (= "welcome" hello) "a wss upgrade is accepted"))))))

(deftest a-closed-client-leaves-no-subscriber
  (async done
    (let [registry (atom {})]
      (-> (p/let [port (free-port)
                  _    (#'runtime/create! registry {} (js/JSON.stringify (clj->js (imposter port chat))))
                  imp  (get-in @registry [port :imp])
                  open #(vec (js/Object.keys (imposter/channels imp)))
                  s    (sse-open port "/rooms/a/events" {})
                  _    (imposter/respond imp {"method" "POST" "path" "/tokens" "query" {} "headers" {}
                                              "body" "{\"token\":\"t\",\"who\":\"w\"}"} {})
                  c    (ws-open (str "ws://127.0.0.1:" port "/rooms/b/socket?token=t"))
                  _    (until "both subscribed" #(= ["room-a" "room-b"] (sort (open))))
                  _    ((:close! s))
                  _    (.close (:ws c))
                  _    (until "both unsubscribed" #(empty? (open)))
                  ^js early (.get http #js {:host "127.0.0.1" :port port :path "/late"})
                  _    (.on early "error" (fn [_]))
                  _    (p/delay 50)
                  _    (.destroy early)
                  _    (p/delay 450)
                  _    (is (empty? (open)) "a client that left during the answer's wait is never subscribed")
                  c2   (ws-open (str "ws://127.0.0.1:" port "/rooms/b/socket?token=t"))
                  _    (until "subscribed again" #(= ["room-b"] (open)))
                  _    (#'runtime/delete! registry port)
                  _    (until "the delete closed the socket" #(some? @(:closed c2)))]
            (is (empty? (open)) "deleting the imposter cuts its sockets, which unsubscribes them"))
          (p/catch (fn [e] (is (nil? e) (ex-message e))))
          (p/finally (fn [] (p/do (p/all (map #(#'runtime/delete! registry %) (keys @registry))) (done))))))))
