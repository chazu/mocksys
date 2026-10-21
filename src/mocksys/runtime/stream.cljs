(ns mocksys.runtime.stream
  "An effect's stream answer on the wire (mocksys.effect says what one carries). `sse`
  writes server-sent events over the HTTP response; `websocket` takes over a socket
  the server upgraded. Each sends its scripted events in order, each after its delay,
  and every event published on its channel while it is open. Each inbound WebSocket
  message goes through the imposter, one at a time in arrival order, as a MESSAGE
  request with the upgrade's path, query and headers, and its reply becomes outbound
  messages. A client that leaves unsubscribes and stops its timers."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [mocksys.effect :as effect]
            [mocksys.runtime.imposter :as imposter]))

(defn- one-line
  "A field value without line breaks, which would start another field."
  [s]
  (str/replace (str s) #"[\r\n]+" " "))

(defn sse-frame
  "One event in the text/event-stream format. Each line of `data` gets its own data
   field, which the client joins back with line breaks."
  [{:strs [event id data]}]
  (str (when event (str "event: " (one-line event) "\n"))
       (when id (str "id: " (one-line id) "\n"))
       (apply str (map #(str "data: " % "\n") (.split data (js/RegExp. "\r\n|\r|\n"))))
       "\n"))

(defn- play!
  "Send `events` in order, each after its delay, then call `done`. Returns a function
   that cancels what is not sent yet."
  [events send! done]
  (let [timer (atom nil)
        step  (fn step [es]
                (if-let [[e & more] (seq es)]
                  (let [go #(do (send! e) (step more))]
                    (if (pos? (get e "delay" 0))
                      (reset! timer (js/setTimeout go (get e "delay")))
                      (go)))
                  (done)))]
    (step events)
    #(js/clearTimeout @timer)))

(defn- sse!
  "Stream server-sent events over `res`: `retry`, the script, then the channel. A
   stream with no channel ends after its script; one with a channel stays open until
   the client leaves, with a keep-alive comment every `keepAlive` ms. A client gone
   before the answer was ready (during a `wait`) gets nothing: its close has already
   fired, so nothing would clean up after it."
  [imp ^js res {:keys [status headers stream]}]
  (when-not (.-destroyed res)
    (let [{:strs [events channel retry keepAlive]} stream
          write!  #(when-not (.-writableEnded res) (.write res %))
          unsub   (if channel (effect/subscribe! (imposter/channels imp) channel #(write! (sse-frame %))) (fn []))
          beat    (when (pos? keepAlive) (js/setInterval #(write! ": keep-alive\n\n") keepAlive))
          cancel  (atom (fn []))
          stop!   (fn [] (unsub) (js/clearInterval beat) (@cancel))]
      (.on res "close" stop!)
      (.writeHead res status (clj->js headers))
      (.flushHeaders res)
      (when retry (write! (str "retry: " retry "\n\n")))
      (reset! cancel (play! events #(write! (sse-frame %)) #(when-not channel (stop!) (.end res)))))))

(defn- upgrade-required!
  "A plain request that reached a `websocket` answer."
  [_ ^js res _]
  (.writeHead res 426 #js {"Content-Type" "text/plain" "Upgrade" "websocket" "Connection" "close"})
  (.end res "this operation accepts a WebSocket upgrade"))

(def ^:private over-http
  "How a plain HTTP request is answered, by the kind of stream its effect answered with."
  {"sse" sse! "websocket" upgrade-required!})

(defn serve-http!
  "Answer a plain HTTP request whose outcome `out` is a stream."
  [imp res out]
  ((over-http (get-in out [:stream "kind"])) imp res out))

;; --- WebSockets -------------------------------------------------------------------

(defn- open? [^js ws] (= 1 (.-readyState ws)))

(defn- close-reason
  "`s` cut to the 123 bytes a close frame's reason holds."
  [s]
  (loop [s (str s)]
    (if (> (.byteLength js/Buffer s) 123) (recur (.slice s 0 -1)) s)))

(defn outbound
  "The messages a reply body sends: none for an empty body, one per element of a JSON
   array (a string as written, anything else as JSON), else the body as one."
  [body]
  (let [arr (when (str/starts-with? (str/triml body) "[") (try (js/JSON.parse body) (catch :default _ nil)))]
    (cond (= "" body)  []
          (array? arr) (mapv #(if (string? %) % (js/JSON.stringify %)) arr)
          :else        [body])))

(defn- no-proxy [_ _]
  (p/rejected (js/Error. "a WebSocket message cannot be proxied")))

(defn- reply!
  "Send inbound message `request` through the imposter and answer on `ws`: below 400
   with the body's messages, from 400 by closing with 4000 + the status."
  [imp ^js ws request]
  (-> (p/let [out (imposter/respond imp request {:proxy! no-proxy})
              _   (when (pos? (:wait out 0)) (p/delay (:wait out)))]
        (when (open? ws)
          (cond (:fault out)           (.terminate ws)
                (>= (:status out) 400) (.close ws (+ 4000 (:status out)) (close-reason (:body out)))
                :else                  (doseq [m (outbound (:body out))] (.send ws m)))))
      (p/catch (fn [e] (when (open? ws) (.close ws 4500 (close-reason (ex-message e))))))))

(defn websocket!
  "Serve an accepted WebSocket. `request` is the upgrade request and `out` its
   outcome, a `websocket` answer."
  [imp ^js ws request {:keys [stream]}]
  (let [{:strs [events channel]} stream
        send!  #(when (open? ws) (.send ws (get % "data")))
        unsub  (if channel (effect/subscribe! (imposter/channels imp) channel send!) (fn []))
        cancel (play! events send! (fn []))
        queue  (atom (p/resolved nil))]
    (.on ws "message" (fn [data _]
                        (let [m (assoc request "method" "MESSAGE" "body" (.toString data "utf8"))]
                          (swap! queue p/then #(reply! imp ws m)))))
    (.on ws "close" (fn [] (unsub) (cancel)))
    (.on ws "error" (fn [_]))))
