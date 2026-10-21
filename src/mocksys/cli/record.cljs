(ns mocksys.cli.record
  "Seeding a contract from live traffic: init, record, freeze, promote."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [mocksys.mb :as mb]
            [mocksys.store :as store]
            [mocksys.scrub :as scrub]
            [mocksys.service :as service]
            [mocksys.contract :as contract]
            [mocksys.lifecycle :as lifecycle]
            [mocksys.proto :as proto]
            [mocksys.runtime.imposter :refer [proxy-target-error]]
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

(defn- grpc-target
  "A gRPC upstream's address as the proxy's `to`: host:port is the http:// origin
   gRPC without TLS speaks."
  [target]
  (cond
    (str/starts-with? target "https://")
    (die (str "record does not reach a gRPC upstream over TLS yet; --target must be host:port, without TLS (got " target ")"))
    (str/starts-with? target "http://") target
    :else (str "http://" target)))

(defn- copy-proto!
  "Copy the proto at `file`, and the files it imports, into `scenario`'s directory,
   at their paths relative to its directory, so the frozen contract compiles from
   what the scenario holds. Returns {:descriptor :proto (the contract's `proto`)}."
  [scenario file]
  (let [{:keys [descriptor files]} (try (proto/read-proto file) (catch :default e (die (str "--proto: " (ex-message e)))))
        from    (path/dirname (path/resolve file))
        to      (store/scenario-dir scenario)
        rels    (map #(path/relative from %) files)
        outside (filter #(str/starts-with? % (str ".." path/sep)) rels)]
    (when (seq outside)
      (die (str "--proto " file " imports " (str/join ", " (map #(path/join from %) outside))
                ", outside its directory; put the imports beside it or below it")))
    (doseq [r rels]
      (fs/mkdirSync (path/dirname (path/join to r)) #js {:recursive true})
      (fs/copyFileSync (path/join from r) (path/join to r)))
    {:descriptor descriptor :proto (str "./" (path/basename file))}))

(defn cmd-record [{:keys [pos opts]}]
  (let [scenario (or (:scenario opts) (first pos))
        prof     (when scenario (service/effective scenario))
        grpc?    (some? (:proto opts))
        target   (cond-> (or (:target opts) (:target prof)) grpc? (some-> grpc-target))]
    (when-not scenario (die "record needs a scenario: record <service/name> [--target URL] [--proto FILE]"))
    (when-not target   (die (if grpc?
                              "record needs --target host:port for a gRPC upstream"
                              "record needs --target URL (or a service template with a default)")))
    (when-let [e (proxy-target-error (if (:target opts) "--target" (str "the " (:service prof) " service's default target"))
                                     target)]
      (die (str e " (got " target ")"
                (when-let [origin (re-find #"^https?://[^/?#]+" (str target))]
                  (str "; record from the origin, --target " origin ", and send the full path to the proxy")))))
    (let [{:keys [descriptor proto]} (when grpc? (copy-proto! scenario (:proto opts)))]
      (p/let [_    (mb/ensure-up!)
              port (if (:port opts) (js/parseInt (:port opts)) (mb/free-port))]
        (mb/delete-imposter! port)
        (mb/post-imposter! (mb/proxy-imposter port target scenario descriptor))
        (store/write-recording! scenario (cond-> {:port port :target target :service (:service prof)}
                                           proto (assoc :proto proto)))
        (println (str "● recording '" scenario "'"))
        (println (if grpc?
                   (str "  proxy:  localhost:" port " (gRPC)  ->  " (subs target 7))
                   (str "  proxy:  http://localhost:" port "  ->  " target)))
        (println (if grpc?
                   "  call the proxy above (each rpc's first answer is kept; bidirectional streams are not recorded), then:"
                   "  send your traffic at the proxy URL above, then:"))
        (println (str "    mocksys freeze " scenario))))))

(defn cmd-freeze
  "Scrub a recording and lift it into a contract. By default the contract becomes
   canonical immediately; with --draft it lands as contract.draft.yaml for review,
   to be `promote`d (or discarded) — recording as an input, not the source of truth:
     freeze <name> [--draft]"
  [{:keys [pos opts]}]
  (let [scenario (first pos)
        draft?   (:draft opts)]
    (when-not scenario (die "freeze needs a scenario: freeze <name> [--draft]"))
    (let [{:keys [port proto]} (or (store/read-recording scenario)
                                   (die (str "no active recording for '" scenario "'")))
          prof (service/effective scenario)]
      (p/let [_   (mb/ensure-up!)
              raw (mb/get-replayable port)
              {:keys [imposter stub-count redacted stripped]}
              (scrub/scrub-imposter raw (:redact prof) (:volatile prof))]
        (store/write-imposter! scenario imposter)
        ;; Recorded scenarios are contract-canonical too: lift the scrubbed imposter
        ;; into a contract so `examples`/`use`/`fault` work on it like any other.
        (let [c (contract/lift imposter scenario (:service prof) proto)]
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

