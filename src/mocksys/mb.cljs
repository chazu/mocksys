(ns mocksys.mb
  "Client for the mocksys daemon's admin API, and the daemon's lifecycle.

  mocksys never writes runtime config files. It drives a live daemon
  (`mocksys daemon`, see mocksys.runtime) over its admin API on 127.0.0.1: create
  proxy imposters, pull recorded responses back, post compiled imposters. The
  namespace keeps its historical name; its API mirrors the Mountebank admin calls
  the daemon implements."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:net" :as net]
            ["node:path" :as path]
            [mocksys.store :as store]))

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
  ;; a bad MOCKSYS_ADMIN_PORT must surface, not be read as "down" by the catch below
  (admin-port)
  (-> (fetch-json "GET" "/")
      (p/then (fn [{:keys [body]}] (if (= "mocksys" (get body "name")) :up :foreign)))
      (p/catch (fn [e] (if (instance? js/SyntaxError e) :foreign :down)))))

(defn up?
  "True if the mocksys daemon is answering on the admin port."
  []
  (p/let [s (daemon-status)] (= :up s)))

(defn daemon-command
  "How to re-run mocksys as the daemon, as [cmd args], from the running process's
   {:exec-path :script :bun?}. A bun-compiled binary is its own runtime and keeps a
   virtual script path under /$bunfs/; node, or bun running a script, re-runs the
   script."
  [{:keys [exec-path script bun?]}]
  (if (and bun? (str/starts-with? (str script) "/$bunfs/"))
    [exec-path ["daemon"]]
    [exec-path [script "daemon"]]))

(defn- this-process []
  {:exec-path (.-execPath js/process)
   :script    (aget (.-argv js/process) 1)
   :bun?      (some? (.. js/process -versions -bun))})

(defn log-path
  "Where the daemon's output goes, truncated on each start."
  []
  (path/join store/root "daemon.log"))

(defn spawn-daemon!
  "Start `[cmd args]` detached with its stdout and stderr in `log`. Returns an atom
   that receives the reason if the process cannot start or exits."
  [[cmd args] log]
  (fs/mkdirSync (path/dirname log) #js {:recursive true})
  (let [fd    (fs/openSync log "w")
        ended (atom nil)
        child (cp/spawn cmd (clj->js args) #js {:detached true :stdio #js ["ignore" fd fd]})]
    (fs/closeSync fd)
    (.on child "error" (fn [^js e] (reset! ended (str "could not start (" cmd "): " (.-message e)))))
    (.on child "exit" (fn [code signal]
                        (reset! ended (str "exited " (if signal (str "on " signal) (str "with status " code))
                                           " before it came up"))))
    (.unref child)
    ended))

(defn- log-tail [log]
  (let [text (try (fs/readFileSync log "utf8") (catch :default _ ""))
        tail (take-last 10 (remove str/blank? (str/split-lines text)))]
    (when (seq tail)
      (str "\n" (str/join "\n" (map #(str "  " %) tail)) "\n(full log: " log ")"))))

(defn- startup-error [reason log]
  (js/Error. (str "the mocksys daemon " reason (or (log-tail log) (str " (its log, " log ", is empty)")))))

(defn- wait-up [ended log deadline]
  (p/let [ok (up?)]
    (cond
      ok                       true
      (some-> ended deref)     (throw (startup-error @ended log))
      (> (js/Date.now) deadline) (throw (startup-error (str "did not come up on 127.0.0.1:" (admin-port)) log))
      :else                    (p/do (p/delay 100) (wait-up ended log deadline)))))

(defn- start-daemon! [{:keys [spawn! log timeout-ms]}]
  (wait-up (spawn! log) log (+ (js/Date.now) timeout-ms)))

(defn ensure-up!
  "Make sure the daemon is listening; start one if not. `opts` replaces how it is
   started, for tests: :spawn! (fn [log] ended-atom-or-nil), :log and :timeout-ms."
  ([] (ensure-up! {}))
  ([opts]
   (let [opts (merge {:log        (log-path)
                      :spawn!     #(spawn-daemon! (daemon-command (this-process)) %)
                      :timeout-ms 10000}
                     opts)]
     (p/let [s (daemon-status)]
       (case s
         :up      true
         :foreign (throw (js/Error. (str "port " (admin-port) " is taken by something that is not the mocksys "
                                          "daemon; free it or set MOCKSYS_ADMIN_PORT")))
         :down    (start-daemon! opts))))))

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
