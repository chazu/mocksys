(ns mocksys.conform
  "Differential conformance: replay a request corpus at the *twin* and (when you
  have credentials) the *live* service, then diff the responses — normalized by the
  same volatility knowledge `scrub` uses, so request-ids/dates don't read as drift.
  This is the DTU fidelity loop: drive a twin toward zero observable delta. Without a
  live target it's a golden check (each request's status vs an expected one).

  A corpus is a JSON array of requests:
    [{\"method\":\"GET\",\"path\":\"/api/v1/users\",\"expect\":200},
     {\"method\":\"POST\",\"path\":\"/api/v1/users\",\"headers\":{...},\"body\":{...}}]
  `body` may be a JSON value (sent as JSON) or a string. `expect` (status) is used
  only in the no-live golden mode. `--ignore` (global, comma-separated) masks
  volatile JSON keys before the body diff."
  (:require [promesa.core :as p]
            [clojure.string :as str]))

;; --- corpus ---------------------------------------------------------------

(defn parse-corpus
  "A corpus JSON string -> vector of request maps. Accepts a bare array or
   {\"requests\":[...]}; throws a clean error otherwise."
  [text]
  (let [v (try (js->clj (js/JSON.parse text))
               (catch :default e
                 (throw (js/Error. (str "corpus is not valid JSON — " (.-message e))))))
        reqs (cond (vector? v) v (map? v) (get v "requests") :else nil)]
    (when-not (vector? reqs)
      (throw (js/Error. "corpus must be a JSON array of requests (or {\"requests\":[...]})")))
    reqs))

(defn recorded->corpus
  "Reconstruct a corpus from a running mock's recorded requests (the daemon's shape),
   so `conform` can replay real traffic the twin already saw."
  [reqs]
  (mapv (fn [r]
          (let [q (get r "query")
                qs (when (seq q) (str "?" (str/join "&" (for [[k v] q] (str k "=" v)))))]
            (cond-> {"method" (get r "method") "path" (str (get r "path") (or qs ""))}
              (seq (get r "body")) (assoc "body" (get r "body")))))
        reqs))

;; --- fire + normalize -----------------------------------------------------

(defn fire
  "Send one corpus request to `base` (e.g. http://localhost:PORT) with `fetch`
   (js/fetch unless given). Returns {:status :headers :body} where body is the raw
   text."
  ([base req] (fire base req js/fetch))
  ([base {:strs [method path headers body]} fetch]
   (let [json? (and (some? body) (not (string? body)))
         hdrs  (cond-> (or headers {})
                 json? (assoc "Content-Type" "application/json"))
         opts  #js {:method (or method "GET") :headers (clj->js hdrs) :redirect "manual"}]
     (when (some? body)
       (set! (.-body opts) (if (string? body) body (js/JSON.stringify (clj->js body)))))
     (p/let [res  (fetch (str base path) opts)
             text (.text res)]
       {:status (.-status res)
        :headers (js->clj (js/Object.fromEntries (.entries (.-headers res))))
        :body   text}))))

(def ^:private jwt-re #"^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$")

;; claims that legitimately change every issuance (random id / clock-driven), so a raw
;; JWT string diff is all noise. Dropping them (and comparing the decoded payload, which
;; also drops the signature segment) leaves the meaningful claims — aud/sub/scp/… — to
;; diff. A blanket `--ignore` of the token field would have masked those too (Q5).
(def ^:private jwt-volatile-claims #{"jti" "iat" "exp" "auth_time" "nbf"})

(defn- normalize-jwt
  "If `s` is a compact JWT, decode its payload and drop the volatile claims, so the
   diff compares claims not the raw signed string. Otherwise return `s` unchanged."
  [s]
  (if (and (string? s) (re-matches jwt-re s))
    (try
      (let [payload (-> (second (str/split s #"\."))
                        (str/replace "-" "+") (str/replace "_" "/"))
            claims  (js->clj (js/JSON.parse (.toString (js/Buffer.from payload "base64") "utf8")))]
        (if (map? claims)
          {"__jwt_claims" (apply dissoc claims jwt-volatile-claims)}
          s))
      (catch :default _ s))
    s))

(defn- canonical-body
  "A stable, comparable form of a response body: JSON is parsed, the `ignore` keys
   masked (top-level, recursively), compact JWTs normalized to their non-volatile
   decoded claims, and re-serialized with sorted keys; non-JSON is returned verbatim.
   So key order, volatile fields, and per-issuance token noise don't create false diffs."
  [text ignore]
  (let [ign (set ignore)]
    (try
      (let [v (js->clj (js/JSON.parse text))
            mask (fn mask [x]
                   (cond
                     (map? x)    (into (sorted-map)
                                       (for [[k val] x]
                                         [k (if (contains? ign k) "<ignored>" (mask val))]))
                     (vector? x) (mapv mask x)
                     (string? x) (let [j (normalize-jwt x)] (if (map? j) (mask j) x))
                     :else x))]
        (js/JSON.stringify (clj->js (mask v))))
      (catch :default _ text))))

(defn- volatile-headers-dropped [headers vol-set]
  (into (sorted-map)
        (remove (fn [[k _]] (contains? vol-set (str/lower-case k))) headers)))

;; --- diff -----------------------------------------------------------------

(defn diff-one
  "Compare a twin response with a live one. Returns nil if they agree (on status and
   canonical body — headers only when `compare-headers?`), else a vector of reasons."
  [twin live {:keys [ignore vol-set compare-headers?]}]
  (let [reasons (cond-> []
                  (not= (:status twin) (:status live))
                  (conj (str "status " (:status twin) " vs " (:status live)))

                  (not= (canonical-body (:body twin) ignore)
                        (canonical-body (:body live) ignore))
                  (conj "body differs")

                  (and compare-headers?
                       (not= (volatile-headers-dropped (:headers twin) vol-set)
                             (volatile-headers-dropped (:headers live) vol-set)))
                  (conj "headers differ"))]
    (seq reasons)))
