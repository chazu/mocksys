(ns mocksys.mb
  "Client for the mocksys daemon's admin API, and the daemon's lifecycle.

  mocksys never writes runtime config files. It drives a live daemon
  (`mocksys daemon`, see mocksys.runtime) over its admin API on 127.0.0.1: create
  proxy imposters, pull recorded responses back, post compiled imposters. The
  namespace keeps its historical name; its API mirrors the Mountebank admin calls
  the daemon implements."
  (:require [promesa.core :as p]
            ["node:child_process" :as cp]
            ["node:net" :as net]))

(def default-admin-port 2626)

(defn admin-port
  "The daemon's admin port: $MOCKSYS_ADMIN_PORT, else 2626 (not Mountebank's 2525,
   so a real Mountebank is never mistaken for the daemon)."
  []
  (let [v (.-MOCKSYS_ADMIN_PORT js/process.env)]
    (if (and v (not= v ""))
      (let [n (js/Number v)]
        (if (and (js/Number.isInteger n) (< 0 n 65536))
          n
          (throw (js/Error. (str "MOCKSYS_ADMIN_PORT must be a port number, got " (pr-str v))))))
      default-admin-port)))

(defn- base [] (str "http://127.0.0.1:" (admin-port)))

;; --- HTTP -----------------------------------------------------------------

(defn fetch-json
  "Call the admin API. Returns {:status int :body <clj|nil>}.
   `body` (clj) is JSON-encoded when present."
  ([method path] (fetch-json method path nil))
  ([method path body]
   (let [opts #js {:method method
                   :headers #js {"Content-Type" "application/json"}}]
     (when (some? body)
       (set! (.-body opts) (js/JSON.stringify (clj->js body))))
     (p/let [res  (js/fetch (str (base) path) opts)
             text (.text res)]
       {:status (.-status res)
        :body   (when (seq text) (js->clj (js/JSON.parse text)))}))))

;; --- Lifecycle ------------------------------------------------------------

(defn- daemon-status
  "What answers on the admin port: :up (the mocksys daemon), :foreign (something
   else), or :down (nothing)."
  []
  (-> (fetch-json "GET" "/")
      (p/then (fn [{:keys [body]}] (if (= "mocksys" (get body "name")) :up :foreign)))
      (p/catch (fn [e] (if (instance? js/SyntaxError e) :foreign :down)))))

(defn up?
  "True if the mocksys daemon is answering on the admin port."
  []
  (p/let [s (daemon-status)] (= :up s)))

(defn- daemon-command
  "How to re-run this mocksys as the daemon, as [cmd args]. The bun binary is its own
   runtime (execPath is mocksys); under node, execPath is node and argv[1] the script."
  []
  (let [exec (.-execPath js/process)]
    (if (.. js/process -versions -bun)
      [exec ["daemon"]]
      [exec [(aget (.-argv js/process) 1) "daemon"]])))

(defn- spawn-daemon! []
  (let [[cmd args] (daemon-command)
        child (.spawn cp cmd (clj->js args) #js {:detached true :stdio "ignore"})]
    (.unref child)))

(defn- wait-up [tries]
  (p/let [ok (up?)]
    (cond
      ok            true
      (zero? tries) (throw (js/Error. (str "the mocksys daemon did not come up on 127.0.0.1:" (admin-port))))
      :else         (p/do (p/delay 200) (wait-up (dec tries))))))

(defn ensure-up!
  "Make sure the daemon is listening; start one if not."
  []
  (p/let [s (daemon-status)]
    (case s
      :up      true
      :foreign (throw (js/Error. (str "port " (admin-port) " is taken by something that is not the mocksys "
                                      "daemon; free it or set MOCKSYS_ADMIN_PORT")))
      :down    (do (spawn-daemon!) (wait-up 50)))))

(defn free-port
  "Ask the OS for a free ephemeral port (bind :0, read the assignment, release).
   Small TOCTOU window before the daemon claims it, fine for a local dev tool."
  []
  (js/Promise.
   (fn [resolve reject]
     (let [srv (.createServer net)]
       (.on srv "error" reject)
       (.listen srv 0 (fn []
                        (let [port (.-port (.address srv))]
                          (.close srv (fn [] (resolve port))))))))))

;; --- Imposters ------------------------------------------------------------

(defn get-imposter
  "Full imposter on `port` (includes recorded `requests` when recordRequests=true)."
  [port]
  (p/let [{:keys [status body]} (fetch-json "GET" (str "/imposters/" port))]
    (when (= 200 status) body)))

(defn clear-requests! [port]
  (fetch-json "DELETE" (str "/imposters/" port "/savedRequests")))

(defn post-imposter! [imposter]
  (p/let [{:keys [status body]} (fetch-json "POST" "/imposters" imposter)]
    (if (= 201 status)
      body
      (throw (js/Error. (str "the daemon rejected the imposter (" status "): "
                             (js/JSON.stringify (clj->js body))))))))

(defn delete-imposter!
  "Remove the imposter on `port` if present (idempotent — frees the port)."
  [port]
  (fetch-json "DELETE" (str "/imposters/" port)))

(defn list-imposters
  "Live imposters as [{:port :name}] (empty if the daemon is down). `name` carries the
   scenario id, so the catalog can track a scenario regardless of its port."
  []
  (p/let [running (up?)]
    (if-not running
      []
      (p/let [{:keys [body]} (fetch-json "GET" "/imposters")]
        (mapv (fn [imp] {:port (get imp "port") :name (get imp "name")})
              (get body "imposters"))))))

(defn get-replayable
  "Pull the imposter on `port` back as a pure-replay object: recorded responses
  baked into stubs, proxy stripped. This is the heart of `freeze`."
  [port]
  (p/let [{:keys [status body]}
          (fetch-json "GET" (str "/imposters/" port
                                 "?replayable=true&removeProxies=true"))]
    (if (= 200 status)
      body
      (throw (js/Error. (str "No recording on port " port
                             " (status " status "). Did `record` run?"))))))

(defn proxy-imposter
  "An imposter that records everything it proxies to `target`, generating
  replay stubs keyed on method+path (deliberately loose to avoid overfitting).
  Tagged with the scenario `name` so the catalog can track it."
  [port target name]
  {:port     port
   :name     name
   :protocol "http"
   :stubs    [{:responses
               [{:proxy {:to                  target
                         :mode                "proxyOnce"
                         :predicateGenerators [{:matches {:method true
                                                          :path   true}}]}}]}]})
