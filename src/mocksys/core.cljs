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
        (store/write-mock! scenario {:scenario scenario
                                     :service  (:service prof)
                                     :port     port
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

;; --- v0.3 authoring -------------------------------------------------------

(defn- save-imposter!
  "Persist an imposter and refresh its mock.yaml metadata (stub count, etc.)."
  [scenario imposter]
  (store/write-imposter! scenario imposter)
  (let [prof (service/effective scenario)
        prev (or (store/read-mock scenario) {})]
    (store/write-mock! scenario (merge prev {:scenario scenario
                                             :service  (:service prof)
                                             :port     (get imposter "port")
                                             :env      (:env prof)
                                             :stubs    (count (get imposter "stubs"))}))))

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

(defn- build-stub [method path status body json?]
  {"predicates" [{"deepEquals" {"method" (str/upper-case method)}}
                 {"deepEquals" {"path" path}}]
   "responses"  [{"is" (cond-> {"statusCode" status}
                         (some? body) (assoc "headers" {"Content-Type" (if json?
                                                                         "application/json"
                                                                         "text/plain")}
                                             "body" body))}]})

(defn cmd-add
  "Hand-author an endpoint without recording:
     add <scenario> --request 'METHOD /path' [--status N] [--body FILE | --text STR]"
  [{:keys [pos opts]}]
  (let [scenario (first pos)
        request  (:request opts)]
    (when-not scenario (die "add needs a scenario: add <name> --request 'METHOD /path'"))
    (when-not (string? request) (die "add needs --request 'METHOD /path'"))
    (let [[method path] (str/split (str/trim request) #"\s+" 2)
          status        (js/parseInt (or (:status opts) 200))
          [body json?]  (body-value opts)
          stub          (build-stub method path status body json?)]
      (p/let [imposter (if (store/exists? scenario)
                         (store/read-imposter scenario)
                         (p/let [port (mb/free-port)]
                           {"protocol" "http" "port" port "stubs" []}))]
        (save-imposter! scenario (update imposter "stubs" (fnil conj []) stub))
        (println (str "✚ added " (str/upper-case method) " " path " -> " status
                      " to '" scenario "'  (" (inc (count (get imposter "stubs"))) " stub(s))"))))))

(defn- apply-fault [resp opts]
  (cond
    (:drop-connection opts) {"fault" "CONNECTION_RESET_BY_PEER"}
    (:malformed-json opts)  {"fault" "RANDOM_DATA_THEN_CLOSE"}
    :else
    (let [is   (cond-> (get resp "is" {})
                 (:status opts) (assoc "statusCode" (js/parseInt (:status opts))))
          wait (cond (:timeout opts)  120000
                     (:latency opts)  (js/parseInt (:latency opts)))]
      (cond-> (assoc resp "is" is)
        wait (assoc "behaviors" [{"wait" wait}])))))

(defn cmd-fault
  "Degrade a scenario's responses in place:
     fault <scenario> [--status N] [--latency MS] [--timeout] [--drop-connection] [--malformed-json]
   Mutates the fixture — copy/pack first if you want to keep the golden one."
  [{:keys [pos opts]}]
  (let [scenario (first pos)
        knobs    (select-keys opts [:status :latency :timeout :drop-connection :malformed-json])]
    (when-not scenario (die "fault needs a scenario: fault <name> --status N | --latency MS | ..."))
    (when (empty? knobs) (die "fault needs at least one of --status --latency --timeout --drop-connection --malformed-json"))
    (let [imposter (store/read-imposter scenario)
          faulted  (update imposter "stubs"
                           (fn [stubs]
                             (mapv #(update % "responses"
                                            (fn [rs] (mapv (fn [r] (apply-fault r opts)) rs)))
                                   stubs)))]
      (save-imposter! scenario faulted)
      (println (str "⚡ faulted '" scenario "' — "
                    (str/join ", " (map name (keys knobs))) " applied to all stubs")))))

(defn- template->regex-str
  "`/repos/{owner}/{repo}/issues` -> ^/repos/[^/]+/[^/]+/issues$"
  [t]
  (let [sentinel " "]
    (str "^" (-> t
                 (str/replace #"\{[^}]+\}" sentinel)
                 (str/replace #"[.+?^${}()|\[\]\\]" "\\\\$&")
                 (str/replace sentinel "[^/]+"))
         "$")))

(defn- pred-path [stub]
  (some (fn [pred] (some #(get % "path") (vals pred))) (get stub "predicates")))

(defn cmd-parameterize
  "Loosen exact path matches to a template so a fixture serves any owner/repo/id:
     parameterize <scenario> --path '/repos/{owner}/{repo}/issues'"
  [{:keys [pos opts]}]
  (let [scenario (first pos)
        tmpl     (:path opts)]
    (when-not scenario (die "parameterize needs a scenario: parameterize <name> --path '/a/{x}/b'"))
    (when-not (string? tmpl) (die "parameterize needs --path '/template/{with}/{vars}'"))
    (let [re-str   (template->regex-str tmpl)
          re       (re-pattern re-str)
          imposter (store/read-imposter scenario)
          changed  (atom 0)
          updated  (update imposter "stubs"
                           (fn [stubs]
                             (mapv (fn [stub]
                                     (let [path (pred-path stub)]
                                       (if (and path (re-matches re path))
                                         (do (swap! changed inc)
                                             (update stub "predicates"
                                                     (fn [preds]
                                                       (mapv (fn [pred]
                                                               (if (some #(contains? % "path") (vals pred))
                                                                 {"matches" {"path" re-str}}
                                                                 pred))
                                                             preds))))
                                         stub)))
                                   stubs)))]
      (if (zero? @changed)
        (println (str "no stubs matched " tmpl " — nothing parameterized"))
        (do (save-imposter! scenario updated)
            (println (str "❮❯ parameterized " @changed " stub(s) in '" scenario "' to " tmpl)))))))

;; --- v0.3 bundles ---------------------------------------------------------

(defn cmd-pack [{:keys [pos opts]}]
  (let [scenario (first pos)]
    (when-not scenario (die "pack needs a scenario: pack <name> [--out FILE] | --stdout > file"))
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
     "## Author without recording (failure modes, edge cases)"
     "  mocksys add github/rate-limited --request 'GET /rate_limit' --status 403 --body rl.json"
     "  mocksys fault github/create-issue --status 500        # or --latency 2000 --timeout"
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
     "  mocksys publish github/create-issue   # git-commit a frozen scenario"
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
  (println "  mocksys add <name> --request 'METHOD /path' [--status N] [--body FILE | --text STR]")
  (println "  mocksys fault <name> [--status N] [--latency MS] [--timeout] [--drop-connection]")
  (println "  mocksys parameterize <name> --path '/a/{var}/b'")
  (println "  mocksys pack <name> [--out FILE] | --stdout")
  (println "  mocksys unpack <file.mock.tgz>")
  (println "  mocksys ls")
  (println "  mocksys stop <name> | --all")
  (println)
  (println "  mocksys home                                    show the store path + git status")
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
          "add"           (cmd-add parsed)
          "fault"         (p/resolved (cmd-fault parsed))
          "parameterize"  (p/resolved (cmd-parameterize parsed))
          "pack"          (p/resolved (cmd-pack parsed))
          "unpack"        (p/resolved (cmd-unpack parsed))
          "ls"            (cmd-ls parsed)
          "stop"          (cmd-stop parsed)
          "home"          (p/resolved (cmd-home parsed))
          "publish"       (p/resolved (cmd-publish parsed))
          "remote"        (p/resolved (cmd-remote parsed))
          "push"          (p/resolved (cmd-push parsed))
          "pull"          (p/resolved (cmd-pull parsed))
          "prime"         (p/resolved (cmd-prime parsed))
          ("help" nil "--help" "-h") (p/resolved (usage))
          (p/resolved (do (println "unknown command:" cmd) (usage))))
        (p/catch (fn [err] (die (.-message err)))))))

(apply -main *command-line-args*)
