(ns mocksys.core
  "mocksys — agent-native CLI over Mountebank.

  Lifecycle:  init? -> record -> freeze -> run, plus inspect / doctor / ls / stop.
    init     materialize a service template (github, gitlab, aws-s3, ...)
    record   start a proxy that captures real traffic to a target
    freeze   pull the recording back, scrub secrets+volatility, save a fixture
    run      host the saved fixture as a standalone mock (no real API involved)
    inspect  agent-readable summary of a frozen scenario
    doctor   matcher-hygiene lint; --fix cleans in place
    ls       list scenarios and which are live
    stop     tear down a running scenario (or --all)

  Scenarios are named `service/name`; the service supplies redact/volatile/env
  profiles (see mocksys.service)."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [mocksys.mb :as mb]
            [mocksys.store :as store]
            [mocksys.scrub :as scrub]
            [mocksys.analyze :as analyze]
            [mocksys.service :as service]
            [mocksys.contract :as contract]
            [mocksys.openapi :as openapi]
            [mocksys.bundle :as bundle]
            [mocksys.git :as git]))

;; --- arg parsing ----------------------------------------------------------

(defn parse-args
  "Split a flat arg seq into {:pos [...] :opts {kw val}}. `--flag value` pairs
   become opts; a bare `--flag` (followed by another flag or nothing) is true."
  [args]
  (loop [args args pos [] opts {}]
    (if-let [a (first args)]
      (if (str/starts-with? a "--")
        (let [k (keyword (subs a 2))
              v (second args)]
          (if (and v (not (str/starts-with? v "--")))
            (recur (drop 2 args) pos (assoc opts k v))
            (recur (rest args) pos (assoc opts k true))))
        (recur (rest args) (conj pos a) opts))
      {:pos pos :opts opts})))

(defn- die [msg]
  (binding [*print-fn* *print-err-fn*] (println "error:" msg))
  (js/process.exit 1))

;; --- contract lifecycle ---------------------------------------------------
;; The contract is canonical; the imposter is a build artifact. `compile!` lowers
;; one to the other; `ensure-contract!` lifts a legacy/recorded imposter into a
;; contract on demand; `ensure-compiled!` rebuilds a stale artifact transparently.

(defn compile!
  "Lower a scenario's contract to imposter.json and refresh mock.yaml. Returns the
   imposter. Carries the prior artifact's port so a running mock keeps its slot."
  [scenario]
  (let [contract* (store/read-contract scenario)
        port      (when (store/exists? scenario)
                    (try (get (store/read-imposter scenario) "port") (catch :default _ nil)))
        imposter  (contract/lower contract* port)
        prof      (service/effective scenario)]
    (store/write-imposter! scenario imposter)
    (store/write-mock! scenario (merge (or (store/read-mock scenario) {})
                                       {:scenario scenario
                                        :service  (:service prof)
                                        :port     port
                                        :env      (:env prof)
                                        :origin   (get contract* "origin")
                                        :stubs    (count (get imposter "stubs"))}))
    imposter))

(defn ensure-contract!
  "The scenario's contract, lifting one from a recorded/legacy imposter if none
   exists yet. nil when there's nothing to work from."
  [scenario]
  (or (store/read-contract scenario)
      (when (store/exists? scenario)
        (let [prof (service/effective scenario)
              c    (contract/lift (store/read-imposter scenario) scenario (:service prof))]
          (store/write-contract! scenario c)
          c))))

(defn ensure-compiled!
  "Recompile imposter.json when the contract is newer (or the artifact is missing).
   No-op for legacy imposter-only scenarios (no contract -> never stale)."
  [scenario]
  (when (store/contract-stale? scenario)
    (compile! scenario)))

;; --- commands -------------------------------------------------------------

(defn cmd-init [{:keys [pos]}]
  (let [name (first pos)]
    (when-not name (die (str "init needs a service: init <" (str/join "|" (sort (keys service/templates))) ">")))
    (let [tmpl (service/write-template! name)]
      (println (str "✚ initialized service '" name "'  ->  .mocks/services/" name ".yaml"))
      (when-let [t (:default_target tmpl)] (println (str "  default target: " t)))
      (println (str "  record a scenario with:  mocksys record " name "/<scenario>"
                    (when (:default_target tmpl) "  (target optional)"))))))

(defn cmd-record [{:keys [pos opts]}]
  (let [scenario (or (:scenario opts) (first pos))
        prof     (when scenario (service/effective scenario))
        target   (or (:target opts) (:target prof))]
    (when-not scenario (die "record needs a scenario: record <service/name> [--target URL]"))
    (when-not target   (die "record needs --target URL (or a service template with a default)"))
    (p/let [_    (mb/ensure-up!)
            port (if (:port opts) (js/parseInt (:port opts)) (mb/free-port))]
      (mb/delete-imposter! port)
      (mb/post-imposter! (mb/proxy-imposter port target scenario))
      (store/write-recording! scenario {:port port :target target :service (:service prof)})
      (println (str "● recording '" scenario "'"))
      (println (str "  proxy:  http://localhost:" port "  ->  " target))
      (println "  send your traffic at the proxy URL above, then:")
      (println (str "    mocksys freeze " scenario)))))

(defn cmd-freeze [{:keys [pos]}]
  (let [scenario (first pos)]
    (when-not scenario (die "freeze needs a scenario: freeze <name>"))
    (let [{:keys [port]} (or (store/read-recording scenario)
                             (die (str "no active recording for '" scenario "'")))
          prof (service/effective scenario)]
      (p/let [_   (mb/ensure-up!)
              raw (mb/get-replayable port)
              {:keys [imposter stub-count redacted stripped]}
              (scrub/scrub-imposter raw (:redact prof) (:volatile prof))]
        (store/write-imposter! scenario imposter)
        ;; Recorded scenarios are contract-canonical too: lift the scrubbed imposter
        ;; into a contract so `examples`/`use`/`fault` work on it like any other.
        (store/write-contract! scenario (contract/lift imposter scenario (:service prof)))
        (store/write-mock! scenario {:scenario scenario
                                     :service  (:service prof)
                                     :port     port
                                     :origin   "recorded"
                                     :stubs    stub-count
                                     :env      (:env prof)
                                     :redacted (vec (sort redacted))
                                     :stripped (vec (sort stripped))})
        (mb/delete-imposter! port)
        (println (str "■ froze '" scenario "' — " stub-count " stub(s)"))
        (when (seq redacted) (println (str "  redacted secrets:  " (str/join ", " (sort redacted)))))
        (when (seq stripped) (println (str "  stripped volatile: " (str/join ", " (sort stripped)))))
        (println (str "  run it with:  mocksys run " scenario))))))

(defn ensure-running!
  "Make `scenario` live and return its port. Reuses the running port unless
   --port forces a move; auto-allocates a free port otherwise. Always (re)posts
   with recordRequests=true so `requests`/`assert` have data."
  [scenario port-opt]
  (p/let [_        (mb/ensure-up!)
          live     (mb/list-imposters)
          existing (first (filter #(= (:name %) scenario) live))
          imposter (store/read-imposter scenario)
          port     (cond
                     port-opt (js/parseInt port-opt)
                     existing (:port existing)
                     :else    (mb/free-port))]
    (mb/delete-imposter! port)
    (mb/post-imposter! (assoc imposter "port" port "name" scenario "recordRequests" true))
    port))

(defn- env-vars-for [scenario opts prof]
  (cond (:env opts)        [(:env opts)]
        (seq (:env prof))  (:env prof)
        :else              ["BASE_URL"]))

(defn cmd-run [{:keys [pos opts]}]
  (let [scenario (first pos)]
    (when-not scenario (die "run needs a scenario: run <name>"))
    (ensure-compiled! scenario)
    (let [prof (service/effective scenario)]
      (p/let [port (ensure-running! scenario (:port opts))]
        (println (str "▶ running '" scenario "' at http://localhost:" port))
        (doseq [v (env-vars-for scenario opts prof)]
          (println (str "  export " v "=http://localhost:" port)))))))

(defn cmd-env
  "Eval-safe: emits only `export` lines so `eval \"$(mocksys env NAME)\"` wires a
   shell up to the mock, starting it if needed."
  [{:keys [pos opts]}]
  (let [scenario (first pos)]
    (when-not scenario (die "env needs a scenario: env <name>"))
    (ensure-compiled! scenario)
    (let [prof (service/effective scenario)]
      (p/let [port (ensure-running! scenario (:port opts))]
        (doseq [v (env-vars-for scenario opts prof)]
          (println (str "export " v "=http://localhost:" port)))
        (println (str "export MOCKSYS_SCENARIO=" scenario))))))

(defn- call-line [{:keys [method path status]} i]
  (str "  " (inc i) ". " method " " path " -> " status))

(defn cmd-inspect [{:keys [pos]}]
  (let [scenario (first pos)]
    (when-not scenario (die "inspect needs a scenario: inspect <name>"))
    (ensure-compiled! scenario)
    (let [imposter (store/read-imposter scenario)
          prof     (service/effective scenario)
          stubs    (analyze/summarize imposter (:volatile prof))
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
    (ensure-compiled! scenario)
    (let [imposter (store/read-imposter scenario)
          prof     (service/effective scenario)
          stubs    (analyze/summarize imposter (:volatile prof))
          warnings (for [{:keys [method path] :as s} stubs
                         w (concat
                            (when-let [over (seq (analyze/overfit s))]
                              [(str method " " path ": over-specific matcher on "
                                    (str/join ", " over) " — consider matching method+path only")])
                            (when-let [vol (seq (:volatile-headers s))]
                              [(str method " " path ": freezes volatile header(s) "
                                    (str/join ", " vol) " — replay will serve a stale value")]))]
                     w)]
      (println (str "# doctor: " scenario))
      (cond
        (:fix opts)
        (let [{:keys [imposter stripped redacted]}
              (scrub/scrub-imposter imposter (:redact prof) (:volatile prof))]
          (store/write-imposter! scenario imposter)
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

(defn- running-imposter
  "Resolve the live imposter for a scenario, or die with guidance."
  [scenario live]
  (or (first (filter #(= (:name %) scenario) live))
      (die (str "'" scenario "' is not running — `mocksys run " scenario "` first"))))

(defn cmd-requests [{:keys [pos opts]}]
  (let [scenario (first pos)]
    (when-not scenario (die "requests needs a scenario: requests <name> [--clear]"))
    (p/let [live (mb/list-imposters)
            imp  (running-imposter scenario live)]
      (if (:clear opts)
        (p/do (mb/clear-requests! (:port imp))
              (println (str "✓ cleared recorded requests for " scenario)))
        (p/let [full (mb/get-imposter (:port imp))
                reqs (get full "requests")]
          (println (str "# requests seen by " scenario " (" (count reqs) ")"))
          (doseq [[r i] (map vector reqs (range))]
            (let [q (get r "query")]
              (println (str "  " (inc i) ". " (get r "method") " " (get r "path")
                            (when (seq q) (str " ?" (str/join "&" (map (fn [[k v]] (str k "=" v)) q))))))))))) ))

(defn- glob->re
  "METHOD-PATH glob: `*` matches any run of chars; everything else is literal."
  [g]
  (re-pattern (str "^" (-> g
                           (str/replace #"[.+?^${}()|\[\]\\]" "\\\\$&")
                           (str/replace #"\*" ".*"))
                   "$")))

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
                (println (str "✗ did NOT see " saw "  (" (count reqs) " request(s) recorded)")))
              (js/process.exit 1)))))))

(defn cmd-ls [_]
  (let [scenarios (store/list-scenarios)]
    (p/let [live (mb/list-imposters)]
      (let [by-name (into {} (map (juxt :name identity)) live)]
        (if (empty? scenarios)
          (println "no scenarios yet — record one with `mocksys record <service/name> --target URL`")
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

;; --- contract authoring (import / compile / new / validate / examples / use) ---

(defn cmd-import
  "Seed scenarios from an OpenAPI/Swagger spec — one per operation, schema + named
   examples, plus a synthesized service profile:
     import openapi <spec> [--service NAME] [--target URL]"
  [{:keys [pos opts]}]
  (let [kind (first pos)
        spec (second pos)]
    (when-not (= kind "openapi")
      (die "import supports: import openapi <spec> [--service NAME] [--target URL]"))
    (when-not spec (die "import openapi needs a spec file: import openapi <spec.yaml|json|url>"))
    (p/let [{:keys [service template scenarios]}
            (openapi/parse-spec spec {:service (when (string? (:service opts)) (:service opts))
                                      :target  (when (string? (:target opts)) (:target opts))})]
      (when (empty? scenarios) (die (str "no operations found in " spec)))
      (store/write-service! service template)
      (doseq [{:keys [scenario contract]} scenarios]
        (store/write-contract! scenario contract)
        (compile! scenario))
      (println (str "⇣ imported " (count scenarios) " operation(s) into service '" service "'"))
      (when (:default_target template) (println (str "  target: " (:default_target template))))
      (when (seq (:redact_headers template))
        (println (str "  redacting: " (str/join ", " (:redact_headers template)))))
      (doseq [{:keys [scenario]} scenarios] (println (str "  · " scenario)))
      (println)
      (println (str "  run one:       mocksys run " (:scenario (first scenarios))))
      (println (str "  pick examples: mocksys examples " (:scenario (first scenarios)))))))

(defn cmd-compile
  "Rebuild imposter.json from contract.yaml (normally automatic on `run`):
     compile <scenario>"
  [{:keys [pos]}]
  (let [scenario (first pos)]
    (when-not scenario (die "compile needs a scenario: compile <name>"))
    (if (ensure-contract! scenario)
      (let [imposter (compile! scenario)]
        (println (str "⚙ compiled '" scenario "' — " (count (get imposter "stubs")) " stub(s)")))
      (die (str "no contract for '" scenario "' — `new`, `add`, `import`, or `record`+`freeze` it first")))))

(defn cmd-new
  "Scaffold a blank contract to hand-author after reading API docs/source:
     new <service/name> [--service S]"
  [{:keys [pos opts]}]
  (let [scenario (first pos)]
    (when-not scenario (die "new needs a scenario: new <service/name>"))
    (when (store/has-contract? scenario)
      (die (str "'" scenario "' already has a contract — edit it or `compile` it")))
    (let [svc (or (when (string? (:service opts)) (:service opts)) (service/service-of scenario))]
      (store/write-contract! scenario (contract/scaffold scenario svc))
      (compile! scenario)
      (println (str "✚ scaffolded '" scenario "'"))
      (println (str "  edit:    " store/root "/" scenario "/contract.yaml"))
      (println (str "  then:    mocksys validate " scenario "  &&  mocksys run " scenario)))))

(defn cmd-validate
  "Structurally check a contract and flag examples that diverge from their schema:
     validate <scenario>   (exit 1 on errors)"
  [{:keys [pos]}]
  (let [scenario (first pos)]
    (when-not scenario (die "validate needs a scenario: validate <name>"))
    (let [c (or (ensure-contract! scenario) (die (str "no contract for '" scenario "'")))
          {:keys [errors warnings]} (contract/validate c)]
      (println (str "# validate: " scenario))
      (doseq [w warnings] (println (str "  ⚠ " w)))
      (doseq [e errors]   (println (str "  ✗ " e)))
      (when (and (empty? errors) (empty? warnings))
        (println (str "  ✓ valid — " (count (get c "operations")) " operation(s)")))
      (when (seq errors)
        (binding [*print-fn* *print-err-fn*] (println (str (count errors) " error(s)")))
        (js/process.exit 1)))))

(defn cmd-examples
  "List a contract's operations, responses and which examples are in play:
     examples <scenario> [--op ID]"
  [{:keys [pos opts]}]
  (let [scenario (first pos)]
    (when-not scenario (die "examples needs a scenario: examples <name> [--op ID]"))
    (let [c  (or (ensure-contract! scenario) (die (str "no contract for '" scenario "'")))
          op (when (string? (:op opts)) (:op opts))
          rows (contract/examples-overview c op)]
      (println (str "# examples: " scenario))
      (doseq [{:keys [id method path responses]} rows]
        (println (str "  " id "  (" method " " path ")"))
        (doseq [{:keys [status schema? examples]} responses]
          (println (str "    " status (when schema? "  [schema]")))
          (doseq [{:keys [name select]} examples]
            (println (str "      " (if select "[x]" "[ ]") " " name)))))
      (println)
      (println (str "  toggle:  mocksys use " scenario " --op ID --example NAME [--only|--off]")))))

(defn cmd-use
  "Choose which example(s) are in play for an operation (selected examples become
   the mock's responses; `--only` swaps, e.g. happy -> error):
     use <scenario> --op ID --example NAME [--off] [--only]"
  [{:keys [pos opts]}]
  (let [scenario (first pos)
        op       (:op opts)
        ex       (:example opts)]
    (when-not scenario (die "use needs a scenario: use <name> --op ID --example NAME"))
    (when-not (string? op)  (die "use needs --op ID  (see `examples <name>`)"))
    (when-not (string? ex)  (die "use needs --example NAME  (see `examples <name>`)"))
    (let [c (or (ensure-contract! scenario) (die (str "no contract for '" scenario "'")))]
      (when (empty? (contract/example-locations c op ex))
        (die (str "no example '" ex "' on operation '" op "' — see `examples " scenario "`")))
      (let [on?  (not (:off opts))
            c'   (contract/set-selection c op ex on? (boolean (:only opts)))]
        (store/write-contract! scenario c')
        (compile! scenario)
        (println (str (if on? "✓ selected" "✓ deselected") " " ex " on " op
                      (when (:only opts) " (only)") " in '" scenario "'"))))))

;; --- authoring: add / fault / parameterize (all edit the contract) --------

(defn- body-value
  "Resolve a response body from --body FILE (parsed as JSON when possible) or
   --text STRING. Returns [value json?] or [nil nil]."
  [opts]
  (cond
    (:body opts) (let [raw (store/slurp (:body opts))]
                   (try [(js->clj (js/JSON.parse raw)) true]
                        (catch :default _ [raw false])))
    (:text opts) [(:text opts) false]
    :else        [nil nil]))

(defn cmd-add
  "Hand-author an endpoint (appends an operation to the contract, then compiles):
     add <scenario> --request 'METHOD /path' [--status N] [--body FILE | --text STR]"
  [{:keys [pos opts]}]
  (let [scenario (first pos)
        request  (:request opts)]
    (when-not scenario (die "add needs a scenario: add <name> --request 'METHOD /path'"))
    (when-not (string? request) (die "add needs --request 'METHOD /path'"))
    (let [[method path] (str/split (str/trim request) #"\s+" 2)
          status        (js/parseInt (or (:status opts) 200))
          [body json?]  (body-value opts)
          existing      (ensure-contract! scenario)
          svc           (service/service-of scenario)
          c             (contract/add-operation existing scenario svc method path status body json?)]
      (store/write-contract! scenario c)
      (let [imposter (compile! scenario)]
        (println (str "✚ added " (str/upper-case method) " " path " -> " status
                      " to '" scenario "'  (" (count (get imposter "stubs")) " operation(s))"))))))

(defn cmd-fault
  "Layer a transport fault onto a scenario, stored on the contract so it survives
   recompiles. --clear removes it:
     fault <scenario> [--status N] [--latency MS] [--timeout] [--drop-connection] [--malformed-json] [--clear]"
  [{:keys [pos opts]}]
  (let [scenario (first pos)
        knobs    (select-keys opts [:status :latency :timeout :drop-connection :malformed-json])]
    (when-not scenario (die "fault needs a scenario: fault <name> --status N | --latency MS | --clear"))
    (when (and (empty? knobs) (not (:clear opts)))
      (die "fault needs one of --status --latency --timeout --drop-connection --malformed-json (or --clear)"))
    (let [c     (or (ensure-contract! scenario) (die (str "no contract for '" scenario "'")))
          fault (if (:clear opts)
                  {}
                  (merge (get c "fault") (into {} (map (fn [[k v]] [(name k) v]) knobs))))
          c'    (assoc c "fault" fault)]
      (store/write-contract! scenario c')
      (compile! scenario)
      (if (:clear opts)
        (println (str "cleared fault on '" scenario "'"))
        (println (str "faulted '" scenario "' - " (str/join ", " (map name (keys knobs))) " (all operations)"))))))

(defn cmd-parameterize
  "Loosen an operation's exact path to a {param} template so it serves any id; the
   contract compiles templated paths to regex predicates automatically:
     parameterize <scenario> --path '/repos/{owner}/{repo}/issues'"
  [{:keys [pos opts]}]
  (let [scenario (first pos)
        tmpl     (:path opts)]
    (when-not scenario (die "parameterize needs a scenario: parameterize <name> --path '/a/{x}/b'"))
    (when-not (string? tmpl) (die "parameterize needs --path '/template/{with}/{vars}'"))
    (let [c       (or (ensure-contract! scenario) (die (str "no contract for '" scenario "'")))
          re      (re-pattern (contract/path->regex tmpl))
          changed (atom 0)
          c'      (update c "operations"
                          (fn [ops]
                            (mapv (fn [op]
                                    (let [path (get-in op ["request" "path"])]
                                      (if (and path (not= path tmpl) (re-matches re path))
                                        (do (swap! changed inc)
                                            (assoc-in op ["request" "path"] tmpl))
                                        op)))
                                  ops)))]
      (if (zero? @changed)
        (println (str "no operations matched " tmpl " - nothing parameterized"))
        (do (store/write-contract! scenario c')
            (compile! scenario)
            (println (str "parameterized " @changed " operation(s) in '" scenario "' to " tmpl)))))))

;; --- v0.3 bundles ---------------------------------------------------------

(defn cmd-pack [{:keys [pos opts]}]
  (let [scenario (first pos)]
    (when-not scenario (die "pack needs a scenario: pack <name> [--out FILE] | --stdout > file"))
    (ensure-compiled! scenario)
    ;; Default to a file (predictable for agents, who always run non-TTY); stream
    ;; the raw archive to stdout only when --stdout is explicitly requested.
    (if (:stdout opts)
      (bundle/pack! scenario "-")
      (let [out (or (:out opts) (str (str/replace scenario "/" "-") ".mock.tgz"))]
        (bundle/pack! scenario out)
        (println (str "▦ packed '" scenario "' → " out))))))

(defn cmd-unpack [{:keys [pos]}]
  (let [file (first pos)]
    (when-not file (die "unpack needs a bundle: unpack <file.mock.tgz>"))
    (let [names (bundle/unpack! file)]
      (println (str "▣ unpacked " (str/join ", " names) " into .mocks/"))
      (doseq [n names] (println (str "  run it with:  mocksys run " n))))))

;; --- store / sharing (git-backed ~/.mocksys) ------------------------------

(defn cmd-home [_]
  (let [{:keys [root repo? branch remote dirty?]} (git/info)]
    (println (str "store: " root))
    (if repo?
      (do (println (str "git:   " branch (when dirty? " (uncommitted changes)")))
          (println (str "remote: " (or remote "none — `mocksys remote <url>` to set"))))
      (println "git:   not initialized — `mocksys publish` will init it"))
    (let [n (count (store/list-scenarios))]
      (println (str "scenarios: " n)))))

(defn cmd-publish [{:keys [pos]}]
  (let [id (first pos)]
    (case (git/publish! id)
      :clean     (println "nothing to publish — store is clean")
      :committed (println (str "✔ published " (or id "all changes") "  (" store/root ")")))))

(defn cmd-remote [{:keys [pos]}]
  (let [url (first pos)]
    (when-not url (die "remote needs a URL: remote <git-url>"))
    (git/set-remote! url)
    (println (str "✔ remote set: " url))))

(defn cmd-push [_]
  (git/push!)
  (println "✔ pushed to remote"))

(defn cmd-pull [_]
  (git/pull!)
  (println (str "✔ pulled from remote  (" (count (store/list-scenarios)) " scenario(s) now in store)")))

(defn cmd-rm
  "Remove a scenario or a whole service from the store (stops it if live; commits
   the removal in a git store):  rm <scenario|service>"
  [{:keys [pos]}]
  (let [id (first pos)]
    (when-not id (die "rm needs a scenario or service: rm <name>  (e.g. github/create-issue or github)"))
    (p/let [live (mb/list-imposters)]
      (doseq [imp (filter #(let [n (str (:name %))]
                             (or (= n id) (str/starts-with? n (str id "/")))) live)]
        (mb/delete-imposter! (:port imp)))
      (let [res (git/remove! id)]
        (println (str "🗑 removed '" id "' from the store"
                      (when (= res :committed) " (committed)")))))))

(defn- status-word [state]
  (case state "??" "untracked" "A" "added" "M" "modified" "D" "deleted" "R" "renamed" state))

(defn cmd-status
  "Show uncommitted changes in the store (which scenarios are new/edited/removed)."
  [_]
  (let [files (git/changed-files)]
    (cond
      (nil? files)   (println "store is not a git repo yet — `mocksys publish` will init it")
      (empty? files) (println (str "store is clean — nothing to publish  (" store/root ")"))
      :else
      (do (println (str (count files) " uncommitted change(s) in " store/root ":"))
          (doseq [{:keys [state path]} files]
            (println (str "  " (status-word state) ": " path)))
          (println)
          (println "  record them:  mocksys publish [<name>]")))))

(defn cmd-log
  "Recent store history:  log [--n N]"
  [{:keys [opts]}]
  (let [n (if (string? (:n opts)) (js/parseInt (:n opts)) 20)]
    (if-let [l (git/log n)]
      (do (println (str "# last " n " change(s) — " store/root))
          (println l))
      (println "store has no history yet — `mocksys publish` will init it"))))

(defn cmd-restore
  "Discard uncommitted local changes (revert edits, drop new files):
     restore <name>   (or restore --all for the whole store)"
  [{:keys [pos opts]}]
  (let [id (first pos)]
    (when-not (or id (:all opts))
      (die "restore needs a scenario/service (or --all): restore <name> | restore --all"))
    (git/restore! id)
    (println (str "↩ restored " (or id "the whole store") " to the last committed state"))))

;; --- prime: orient an agent ----------------------------------------------

(defn cmd-prime [_]
  (println
   (str/join
    "\n"
    ["# mocksys — turn real APIs into reusable mock fixtures"
     ""
     "You wrap an external system (github, gitlab, aws-s3, stripe, ...) as a local"
     "mock an app/test can hit instead of the real thing. Vocabulary: a *scenario*"
     "(named `service/name`) holds recorded request/response *fixtures*; you record"
     "reality, scrub secrets+volatility, then replay it offline."
     ""
     "## Core loop"
     "  mocksys init github                         # one-time: load the service profile"
     "  mocksys record github/create-issue          # starts a proxy → api.github.com"
     "  #   ...drive your app/curl at the printed proxy URL to capture traffic..."
     "  mocksys freeze github/create-issue          # scrub + save it as a replay fixture"
     "  mocksys run github/create-issue             # host the mock (prints a port)"
     ""
     "## Wire a test/app to the mock"
     "  eval \"$(mocksys env github/create-issue)\"   # exports GITHUB_API_URL=... etc"
     "  # point your code at $GITHUB_API_URL, run it, then assert what it called:"
     "  mocksys assert github/create-issue --saw 'POST /repos/*/issues'   # exit 1 on miss"
     ""
     "## Author from a spec / docs / source (no recording)"
     "The canonical source is contract.yaml; imposter.json is a build artifact that"
     "`run` recompiles automatically. Three ways to get a contract:"
     "  mocksys import openapi ./openapi.yaml --service stripe   # one scenario per operation"
     "  mocksys new acme/get-widget                              # scaffold, then edit contract.yaml"
     "  mocksys add acme/get-widget --request 'GET /widgets/1' --status 200 --body w.json"
     "Each operation keeps its schema plus *named examples* you select to serve:"
     "  mocksys examples stripe/create-charge                    # list examples, [x]=in play"
     "  mocksys use stripe/create-charge --op createCharge --example card_declined --only"
     "  mocksys validate stripe/create-charge                    # schema/structure check"
     "  mocksys compile stripe/create-charge                     # (usually automatic on run)"
     ""
     "## Failure modes & edge cases"
     "  mocksys add github/rate-limited --request 'GET /rate_limit' --status 403 --body rl.json"
     "  mocksys fault github/create-issue --latency 2000     # or --timeout --drop-connection --clear"
     "  mocksys parameterize github/create-issue --path '/repos/{owner}/{repo}/issues'"
     ""
     "## Inspect / manage"
     "  mocksys inspect <name>     # agent-readable summary of a fixture"
     "  mocksys doctor  <name>     # lint matchers/volatility; --fix to clean"
     "  mocksys requests <name>    # what the running mock actually received"
     "  mocksys ls                 # all scenarios + which are live"
     "  mocksys stop <name>        # or --all"
     ""
     "## Store + sharing"
     "All scenarios live in one shared library at ~/.mocksys (a git repo; override"
     "with MOCKSYS_HOME). It's the same library from every project."
     "  mocksys home                          # store path + git status"
     "  mocksys status / log                  # uncommitted changes / recent history"
     "  mocksys publish github/create-issue   # git-commit a frozen scenario"
     "  mocksys rm github/create-issue        # delete a scenario (or a whole service)"
     "  mocksys restore github/create-issue   # discard uncommitted local edits"
     "  mocksys remote git@host:org/mocks.git # one-time: set the share remote"
     "  mocksys push   /  mocksys pull        # sync with teammates"
     "  mocksys pack <name>                   # or a standalone .mock.tgz (no git needed)"
     ""
     "Secrets are redacted and volatile headers (Date, request-ids) stripped on freeze,"
     "and nothing unscrubbed is ever written to disk — so the store is safe to commit."
     "Run `mocksys help` for the full flag list."])))

(defn- usage []
  (println "mocksys — agent-native Mountebank wrapper")
  (println)
  (println "  mocksys init <service>                          github | gitlab | aws-s3 | stripe | generic-http")
  (println "  mocksys record <service/name> [--target URL] [--port N]")
  (println "  mocksys freeze <name>")
  (println "  mocksys run <name> [--port N] [--env VAR]")
  (println "  mocksys env <name>                              eval \"$(mocksys env <name>)\"")
  (println "  mocksys inspect <name>")
  (println "  mocksys doctor <name> [--fix]")
  (println "  mocksys requests <name> [--clear]")
  (println "  mocksys assert <name> --saw 'METHOD /path'")
  (println)
  (println "  mocksys import openapi <spec> [--service NAME] [--target URL]   spec -> one scenario per operation")
  (println "  mocksys new <service/name> [--service S]        scaffold a contract to hand-author")
  (println "  mocksys compile <name>                          rebuild imposter.json from contract.yaml")
  (println "  mocksys validate <name>                         check the contract (exit 1 on errors)")
  (println "  mocksys examples <name> [--op ID]               list examples + which are in play")
  (println "  mocksys use <name> --op ID --example NAME [--only|--off]   select example(s) to serve")
  (println "  mocksys add <name> --request 'METHOD /path' [--status N] [--body FILE | --text STR]")
  (println "  mocksys fault <name> [--status N] [--latency MS] [--timeout] [--drop-connection] [--clear]")
  (println "  mocksys parameterize <name> --path '/a/{var}/b'")
  (println "  mocksys pack <name> [--out FILE] | --stdout")
  (println "  mocksys unpack <file.mock.tgz>")
  (println "  mocksys ls")
  (println "  mocksys stop <name> | --all")
  (println "  mocksys rm <scenario|service>                   delete from the store (stops it if live)")
  (println)
  (println "  mocksys home                                    show the store path + git status")
  (println "  mocksys status                                  list uncommitted changes in the store")
  (println "  mocksys log [--n N]                             recent store history")
  (println "  mocksys restore <name> | --all                 discard uncommitted local changes")
  (println "  mocksys publish [<name>|<service>]              git-commit frozen scenarios")
  (println "  mocksys remote <git-url>                        set the share remote")
  (println "  mocksys push | pull                             sync the store with the remote")
  (println "  mocksys prime                                   agent orientation / cheatsheet"))

;; --- main -----------------------------------------------------------------

(defn -main [& args]
  (let [[cmd & more] args
        parsed (parse-args more)]
    (-> (case cmd
          "init"    (p/resolved (cmd-init parsed))
          "record"  (cmd-record parsed)
          "freeze"  (cmd-freeze parsed)
          "run"      (cmd-run parsed)
          "env"      (cmd-env parsed)
          "inspect"  (p/resolved (cmd-inspect parsed))
          "doctor"   (p/resolved (cmd-doctor parsed))
          "requests" (cmd-requests parsed)
          "assert"   (cmd-assert parsed)
          "import"        (cmd-import parsed)
          "compile"       (p/resolved (cmd-compile parsed))
          "new"           (p/resolved (cmd-new parsed))
          "validate"      (p/resolved (cmd-validate parsed))
          "examples"      (p/resolved (cmd-examples parsed))
          "use"           (p/resolved (cmd-use parsed))
          "add"           (p/resolved (cmd-add parsed))
          "fault"         (p/resolved (cmd-fault parsed))
          "parameterize"  (p/resolved (cmd-parameterize parsed))
          "pack"          (p/resolved (cmd-pack parsed))
          "unpack"        (p/resolved (cmd-unpack parsed))
          "ls"            (cmd-ls parsed)
          "stop"          (cmd-stop parsed)
          ("rm" "delete") (cmd-rm parsed)
          "home"          (p/resolved (cmd-home parsed))
          "status"        (p/resolved (cmd-status parsed))
          "log"           (p/resolved (cmd-log parsed))
          "restore"       (p/resolved (cmd-restore parsed))
          "publish"       (p/resolved (cmd-publish parsed))
          "remote"        (p/resolved (cmd-remote parsed))
          "push"          (p/resolved (cmd-push parsed))
          "pull"          (p/resolved (cmd-pull parsed))
          "prime"         (p/resolved (cmd-prime parsed))
          ("help" nil "--help" "-h") (p/resolved (usage))
          (p/resolved (do (println "unknown command:" cmd) (usage))))
        (p/catch (fn [err] (die (.-message err)))))))

(defn- cli-args
  "User args from process.argv. The node bin shim (`node bin/mocksys a b`) and a
   bun-compiled binary (`mocksys a b`) both expose argv as [runtime script-or-vpath
   ...args] — bun keeps a virtual script path (/$bunfs/root/mocksys) at argv[1] — so
   drop the first two."
  []
  (let [argv (vec (.-argv js/process))]
    (if (>= (count argv) 2) (subvec argv 2) [])))

(defn ^:export main
  "Entry point: shadow-cljs sets this as :main, so it runs on load of out/mocksys.js
   (both the node bin shim and the bun binary go through here)."
  [& _]
  (apply -main (cli-args)))
