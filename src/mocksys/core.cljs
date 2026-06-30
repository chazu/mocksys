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
            [mocksys.inject :as inject]
            [mocksys.conform :as conform]
            [mocksys.kits :as kits]
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

(defn- parse-kv
  "`K=V,K2=V2` -> {\"K\" \"V\" \"K2\" \"V2\"}. Splits each pair on the first `=`
   only, so values may contain `=`. Bare `K` (no `=`) maps to \"\"."
  [s]
  (when (string? s)
    (into {} (for [pair (str/split s #",") :when (seq (str/trim pair))]
               (let [i (str/index-of pair "=")]
                 (if i [(str/trim (subs pair 0 i)) (subs pair (inc i))]
                     [(str/trim pair) ""]))))))

(defn- parse-conditions
  "`k=v,present-key` -> {\"k\" {\"equals\" \"v\"} \"present-key\" {\"present\" true}}.
   A bare key becomes a presence predicate; `k=v` an equality predicate."
  [s]
  (when (string? s)
    (into {} (for [pair (str/split s #",") :when (seq (str/trim pair))]
               (let [i (str/index-of pair "=")]
                 (if i [(str/trim (subs pair 0 i)) {"equals" (subs pair (inc i))}]
                     [(str/trim pair) {"present" true}]))))))

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
      (println "  (a recording profile: redact/volatile headers + default target)")
      (when-let [t (:default_target tmpl)] (println (str "  default target: " t)))
      (println "  author a scenario:")
      (println (str "    mocksys new " name "/<scenario>            # scaffold a contract to edit"))
      (println (str "    mocksys add " name "/<scenario> --request 'GET /path' --status 200 --text ok"))
      (println "  or seed one from live traffic (needs real access):")
      (println (str "    mocksys record " name "/<scenario>"
                    (when (:default_target tmpl) "         # target optional"))))))

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

(defn cmd-freeze
  "Scrub a recording and lift it into a contract. By default the contract becomes
   canonical immediately; with --draft it lands as contract.draft.yaml for review,
   to be `promote`d (or discarded) — recording as an input, not the source of truth:
     freeze <name> [--draft]"
  [{:keys [pos opts]}]
  (let [scenario (first pos)
        draft?   (:draft opts)]
    (when-not scenario (die "freeze needs a scenario: freeze <name> [--draft]"))
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
        (let [c (contract/lift imposter scenario (:service prof))]
          (if draft? (store/write-draft! scenario c) (store/write-contract! scenario c)))
        (store/write-mock! scenario {:scenario scenario
                                     :service  (:service prof)
                                     :port     port
                                     :origin   "recorded"
                                     :stubs    stub-count
                                     :env      (:env prof)
                                     :redacted (vec (sort redacted))
                                     :stripped (vec (sort stripped))})
        (mb/delete-imposter! port)
        (println (str "■ froze '" scenario "' — " stub-count " stub(s)"
                      (when draft? " (draft)")))
        (when (seq redacted) (println (str "  redacted secrets:  " (str/join ", " (sort redacted)))))
        (when (seq stripped) (println (str "  stripped volatile: " (str/join ", " (sort stripped)))))
        (if draft?
          (do (println (str "  review:   mocksys inspect " scenario "   /   mocksys doctor " scenario))
              (println (str "  promote:  mocksys promote " scenario "   (makes the draft canonical)")))
          (println (str "  run it with:  mocksys run " scenario)))))))

(defn cmd-promote
  "Make a scenario's reviewed draft its canonical contract:  promote <name>"
  [{:keys [pos]}]
  (let [scenario (first pos)]
    (when-not scenario (die "promote needs a scenario: promote <name>"))
    (if (store/promote-draft! scenario)
      (let [imposter (compile! scenario)]
        (println (str "✔ promoted '" scenario "' — draft is now contract.yaml ("
                      (count (get imposter "stubs")) " stub(s))")))
      (die (str "no draft for '" scenario "' — `freeze " scenario " --draft` first")))))

(defn ensure-running!
  "Make `scenario` live and return its port. Resolution order: explicit --port >
   the already-running port > a port *pinned* on the contract > a fresh free port.
   An explicit --port is persisted onto the contract so it stays pinned. Always
   (re)posts with recordRequests=true so `requests`/`assert` have data."
  [scenario port-opt]
  (let [imposter  (store/read-imposter scenario)
        contract  (store/read-contract scenario)
        pinned    (get contract "port")
        stateful? (inject/imposter-stateful? imposter)]
    (when (and stateful? (let [v (.-MOCKSYS_NO_INJECTION js/process.env)] (and v (not= v ""))))
      (die (str "'" scenario "' is a stateful mock (needs Mountebank injection), but "
                "MOCKSYS_NO_INJECTION is set. Unset it to run stateful scenarios.")))
    (p/let [_        (mb/ensure-up! stateful?)
            live     (mb/list-imposters)
            existing (first (filter #(= (:name %) scenario) live))
            port     (cond
                       port-opt (js/parseInt port-opt)
                       existing (:port existing)
                       pinned   pinned
                       :else    (mb/free-port))]
      (mb/delete-imposter! port)
      (let [imp (assoc imposter "port" port "name" scenario "recordRequests" true)]
        (p/let [_ (if stateful?
                    (mb/post-imposter-injecting! imp)
                    (mb/post-imposter! imp))]
          ;; persist an explicit --port as a pin (only when it actually changed, so
          ;; we don't churn contract.yaml's mtime and force needless recompiles)
          (when (and port-opt contract (not= port pinned))
            (store/write-contract! scenario (assoc contract "port" port)))
          port)))))

(defn- env-vars-for [scenario opts prof]
  (cond (:env opts)        [(:env opts)]
        (seq (:env prof))  (:env prof)
        :else              ["BASE_URL"]))

(defn- watch-scenario!
  "Recompile + reload `scenario` whenever its contract.yaml changes (debounced).
   Keeps the process alive — the Tilt-style save-and-reload dev loop."
  [scenario]
  (let [timer (atom nil)]
    (println (str "  ⊙ watching contract.yaml — edits recompile + reload (Ctrl-C to stop)"))
    (store/watch-contract!
     scenario
     (fn []
       (when @timer (js/clearTimeout @timer))
       (reset! timer
               (js/setTimeout
                (fn []
                  (-> (p/do (compile! scenario)
                            (ensure-running! scenario nil))
                      (p/then (fn [port] (println (str "  ↻ reloaded '" scenario "' @ http://localhost:" port))))
                      (p/catch (fn [e] (binding [*print-fn* *print-err-fn*]
                                         (println (str "  ✗ reload failed: " (.-message e))))))))
                150))))))

(defn cmd-run [{:keys [pos opts]}]
  (let [scenario (first pos)]
    (when-not scenario (die "run needs a scenario: run <name>"))
    (ensure-compiled! scenario)
    (let [prof (service/effective scenario)]
      (p/let [port (ensure-running! scenario (:port opts))]
        (println (str "▶ running '" scenario "' at http://localhost:" port))
        (doseq [v (env-vars-for scenario opts prof)]
          (println (str "  export " v "=http://localhost:" port)))
        (when (:watch opts) (watch-scenario! scenario))))))

(defn- scenario-port-pin!
  "The scenario's pinned port, allocating+persisting a free one if it has none.
   Used by emitters that must name a stable port without starting the mock."
  [scenario]
  (p/let [c      (store/read-contract scenario)
          pinned (get c "port")
          port   (or pinned (mb/free-port))]
    (when (and (not pinned) c)
      (store/write-contract! scenario (assoc c "port" port)))
    port))

(defn- tilt-snippet
  "A Tilt `local_resource` that serves the scenario on a pinned port — drop into a
   Tiltfile to run the mock as part of a dev stack (cf. snooker's mockoauth)."
  [scenario port vars]
  (let [res (str/replace scenario "/" "-")
        url (str "http://localhost:" port)]
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
    (ensure-compiled! scenario)
    (let [prof (service/effective scenario)
          vars (env-vars-for scenario opts prof)]
      (cond
        (:tilt opts)
        (p/let [port (scenario-port-pin! scenario)]
          (print (tilt-snippet scenario port vars)))

        (:json opts)
        (p/let [port (ensure-running! scenario (:port opts))]
          (let [url (str "http://localhost:" port)]
            (println (js/JSON.stringify
                      (clj->js (into {"scenario" scenario "port" port "url" url}
                                     (map (fn [v] [v url]) vars)))
                      nil 2))))

        :else
        (p/let [port (ensure-running! scenario (:port opts))]
          (doseq [v vars]
            (println (str "export " v "=http://localhost:" port)))
          (println (str "export MOCKSYS_SCENARIO=" scenario)))))))

(defn- call-line [{:keys [method path status]} i]
  (str "  " (inc i) ". " method " " path " -> " status))

(defn cmd-inspect [{:keys [pos]}]
  (let [scenario (first pos)]
    (when-not scenario (die "inspect needs a scenario: inspect <name>"))
    (ensure-compiled! scenario)
    (let [imposter (store/read-imposter scenario)
          prof     (service/effective scenario)
          ;; Prefer the contract's friendly method/path (e.g. /users/{id}) over the
          ;; imposter's compiled regex — stubs map 1:1 to operations in order.
          ops      (get (or (store/read-contract scenario) {}) "operations" [])
          stubs    (->> (analyze/summarize imposter (:volatile prof))
                        (map-indexed
                         (fn [i s]
                           (if-let [op (nth ops i nil)]
                             (assoc s :method (get-in op ["request" "method"])
                                      :path   (get-in op ["request" "path"]))
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
    (ensure-compiled! scenario)
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

(defn cmd-conform
  "Replay a request corpus at the twin and diff it against reality — the fidelity
   loop that drives a twin toward zero observable delta:
     conform <scenario> --corpus calls.json [--against URL] [--ignore k1,k2] [--headers]
   With --against URL, each response is diffed twin-vs-live (status + body, normalized
   by the service's volatile fields; --headers also compares non-volatile headers).
   Without it, it's a golden check: each status must equal the request's `expect`
   (or, absent one, be < 500). Omit --corpus to replay what the twin already received.
   Exits 1 on any divergence — drops straight into CI."
  [{:keys [pos opts]}]
  (let [scenario (first pos)]
    (when-not scenario (die "conform needs a scenario: conform <name> --corpus FILE [--against URL]"))
    (ensure-compiled! scenario)
    (let [prof     (service/effective scenario)
          against  (when (string? (:against opts)) (:against opts))
          ignore   (when (string? (:ignore opts)) (vec (remove str/blank? (str/split (:ignore opts) #","))))
          diff-opts {:ignore ignore :vol-set (:volatile prof) :compare-headers? (boolean (:headers opts))}]
      (p/let [;; Load the corpus BEFORE (re)starting the twin: ensure-running! reposts
              ;; the imposter (clearing its recorded requests), so the no-corpus
              ;; "replay what the twin already saw" mode must read them first.
              live     (mb/list-imposters)
              existing (first (filter #(= (:name %) scenario) live))
              corpus   (if (string? (:corpus opts))
                         (conform/parse-corpus (store/slurp (:corpus opts)))
                         (if existing
                           (p/let [full (mb/get-imposter (:port existing))]
                             (conform/recorded->corpus (get full "requests")))
                           []))
              port      (ensure-running! scenario (:port opts))
              twin-base (str "http://localhost:" port)]
        (when (empty? corpus)
          (die (str "empty corpus — pass --corpus FILE, or `run` the twin, drive some "
                    "traffic at it, then `conform " scenario "` to replay it")))
        (println (str "# conform: " scenario "  (" (count corpus) " request(s) — "
                      (if against (str "diff vs " against) "golden mode") ")"))
        (-> (p/loop [reqs corpus i 0 fails 0]
              (if (empty? reqs)
                {:fails fails :total (count corpus)}
                (let [req (first reqs)
                      label (str (get req "method" "GET") " " (get req "path"))]
                  (p/let [tw (conform/fire twin-base req)
                          lv (when against (conform/fire against req))]
                    (let [[ok? note]
                          (if against
                            (if-let [rs (conform/diff-one tw lv diff-opts)]
                              [false (str "DIFF: " (str/join "; " rs))]
                              [true (str "match (" (:status tw) ")")])
                            (let [exp (get req "expect")]
                              (if exp
                                [(= (:status tw) exp) (str (:status tw) " (expect " exp ")")]
                                [(< (:status tw) 500) (str (:status tw))])))]
                      (println (str "  " (if ok? "✓" "✗") " " label "  →  " note))
                      (p/recur (rest reqs) (inc i) (if ok? fails (inc fails))))))))
            (p/then
             (fn [{:keys [fails total]}]
               (println)
               (if (zero? fails)
                 (println (str "  ✓ conformant — " total " request(s), 0 diffs"))
                 (do (binding [*print-fn* *print-err-fn*]
                       (println (str "  ✗ " fails "/" total " diverged")))
                     (js/process.exit 1))))))))))

(defn- parse-duration
  "`1h`/`30m`/`45s`/`2d`/`3600` -> seconds (bare number = seconds). nil if unparseable."
  [s]
  (when-let [[_ n unit] (re-matches #"(?i)(\d+)\s*([smhd]?)" (str/trim (str s)))]
    (* (js/parseInt n)
       (case (str/lower-case unit) "m" 60 "h" 3600 "d" 86400 1))))

(defn- ts->epoch
  "An epoch number or an ISO timestamp -> epoch seconds. nil if unparseable."
  [s]
  (cond
    (re-matches #"\d+" (str s)) (js/parseInt s)
    :else (let [e (js/Math.floor (/ (.getTime (js/Date. s)) 1000))]
            (when-not (js/isNaN e) e))))

(defn cmd-clock
  "Read or move a twin's *virtual* clock — deterministic, frozen until you advance it
   (so token/session expiry and rate-limit windows are reproducible):
     clock <name>                 show the current virtual time
     clock <name> advance <dur>   advance by 1h / 30m / 45s / 2d / <seconds>
     clock <name> set <ts>        set to an epoch or ISO timestamp
   The twin must already be running — the clock lives in its live state, and this
   never reposts it (which would reset state)."
  [{:keys [pos]}]
  (let [scenario (first pos)
        verb     (second pos)
        arg      (nth pos 2 nil)]
    (when-not scenario (die "clock needs a scenario: clock <name> [advance <dur> | set <ts>]"))
    (let [body (case verb
                 nil       {}
                 "now"     {}
                 "advance" {"advance" (or (parse-duration arg)
                                          (die (str "bad duration '" arg "' — try 1h, 30m, 45s, 2d, or seconds")))}
                 "set"     {"set" (or (ts->epoch arg)
                                      (die (str "bad timestamp '" arg "' — use an epoch or ISO string")))}
                 (die (str "unknown clock verb '" verb "' — use: advance <dur> | set <ts> | (nothing)")))]
      (p/let [live (mb/list-imposters)
              imp  (running-imposter scenario live)
              res  (js/fetch (str "http://localhost:" (:port imp) "/_mocksys/clock")
                             #js {:method "POST"
                                  :headers #js {"Content-Type" "application/json"}
                                  :body (js/JSON.stringify (clj->js body))})
              text (.text res)
              out  (try (js->clj (js/JSON.parse text)) (catch :default _ nil))]
        (if (and (map? out) (get out "now"))
          (println (str "🕐 " scenario " — virtual time " (get out "iso")
                        "  (epoch " (get out "now") ")"))
          (die (str "'" scenario "' has no virtual clock — enable it with `state.clock`, "
                    "a `store.ttl`, a `limit`, or a `now` bind (see docs/effects.md)")))))))

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
              (ensure-compiled! sc)
              (p/let [prof (service/effective sc)
                      port (ensure-running! sc nil)]
                (p/recur (rest scs) (conj acc [sc port (env-vars-for sc {} prof)]))))))
        (p/then
         (fn [results]
           (println (str "▲ up '" name "' (" (count results) " scenario(s))"))
           (doseq [[sc port _] results] (println (str "  ▶ " sc "  http://localhost:" port)))
           (println)
           (doseq [[_ port vars] results, v vars]
             (println (str "export " v "=http://localhost:" port))))))))

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

(defn- default-scenario-for
  "A sensible default scenario id when --scenario is omitted, per kit."
  [kind provider]
  (case kind
    "oauth2" (str "oauth/" (or provider "github"))
    (str kind "/" kind)))         ; e.g. okta -> okta/okta

(defn cmd-kit
  "Generate a complete (often stateful) mock — a digital twin — from a recipe in one
   shot:
     kit okta [--users FILE.json] [--scenario NAME] [--port N]
     kit oauth2 [--provider github|codeberg] [--users FILE.json] [--scenario NAME] [--port N]"
  [{:keys [pos opts]}]
  (let [kind (first pos)]
    (when-not kind
      (die (str "kit needs a recipe: kit <" (str/join "|" (sort (keys kits/kits))) "> [options]\n"
                "  (see `mocksys genes` for the building blocks a kit composes)")))
    (let [builder  (or (:build (get kits/kits kind))
                       (die (str "unknown kit '" kind "'. known: "
                                 (str/join ", " (sort (keys kits/kits))))))
          provider (when (string? (:provider opts)) (:provider opts))
          users    (when (string? (:users opts))
                     (try (js->clj (js/JSON.parse (store/slurp (:users opts))))
                          (catch :default _ (die (str "could not parse --users as JSON: " (:users opts))))))
          scenario (or (when (string? (:scenario opts)) (:scenario opts))
                       (default-scenario-for kind provider))
          rate-limit (when (string? (:rate-limit opts))
                       {:max    (js/parseInt (:rate-limit opts))
                        :window (when (string? (:rate-window opts)) (js/parseInt (:rate-window opts)))})
          token-ttl  (when (string? (:token-ttl opts)) (js/parseInt (:token-ttl opts)))
          built    (cond-> (builder scenario {:provider provider :users users
                                              :rate-limit rate-limit :token-ttl token-ttl})
                     (:port opts) (assoc "port" (js/parseInt (:port opts))))]
      (store/write-contract! scenario built)
      (compile! scenario)
      (println (str "✦ generated '" scenario "' from kit '" kind "'"))
      (when (inject/contract-stateful? built)
        (println "  (stateful — served via Mountebank injection, managed automatically by `run`)"))
      (doseq [op (get built "operations")]
        (println (str "  · " (get-in op ["request" "method"]) " " (get-in op ["request" "path"]))))
      (println)
      (println (str "  run it:   mocksys run " scenario
                    (when (:port opts) (str " --port " (:port opts)))))
      (println (str "  inspect:  mocksys inspect " scenario)))))

(defn cmd-genes
  "List the reusable behavioral genes that kits compose (the building blocks of a
   twin), and the kits available:  genes"
  [_]
  (println "# genes — reusable behavioral patterns a kit composes")
  (doseq [{:keys [name doc]} kits/genes]
    (println (str "  · " name "  —  " doc)))
  (println)
  (println "# kits — full twins generated in one command")
  (doseq [[k {:keys [doc]}] (sort kits/kits)]
    (println (str "  · mocksys kit " k "   —  " doc)))
  (println)
  (println "  e.g.  mocksys kit okta            # a digital twin of Okta"))

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
     add <scenario> --request 'METHOD /path' [--status N] [--body FILE | --text STR]
       [--header 'K=V,K2=V2']         response headers (e.g. a redirect Location)
       [--when-query 'k=v,present']   only match when these query conditions hold
       [--when-header 'k=v,present']  only match when these header conditions hold
   Request conditions let several operations share a path and branch on content;
   order operations specific-first (mountebank serves the first matching stub)."
  [{:keys [pos opts]}]
  (let [scenario (first pos)
        request  (:request opts)]
    (when-not scenario (die "add needs a scenario: add <name> --request 'METHOD /path'"))
    (when-not (string? request) (die "add needs --request 'METHOD /path'"))
    (let [[method path] (str/split (str/trim request) #"\s+" 2)
          status        (js/parseInt (or (:status opts) 200))
          [body json?]  (body-value opts)
          extras        {:resp-headers (parse-kv (when (string? (:header opts)) (:header opts)))
                         :query        (parse-conditions (when (string? (:when-query opts)) (:when-query opts)))
                         :headers      (parse-conditions (when (string? (:when-header opts)) (:when-header opts)))}
          existing      (ensure-contract! scenario)
          svc           (service/service-of scenario)
          c             (contract/add-operation existing scenario svc method path status body json? extras)]
      (store/write-contract! scenario c)
      (let [imposter (compile! scenario)]
        (println (str "✚ added " (str/upper-case method) " " path " -> " status
                      (when (seq (:query extras))   (str "  [?" (str/join "," (keys (:query extras))) "]"))
                      (when (seq (:headers extras)) (str "  [hdr " (str/join "," (keys (:headers extras))) "]"))
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
    ["# mocksys — local mocks and full digital twins of external systems"
     ""
     "You stand up a local mock of an external system (github, stripe, okta, an OAuth"
     "provider, ...) that an app/test hits instead of the real thing — from a fixed"
     "fixture up to a stateful *digital twin* (CRUD resources, auth flows, rate limits,"
     "expiring tokens). Vocabulary: a *scenario* (named `service/name`) is described by a"
     "canonical `contract.yaml` (method/path, response schemas, selectable *examples*, and"
     "an optional declarative `effect` for stateful behavior); it compiles to a Mountebank"
     "imposter automatically on `run`. You *author* a contract (or generate one with a"
     "`kit`); recording live traffic is just one way to seed one, when you have creds."
     ""
     "## Stand up a digital twin in one command (the fastest path)"
     "A *kit* generates a whole behavioral clone — stateful, seeded, runnable:"
     "  mocksys kit okta                              # Okta twin: users+groups CRUD, SCIM filter, OIDC"
     "  mocksys kit oauth2 --provider github          # OAuth2 provider: picker, single-use codes, userinfo"
     "  mocksys genes                                 # the reusable behavioral genes a kit composes"
     "A twin is one stateful scenario sharing one port/state; `run` it like any other."
     ""
     "## Or author a mock yourself (no live access needed)"
     "  mocksys import openapi ./openapi.yaml --service stripe   # one scenario per operation"
     "  mocksys new acme/get-widget                   # scaffold, then edit contract.yaml"
     "  mocksys add acme/get-widget --request 'GET /widgets/1' --status 200 --body w.json"
     "Stateful resources (a REST collection) are declarative — no JavaScript:"
     "  state.collections: {users: {idField: id, seed: users}}   # in contract.yaml"
     "  effect: {bind: {...}, create|get|update|remove|list: {collection: users, ...}}"
     "Each operation keeps its schema plus *named examples* you select to serve:"
     "  mocksys examples stripe/create-charge          # list examples, [x]=in play"
     "  mocksys use stripe/create-charge --op createCharge --example card_declined --only"
     "  mocksys validate stripe/create-charge          # schema/structure check"
     ""
     "## Run it & wire a test/app to the mock"
     "  mocksys run acme/get-widget                    # host the mock (prints a port)"
     "  eval \"$(mocksys env acme/get-widget)\"          # exports BASE_URL=... etc"
     "  # point your code at the exported URL, run it, then assert what it called:"
     "  mocksys assert acme/get-widget --saw 'GET /widgets/*'   # exit 1 on miss"
     ""
     "## Validate fidelity against reality (the digital-twin loop)"
     "  mocksys conform okta/okta --corpus calls.json            # golden: status vs expect"
     "  mocksys conform okta/okta --corpus calls.json --against https://your.okta.com"
     "Replays the same requests at the twin and (with creds) the live service, diffs"
     "responses (normalized by volatile fields), and exits 1 on drift — drive a twin to"
     "zero observable delta. corpus = a JSON array of {method, path, body?, expect?}."
     ""
     "## Branch on request content (several ops share a path, first match wins)"
     "  mocksys add gh/auth --request 'GET /authorize' --status 302 \\"
     "      --when-query 'login' --header 'Location=https://app/cb?code=abc'   # ?login present"
     "  mocksys add gh/auth --request 'GET /authorize' --status 200 --text '<picker/>'  # fallback"
     ""
     "## Stateful flows, time & failure modes"
     "  mocksys kit oauth2 --provider github           # code→token→userinfo handshake (stateful)"
     "  mocksys kit okta --rate-limit 100 --token-ttl 3600   # 429s + tokens that expire on the clock"
     "  mocksys clock okta/okta advance 1h            # move the deterministic virtual clock (expiry, windows)"
     "  mocksys fault github/create-issue --latency 2000   # or --timeout --drop-connection --clear"
     "  mocksys parameterize github/create-issue --path '/repos/{owner}/{repo}/issues'"
     ""
     "## Seed a contract from live traffic (optional — needs real access)"
     "  mocksys init github                            # load the service profile (redact/target)"
     "  mocksys record github/create-issue             # proxy → api.github.com; drive traffic at it"
     "  mocksys freeze github/create-issue             # scrub secrets+volatility → a contract"
     "Secrets are redacted and volatile headers stripped on freeze, in memory, so"
     "nothing unscrubbed is ever written to disk — the store is safe to commit."
     ""
     "## Inspect / manage"
     "  mocksys inspect <name>     # agent-readable summary of a scenario"
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
     "  mocksys publish github/create-issue   # git-commit a scenario"
     "  mocksys rm github/create-issue        # delete a scenario (or a whole service)"
     "  mocksys restore github/create-issue   # discard uncommitted local edits"
     "  mocksys remote git@host:org/mocks.git # one-time: set the share remote"
     "  mocksys push   /  mocksys pull        # sync with teammates"
     "  mocksys pack <name>                   # or a standalone .mock.tgz (no git needed)"
     ""
     "Run `mocksys help` for the full flag list."])))

(defn- usage []
  (println "mocksys — author reusable mock fixtures for external systems")
  (println)
  (println "  TWINS (full behavioral clones in one command — see `mocksys genes`)")
  (println "  mocksys kit okta [--users F.json] [--rate-limit N] [--rate-window S] [--token-ttl S] [--scenario NAME] [--port N]")
  (println "  mocksys kit oauth2 [--provider github|codeberg] [--users F.json] [--port N]   OAuth2 provider twin")
  (println "  mocksys genes                                   list the behavioral genes kits compose")
  (println "  mocksys conform <name> --corpus FILE [--against URL] [--ignore k1,k2]   replay+diff (exit 1 on drift)")
  (println "  mocksys clock <name> [advance <1h|30m|45s> | set <ts>]   read/move a twin's deterministic virtual clock")
  (println)
  (println "  AUTHOR (hand-author or import — no live access needed)")
  (println "  mocksys new <service/name> [--service S]        scaffold a contract to hand-author")
  (println "  mocksys import openapi <spec> [--service NAME] [--target URL]   spec -> one scenario per operation")
  (println "  mocksys add <name> --request 'METHOD /path' [--status N] [--body FILE | --text STR]")
  (println "      [--header 'K=V,..'] [--when-query 'k=v,..'] [--when-header 'k=v,..']   response hdrs / request branching")
  (println "  mocksys examples <name> [--op ID]               list examples + which are in play")
  (println "  mocksys use <name> --op ID --example NAME [--only|--off]   select example(s) to serve")
  (println "  mocksys fault <name> [--status N] [--latency MS] [--timeout] [--drop-connection] [--clear]")
  (println "  mocksys parameterize <name> --path '/a/{var}/b'")
  (println "  mocksys validate <name>                         check the contract (exit 1 on errors)")
  (println "  mocksys compile <name>                          rebuild imposter.json from contract.yaml")
  (println)
  (println "  RUN / VERIFY")
  (println "  mocksys run <name> [--port N] [--env VAR] [--watch]   --watch: recompile+reload on edit")
  (println "  mocksys env <name> [--json|--tilt]              eval \"$(mocksys env <name>)\"; or emit JSON / a Tiltfile resource")
  (println "  mocksys stack <name> <scenario>...              define a stack (a set of scenarios)")
  (println "  mocksys up <stack> | down <stack>               bring a whole stack up/down with combined env")
  (println "  mocksys inspect <name>")
  (println "  mocksys doctor <name> [--fix]")
  (println "  mocksys requests <name> [--clear]")
  (println "  mocksys assert <name> --saw 'METHOD /path'")
  (println "  mocksys ls")
  (println "  mocksys stop <name> | --all")
  (println "  mocksys rm <scenario|service>                   delete from the store (stops it if live)")
  (println)
  (println "  SEED FROM LIVE TRAFFIC (optional — needs real access)")
  (println "  mocksys init <service>                          github | gitlab | aws-s3 | stripe | generic-http")
  (println "  mocksys record <service/name> [--target URL] [--port N]")
  (println "  mocksys freeze <name> [--draft]                 --draft: land for review, then `promote`")
  (println "  mocksys promote <name>                          make a reviewed draft canonical")
  (println)
  (println "  PACK / SHARE")
  (println "  mocksys pack <name> [--out FILE] | --stdout")
  (println "  mocksys unpack <file.mock.tgz>")
  (println)
  (println "  STORE (git-backed shared library)")
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
    ;; Commands run eagerly inside the case (to produce the promise), so a
    ;; *synchronous* throw (e.g. a malformed contract.yaml) would escape the
    ;; promise chain as a raw stack trace. Funnel both sync and async failures
    ;; through `die` for a clean one-line error.
    (-> (try
          (case cmd
          "init"    (p/resolved (cmd-init parsed))
          "record"  (cmd-record parsed)
          "freeze"  (cmd-freeze parsed)
          "promote" (p/resolved (cmd-promote parsed))
          "run"      (cmd-run parsed)
          "env"      (cmd-env parsed)
          "inspect"  (p/resolved (cmd-inspect parsed))
          "doctor"   (p/resolved (cmd-doctor parsed))
          "requests" (cmd-requests parsed)
          "assert"   (cmd-assert parsed)
          "import"        (cmd-import parsed)
          "kit"           (p/resolved (cmd-kit parsed))
          "genes"         (p/resolved (cmd-genes parsed))
          "conform"       (cmd-conform parsed)
          "clock"         (cmd-clock parsed)
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
          "stack"         (p/resolved (cmd-stack parsed))
          "up"            (cmd-up parsed)
          "down"          (cmd-down parsed)
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
          (catch :default e (p/rejected e)))
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
