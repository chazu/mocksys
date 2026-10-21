(ns mocksys.cli.serve
  "Running and verifying mocks: run, env, inspect, doctor, requests, assert, ls,
  stop, and stacks (stack/up/down)."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [mocksys.mb :as mb]
            [mocksys.store :as store]
            [mocksys.scrub :as scrub]
            [mocksys.analyze :as analyze]
            [mocksys.service :as service]
            [mocksys.contract :as contract]
            [mocksys.proto :as proto]
            [mocksys.lifecycle :as lifecycle]
            [mocksys.runtime :as runtime]
            [mocksys.version :refer [version]]
            [mocksys.cli.util :refer [die env-vars-for running-imposter]]))

(defn- keep-serving!
  "Log a failure that no request owns, such as a module's top-level Promise.reject or
   a throw from a verb's timer, with its stack, instead of letting it end the daemon
   and every mock it serves. The daemon's stderr is its log."
  []
  (doseq [[event kind] [["unhandledRejection" "unhandled rejection"] ["uncaughtException" "uncaught exception"]]]
    (.on js/process event
         (fn [^js e]
           (js/console.error (str (.toISOString (js/Date.)) " mocksys daemon: " kind ", still serving: "
                                  (if (instance? js/Error e) (.-stack e) (pr-str e))))))))

(defn cmd-daemon
  "`daemon` runs the runtime in the foreground (`mb/ensure-up!` spawns it detached);
   `daemon stop` stops it, and every mock it serves."
  [{:keys [pos]}]
  (case (first pos)
    nil    (p/let [_    (keep-serving!)
                   port (mb/admin-port)
                   _    (runtime/start! {:host         "127.0.0.1"
                                         :port         port
                                         :token-file   (mb/token-path port)
                                         :max-requests (runtime/max-recorded-requests)
                                         :on-stop      #(js/process.exit 0)})]
             (println (str "mocksys " version " daemon on http://127.0.0.1:" port " (pid " (.-pid js/process) ")")))
    "stop" (p/let [mocks (mb/stop-daemon!)]
             (println (cond
                        (nil? mocks)   (str "no mocksys daemon is running on 127.0.0.1:" (mb/admin-port))
                        (empty? mocks) "■ stopped the mocksys daemon"
                        :else          (str "■ stopped the mocksys daemon and its " (count mocks) " mock(s): "
                                            (str/join ", " (keep :name mocks))))))
    (die (str "unknown daemon command " (pr-str (first pos)) "; did you mean `mocksys daemon stop`?"))))

(defn cmd-run [{:keys [pos opts]}]
  (let [scenario (first pos)]
    (when-not scenario (die "run needs a scenario: run <name>"))
    (let [prof (service/effective scenario)]
      (p/let [port (lifecycle/ensure-running! scenario (:port opts))]
        (let [at (lifecycle/address scenario port)]
          (println (str "▶ running '" scenario "' at " at (when (proto/grpc? (store/read-contract scenario)) " (gRPC)")))
          (doseq [v (env-vars-for scenario opts prof)]
            (println (str "  export " v "=" at))))
        (when (:watch opts) (lifecycle/watch-scenario! scenario))))))

(defn- tilt-snippet
  "A Tilt `local_resource` that serves the scenario on a pinned port — drop into a
   Tiltfile to run the mock as part of a dev stack (cf. snooker's mockoauth)."
  [scenario port vars]
  (let [res (str/replace scenario "/" "-")
        url (lifecycle/address scenario port)]
    (str "# mocksys mock for " scenario " — add to your Tiltfile\n"
         "local_resource(\n"
         "    '" res "',\n"
         "    serve_cmd='mocksys run " scenario " --port " port "',\n"
         "    links=[link('" url "', '" scenario "')],\n"
         "    labels=['mocks'],\n"
         ")\n"
         "# point your app at: " (str/join ", " (map #(str % "=" url) vars)) "\n")))

(defn cmd-env
  "Wire a consumer up to the mock (starting it if needed). Default output is
   eval-safe `export` lines: `eval \"$(mocksys env NAME)\"`.
     --json   emit JSON {scenario, port, url, <env vars>}
     --tilt   emit a Tiltfile local_resource (pins a stable port; does not start)"
  [{:keys [pos opts]}]
  (let [scenario (first pos)]
    (when-not scenario (die "env needs a scenario: env <name> [--json|--tilt]"))
    (lifecycle/ensure-compiled! scenario)
    (let [prof (service/effective scenario)
          vars (env-vars-for scenario opts prof)]
      (cond
        (:tilt opts)
        (p/let [port (lifecycle/scenario-port-pin! scenario)]
          (print (tilt-snippet scenario port vars)))

        (:json opts)
        (p/let [port (lifecycle/ensure-running! scenario (:port opts))]
          (let [url (lifecycle/address scenario port)]
            (println (js/JSON.stringify
                      (clj->js (into {"scenario" scenario "port" port "url" url}
                                     (map (fn [v] [v url]) vars)))
                      nil 2))))

        :else
        (p/let [port (lifecycle/ensure-running! scenario (:port opts))]
          (doseq [v vars]
            (println (str "export " v "=" (lifecycle/address scenario port))))
          (println (str "export MOCKSYS_SCENARIO=" scenario)))))))

(defn- call-line [{:keys [method path status]} i]
  (str "  " (inc i) ". " method " " path " -> " status))

(defn cmd-inspect [{:keys [pos]}]
  (let [scenario (first pos)]
    (when-not scenario (die "inspect needs a scenario: inspect <name>"))
    (lifecycle/ensure-compiled! scenario)
    (let [imposter (store/read-imposter scenario)
          prof     (service/effective scenario)
          ;; Prefer the contract's friendly method/path (e.g. /users/{id}) over the
          ;; imposter's compiled regex — stubs map 1:1 to operations in order.
          ops      (get (contract/expand (or (store/read-contract scenario) {})) "operations" [])
          stubs    (->> (analyze/summarize imposter (:volatile prof))
                        (map-indexed
                         (fn [i s]
                           (if-let [op (nth ops i nil)]
                             (let [req (contract/http-request (get op "request"))]
                               (assoc s :method (get req "method") :path (get req "path")))
                             s))))
          redacted (->> stubs
                        (mapcat (fn [s] (keep (fn [[k v]] (when (= "REDACTED" v) k)) (:headers s))))
                        distinct sort)
          vol      (->> stubs (mapcat :volatile-headers) distinct sort)]
      (println (str "# Scenario: " scenario))
      (when (:service prof) (println (str "Service: " (:service prof))))
      (println)
      (println "## Calls observed")
      (doseq [[s i] (map vector stubs (range))] (println (call-line s i)))
      (println)
      (println "## Matchers")
      (doseq [{:keys [method path match-fields]} stubs]
        (println (str "  " method " " path ":  " (str/join " + " (sort match-fields)))))
      (when (seq redacted)
        (println) (println "## Redacted secrets")
        (doseq [h redacted] (println (str "  - " h " header: REDACTED"))))
      (when (seq vol)
        (println) (println "## Volatile fields (frozen verbatim — run `doctor` to review)")
        (doseq [h vol] (println (str "  - response header: " h))))
      (println))))

(defn cmd-doctor [{:keys [pos opts]}]
  (let [scenario (first pos)]
    (when-not scenario (die "doctor needs a scenario: doctor <name>"))
    (lifecycle/ensure-compiled! scenario)
    (let [imposter (store/read-imposter scenario)
          prof     (service/effective scenario)
          ;; Overfit flagging targets *recorded* traffic (a proxy capturing a full
          ;; body/header match). Authored/imported/kit predicates are intentional
          ;; (request-conditional branching), so don't second-guess them.
          origin   (get (or (store/read-contract scenario) {}) "origin")
          recorded? (or (nil? origin) (= origin "recorded"))
          stubs    (analyze/summarize imposter (:volatile prof))
          warnings (for [{:keys [method path] :as s} stubs
                         w (concat
                            (when-let [over (and recorded? (seq (analyze/overfit s)))]
                              [(str method " " path ": over-specific matcher on "
                                    (str/join ", " over) " — consider matching method+path only")])
                            (when-let [vol (seq (:volatile-headers s))]
                              [(str method " " path ": freezes volatile header(s) "
                                    (str/join ", " vol) " — replay will serve a stale value")]))]
                     w)]
      (println (str "# doctor: " scenario))
      (cond
        (:fix opts)
        (let [{:keys [contract stripped redacted]}
              (scrub/scrub-contract (store/read-contract scenario) (:redact prof) (:volatile prof))]
          (store/write-contract! scenario contract)
          (lifecycle/compile! scenario)
          (if (or (seq stripped) (seq redacted))
            (do (when (seq stripped) (println (str "  ✎ stripped volatile: " (str/join ", " (sort stripped)))))
                (when (seq redacted) (println (str "  ✎ redacted secrets:  " (str/join ", " (sort redacted))))))
            (println "  ✓ nothing to fix")))

        (empty? warnings)
        (println (str "  ✓ clean — " (count stubs)
                      " stub(s), all matching method+path, no volatile values frozen"))

        :else
        (do (doseq [w warnings] (println (str "  ⚠ " w)))
            (println (str "  → run `doctor " scenario " --fix` to clean these"))))
      (println))))

(defn cmd-requests [{:keys [pos opts]}]
  (let [scenario (first pos)]
    (when-not scenario (die "requests needs a scenario: requests <name> [--clear]"))
    (p/let [live (mb/list-imposters)
            imp  (running-imposter scenario live)]
      (if (:clear opts)
        (p/do (mb/clear-requests! (:port imp))
              (println (str "✓ cleared recorded requests for " scenario)))
        (p/let [full    (mb/get-imposter (:port imp))
                reqs    (get full "requests")
                dropped (get full "droppedRequests" 0)]
          (println (str "# requests seen by " scenario " (" (count reqs)
                        (when (pos? dropped) (str "; " dropped " older dropped")) ")"))
          (doseq [[r i] (map vector reqs (range))]
            (let [q (get r "query")]
              (println (str "  " (inc i) ". " (get r "method") " " (get r "path")
                            (when (seq q) (str " ?" (str/join "&" (map (fn [[k v]] (str k "=" v)) q))))))))))) ))

(defn glob->re
  "METHOD-PATH glob: `*` matches any run of chars; everything else is literal."
  [g]
  (re-pattern (str "^" (-> g
                           (str/replace #"[.+?^${}()|\[\]\\]" "\\$&")
                           (str/replace #"\*" ".*"))
                   "$")))

(defn assert-miss
  "The report for an `assert` that found no match among `n` recorded requests. When
   the daemon dropped older ones from the log, the call may have been among them."
  [scenario saw n dropped]
  (str "✗ did NOT see " saw "  (" n " request(s) recorded)"
       (when (pos? dropped)
         (str "\n  " dropped " older request(s) were dropped from the log, which keeps the newest " n
              " (MOCKSYS_MAX_RECORDED_REQUESTS), so the call may be among them. Run `mocksys requests "
              scenario " --clear` before the calls you assert on."))))

(defn cmd-assert
  "Verify a request was observed: assert <name> --saw 'METHOD /path' (path may
   use * wildcards). Exit 1 on miss, so it drops into test scripts."
  [{:keys [pos opts]}]
  (let [scenario (first pos)
        saw      (:saw opts)]
    (when-not scenario (die "assert needs a scenario: assert <name> --saw 'METHOD PATH'"))
    (when-not (string? saw) (die "assert needs --saw 'METHOD PATH'"))
    (let [[method path-glob] (str/split (str/trim saw) #"\s+" 2)
          re (glob->re (or path-glob ""))]
      (p/let [live (mb/list-imposters)
              imp  (running-imposter scenario live)
              full (mb/get-imposter (:port imp))
              reqs (get full "requests")
              hit? (some (fn [r]
                           (and (= (str/upper-case method) (str/upper-case (get r "method")))
                                (re-matches re (get r "path"))))
                         reqs)]
        (if hit?
          (println (str "✓ saw " saw))
          (do (binding [*print-fn* *print-err-fn*]
                (println (assert-miss scenario saw (count reqs) (get full "droppedRequests" 0))))
              (js/process.exit 1)))))))

(defn cmd-ls [_]
  (let [scenarios (store/list-scenarios)]
    (p/let [live (mb/list-imposters)]
      (let [by-name (into {} (map (juxt :name identity)) live)]
        (if (empty? scenarios)
          (println (str "no scenarios yet — author one:\n"
                        "  mocksys new <service/name>                 scaffold a contract\n"
                        "  mocksys import openapi <spec>              one scenario per operation\n"
                        "  mocksys kit oauth2 --provider github       a stateful recipe\n"
                        "  mocksys record <service/name> --target URL seed from live traffic"))
          (do
            (println (str (count scenarios) " scenario(s):"))
            (doseq [id scenarios]
              (let [{:keys [stubs port]} (or (store/read-mock id) {})
                    imp       (by-name id)
                    shown-port (or (:port imp) port)]
                (println (str "  " (if imp "▶" "·") " " id
                              "  (" (or stubs "?") " stub" (when (not= stubs 1) "s")
                              ", port " (or shown-port "?") ")"
                              (when imp "  LIVE")))))))))))

(defn cmd-stop [{:keys [pos opts]}]
  (p/let [live (mb/list-imposters)]
    (cond
      (empty? live)
      (println "nothing running")

      (:all opts)
      (p/do
        (p/all (map #(mb/delete-imposter! (:port %)) live))
        (println (str "■ stopped all (" (count live) " imposter(s))")))

      :else
      (let [scenario (first pos)]
        (when-not scenario (die "stop needs a scenario: stop <name>  (or --all)"))
        (if-let [imp (first (filter #(= (:name %) scenario) live))]
          (p/do (mb/delete-imposter! (:port imp))
                (println (str "■ stopped '" scenario "' (port " (:port imp) ")")))
          (println (str "'" scenario "' is not running")))))))

;; --- stacks: bring up a named set of scenarios together -------------------

(defn cmd-stack
  "Define (or show) a stack — a named set of scenarios brought up together:
     stack <name> <scenario>...      define/replace
     stack <name>                    show"
  [{:keys [pos]}]
  (let [name (first pos) scenarios (vec (rest pos))]
    (when-not name (die "stack needs a name: stack <name> <scenario>...  (or `stack <name>` to show)"))
    (if (empty? scenarios)
      (if-let [s (store/read-stack name)]
        (do (println (str "# stack " name))
            (doseq [sc (:scenarios s)] (println (str "  · " sc)))
            (println) (println (str "  bring it up:  mocksys up " name)))
        (die (str "no stack '" name "' yet — define it: stack " name " <scenario>...")))
      (do (store/write-stack! name {:scenarios scenarios})
          (println (str "✚ stack '" name "' = " (str/join ", " scenarios)))
          (println (str "  bring it up:  mocksys up " name))))))

(defn cmd-up
  "Bring up every scenario in a stack (each on its pinned/auto port) and print a
   combined env block:  up <stack>"
  [{:keys [pos]}]
  (let [name  (first pos)
        stack (or (store/read-stack name)
                  (die (str "no stack '" name "' — `mocksys stack " name " <scenario>...` first")))
        scenarios (:scenarios stack)]
    (-> (p/loop [scs scenarios acc []]
          (if (empty? scs)
            acc
            (let [sc (first scs)]
              (p/let [prof (service/effective sc)
                      port (lifecycle/ensure-running! sc nil)]
                (p/recur (rest scs) (conj acc [sc port (env-vars-for sc {} prof)]))))))
        (p/then
         (fn [results]
           (println (str "▲ up '" name "' (" (count results) " scenario(s))"))
           (doseq [[sc port _] results] (println (str "  ▶ " sc "  " (lifecycle/address sc port))))
           (println)
           (doseq [[sc port vars] results, v vars]
             (println (str "export " v "=" (lifecycle/address sc port)))))))))

(defn cmd-down
  "Stop every scenario in a stack:  down <stack>"
  [{:keys [pos]}]
  (let [name  (first pos)
        stack (or (store/read-stack name) (die (str "no stack '" name "'")))
        wanted (set (:scenarios stack))]
    (p/let [live (mb/list-imposters)
            hits (filter #(wanted (:name %)) live)]
      (p/all (map #(mb/delete-imposter! (:port %)) hits))
      (println (str "▽ down '" name "' (" (count hits) " stopped)")))))

