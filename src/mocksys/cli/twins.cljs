(ns mocksys.cli.twins
  "Digital twins: kit, genes, conform, clock."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [mocksys.mb :as mb]
            [mocksys.store :as store]
            [mocksys.service :as service]
            [mocksys.inject :as inject]
            [mocksys.contract :as contract]
            [mocksys.proto :as proto]
            [mocksys.runtime.grpc :as grpc]
            [mocksys.conform :as conform]
            [mocksys.kits :as kits]
            [mocksys.lifecycle :as lifecycle]
            [mocksys.modules :as modules]
            [mocksys.cli.util :refer [die running-imposter modules-notice]]))

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
    (when (proto/grpc? (store/read-contract scenario))
      (die (str "conform replays HTTP requests; '" scenario "' is a gRPC mock, which it cannot replay")))
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
              port      (lifecycle/ensure-running! scenario (:port opts))
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
              out  (if (proto/grpc? (store/read-contract scenario))
                     (grpc/clock-call (:port imp) body)
                     (p/let [res  (js/fetch (str "http://localhost:" (:port imp) "/_mocksys/clock")
                                            #js {:method "POST"
                                                 :headers #js {"Content-Type" "application/json"}
                                                 :body (js/JSON.stringify (clj->js body))})
                             text (.text res)]
                       (try (js->clj (js/JSON.parse text)) (catch :default _ nil))))]
        (if (and (map? out) (get out "now"))
          (println (str "🕐 " scenario " — virtual time " (get out "iso")
                        "  (epoch " (get out "now") ")"))
          (die (str "'" scenario "' has no virtual clock — enable it with `state.clock`, "
                    "a `store.ttl`, a `limit`, or a `now` bind (see docs/effects.md)")))))))

(defn- default-scenario-for
  "A sensible default scenario id when --scenario is omitted, per kit."
  [kind provider]
  (case kind
    "oauth2" (str "oauth/" (or provider "github"))
    (str kind "/" kind)))         ; e.g. okta -> okta/okta

(defn- load-kits
  "Built-in kits plus $MOCKSYS_HOME/kits/*.yaml, printing any load warnings."
  []
  (let [{:keys [kits warnings]} (kits/with-user-kits (store/kit-files store/kits-dir))]
    (binding [*print-fn* *print-err-fn*]
      (doseq [w warnings] (println (str "warning: " w))))
    kits))

(defn cmd-kit
  "Generate a complete (often stateful) mock — a digital twin — from a kit in one
   shot. Kits are built in or YAML files in $MOCKSYS_HOME/kits (see `mocksys kits`):
     kit okta [--users FILE.json] [--rate-limit N] [--rate-window S] [--token-ttl S]
              [--issuer URL] [--signing-key FILE.pem] [--scenario NAME] [--port N]
     kit oauth2 [--provider github|codeberg] [--users FILE.json] [--token-ttl S]
                [--scenario NAME] [--port N]
     kit <name> [--<flag> VALUE ...] [--scenario NAME] [--port N]"
  [{:keys [pos opts]}]
  (let [kind     (first pos)
        registry (load-kits)
        known    (str/join "|" (sort (keys registry)))]
    (when-not kind
      (die (str "kit needs a recipe: kit <" known "> [options]\n"
                "  (see `mocksys kits` for each kit's flags)")))
    (let [kit      (or (get registry kind)
                       (die (str "unknown kit '" kind "'. known: " known)))
          _        (when-let [bad (seq (kits/unsupported-flags kit opts))]
                     (die (str "kit " kind " does not support "
                               (str/join ", " (map #(str "--" (name %)) bad)))))
          flags    (try (kits/parse-flags kit opts store/slurp)
                        (catch :default e (die (.-message e))))
          scenario (or (when (string? (:scenario opts)) (:scenario opts))
                       (default-scenario-for kind (or (:provider flags) (get-in kit [:flags :provider :default]))))
          built    (cond-> ((:build kit) scenario flags)
                     (:port opts) (assoc "port" (js/parseInt (:port opts))))
          kit-dir  (:dir kit)
          sdir     (store/scenario-dir scenario)
          plan     (try (when kit-dir
                          (modules/copy-plan kit-dir sdir
                                             (modules/scenario-paths kit-dir (get built "modules") "kit directory")))
                        (catch :default e (die (str "kit " kind ": " (.-message e) "; nothing was written"))))
          _        (when-let [errs (seq (:errors (contract/validate built kit-dir)))]
                     (die (str "kit " kind " generated an invalid contract; nothing was written\n  "
                               (str/join "\n  " errs))))
          changed  (for [[p state] plan :when (= state :changed)] p)
          _        (when (and (seq changed) (not (:force opts)))
                     (die (str (str/join ", " changed) " in " sdir (if (next changed) " differ" " differs")
                               " from the kit's copy; nothing was written. "
                               "Rerun with --force to replace it, or pick another --scenario")))
          expanded (contract/expand built)]
      (store/write-contract! scenario built)
      (modules/copy! kit-dir sdir plan)
      (lifecycle/compile! scenario)
      (println (str "✦ generated '" scenario "' from kit '" kind "'"))
      (doseq [p changed]
        (println (str "  replaced " p ", which differed from the kit's copy")))
      (when-let [ms (seq (get built "modules"))]
        (println (modules-notice scenario ms)))
      (when (inject/contract-stateful? expanded)
        (println "  (stateful: served by the mocksys daemon, started automatically by `run`)"))
      (doseq [op (get expanded "operations")]
        (println (str "  · " (get-in op ["request" "method"]) " " (get-in op ["request" "path"]))))
      (println)
      (println (str "  run it:   mocksys run " scenario
                    (when (:port opts) (str " --port " (:port opts)))))
      (println (str "  inspect:  mocksys inspect " scenario)))))

(defn cmd-kits
  "List every kit `kit` can generate, built in or from $MOCKSYS_HOME/kits, with its
   flags, their types and their defaults:  kits"
  [_]
  (let [registry (load-kits)]
    (println (str "# kits (mocksys kit <name> [--flag VALUE ...]); yours go in " store/kits-dir
                  "/*.yaml, or <name>/kit.yaml beside the modules it ships"))
    (doseq [[kname {:keys [doc source flags modules]}] (sort registry)]
      (println (str "  · " kname "  " doc "  [" source "]"
                    (when modules
                      (str "  ships modules: " (if (sequential? modules) (str/join ", " modules) modules)))))
      (println (str "      " (if (empty? flags)
                                 "(no flags)"
                                 (str/join "  " (for [[f {:keys [default type]}] (sort flags)]
                                                  (str "--" (name f) " <" (name type) ">"
                                                       (when (some? default) (str " (default " default ")")))))))))
    (println)
    (println "  e.g.  mocksys kit okta            # a digital twin of Okta")))

(defn cmd-genes
  "List the genes a contract can list under `genes:`, each with its params, and the
   capabilities that are built in rather than genes:  genes"
  [_]
  (println "# genes (list them under `genes:` in contract.yaml; * marks a required param)")
  (doseq [[gname {:keys [doc params required]}] (sort kits/genes)]
    (println (str "  · " gname "  " doc))
    (let [label #(str (name %) (when (required %) "*"))
          width (apply max (map (comp count label first) params))]
      (doseq [[k shape pdoc] params]
        (println (str "      " (.padEnd (label k) width) "  " (.padEnd (name shape) 6) "  " pdoc)))))
  (println)
  (println "# built in, not genes")
  (let [width (apply max (map (comp count first) kits/capabilities))]
    (doseq [[cname where] kits/capabilities]
      (println (str "  · " (.padEnd cname width) "  " where))))
  (println)
  (println "# kits compose genes into a whole twin: `mocksys kits` lists them"))

