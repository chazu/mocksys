(ns mocksys.stream-wire-test
  "What a stream puts on the wire: an SSE frame, and the messages a WebSocket reply
   body sends."
  (:require [clojure.test :refer [deftest is]]
            [mocksys.runtime.stream :as stream]))

(deftest an-sse-frame-cannot-be-forged-through-event-or-id
  (is (= "event: message data: forged\nid: 1 event: admin\ndata: d\n\n"
         (stream/sse-frame {"event" "message\r\ndata: forged" "id" "1\nevent: admin" "data" "d"}))
      "a line break in event or id becomes a space, so it starts no field of its own"))

(deftest a-reply-body-becomes-outbound-messages
  (is (= [] (stream/outbound "")))
  (is (= ["1" "a" "{\"b\":2}"] (stream/outbound " [1, \"a\", {\"b\": 2}]")) "a JSON array, one message per element")
  (is (= ["[not json"] (stream/outbound "[not json")) "a body that only starts like an array is one message")
  (is (= ["{\"a\":1}"] (stream/outbound "{\"a\":1}")) "any other body is one message"))
