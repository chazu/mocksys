(ns mocksys.cli.util
  "Arg parsing and small helpers shared by the command namespaces."
  (:require [clojure.string :as str]))

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

(defn modules-notice
  "The line `kit` and `unpack` print for a scenario that will run module code."
  [scenario modules]
  (str "  ! " scenario " runs JavaScript from its modules (" (str/join ", " modules)
       ") in the mocksys daemon when you `run` it; read them first"))

(defn die [msg]
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

(defn env-vars-for [scenario opts prof]
  (cond (:env opts)        [(:env opts)]
        (seq (:env prof))  (:env prof)
        :else              ["BASE_URL"]))

(defn running-imposter
  "Resolve the live imposter for a scenario, or die with guidance."
  [scenario live]
  (or (first (filter #(= (:name %) scenario) live))
      (die (str "'" scenario "' is not running — `mocksys run " scenario "` first"))))

