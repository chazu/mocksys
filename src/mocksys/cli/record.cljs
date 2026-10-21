(ns mocksys.cli.record
  "Seeding a contract from live traffic: init, record, freeze, promote."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [mocksys.mb :as mb]
            [mocksys.store :as store]
            [mocksys.scrub :as scrub]
            [mocksys.service :as service]
            [mocksys.contract :as contract]
            [mocksys.lifecycle :as lifecycle]
            [mocksys.cli.util :refer [die]]))

(defn cmd-init [{:keys [pos]}]
  (let [name (first pos)]
    (when-not name (die (str "init needs a service: init <" (str/join "|" (sort (keys service/templates))) ">")))
    (let [tmpl (service/write-template! name)]
      (println (str "✚ initialized service '" name "'  ->  " store/root "/services/" name ".yaml"))
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
      (let [imposter (lifecycle/compile! scenario)]
        (println (str "✔ promoted '" scenario "' — draft is now contract.yaml ("
                      (count (get imposter "stubs")) " stub(s))")))
      (die (str "no draft for '" scenario "' — `freeze " scenario " --draft` first")))))

