(ns mocksys.mb
  "Thin client over the Mountebank admin API (and its lifecycle).

  We never write Mountebank config files. We drive a live `mb` daemon over its
  REST admin API on :2525 — create proxy imposters, pull recorded responses
  back, repost frozen imposters. Mountebank is the runtime, not the interface."
  (:require [promesa.core :as p]
            ["node:child_process" :as cp]
            ["node:net" :as net]))

(def admin-port 2525)
(def base (str "http://localhost:" admin-port))

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
     (p/let [res  (js/fetch (str base path) opts)
             text (.text res)]
       {:status (.-status res)
        :body   (when (seq text) (js->clj (js/JSON.parse text)))}))))

;; --- Lifecycle ------------------------------------------------------------

(defn up?
  "True if an mb admin API is answering on :2525."
  []
  (-> (js/fetch (str base "/"))
      (p/then (constantly true))
      (p/catch (constantly false))))

(defn- on-path?
  "Whether `cmd` resolves on PATH (POSIX `command -v`)."
  [cmd]
  (zero? (.-status (cp/spawnSync "sh" #js ["-c" (str "command -v " cmd)]
                                 #js {:stdio "ignore"}))))

(defn mb-launcher
  "How to start mountebank, as [cmd extra-args]:
     $MOCKSYS_MB (a path/command) if set  ->  use it directly
     else `mb` on PATH (e.g. a Homebrew install)
     else `npx mb` (node_modules / on-demand fetch — the dev/npm path)."
  []
  (let [env (.-MOCKSYS_MB js/process.env)]
    (cond
      (and env (not= env "")) [env []]
      (on-path? "mb")         ["mb" []]
      :else                   ["npx" ["mb"]])))

(defn- spawn-daemon!
  "Start the mb daemon. With `allow-injection?` it is launched with --allowInjection
   so stateful scenarios (generated response injections) can run."
  ([] (spawn-daemon! false))
  ([allow-injection?]
   ;; `mb start` double-forks into a backgrounded daemon and returns; the child we
   ;; spawn just triggers it, so we detach + unref and poll for readiness.
   (let [[cmd extra] (mb-launcher)
         args  (cond-> (concat extra ["start" "--port" (str admin-port)])
                 allow-injection? (concat ["--allowInjection"]))
         child (.spawn cp cmd (clj->js args) #js {:detached true :stdio "ignore"})]
     (.unref child))))

(defn- kill-daemon!
  "Stop whatever is listening on the admin port (so we can relaunch it with a
   different flag). POSIX-only; mocksys targets darwin/linux."
  []
  (cp/spawnSync "sh" #js ["-c" (str "lsof -ti tcp:" admin-port " | xargs kill -9")]
                #js {:stdio "ignore"}))

(defn- wait-up [tries]
  (p/let [ok (up?)]
    (cond
      ok            true
      (zero? tries) (throw (js/Error. "Mountebank did not come up on :2525"))
      :else         (p/do (p/delay 300) (wait-up (dec tries))))))

(defn ensure-up!
  "Make sure an mb daemon is listening; start one if not. `allow-injection?` only
   takes effect when *starting* a fresh daemon (an already-running one keeps its
   flags — see `restart-injecting!` for forcing the flag on)."
  ([] (ensure-up! false))
  ([allow-injection?]
   (p/let [ok (up?)]
     (if ok
       true
       (do (spawn-daemon! allow-injection?)
           (wait-up 25))))))

(defn restart-injecting!
  "Kill the running daemon and relaunch it with --allowInjection. Needed when a
   stateful imposter must be posted but the live daemon was started without the
   flag. Drops other running imposters (they re-post on their next `run`)."
  []
  (kill-daemon!)
  (p/do (p/delay 300)
        (spawn-daemon! true)
        (wait-up 25)))

(defn free-port
  "Ask the OS for a free ephemeral port (bind :0, read the assignment, release).
   Small TOCTOU window before mb claims it — fine for a local dev tool."
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
      (throw (js/Error. (str "mb rejected imposter (" status "): "
                             (js/JSON.stringify (clj->js body))))))))

(defn post-imposter-injecting!
  "Like `post-imposter!`, but if the daemon rejects the imposter because injection
   is disabled, relaunch it with --allowInjection and retry once."
  [imposter]
  (-> (post-imposter! imposter)
      (p/catch
       (fn [err]
         (if (re-find #"(?i)allowInjection|injection is not allowed" (or (.-message err) ""))
           (p/do (restart-injecting!)
                 (post-imposter! imposter))
           (throw err))))))

(defn delete-imposter!
  "Remove the imposter on `port` if present (idempotent — frees the port)."
  [port]
  (fetch-json "DELETE" (str "/imposters/" port)))

(defn list-imposters
  "Live imposters as [{:port :name}] (empty if mb is down). `name` carries the
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
