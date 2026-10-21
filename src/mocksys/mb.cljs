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
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:net" :as net]
            ["node:path" :as path]
            [mocksys.store :as store]
            [mocksys.version :refer [version]]))

(def default-admin-port 2626)

(defn- real-path
  "`p` with its links resolved as far as it exists, so a store named through a link,
   before or after it is created, is the same store."
  [p]
  (try (fs/realpathSync p)
       (catch :default _
         (let [parent (path/dirname p)]
           (if (= parent p) p (path/join (real-path parent) (path/basename p)))))))

(defn- store-admin-port
  "A port in 26000-26999 that a stable hash of the store's path picks. The range is
   below the ephemeral ranges of Linux, macOS and Windows, where mocks get their
   ports, so a mock never takes it."
  [root]
  (+ 26000 (mod (.readUInt32BE (.digest (.update (crypto/createHash "sha256") root)) 0) 1000)))

(defn admin-port
  "The daemon's admin port: $MOCKSYS_ADMIN_PORT, else 2626 for the default store
   (not Mountebank's 2525, so a real Mountebank is never mistaken for the daemon),
   else a port derived from the store's path, since a daemon serves one store."
  []
  (let [v (.-MOCKSYS_ADMIN_PORT js/process.env)]
    (if (and v (not= v ""))
      (let [n (js/Number v)]
        (if (and (js/Number.isInteger n) (< 0 n 65536))
          n
          (throw (js/Error. (str "MOCKSYS_ADMIN_PORT must be a port number, got " (pr-str v))))))
      (let [root (real-path store/root)]
        (if (= root (real-path store/default-root)) default-admin-port (store-admin-port root))))))

(defn- base [] (str "http://127.0.0.1:" (admin-port)))

(defn token-path
  "Where the daemon on `port` writes its admin token. One file per port, so daemons
   that share a store on different ports keep their own."
  [port]
  (path/join store/root (str "daemon-" port ".token")))

(defn- read-token []
  (try (str/trim (fs/readFileSync (token-path (admin-port)) "utf8"))
       (catch :default _ nil)))

;; --- HTTP -----------------------------------------------------------------

(defn- call
  "One admin call with the store's token, as {:status int :body <clj|nil>}."
  [method path body]
  (let [token (read-token)
        opts  #js {:method  method
                   :headers (clj->js (cond-> {"Content-Type" "application/json"}
                                       token (assoc "Authorization" (str "Bearer " token))))}]
    (when (some? body)
      (set! (.-body opts) (js/JSON.stringify (clj->js body))))
    (p/let [res  (js/fetch (str (base) path) opts)
            text (.text res)]
      {:status (.-status res)
       :body   (when (seq text) (js->clj (js/JSON.parse text)))})))

(defn- another-store-error
  "`pid` is the one the daemon reports on GET /, which needs no token."
  [pid]
  (js/Error. (str "the mocksys daemon on 127.0.0.1:" (admin-port) " belongs to another store or user "
                  "(its token is not the one in " (token-path (admin-port)) "). Use the MOCKSYS_HOME it was "
                  "started with, " (when pid (str "stop it (pid " pid "), "))
                  "or set MOCKSYS_ADMIN_PORT to another port to run a daemon for this store")))

(defn fetch-json
  "Call the admin API. Returns {:status int :body <clj|nil>}.
   `body` (clj) is JSON-encoded when present. A 401 throws: the daemon on the port
   was started from another store, or by another user."
  ([method path] (fetch-json method path nil))
  ([method path body]
   (p/let [{:keys [status] :as res} (call method path body)]
     (if (= 401 status)
       (p/let [ident (-> (call "GET" "/" nil) (p/then :body) (p/catch (constantly nil)))]
         (throw (another-store-error (when (map? ident) (let [pid (get ident "pid")] (when (integer? pid) pid))))))
       res))))

;; --- Lifecycle ------------------------------------------------------------

(declare list-imposters)

(defn- daemon-status
  "What answers on the admin port: {:state :up :version :pid} for the mocksys
   daemon, {:state :foreign} for something else, {:state :down} for nothing."
  []
  ;; a bad MOCKSYS_ADMIN_PORT must surface, not be read as "down" by the catch below
  (admin-port)
  (-> (call "GET" "/" nil)
      (p/then (fn [{:keys [body]}]
                (if (and (map? body) (= "mocksys" (get body "name")))
                  {:state :up :version (get body "version") :pid (get body "pid")}
                  {:state :foreign})))
      (p/catch (fn [e] {:state (if (instance? js/SyntaxError e) :foreign :down)}))))

(defn up?
  "True if the mocksys daemon is answering on the admin port."
  []
  (p/let [s (daemon-status)] (= :up (:state s))))

(defn- foreign-error []
  (js/Error. (str "port " (admin-port) " is taken by something that is not the mocksys "
                  "daemon; free it or set MOCKSYS_ADMIN_PORT")))

(defn- wait-down [deadline pid]
  (p/let [running (up?)]
    (cond
      (not running)              true
      (> (js/Date.now) deadline) (throw (js/Error. (str "the mocksys daemon (pid " pid ") did not stop; kill " pid)))
      :else                      (p/do (p/delay 100) (wait-down deadline pid)))))

(defn stop-daemon!
  "Stop the daemon on the admin port. Resolves the mocks it was serving, as
   [{:port :name}], or nil when no daemon was running."
  []
  (p/let [{:keys [state pid]} (daemon-status)]
    (case state
      :down    nil
      :foreign (throw (foreign-error))
      :up      (p/let [running          (list-imposters)
                       {:keys [status]} (fetch-json "DELETE" "/")
                       _                (when-not (= 200 status)
                                          (throw (js/Error. (str "the mocksys daemon (pid " pid ") is too old to be "
                                                                 "stopped this way; stop it with: kill " pid))))
                       _                (wait-down (+ (js/Date.now) 10000) pid)]
                 running))))

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
      ok                         true
      (some-> ended deref)       (throw (startup-error @ended log))
      (> (js/Date.now) deadline) (throw (startup-error (str "did not come up on 127.0.0.1:" (admin-port)) log))
      :else                      (p/do (p/delay 100) (wait-up ended log deadline)))))

(defn- start-daemon! [{:keys [spawn! log timeout-ms]}]
  (wait-up (spawn! log) log (+ (js/Date.now) timeout-ms)))

(defn- restart-notice [old-version mocks]
  (str "mocksys: replacing the daemon on 127.0.0.1:" (admin-port) " (it runs "
       (if old-version (str "mocksys " old-version) "an older mocksys") ", this is " version "); "
       (if (seq mocks)
         (str "its " (count mocks) " running mock(s) stop: " (str/join ", " (keep :name mocks))
              ". Run them again.")
         "it had no running mocks.")))

(defn ensure-up!
  "Make sure this mocksys version's daemon is listening. Start one if none is;
   replace one from another version, saying so on stderr, since its mocks are lost.
   `opts` replaces the effects, for tests: :spawn! (fn [log] ended-atom-or-nil),
   :log, :timeout-ms and :warn! (fn [line])."
  ([] (ensure-up! {}))
  ([opts]
   (let [opts (merge {:log        (log-path)
                      :spawn!     #(spawn-daemon! (daemon-command (this-process)) %)
                      :timeout-ms 10000
                      :warn!      #(binding [*print-fn* *print-err-fn*] (println %))}
                     opts)]
     (p/let [{:keys [state] :as s} (daemon-status)]
       (case state
         :up      (if (= version (:version s))
                    true
                    (p/let [mocks (stop-daemon!)]
                      ((:warn! opts) (restart-notice (:version s) mocks))
                      (start-daemon! opts)))
         :foreign (throw (foreign-error))
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
  Tagged with the scenario `name` so the catalog can track it. Given a proto's
  `descriptor`, it is a grpc imposter, which keys on the rpc."
  ([port target name] (proxy-imposter port target name nil))
  ([port target name descriptor]
   (cond-> {:port     port
            :name     name
            :protocol (if descriptor "grpc" "http")
            :stubs    [{:responses
                        [{:proxy {:to                  target
                                  :mode                "proxyOnce"
                                  :predicateGenerators [{:matches {:method true
                                                                   :path   true}}]}}]}]}
     descriptor (assoc :descriptor descriptor))))
