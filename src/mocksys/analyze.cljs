(ns mocksys.analyze
  "Definitions + pure analysis over a frozen imposter — the substrate for
  `inspect` (semantic summary) and `doctor` (matcher hygiene / overfit).

  Owns the *base* secret/volatile header sets. Service templates extend these;
  see mocksys.service, which composes base ∪ template into an effective profile."
  (:require [clojure.string :as str]))

;; Recorded response headers whose values drift on every real call (frozen
;; verbatim during record -> a replay would serve a stale value).
(def base-volatile-headers
  #{"date" "age" "expires" "last-modified" "etag"
    "x-request-id" "x-amzn-requestid" "x-github-request-id"
    "x-runtime" "cf-ray" "server-timing"})

;; Response headers carrying secrets -> redacted to a visible marker.
(def base-redact-headers
  #{"set-cookie" "authorization" "proxy-authorization"
    "x-api-key" "x-github-token" "x-amz-security-token"})

;; The only request fields we intend to match on. Anything else in a predicate
;; is overfitting — the fixture fires on fewer real requests than it should.
(def intended-match-fields #{"method" "path"})

(defn- find-field [stub field]
  (some #(some (fn [op-map] (get op-map field)) (vals %))
        (get stub "predicates")))

(defn- pred-fields [stub]
  (->> (get stub "predicates")
       (mapcat (fn [pred] (mapcat keys (vals pred)))) ; {op {field val}} -> fields
       set))

(defn stub-summary
  "One stub -> summary map. `vol-set` decides which response headers count as
   volatile (defaults to the base set; a service profile may widen it)."
  ([stub] (stub-summary stub base-volatile-headers))
  ([stub vol-set]
   (let [resp0   (get-in stub ["responses" 0])
         inject? (contains? resp0 "inject")
         resp-is (get resp0 "is")
         headers (get resp-is "headers")
         vol     (->> (keys headers)
                      (filter #(contains? vol-set (str/lower-case %)))
                      vec)]
     {:method           (find-field stub "method")
      :path             (find-field stub "path")
      :status           (if inject? "stateful" (get resp-is "statusCode"))
      :stateful         inject?
      :headers          headers
      :match-fields     (pred-fields stub)
      :volatile-headers vol})))

(defn summarize
  ([imposter] (summarize imposter base-volatile-headers))
  ([imposter vol-set]
   (mapv #(stub-summary % vol-set) (get imposter "stubs"))))

(defn overfit
  "Match fields on a stub beyond the intended set (method+path)."
  [{:keys [match-fields]}]
  (vec (remove intended-match-fields match-fields)))
