(ns mocksys.scrub
  "Clean a recorded imposter before it lands on disk. Two transforms on recorded
  response headers:

    redact   secrets (auth, cookies, tokens) -> visible REDACTED marker
    strip    volatile values (Date, ETag, request-ids) -> removed entirely, so
             a replay regenerates a fresh value instead of serving a stale one

  Pure: the caller supplies the redact/volatile sets (base ∪ service template),
  so this module has no policy of its own. Imposters keep string keys to
  round-trip Mountebank's shape exactly."
  (:require [clojure.string :as str]))

(defn- clean-headers [headers redact-set volatile-set]
  (let [in? (fn [s h] (contains? s (str/lower-case h)))]
    (reduce-kv
     (fn [acc k v]
       (cond
         (in? redact-set k)   (-> acc (assoc-in [:headers k] "REDACTED")
                                  (update :redacted conj (str/lower-case k)))
         (in? volatile-set k) (update acc :stripped conj (str/lower-case k)) ; drop it
         :else                (assoc-in acc [:headers k] v)))
     {:headers {} :redacted #{} :stripped #{}}
     headers)))

(defn- scrub-response [resp acc redact-set volatile-set]
  (if-let [h (get-in resp ["is" "headers"])]
    (let [{:keys [headers redacted stripped]} (clean-headers h redact-set volatile-set)]
      [(assoc-in resp ["is" "headers"] headers)
       (-> acc (update :redacted into redacted) (update :stripped into stripped))])
    [resp acc]))

(defn scrub-imposter
  "Redact secrets and strip volatile headers across every stub response, using
   the supplied sets. Returns {:imposter :stub-count :redacted :stripped}.
   Idempotent — safe to re-run on an already-frozen imposter (that's `doctor --fix`)."
  [imposter redact-set volatile-set]
  (let [[new-stubs acc]
        (reduce
         (fn [[stubs acc] stub]
           (let [[new-resps acc']
                 (reduce (fn [[rs a] r]
                           (let [[nr a'] (scrub-response r a redact-set volatile-set)]
                             [(conj rs nr) a']))
                         [[] acc] (get stub "responses"))]
             [(conj stubs (assoc stub "responses" new-resps)) acc']))
         [[] {:redacted #{} :stripped #{}}]
         (get imposter "stubs"))]
    {:imposter   (assoc imposter "stubs" new-stubs)
     :stub-count (count new-stubs)
     :redacted   (:redacted acc)
     :stripped   (:stripped acc)}))

(defn scrub-contract
  "The same cleaning applied to a contract's response and example headers (the
   contract is canonical, so `doctor --fix` must fix it, not the compiled
   imposter). Returns {:contract :redacted :stripped}."
  [contract redact-set volatile-set]
  (let [acc   (atom {:redacted #{} :stripped #{}})
        clean (fn [owner]
                (if-let [h (get owner "headers")]
                  (let [{:keys [headers redacted stripped]} (clean-headers h redact-set volatile-set)]
                    (swap! acc #(-> % (update :redacted into redacted) (update :stripped into stripped)))
                    (assoc owner "headers" headers))
                  owner))
        c'    (update contract "operations"
                      (fn [ops]
                        (mapv (fn [op]
                                (update op "responses"
                                        (fn [rs]
                                          (mapv (fn [r]
                                                  (cond-> (clean r)
                                                    (get r "examples")
                                                    (update "examples" update-vals clean)))
                                                rs))))
                              ops)))]
    (assoc @acc :contract c')))
