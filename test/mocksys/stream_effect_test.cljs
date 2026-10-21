(ns mocksys.stream-effect-test
  "The stream answers (sse, websocket) and publish, as the effect language checks and
   runs them: what a stream answer carries, Last-Event-ID, and who a publish reaches."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [mocksys.effect :as effect]
            [mocksys.harness :as h]))

(defn- op [effect] {"id" "op" "request" {"method" "GET" "path" "/"} "effect" effect})

(defn- errors-of [e] (str/join "\n" (effect/errors e)))

(deftest stream-answers-are-checked
  (testing "an sse answer needs events, a channel or both"
    (is (str/includes? (errors-of {"sse" {}}) "sse needs events, a channel or both"))
    (is (= [] (effect/errors {"sse" {"channel" "c"}})))
    (is (= [] (effect/errors {"sse" {"events" [{"data" "x"}]}}))))
  (testing "unknown fields are refused, naming the field"
    (is (str/includes? (errors-of {"sse" {"channel" "c" "replay" true}}) "sse: unknown field replay"))
    (is (str/includes? (errors-of {"sse" {"events" [{"data" "x" "colour" 1}]}}) "sse.events[0]: unknown field colour"))
    (is (str/includes? (errors-of {"websocket" {"messages" [{"data" "x" "event" "e"}]}}) "websocket.messages[0]: unknown field event")))
  (testing "an event needs data or dataVar"
    (is (str/includes? (errors-of {"sse" {"events" [{"event" "e"}]}}) "sse.events[0] needs data or dataVar")))
  (testing "events is a list; delays, retry and keepAlive are whole milliseconds"
    (is (str/includes? (errors-of {"sse" {"events" {"data" "x"}}}) "sse.events must be a list"))
    (is (str/includes? (errors-of {"sse" {"events" [{"data" "x" "delay" -1}]}}) "sse.events[0].delay must be a whole number of milliseconds"))
    (is (str/includes? (errors-of {"sse" {"channel" "c" "retry" 1.5}}) "sse.retry must be a whole number of milliseconds"))
    (is (str/includes? (errors-of {"sse" {"channel" "c" "keepAlive" "1s"}}) "sse.keepAlive must be a whole number of milliseconds")))
  (testing "a channel cannot take a name JavaScript gives meaning to"
    (is (str/includes? (errors-of {"sse" {"channel" "__proto__"}}) "sse.channel must be a string other than __proto__"))
    (is (str/includes? (errors-of {"publish" {"channel" "constructor" "data" "x"} "respond" {}}) "publish.channel must be a string other than")))
  (testing "one terminal: a stream cannot share the answer with respond or a collection verb"
    (is (str/includes? (errors-of {"sse" {"channel" "c"} "respond" {}}) "respond and sse both answer"))
    (is (str/includes? (errors-of {"websocket" {} "create" {"collection" "c"}}) "more than one terminal action (create, websocket)")))
  (testing "publish needs a channel and data, and something else to answer"
    (is (str/includes? (errors-of {"publish" {"data" "x"} "respond" {}}) "publish.channel is required"))
    (is (str/includes? (errors-of {"publish" {"channel" "c"} "respond" {}}) "publish needs data or dataVar"))
    (is (str/includes? (errors-of {"publish" {"channel" "c" "data" "x"}}) "effect has no `respond`"))
    (is (= [] (effect/errors {"publish" [{"channel" "a" "data" "x"} {"channel" "b" "dataVar" "v"}] "respond" {}})))))

(deftest an-sse-answer-carries-its-interpolated-script
  (let [r (h/run (op {"bind" {"job" {"pathRegex" "/jobs/([^/]+)/events$"}}
                      "sse"  {"channel" "job-{job}" "retry" 2000 "headers" {"X-Job" "{job}"}
                              "events" [{"event" "status" "id" "1" "data" {"job" "{job}" "pct" 0}}
                                        {"id" "2" "data" "line one\nline two" "delay" 50}]}})
                 {:path "/jobs/j7/events"})]
    (is (= 200 (:status r)))
    (is (= "text/event-stream" (get-in r [:headers "Content-Type"])))
    (is (= "j7" (get-in r [:headers "X-Job"])))
    (is (= {"kind" "sse" "channel" "job-j7" "retry" 2000 "keepAlive" 15000
            "events" [{"event" "status" "id" "1" "data" "{\"job\":\"j7\",\"pct\":0}"}
                      {"id" "2" "data" "line one\nline two" "delay" 50}]}
           (:stream r)))))

(deftest last-event-id-resumes-the-script
  (let [e   (op {"sse" {"events" [{"id" "a" "data" "1"} {"id" "b" "data" "2"} {"id" "c" "data" "3"}]}})
        ids #(mapv (fn [ev] (get ev "data")) (get-in % [:stream "events"]))]
    (is (= ["1" "2" "3"] (ids (h/run e {}))))
    (is (= ["3"] (ids (h/run e {:headers {"Last-Event-ID" "b"}}))))
    (is (= [] (ids (h/run e {:headers {"last-event-id" "c"}}))))
    (is (= ["1" "2" "3"] (ids (h/run e {:headers {"Last-Event-ID" "zzz"}}))) "an unknown id replays the whole script")))

(deftest a-websocket-answer-carries-its-messages
  (let [r (h/run (op {"bind" {"room" {"query" "room"}}
                      "websocket" {"channel" "room-{room}" "messages" [{"data" {"hello" "{room}"}} {"data" "bye" "delay" 10}]}})
                 {:query {"room" "r1"}})]
    (is (= {"kind" "websocket" "channel" "room-r1"
            "events" [{"data" "{\"hello\":\"r1\"}"} {"data" "bye" "delay" 10}]}
           (:stream r)))))

(defn- listen
  "Subscribe to `channel` on `channels`; returns an atom of what arrives."
  [channels channel]
  (let [got (atom [])]
    (effect/subscribe! channels channel #(swap! got conj %))
    got))

(deftest publish-reaches-the-channel-after-a-successful-answer
  (let [channels (effect/new-channels)
        state    (h/fresh-state)
        opts     {:channels channels :collections {"msgs" {"seed" []}}}
        room1    (listen channels "room-1")
        room2    (listen channels "room-2")
        post     (op {"bind"    {"room" {"query" "room"} "text" {"body" "text"}}
                      "create"  {"collection" "msgs" "body" {"id" "m1" "text" "{text}"} "as" "msg"}
                      "publish" [{"channel" "room-{room}" "event" "message" "id" "m1" "dataVar" "msg"}
                                 {"channel" "room-{room}" "data" "plain {text}"}]})]
    (is (= 201 (:status (h/run post {:method "POST" :query {"room" "1"} :body {"text" "hi"}} opts state))))
    (is (= [{"event" "message" "id" "m1" "data" "{\"id\":\"m1\",\"text\":\"hi\"}"}
            {"data" "plain hi"}]
           @room1)
        "each publish in order, after the terminal bound `msg`")
    (is (= [] @room2) "another channel hears nothing")
    (testing "an answer of 400 or more publishes nothing, nor does a read that misses"
      (h/run (op {"publish" {"channel" "room-1" "data" "x"} "respond" {"status" 409}}) {} opts state)
      (h/run (op {"lookup" {"bucket" "b" "key" "k"} "publish" {"channel" "room-1" "data" "x"} "respond" {}}) {} opts state)
      (h/run (op {"lookup" {"bucket" "b" "key" "k" "miss" {"status" 200}} "publish" {"channel" "room-1" "data" "x"} "respond" {}}) {} opts state)
      (is (= 2 (count @room1))))))

(deftest channels-are-keys-only
  (let [channels (effect/new-channels)
        got      (listen channels "__proto__")
        run      #(h/run (op {"bind" {"c" {"query" "c"}} "publish" {"channel" "{c}" "data" "x"} "respond" {}})
                         {:query {"c" %}} {:channels channels})]
    (run "__proto__")
    (run "toString")
    (is (= [{"data" "x"}] @got) "a channel named __proto__ from data is only a key")
    (is (nil? (js/Object.getPrototypeOf channels)))))

(deftest unsubscribing-removes-an-empty-channel
  (let [channels (effect/new-channels)
        stop-a   (effect/subscribe! channels "c" (fn [_] :a))
        stop-b   (effect/subscribe! channels "c" (fn [_] :b))]
    (stop-a)
    (is (= ["c"] (vec (js/Object.keys channels))))
    (stop-b)
    (is (= [] (vec (js/Object.keys channels))))
    (let [stop-c (effect/subscribe! channels "c" identity)]
      (stop-a)
      (is (= ["c"] (vec (js/Object.keys channels))) "a stale unsubscribe leaves a newer subscriber alone")
      (stop-c))))

(deftest a-failing-subscriber-does-not-fail-the-publisher
  (let [channels (effect/new-channels)
        got      (atom [])]
    (effect/subscribe! channels "c" (fn [_] (throw (js/Error. "socket gone"))))
    (effect/subscribe! channels "c" #(swap! got conj %))
    (is (= 200 (:status (h/run (op {"publish" {"channel" "c" "data" "x"} "respond" {}}) {} {:channels channels}))))
    (is (= [{"data" "x"}] @got))))
