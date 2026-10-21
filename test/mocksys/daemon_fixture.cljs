(ns mocksys.daemon-fixture
  "An in-process daemon for tests, and the admin token each call to it carries."
  (:require [clojure.test :refer [is]]
            [promesa.core :as p]
            [mocksys.runtime :as runtime]))

(def ^:private tokens
  "The token of each in-process daemon, keyed by its admin URL."
  (atom {}))

(defn token
  "The token of the in-process daemon at `url`'s origin, or nil."
  [url]
  (get @tokens (.-origin (js/URL. url))))

(defn auth
  "The Authorization header a call to `url` needs, or none, so a call to a mock
   carries no header."
  [url]
  (if-let [t (token url)] {"Authorization" (str "Bearer " t)} {}))

(defn start!
  "`runtime/start!` on an ephemeral loopback port (`opts` overrides). Resolves the
   daemon with :admin, its URL, whose calls `auth` then authorizes."
  ([] (start! {}))
  ([opts]
   (p/let [d     (runtime/start! (merge {:host "127.0.0.1" :port 0} opts))
           admin (str "http://127.0.0.1:" (:port d))]
     (swap! tokens assoc admin (:token d))
     (assoc d :admin admin
              :stop! (fn [] (swap! tokens dissoc admin) ((:stop! d)))))))

(defn with-daemon
  "Run `(f admin-url)` against a fresh in-process daemon, report a rejection as a
   failure, then stop the daemon and call `done`."
  [done f]
  (p/let [d (start!)]
    (-> (p/do (f (:admin d)))
        (p/catch (fn [e] (is (nil? e) (str "unexpected rejection: " (ex-message e)))))
        (p/finally (fn [] (p/do ((:stop! d)) (done)))))))
