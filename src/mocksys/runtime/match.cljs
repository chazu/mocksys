(ns mocksys.runtime.match
  "Stub predicates, evaluated the way Mountebank evaluates the subset mocksys emits.

  A request is a map with string keys: method, path, query ({k v-or-[v]}), headers
  and body (a string). A predicate is one operator over a map of expected fields:

    equals      every expected field equals the request's (scalar = any array element)
    deepEquals  every expected field equals the request's exactly
    matches     every expected field is a regex that matches the request's
    exists      every expected field is present and non-empty (true) or not (false)

  An optional `jsonpath` {selector} replaces the request body with the values the
  selector picks from the parsed JSON body. Matching ignores case: keys always, and
  values too except under `matches`, whose regex runs with the i flag instead."
  (:require [clojure.string :as str]))

(def operators #{"equals" "deepEquals" "matches" "exists"})

;; --- jsonpath (plain paths only) -------------------------------------------

(def plain-selector
  #"^\$(?:\.[A-Za-z0-9_-]+|\.\*|\[\d+\]|\[\*\]|\['[A-Za-z0-9_ .-]+'\])*$")

(defn- steps [selector]
  (for [[_ k n q] (re-seq #"\.([A-Za-z0-9_-]+)|\[(\d+)\]|\['([^']+)'\]|\.\*|\[\*\]" selector)]
    (cond k [:key k] n [:index (js/parseInt n)] q [:key q] :else [:all])))

(defn- step [node [kind arg]]
  (case kind
    :all   (cond (map? node) (vec (vals node)) (vector? node) node :else [])
    :index (cond (vector? node) (if (< arg (count node)) [(nth node arg)] [])
                 (map? node)    (if (contains? node (str arg)) [(get node (str arg))] [])
                 :else [])
    :key   (cond (map? node) (if (contains? node arg) [(get node arg)] [])
                 ;; a JS array has its indices as properties, so $.a.0 reads element 0
                 (and (vector? node) (re-matches #"\d+" arg)) (step node [:index (js/parseInt arg)])
                 :else [])))

(defn select
  "Every value `selector` picks from `doc`, in document order."
  [selector doc]
  (reduce (fn [nodes s] (vec (mapcat #(step % s) nodes))) [doc] (steps selector)))

;; --- normalization ---------------------------------------------------------

(defn- transform [x key-f val-f]
  (cond
    (map? x)    (into {} (map (fn [[k v]] [(key-f k) (transform v key-f val-f)])) x)
    (vector? x) (mapv #(transform % key-f val-f) x)
    (string? x) (val-f x)
    :else x))

(defn- lower-all [x] (transform x str/lower-case str/lower-case))
(defn- lower-keys [x] (transform x str/lower-case identity))

(defn- force-strings [x]
  (cond
    (nil? x)    "null"
    (map? x)    (update-vals x force-strings)
    (vector? x) (mapv force-strings x)
    :else       (str x)))

(defn- select-body
  "The request body replaced by what `selector` picks: nil for nothing, the value
   for one match, a vector for several. A body that isn't JSON selects nothing."
  [body selector normalize]
  (let [doc (try (js->clj (js/JSON.parse body)) (catch :default _ ::none))]
    (when-not (= ::none doc)
      (let [hits (select (str/lower-case selector) (normalize doc))]
        (case (count hits) 0 nil 1 (first hits) hits)))))

(defn- actual
  "The request as a predicate sees it: normalized, and with the body selected when
   the predicate carries a jsonpath."
  [request selector normalize]
  (cond-> (normalize request)
    selector (assoc "body" (select-body (get request "body") selector normalize))))

;; --- evaluation ------------------------------------------------------------

(defn- satisfied?
  "Every field of `expected` holds against `actual` under `test`, recursing into
   nested maps. A scalar expectation against an array holds if any element does."
  [expected actual test exists?]
  (and (map? actual)
       (every? (fn [[field e]]
                 (let [a (get actual field)]
                   (cond
                     (map? e)    (satisfied? e a test exists?)
                     (vector? a) (or (and exists? (true? e))
                                     (boolean (some #(test e %) a)))
                     :else       (test e (if (nil? a) "" a)))))
               expected)))

(defn- equals-test [e a] (= (str e) (str a)))
(defn- matches-test [e a] (.test (js/RegExp. e "i") (str a)))
(defn- exists-test [e a] (if e (and (some? a) (not= "" a)) (or (nil? a) (= "" a))))

(defn matches?
  "Whether one predicate holds for `request`."
  [predicate request]
  (let [op       (some operators (keys predicate))
        expected (get predicate op)
        selector (get-in predicate ["jsonpath" "selector"])]
    (case op
      "deepEquals" (let [e (lower-all (force-strings expected))
                         a (lower-all (force-strings (actual request selector lower-all)))]
                     (every? (fn [[field v]] (= v (get a field))) e))
      "equals"     (satisfied? (lower-all expected) (actual request selector lower-all) equals-test false)
      "exists"     (satisfied? (lower-all expected) (actual request selector lower-all) exists-test true)
      "matches"    (satisfied? (lower-keys expected) (actual request selector lower-keys) matches-test false))))

(defn all?
  "Whether every predicate holds (a stub with none matches any request)."
  [predicates request]
  (every? #(matches? % request) predicates))
