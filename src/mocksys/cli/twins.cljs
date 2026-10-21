(ns mocksys.cli.twins
  "Digital twins: kit, genes, conform, clock."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [mocksys.mb :as mb]
            [mocksys.store :as store]
            [mocksys.service :as service]
            [mocksys.inject :as inject]
            [mocksys.conform :as conform]
            [mocksys.kits :as kits]
            [mocksys.lifecycle :as lifecycle]
            [mocksys.cli.util :refer [die running-imposter]]))

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
          issuer     (when (string? (:issuer opts)) (:issuer opts))
          signing-key (when (string? (:signing-key opts))
                        (try (store/slurp (:signing-key opts))
                             (catch :default _ (die (str "could not read --signing-key file: " (:signing-key opts))))))
          built    (cond-> (builder scenario {:provider provider :users users
                                              :rate-limit rate-limit :token-ttl token-ttl
                                              :issuer issuer :signing-key signing-key})
                     (:port opts) (assoc "port" (js/parseInt (:port opts))))]
      (store/write-contract! scenario built)
      (lifecycle/compile! scenario)
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

