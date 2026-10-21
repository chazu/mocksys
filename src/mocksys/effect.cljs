(ns mocksys.effect
  "The effect language: what an operation's `effect` map may hold, and how the daemon
  runs one against a request. The CLI checks effects when it lowers a contract; the
  daemon checks them again when an imposter is posted, then evaluates them directly.

  An effect runs over its imposter's `state`, one mutable JS object shared by every
  stub and handed to custom verbs as `ctx.state`. It, its buckets, the collection maps
  and the variables have no prototype, since their keys come from data. Its layout:
    <bucket>       a handshake bucket (store/consume/lookup) or a limit's counters
    __c / __cf     each collection's rows by id, and its id field
    __now          the virtual clock, in epoch seconds
    __rng          the id stream (xorshift32)
  Variables live in a second JS object for the length of one request. Rows, bound
  values and verb results are JS values throughout, so key order and JSON output are
  what a JavaScript reader expects.

  A request runs the verbs in the order of `verbs` below, whatever order the effect
  writes them in: bind, limit, consume, lookup, resolve, find, call, mint, sign, then
  one terminal. A read that misses ends the request with that verb's `miss`.

  An imposter's `state` settings, from the contract:
    seed         {name [item ...]}               resolve reads these
    collections  {name {idField seed [row ...]}} seeded once into state
    clock        epoch seconds the clock starts at, when the contract uses one
    randomSeed   the id stream's starting state
    issuer       baseUrl when a request has no Host header"
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            ["node:crypto" :as crypto]
            [mocksys.scim :as scim]))

(defn stateful?
  "True if an operation answers from an effect rather than a static response."
  [op]
  (some? (get op "effect")))

(defn contract-stateful?
  "True if any operation in the contract is stateful."
  [contract]
  (boolean (some stateful? (get contract "operations"))))

(defn- many
  "`find`, `sign` and `call` take one map or a list of maps."
  [x]
  (cond (nil? x) nil (map? x) [x] :else x))

(defn calls-of
  "The `call` specs of an effect, in the order they run."
  [effect]
  (many (get effect "call")))

;; --- values ------------------------------------------------------------------

(defn- js-or
  "JavaScript's `a || b`. A ClojureScript `or` keeps 0, \"\" and NaN, which the
   defaults below must not."
  [a b]
  (if (js/Boolean a) a b))

(defn- dict
  "An empty JS object with no prototype, for a map keyed by names from an effect, the
   settings or a request: `__proto__` or `toString` is then only a key, and no key
   reaches Object.prototype, which every imposter in the daemon shares."
  []
  (js/Object.create nil))

(defn new-state
  "A new imposter's `state`."
  []
  (dict))

(defn- js-keys-in
  "The keys a JavaScript `for (k in o)` visits."
  [o]
  (gobj/getKeys o))

(def ^:private var-ref (js/RegExp. "\\{([\\w.]+)\\}" "g"))

(defn- interp
  "`t` as a string with each `{name}` or dotted `{a.b}` replaced by that variable's
   value; a missing one becomes empty."
  [t vars]
  (.replace (js/String t) var-ref (fn [_ k] (let [x (scim/dget vars k)] (if (nil? x) "" x)))))

(defn- deep-interp
  "A template from the effect as a fresh JS value, every string in it interpolated."
  [x vars]
  (cond
    (string? x)     (interp x vars)
    (map? x)        (let [o (js-obj)] (doseq [[k v] x] (unchecked-set o k (deep-interp v vars))) o)
    (sequential? x) (into-array (map #(deep-interp % vars) x))
    :else           (clj->js x)))

;; --- the request -----------------------------------------------------------------

(defn- query-value [^js req k]
  (js-or (some-> (unchecked-get req "query") (unchecked-get k)) ""))

(defn- header-value
  "Header `k` (lower case) by any case, as the client sent it."
  [^js req k]
  (let [hs (js-or (unchecked-get req "headers") (js-obj))]
    (if-let [n (first (filter #(= k (.toLowerCase %)) (js-keys-in hs)))]
      (unchecked-get hs n)
      "")))

(defn- bearer [h]
  (let [h (js/String (js-or h ""))]
    (cond (re-find #"(?i)^bearer " h) (.slice h 7)
          (re-find #"(?i)^token " h)  (.slice h 6)
          :else h)))

(defn- object-like? [x] (contains? #{"object" "array"} (goog/typeOf x)))

(defn- form-field [b k]
  (loop [[p & ps] (seq (.split (js/String b) "&"))]
    (if (nil? p)
      ""
      (let [kv (.split p "=")]
        (if (= k (js/decodeURIComponent (aget kv 0)))
          (js/decodeURIComponent (str/replace (js-or (aget kv 1) "") "+" " "))
          (recur ps))))))

(defn- body-value
  "Field `k` of the request body: a JSON object's field, else a form field."
  [^js req k]
  (let [b (unchecked-get req "body")]
    (cond
      (nil? b)          ""
      (object-like? b)  (let [v (unchecked-get b k)] (if (nil? v) "" v))
      :else
      (let [j (try (js/JSON.parse b) (catch :default _ nil))
            v (when (js/Boolean j) (unchecked-get j k))]
        (if (some? v) v (form-field b k))))))

(defn- body-json [^js req]
  (let [b (unchecked-get req "body")]
    (cond
      (nil? b)         (js-obj)
      (object-like? b) b
      :else            (try (js-or (js/JSON.parse b) (js-obj)) (catch :default _ (js-obj))))))

;; --- one request's context -------------------------------------------------------

(defn- now [^js state] (js-or (unchecked-get state "__now") 0))

(defn- base-url
  "The twin's own origin: http://{Host}, else the configured issuer."
  [{:keys [req settings]}]
  (let [h (header-value req "host")]
    (if (js/Boolean h) (str "http://" h) (or (get settings "issuer") ""))))

(defn- now-value
  "A `now` directive's value: epoch seconds plus `add`, or with `iso` an ISO string."
  [{:keys [state]} n]
  (let [t (+ (now state) (if (map? n) (or (get n "add") 0) 0))]
    (if (and (map? n) (get n "iso")) (.toISOString (js/Date. (* t 1000))) t)))

(defn- rng!
  "The next number of the imposter's xorshift32 id stream."
  [^js state]
  (let [x (unsigned-bit-shift-right (unchecked-get state "__rng") 0)
        x (if (zero? x) 1 x)
        x (unsigned-bit-shift-right (bit-xor x (bit-shift-left x 13)) 0)
        x (bit-xor x (unsigned-bit-shift-right x 17))
        x (unsigned-bit-shift-right (bit-xor x (bit-shift-left x 5)) 0)]
    (unchecked-set state "__rng" x)
    x))

(defn- mint-id [state prefix]
  (apply str (js-or prefix "") (repeatedly 24 #(aget "0123456789abcdef" (unsigned-bit-shift-right (rng! state) 28)))))

(defn- with-rate-headers
  "`h` with the budget headers a passing `limit` left, where `h` has none of its own."
  [{:keys [vars]} ^js h]
  (when-let [rl (unchecked-get vars "__rl")]
    (doseq [k (js-keys-in rl) :when (undefined? (unchecked-get h k))]
      (unchecked-set h k (unchecked-get rl k))))
  h)

(defn- response
  "The response a `respond`, `miss` or `invalid` spec describes."
  [{:keys [vars] :as ctx} spec]
  (let [json?   (or (contains? spec "json") (contains? spec "jsonVar"))
        headers (cond-> (or (get spec "headers") {})
                  (and json? (not (get-in spec ["headers" "Content-Type"])))
                  (assoc "Content-Type" "application/json"))]
    (js-obj "statusCode" (or (get spec "status") 200)
            "headers"    (with-rate-headers ctx (deep-interp headers vars))
            "body"       (cond
                           (contains? spec "jsonVar") (js/JSON.stringify (js-or (unchecked-get vars (get spec "jsonVar")) nil))
                           (contains? spec "json")    (js/JSON.stringify (deep-interp (get spec "json") vars))
                           (contains? spec "text")    (interp (get spec "text") vars)
                           :else                      ""))))

(defn- miss [ctx spec] (response ctx (or (get spec "miss") {"status" 404})))

(def ^:private default-invalid-filter
  {"status" 400
   "json"   {"schemas"  ["urn:ietf:params:scim:api:messages:2.0:Error"]
             "scimType" "invalidFilter"
             "detail"   "{filterError}"
             "status"   "400"}})

(defn- guard-filter
  "Run `f`; a malformed SCIM filter inside it answers with `spec`'s `invalid`
   response instead, the parser's message bound as {filterError}."
  [{:keys [vars] :as ctx} spec f]
  (try (f)
       (catch :default e
         (if (scim/invalid? e)
           (do (unchecked-set vars "filterError" (ex-message e))
               (response ctx (or (get spec "invalid") default-invalid-filter)))
           (throw e)))))

;; --- collections ------------------------------------------------------------------

(defn- coll
  "Collection `name` as {:m rows-by-id :f id-field}."
  [^js state name]
  {:m (js-or (unchecked-get (unchecked-get state "__c") name) (dict))
   :f (js-or (unchecked-get (unchecked-get state "__cf") name) "id")})

(defn- coll-get [{:keys [m]} id] (unchecked-get m (js/String id)))
(defn- coll-put! [{:keys [m f]} o] (unchecked-set m (js/String (unchecked-get o f)) o) o)
(defn- coll-del! [{:keys [m]} id] (js-delete m (js/String id)))
(defn- coll-rows [{:keys [m]}] (into-array (map #(unchecked-get m %) (js-keys-in m))))

(defn- page-by
  "`arr` sorted by id field `f`, the page after the row whose id is `after`, at most
   `limit` long (default 200), as {:items :next} where `next` is the page's last id
   when more rows follow."
  [^js arr f after limit]
  (.sort arr (fn [a b] (compare (js/String (unchecked-get a f)) (js/String (unchecked-get b f)))))
  (let [lim   (if (> limit 0) limit 200)
        start (if (js/Boolean after)
                (or (some (fn [i] (when (= (js/String (unchecked-get (aget arr i) f)) (js/String after)) (inc i)))
                          (range (.-length arr)))
                    0)
                0)
        page  (.slice arr start (+ start lim))]
    {:items page
     :next  (if (and (< (+ start lim) (.-length arr)) (pos? (.-length page)))
              (js/String (unchecked-get (aget page (dec (.-length page))) f))
              "")}))

(defn- coll-list [c {:keys [filter after limit]}]
  (page-by (cond-> (coll-rows c) (js/Boolean filter) (.filter (scim/predicate filter)))
           (:f c) after limit))

(defn- coll-response [ctx status obj headers]
  (let [h (with-rate-headers ctx (js-or headers (js-obj)))]
    (unchecked-set h "Content-Type" (js-or (unchecked-get h "Content-Type") "application/json"))
    (js-obj "statusCode" status "headers" h "body" (if (nil? obj) "" (js/JSON.stringify obj)))))

(defn- link-header
  "`spec`'s Link header template with {next} bound, when a next page exists."
  [{:keys [vars]} spec {:keys [next]}]
  (let [h (js-obj)]
    (when (and (get spec "link") (js/Boolean next))
      (unchecked-set h "Link" (interp (get spec "link") (js/Object.assign (js-obj) vars (js-obj "next" next)))))
    h))

(defn- interp-or [spec k default vars]
  (if (get spec k) (interp (get spec k) vars) default))

(defn- page-limit [spec vars]
  (if (get spec "limit") (js/parseInt (interp (get spec "limit") vars) 10) 0))

;; --- custom verbs --------------------------------------------------------------------

(deftype ^:private Respond [spec])

(defn- public-vars
  "A copy of the variables without the __-prefixed ones mocksys keeps (__rl, a kit's
   __cli)."
  [vars]
  (let [o (js-obj)]
    (doseq [k (js-keys-in vars) :when (not= "__" (.slice k 0 2))]
      (unchecked-set o k (unchecked-get vars k)))
    o))

(defn- function? [x] (identical? "function" (goog/typeOf x)))

(defn- call-verb
  "Run custom verb `n` synchronously, so one request's read-modify-write of `state`
   cannot interleave with another's."
  [{:keys [req state vars verbs]} n args]
  (let [f (when verbs (unchecked-get verbs n))]
    (when-not (function? f)
      (throw (js/Error. (str "verb '" n "' is not exported by the scenario's modules"))))
    (let [ctx (js-obj "request" req "state" state "vars" (public-vars vars) "args" args "now" (now state)
                      "respond" (fn [s] (Respond. s)))
          r   (try (.call f js/undefined ctx)
                   (catch :default e
                     (throw (js/Error. (str "verb '" n "' threw: "
                                            (js/String (if (and (js/Boolean e) (not (undefined? (unchecked-get e "message"))))
                                                         (unchecked-get e "message")
                                                         e)))
                                       (js-obj "cause" e)))))]
      (when (and (js/Boolean r) (function? (unchecked-get r "then")))
        ;; a rejected promise nobody awaits would crash the daemon
        (when (function? (unchecked-get r "catch")) (.call (unchecked-get r "catch") r (fn [])))
        (throw (js/Error. (str "verb '" n "' returned a Promise; verbs must be synchronous"))))
      r)))

(def ^:private respond-keys #{"status" "headers" "json" "text" "body"})

(defn- verb-response
  "The response a verb's ctx.respond(r) describes, or an error naming the verb."
  [ctx n ^js r]
  (let [bad! #(throw (js/Error. (str "verb '" n "': ctx.respond " %)))]
    (when-not (identical? "object" (goog/typeOf r))
      (bad! "takes an object {status, headers, json | text | body}"))
    (let [ks     (vec (js/Object.keys r))
          status (unchecked-get r "status")
          hs     (unchecked-get r "headers")]
      (when-let [extra (seq (remove respond-keys ks))]
        (bad! (str "got unknown key(s) " (str/join ", " (sort extra)) " (use status, headers, json, text or body)")))
      (let [kinds (filter #{"json" "text" "body"} ks)
            _     (when (> (count kinds) 1) (bad! "takes one of json, text or body"))
            kind  (first kinds)
            v     (when kind (unchecked-get r kind))]
        (when (and (= "json" kind) (undefined? v)) (bad! "json is undefined"))
        (when (and (not (undefined? status)) (not (and (js/Number.isInteger status) (<= 100 status 599))))
          (bad! "status must be an HTTP status code"))
        (when (and (not (undefined? hs)) (not (identical? "object" (goog/typeOf hs))))
          (bad! "headers must be an object"))
        (when (and (= "text" kind) (not (string? v))) (bad! "text must be a string"))
        (let [body (cond
                     (or (= "text" kind) (and (= "body" kind) (string? v))) v
                     (and kind (some? v))
                     (let [s (try (js/JSON.stringify v)
                                  (catch :default e
                                    (bad! (str kind " does not serialize: " (js/String (unchecked-get e "message"))))))]
                       (when (undefined? s) (bad! (str kind " does not serialize: it is a " (goog/typeOf v))))
                       s)
                     :else "")
              h    (js/Object.assign (js-obj) hs)]
          (when (and (= "json" kind) (not (some #(= "content-type" (.toLowerCase %)) (js-keys-in h))))
            (unchecked-set h "Content-Type" "application/json"))
          (js-obj "statusCode" (js-or status 200) "headers" (with-rate-headers ctx h) "body" body))))))

;; --- signing ----------------------------------------------------------------------------

(defn- b64url [x]
  (-> (.toString (if (js/Buffer.isBuffer x) x (js/Buffer.from (js/String x))) "base64")
      (str/replace "+" "-") (str/replace "/" "_") (str/replace #"=+$" "")))

(defn- sign-jwt [header claims pem]
  (let [enc (str (b64url (js/JSON.stringify header)) "." (b64url (js/JSON.stringify claims)))
        ^js signer (crypto/createSign "RSA-SHA256")]
    (str enc "." (b64url (.sign (.update signer enc) pem)))))

(defn- claim-value
  "One typed claim: a string interpolates; {now}, {split on}, {baseUrl} and {var}
   are directives; any other value is itself."
  [{:keys [vars] :as ctx} v]
  (cond
    (string? v) (interp v vars)
    (and (map? v) (contains? v "now"))     (now-value ctx (get v "now"))
    (and (map? v) (contains? v "split"))   (.filter (.split (js/String (interp (get v "split") vars)) (get v "on" " ")) js/Boolean)
    (and (map? v) (contains? v "baseUrl")) (base-url ctx)
    (and (map? v) (contains? v "var"))     (unchecked-get vars (get v "var"))
    :else (clj->js v)))

;; --- the verbs, in the order a request runs them --------------------------------------
;; Each `run` takes the request's context and the verb's spec, and returns a response
;; to end the request with, or nil to go on.

(defn- bind-source [{:keys [req] :as ctx} spec]
  (cond
    (contains? spec "const")     (clj->js (get spec "const"))
    (contains? spec "query")     (query-value req (get spec "query"))
    (contains? spec "body")      (body-value req (get spec "body"))
    (contains? spec "bodyJson")  (body-json req)
    (contains? spec "pathRegex") (js-or (aget (js-or (.match (js/String (unchecked-get req "path")) (js/RegExp. (get spec "pathRegex"))) #js []) 1) "")
    (contains? spec "header")    (cond-> (header-value req (str/lower-case (get spec "header")))
                                   (get spec "bearer") bearer)
    (contains? spec "baseUrl")   (base-url ctx)
    (contains? spec "now")       (now-value ctx (get spec "now"))
    :else ""))

(defn- run-bind [{:keys [vars] :as ctx} binds]
  (doseq [[n spec] binds] (unchecked-set vars n (bind-source ctx spec))))

(defn- run-limit
  "A per-key request counter in a window of the virtual clock. Over budget answers
   `miss` (default 429) with Remaining 0; otherwise the budget headers ride on
   whatever the request answers."
  [{:keys [state vars] :as ctx} l]
  (let [b      (get l "bucket")
        mx     (or (get l "max") 60)
        window (or (get l "window") 60)
        _      (unchecked-set state b (js-or (unchecked-get state b) (dict)))
        win    (* (js/Math.floor (/ (now state) window)) window)
        k      (str (interp (get l "key") vars) "|" win)
        n      (inc (js-or (unchecked-get (unchecked-get state b) k) 0))]
    (unchecked-set (unchecked-get state b) k n)
    (if (> n mx)
      (let [m  (response ctx (or (get l "miss")
                                 {"status" 429
                                  "json" {"errorCode" "E0000047"
                                          "errorSummary" "API call exceeded rate limit due to too many requests."}}))
            hs (unchecked-get m "headers")]
        (unchecked-set hs "X-Rate-Limit-Limit" (js/String mx))
        (unchecked-set hs "X-Rate-Limit-Remaining" "0")
        (unchecked-set hs "X-Rate-Limit-Reset" (js/String (+ win window)))
        m)
      (do (unchecked-set vars "__rl" (js-obj "X-Rate-Limit-Limit" (js/String mx)
                                             "X-Rate-Limit-Remaining" (js/String (max 0 (- mx n)))
                                             "X-Rate-Limit-Reset" (js/String (+ win window))))
          nil))))

(defn- read-bucket
  "Bucket entry `k`, undefined when absent or expired; `del?` removes it."
  [^js state bucket k del?]
  (let [b (js-or (unchecked-get state bucket) (dict))
        h (unchecked-get b k)]
    (cond
      (undefined? h) js/undefined
      (and (js/Boolean h) (identical? "object" (goog/typeOf h)) (js/Boolean (unchecked-get h "__ttl")))
      (if (> (now state) (unchecked-get h "exp"))
        (do (js-delete b k) js/undefined)
        (do (when del? (js-delete b k)) (unchecked-get h "v")))
      :else (do (when del? (js-delete b k)) h))))

(defn- bucket-reader [del?]
  (fn [{:keys [state vars] :as ctx} l]
    (let [h (read-bucket state (get l "bucket") (interp (get l "key") vars) del?)]
      (if (undefined? h)
        (miss ctx l)
        (do (unchecked-set vars (get l "as") h) nil)))))

(defn- run-resolve [{:keys [vars settings] :as ctx} r]
  (let [eq (interp (get r "eq") vars)
        by (get r "by")
        m  (aget (.filter (js-or (clj->js (get-in settings ["seed" (get r "seed")])) #js [])
                          (fn [x] (identical? (js/String (unchecked-get x by)) eq)))
                 0)]
    (if (js/Boolean m)
      (do (unchecked-set vars (get r "as") m) nil)
      (miss ctx r))))

(defn- find-one
  "A row into `as` (or its dotted `pick`): by id with `key`, or the first whose dotted
   `field` equals `eq`. With `filter` the row must also satisfy it. A miss answers."
  [{:keys [state vars] :as ctx} f]
  (let [c   (coll state (get f "collection"))
        row (if (contains? f "field")
              (let [eq (interp (get f "eq") vars)
                    k  (some #(when (identical? (js/String (scim/dget (unchecked-get (:m c) %) (get f "field"))) eq) %)
                             (js-keys-in (:m c)))]
                (if k (unchecked-get (:m c) k) js/undefined))
              (coll-get c (interp (get f "key") vars)))
        row (if (and (not (undefined? row)) (get f "filter")
                     (not ((scim/predicate (interp (get f "filter") vars)) row)))
              js/undefined
              row)]
    (if (undefined? row)
      (miss ctx f)
      (do (unchecked-set vars (get f "as") (if-let [p (get f "pick")] (scim/dget row p) row)) nil))))

(defn- run-find [ctx finds]
  (some (fn [f] (if (get f "filter") (guard-filter ctx f #(find-one ctx f)) (find-one ctx f))) (many finds)))

(defn- run-call [{:keys [vars] :as ctx} calls]
  (some (fn [c]
          (let [n (get c "verb")
                r (call-verb ctx n (deep-interp (get c "args" {}) vars))]
            (if (instance? Respond r)
              (verb-response ctx n (.-spec r))
              (do (when-let [as (get c "as")] (unchecked-set vars as r)) nil))))
        (many calls)))

(defn- run-mint [{:keys [state vars]} mints]
  (doseq [[n spec] mints] (unchecked-set vars n (mint-id state (or (get spec "prefix") "")))))

(defn- run-sign [{:keys [vars] :as ctx} signs]
  (doseq [s (many signs)]
    (let [header (deep-interp (get s "header" {}) vars)
          claims (let [o (js-obj)] (doseq [[k v] (get s "claims")] (unchecked-set o k (claim-value ctx v))) o)]
      (unchecked-set vars (get s "as") (sign-jwt header claims (get s "key"))))))

;; --- terminals: each forms the request's response ----------------------------------------

(defn- run-create [{:keys [state vars] :as ctx} c]
  (let [o (apply js/Object.assign (js-obj)
                 (concat (map #(js-or (unchecked-get vars %) (js-obj)) (get c "merge" []))
                         [(deep-interp (get c "body" {}) vars)]))]
    (coll-put! (coll state (get c "collection")) o)
    (when-let [as (get c "as")] (unchecked-set vars as o))
    (coll-response ctx (or (get c "status") 201) o nil)))

(defn- run-get [{:keys [state vars] :as ctx} g]
  (let [r (coll-get (coll state (get g "collection")) (interp (get g "key") vars))]
    (if (undefined? r)
      (miss ctx g)
      (do (when-let [as (get g "as")] (unchecked-set vars as r))
          (coll-response ctx (or (get g "status") 200) r nil)))))

(defn- merge1
  "`b` over `a`, merging one level deep: an object field of both merges, any other
   field of `b` replaces `a`'s."
  [a b]
  (let [o (js-obj)]
    (doseq [k (js-keys-in a)] (unchecked-set o k (unchecked-get a k)))
    (doseq [k (js-keys-in b)
            :let [bv (unchecked-get b k) av (unchecked-get a k)]]
      (unchecked-set o k (if (and (js/Boolean bv) (identical? "object" (goog/typeOf bv))
                                  (js/Boolean av) (object-like? av))
                           (let [s (js-obj)]
                             (doseq [j (js-keys-in av)] (unchecked-set s j (unchecked-get av j)))
                             (doseq [j (js-keys-in bv)] (unchecked-set s j (unchecked-get bv j)))
                             s)
                           bv)))
    o))

(defn- run-update [{:keys [state vars] :as ctx} u]
  (let [c (coll state (get u "collection"))
        r (coll-get c (interp (get u "key") vars))]
    (if (undefined? r)
      (miss ctx u)
      (let [patch (cond (get u "mergeVar") (js-or (unchecked-get vars (get u "mergeVar")) (js-obj))
                        (get u "patch")    (deep-interp (get u "patch") vars)
                        :else              (js-obj))
            n     (merge1 r patch)]
        (coll-put! c n)
        (when-let [as (get u "as")] (unchecked-set vars as n))
        (coll-response ctx (or (get u "status") 200) n nil)))))

(defn- run-remove [{:keys [state vars] :as ctx} d]
  (let [c (coll state (get d "collection"))]
    (if (undefined? (coll-get c (interp (get d "key") vars)))
      (miss ctx d)
      (do (coll-del! c (interp (get d "key") vars))
          (coll-response ctx (or (get d "status") 204) nil nil)))))

(defn- run-list [{:keys [state vars] :as ctx} l]
  (guard-filter ctx l
    #(let [p (coll-list (coll state (get l "collection"))
                        {:filter (interp-or l "filter" "" vars)
                         :after  (interp-or l "after" "" vars)
                         :limit  (page-limit l vars)})]
       (coll-response ctx (or (get l "status") 200) (:items p) (link-header ctx l p)))))

(defn- run-link
  "Insert a membership row under its composite `id` unless it exists, so assigning
   twice is one row."
  [{:keys [state vars] :as ctx} l]
  (let [c  (coll state (get l "collection"))
        id (interp (get l "id") vars)]
    (when (undefined? (coll-get c id))
      (let [row (deep-interp (get l "row" {}) vars)]
        (unchecked-set row (:f c) id)
        (coll-put! c row)))
    (coll-response ctx (or (get l "status") 204) nil nil)))

(defn- run-unlink [{:keys [state vars] :as ctx} u]
  (coll-del! (coll state (get u "collection")) (interp (get u "id") vars))
  (coll-response ctx (or (get u "status") 204) nil nil))

(defn- run-list-join
  "The `via` rows whose `byField` is `byValue`, each replaced by its `hydrate` row
   (rows whose target is gone drop out), paged over the hydrated ids."
  [{:keys [state vars] :as ctx} j]
  (let [via  (coll state (get j "via"))
        hy   (coll state (get j "hydrate"))
        objs (into-array
              (keep (fn [k]
                      (let [r (unchecked-get (:m via) k)]
                        (when (identical? (js/String (scim/dget r (get j "byField"))) (interp (get j "byValue") vars))
                          (let [t (coll-get hy (scim/dget r (get j "hydrateVia")))]
                            (when-not (undefined? t) t)))))
                    (js-keys-in (:m via))))
        p    (page-by objs (:f hy) (interp-or j "after" "" vars) (page-limit j vars))]
    (coll-response ctx (or (get j "status") 200) (:items p) (link-header ctx j p))))

(defn- run-render
  "An HTML page: each row of the (filtered) collection through `row`, joined into
   `wrap` at {rows}. Row templates see the request's variables and the row's fields."
  [{:keys [state vars] :as ctx} r]
  (guard-filter ctx r
    #(let [p    (coll-list (coll state (get r "collection")) {:filter (interp-or r "filter" "" vars)})
           rows (apply str (map (fn [row] (interp (get r "row" "") (js/Object.assign (js-obj) vars row))) (:items p)))]
       (js-obj "statusCode" (or (get r "status") 200)
               "headers"    (with-rate-headers ctx (js-obj "Content-Type" "text/html; charset=utf-8"))
               "body"       (interp (get r "wrap" "{rows}") (js/Object.assign (js-obj) vars (js-obj "rows" rows)))))))

(defn- store! [{:keys [state vars]} s]
  (let [v (if (contains? s "valueVar")
            (unchecked-get vars (get s "valueVar"))
            (interp (or (get s "value") "") vars))]
    (unchecked-set (unchecked-get state (get s "bucket")) (interp (get s "key") vars)
                   (if-let [ttl (get s "ttl")]
                     (js-obj "__ttl" true "v" v "exp" (+ (now state) ttl))
                     v))))

;; --- validation ------------------------------------------------------------------------

(defn- finite? [v] (and (number? v) (js/isFinite v)))
(defn- status? [v] (and (integer? v) (<= 100 v 599)))
(defn- scalar? [v] (or (string? v) (number? v) (boolean? v)))

(def ^:private reserved-names
  "Names a state bucket, collection, id field or variable cannot take: JavaScript
   gives them meaning on every object."
  #{"__proto__" "constructor" "prototype"})

(def ^:private not-reserved "other than __proto__, constructor and prototype")

(defn- reserved-errors
  "Problems with the keys of `m` at `at` that name a `what`."
  [at what m]
  (for [k (sort (keys m)) :when (reserved-names k)]
    (str at "." k ": a " what " cannot be named " k " (use a name " not-reserved ")")))

(def ^:private kinds
  "field kind -> [test, what the error says it must be]"
  {:string   [string? "a string"]
   :template [scalar? "a string"]
   :name     [#(and (string? %) (not (reserved-names %))) (str "a string " not-reserved)]
   :number   [finite? "a number"]
   :status   [status? "an HTTP status from 100 to 599"]
   :object   [map? "an object"]
   :names    [#(and (sequential? %) (every? string? %)) "a list of variable names"]
   :boolean  [boolean? "true or false"]
   :any      [any? "a value"]})

(def ^:private response-fields
  {"status" :status "headers" :object "json" :any "jsonVar" :string "text" :template})

(defn- shape-errors
  "Problems with spec `m` at `at` against `fields` ({key kind-or-{nested fields}}),
   with `required` keys."
  [at m fields required]
  (if-not (map? m)
    [(str at " must be an object")]
    (concat
     (for [k (sort (remove (set (keys fields)) (keys m)))]
       (str at ": unknown field " k " (use " (str/join ", " (sort (keys fields))) ")"))
     (for [k required :when (nil? (get m k))]
       (str at "." k " is required"))
     (mapcat (fn [[k kind]]
               (let [v (get m k)]
                 (cond
                   (nil? v)   nil
                   (map? kind) (shape-errors (str at "." k) v kind #{})
                   :else (let [[ok? what] (kinds kind)]
                           (when-not (ok? v) [(str at "." k " must be " what ", got " (pr-str v))])))))
             (sort fields)))))

(defn- each-errors
  "Problems with a verb that takes one map or a list of them."
  [at x check]
  (cond
    (map? x)        (check at x)
    (sequential? x) (mapcat (fn [i m] (check (str at "[" i "]") m)) (range) x)
    :else           [(str at " must be an object or a list of objects")]))

(defn- now-errors [at n]
  (cond
    (true? n) nil
    (map? n)  (shape-errors at n {"add" :number "iso" :boolean} #{})
    :else     [(str at " must be true or {add, iso}")]))

(def ^:private bind-sources ["const" "query" "body" "bodyJson" "pathRegex" "header" "baseUrl" "now"])

(defn- bind-errors [at binds]
  (if-not (map? binds)
    [(str at " must be an object of variable -> source")]
    (concat
     (reserved-errors at "variable" binds)
     (mapcat
      (fn [[n spec]]
        (let [at (str at "." n)]
          (cond
            (not (map? spec))
            [(str at " must be an object")]
            (not-any? #(contains? spec %) bind-sources)
            [(str at " needs one of " (str/join ", " bind-sources))]
            :else
            (concat
             (shape-errors at spec {"const" :any "query" :string "body" :string "bodyJson" :any "pathRegex" :string
                                    "header" :string "bearer" :boolean "baseUrl" :any "now" :any} #{})
             (when-let [re (get spec "pathRegex")]
               (when (string? re)
                 (try (js/RegExp. re) nil (catch :default _ [(str at ".pathRegex is not a valid regex " (pr-str re))]))))
             (when (contains? spec "now") (now-errors (str at ".now") (get spec "now")))))))
      binds))))

(defn- mint-errors [at mints]
  (if-not (map? mints)
    [(str at " must be an object of variable -> {prefix}")]
    (concat (reserved-errors at "variable" mints)
            (mapcat (fn [[n spec]] (when (some? spec) (shape-errors (str at "." n) spec {"prefix" :template} #{}))) mints))))

(defn- claim-errors [at claims]
  (if-not (map? claims)
    [(str at " must be an object")]
    (mapcat (fn [[k v]]
              (when (and (map? v) (contains? v "now")) (now-errors (str at "." k ".now") (get v "now"))))
            claims)))

(defn- reserved-var?
  "A name that cannot hold a verb's value: a key every JS object inherits (assigning
   __proto__ swaps the prototype; a lookup of an unbound `constructor` finds Object's)
   or a __-prefixed name, which mocksys keeps for itself."
  [n]
  (or (str/starts-with? n "__")
      (= "prototype" n)
      (some #(= n %) (js/Object.getOwnPropertyNames (.-prototype js/Object)))))

(defn- call-errors [_ c]
  (let [verb (when (map? c) (get c "verb"))
        bad  #(vector (str "call: verb '" verb "': " %))]
    (cond
      (not (and (string? verb) (seq verb)))            ["call needs a `verb` name"]
      (seq (remove #{"verb" "args" "as"} (keys c)))    (bad (str "unknown key(s) " (str/join ", " (sort (remove #{"verb" "args" "as"} (keys c))))
                                                             " (use verb, args, as)"))
      (not ((some-fn nil? map?) (get c "args")))       (bad "`args` must be an object")
      (not ((some-fn nil? string?) (get c "as")))      (bad "`as` must be a variable name")
      (some-> (get c "as") reserved-var?)              (bad (str "`as` cannot be '" (get c "as") "', a name JavaScript objects or mocksys reserve")))))

(defn- fields-check
  "A verb's check from its fields and required keys; `more` adds verb-specific checks."
  ([fields required] (fields-check fields required (constantly nil)))
  ([fields required more]
   (fn [at m] (let [es (shape-errors at m fields required)] (if (seq es) es (more at m))))))

(def ^:private keyed {"collection" :name "key" :template "as" :name "status" :status "miss" response-fields})
(def ^:private paged {"after" :template "limit" :template "link" :template})

(def verbs
  "Every effect verb in the order a request runs it. `:check` returns the problems
   with a spec as messages, and `:collections` names the fields that name a
   collection. The terminal verbs come last, collection verbs first: an effect has
   exactly one, or `store` then `respond`."
  [{:verb "bind"    :run run-bind    :check bind-errors}
   {:verb "limit"   :run run-limit
    :check (fields-check {"bucket" :name "key" :template "max" :number "window" :number "miss" response-fields} #{"bucket"})}
   {:verb "consume" :run (bucket-reader true)
    :check (fields-check {"bucket" :name "key" :template "as" :name "miss" response-fields} #{"bucket" "key"})}
   {:verb "lookup"  :run (bucket-reader false)
    :check (fields-check {"bucket" :name "key" :template "as" :name "miss" response-fields} #{"bucket" "key"})}
   {:verb "resolve" :run run-resolve
    :check (fields-check {"seed" :string "by" :string "eq" :template "as" :name "miss" response-fields} #{"seed" "by"})}
   {:verb "find"    :run run-find    :collections ["collection"]
    :check (fn [at x]
             (each-errors at x (fields-check (assoc (dissoc keyed "status") "field" :string "eq" :template "pick" :string
                                                    "filter" :template "invalid" response-fields)
                                             #{"collection"}
                                             (fn [at f] (when-not (or (contains? f "key") (contains? f "field"))
                                                          [(str at " needs a key or a field")])))))}
   {:verb "call"    :run run-call    :check (fn [at x] (each-errors at x call-errors))}
   {:verb "mint"    :run run-mint    :check mint-errors}
   {:verb "sign"    :run run-sign
    :check (fn [at x] (each-errors at x (fields-check {"as" :name "key" :string "header" :object "claims" :any}
                                                      #{"as" "key"}
                                                      (fn [at s] (claim-errors (str at ".claims") (get s "claims" {}))))))}
   {:verb "create"   :terminal :collection :collections ["collection"] :run run-create
    :check (fields-check {"collection" :name "merge" :names "body" :object "as" :name "status" :status} #{"collection"})}
   {:verb "get"      :terminal :collection :collections ["collection"] :run run-get    :check (fields-check keyed #{"collection" "key"})}
   {:verb "update"   :terminal :collection :collections ["collection"] :run run-update
    :check (fields-check (assoc keyed "mergeVar" :string "patch" :object) #{"collection" "key"})}
   {:verb "remove"   :terminal :collection :collections ["collection"] :run run-remove :check (fields-check (dissoc keyed "as") #{"collection" "key"})}
   {:verb "list"     :terminal :collection :collections ["collection"] :run run-list
    :check (fields-check (merge paged {"collection" :name "filter" :template "status" :status "invalid" response-fields})
                         #{"collection"})}
   {:verb "link"     :terminal :collection :collections ["collection"] :run run-link
    :check (fields-check {"collection" :name "id" :template "row" :object "status" :status} #{"collection" "id"})}
   {:verb "unlink"   :terminal :collection :collections ["collection"] :run run-unlink
    :check (fields-check {"collection" :name "id" :template "status" :status} #{"collection" "id"})}
   {:verb "listJoin" :terminal :collection :collections ["via" "hydrate"] :run run-list-join
    :check (fields-check (merge paged {"via" :name "byField" :string "byValue" :template "hydrate" :name
                                       "hydrateVia" :string "status" :status})
                         #{"via" "byField" "byValue" "hydrate" "hydrateVia"})}
   {:verb "render"   :terminal :collection :collections ["collection"] :run run-render
    :check (fields-check {"collection" :name "filter" :template "row" :string "wrap" :string "status" :status
                          "invalid" response-fields}
                         #{"collection"})}
   {:verb "store"    :terminal :handshake
    :check (fields-check {"bucket" :name "key" :template "value" :template "valueVar" :string "ttl" :number}
                         #{"bucket" "key"})}
   {:verb "respond"  :terminal :handshake :check (fields-check response-fields #{})}])

(def ^:private verb-names (mapv :verb verbs))
(def ^:private reads (vec (remove :terminal verbs)))
(def ^:private collection-verbs (filterv #(= :collection (:terminal %)) verbs))
(def ^:private by-name (into {} (map (juxt :verb identity)) verbs))

(defn errors
  "Every problem with `effect` as a message naming the field, or empty."
  [effect]
  (if-not (map? effect)
    ["effect must be an object of verbs"]
    (let [unknown  (sort (remove (set verb-names) (keys effect)))
          terminal (filter #(get effect %) (map :verb collection-verbs))]
      (vec
       (concat
        (for [k unknown] (str "effect: unknown verb " k " (use " (str/join ", " verb-names) ")"))
        (cond
          (and (empty? terminal) (not (get effect "respond")))
          [(str "effect has no `respond` or collection action ("
                (str/join "/" (map :verb collection-verbs)) ")")]
          (> (count terminal) 1)
          [(str "effect has more than one collection action (" (str/join ", " terminal) ")")])
        (mapcat (fn [[k v]] (when-let [{:keys [check]} (by-name k)] (check k v))) (sort effect)))))))

(defn collection-errors
  "Problems with the collections `effect` names that the imposter's `state`
   `settings` do not declare: a write to one would be lost, a read would fail."
  [effect settings]
  (let [declared (get settings "collections")]
    (vec (for [{:keys [verb collections]} verbs
               :let [x (get effect verb)]
               [at spec] (cond (map? x) [[verb x]] (sequential? x) (map-indexed (fn [i m] [(str verb "[" i "]") m]) x))
               f collections
               :let [n (get spec f)]
               :when (and (string? n) (not (contains? declared n)))]
           (str at "." f " names " n ", which state.collections does not declare")))))

;; --- running an effect -------------------------------------------------------------------

(def default-random-seed
  "The id stream's starting state when a contract sets no `state.randomSeed`."
  1)

(defn- init-state!
  "Make what this effect reads exist: its buckets, every collection (seeded once), the
   clock and the id stream."
  [^js state effect {:strs [collections clock randomSeed]}]
  (doseq [b (distinct (keep #(get-in effect [% "bucket"]) ["store" "consume" "lookup"]))]
    (unchecked-set state b (js-or (unchecked-get state b) (dict))))
  (doseq [[n spec] collections]
    (unchecked-set state "__c" (js-or (unchecked-get state "__c") (dict)))
    (unchecked-set state "__cf" (js-or (unchecked-get state "__cf") (dict)))
    (when-not (js/Boolean (unchecked-get (unchecked-get state "__c") n))
      (let [f (or (get spec "idField") "id") m (dict)]
        (doseq [r (clj->js (or (get spec "seed") []))] (unchecked-set m (js/String (unchecked-get r f)) r))
        (unchecked-set (unchecked-get state "__c") n m)
        (unchecked-set (unchecked-get state "__cf") n f))))
  (when clock (unchecked-set state "__now" (js-or (unchecked-get state "__now") clock)))
  (unchecked-set state "__rng" (js-or (unchecked-get state "__rng") (or randomSeed default-random-seed))))

(defn run
  "Answer `request` (the map the server builds) with `effect`, over the imposter's JS
   `state`. `settings` are the imposter's `state` settings and `verbs` the JS object of
   its custom verbs. Returns {statusCode headers body}; throws when a verb fails."
  [effect {:keys [state request settings verbs]}]
  (init-state! state effect settings)
  (let [ctx {:state state :req (clj->js request) :vars (dict) :settings settings :verbs verbs}]
    (js->clj
     (or (some (fn [{:keys [verb run]}] (when-let [spec (get effect verb)] (run ctx spec))) reads)
         (if-let [{:keys [verb run]} (some #(when (get effect (:verb %)) %) collection-verbs)]
           (run ctx (get effect verb))
           (do (when-let [s (get effect "store")] (store! ctx s))
               (response ctx (or (get effect "respond") {"status" 200}))))))))

;; --- the virtual clock ---------------------------------------------------------------------

(def default-clock-start
  "A fixed default epoch (2023-11-14T22:13:20Z) so a clock is deterministic out of
   the box, independent of wall time."
  1700000000)

(defn- op-uses-clock? [op]
  (let [e (get op "effect")]
    (boolean
     (or (get-in e ["store" "ttl"])
         (get e "limit")
         ;; a signing op almost always has iat/exp reading the clock; force it on so
         ;; those claims aren't stamped at epoch 0 (1970) in a no-ttl build.
         (get e "sign")
         ;; a verb's ctx.now reads the virtual clock
         (get e "call")
         (some (fn [[_ spec]] (and (map? spec) (contains? spec "now"))) (get e "bind"))))))

(defn contract-uses-clock?
  "True if the contract enables a virtual clock: explicitly (`state.clock`) or
   implicitly (an op uses `store.ttl`, `limit`, `sign`, `call` or a `now` bind)."
  [contract]
  (boolean (or (get-in contract ["state" "clock"])
               (some op-uses-clock? (get contract "operations")))))

(defn clock-start
  "The virtual clock's starting epoch (seconds): `state.clock.start` as an epoch
   number or an ISO string, else the fixed default."
  [contract]
  (let [s (get-in contract ["state" "clock" "start"])]
    (cond
      (number? s) s
      (string? s) (let [e (js/Math.floor (/ (.getTime (js/Date. s)) 1000))]
                    (if (js/isNaN e) default-clock-start e))
      :else default-clock-start)))

(def clock-path "/_mocksys/clock")

(defn clock-control-stub
  "The stub a clock-using twin gets at /_mocksys/clock: POST {advance: N} adds N
   seconds, POST {set: T} sets the epoch, and any request answers the current time."
  []
  {"predicates" [{"deepEquals" {"path" clock-path}}]
   "responses"  [{"clock" true}]})

(defn clock
  "Answer a /_mocksys/clock request over `state`, whose clock starts at `start`."
  [^js state start request]
  (unchecked-set state "__now" (js-or (unchecked-get state "__now") start))
  (let [b (try (js-or (js/JSON.parse (js-or (get request "body") "{}")) (js-obj)) (catch :default _ (js-obj)))
        set-to  (unchecked-get b "set")
        advance (unchecked-get b "advance")]
    (cond (number? set-to)  (unchecked-set state "__now" set-to)
          (number? advance) (unchecked-set state "__now" (+ (unchecked-get state "__now") advance)))
    (let [t (unchecked-get state "__now")]
      {"statusCode" 200
       "headers"    {"Content-Type" "application/json"}
       "body"       (js/JSON.stringify (js-obj "now" t "iso" (.toISOString (js/Date. (* t 1000)))))})))

(defn settings-errors
  "Problems with an imposter's `state` settings, as messages."
  [s]
  (if-not (map? s)
    ["state must be an object"]
    (concat
     (shape-errors "state" s {"seed" :object "collections" :object "clock" :number "randomSeed" :number "issuer" :string} #{})
     (when (map? (get s "seed"))
       (for [[k v] (get s "seed") :when (not (sequential? v))] (str "state.seed." k " must be a list")))
     (when (map? (get s "collections"))
       (concat
        (reserved-errors "state.collections" "collection" (get s "collections"))
        (mapcat (fn [[k c]]
                  (let [at (str "state.collections." k)]
                    (concat (shape-errors at c {"idField" :name "seed" :any} #{})
                            (when (and (map? c) (some? (get c "seed")) (not (sequential? (get c "seed"))))
                              [(str at ".seed must be a list of rows")]))))
                (get s "collections")))))))
