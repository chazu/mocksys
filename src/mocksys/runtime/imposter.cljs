(ns mocksys.runtime.imposter
  "One imposter as the daemon holds it: the posted spec, validated against the subset
  of Mountebank's imposter format that mocksys emits, plus its live state.

  The inventory, and nothing else:
    imposter   protocol http or grpc, port, name, recordRequests, stubs, modules
               (absolute paths of CommonJS files whose `verbs` effects can call),
               state (the settings effects run over, see mocksys.effect) and for
               grpc the proto's descriptor (served by mocksys.runtime.grpc)
    stub       predicates (see mocksys.runtime.match), responses
    response   exactly one of
                 is      {statusCode headers body _mode}
                 effect  an operation's effect map, run over the imposter's state
                 clock   true: read or move the virtual clock (/_mocksys/clock)
                 proxy   {to, mode proxyOnce, predicateGenerators [{matches {field true}}]}
                 fault   CONNECTION_RESET_BY_PEER | RANDOM_DATA_THEN_CLOSE
               plus optional behaviors: wait (ms) and copy (regex or jsonpath from a
               request field into a ${token})

  `respond` turns one request into an outcome for the server to write. Everything
  here is pure data except the modules' verbs and the proxy fetch, which the caller
  passes in."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            ["node:path" :as path]
            [mocksys.effect :as effect]
            [mocksys.modules :as modules]
            [mocksys.runtime.match :as match]))

;; --- validation --------------------------------------------------------------

(defn- bad [path msg]
  (throw (ex-info (str (when (seq path) (str path ": ")) msg) {:code "bad data"})))

(defn- only-keys! [path m allowed]
  (when-let [extra (seq (remove allowed (keys m)))]
    (bad path (str "unsupported field(s) " (str/join ", " (sort extra))))))

(defn- leaves [x]
  (cond (map? x) (mapcat leaves (vals x)) (vector? x) (mapcat leaves x) :else [x]))

(defn- check-predicate! [path pred]
  (when-not (map? pred) (bad path "a predicate must be an object"))
  (let [ops (filter match/operators (keys pred))]
    (when-not (= 1 (count ops))
      (bad path (str "a predicate needs exactly one of " (str/join ", " (sort match/operators)))))
    (only-keys! path pred (conj match/operators "jsonpath"))
    (when-not (map? (get pred (first ops))) (bad path (str (first ops) " must be an object")))
    (when-let [jp (get pred "jsonpath")]
      (let [sel (get jp "selector")]
        (when-not (and (map? jp) (string? sel) (re-matches match/plain-selector sel))
          (bad path (str "unsupported jsonpath selector " (pr-str sel) " (plain paths only)")))))
    (when (= "matches" (first ops))
      (doseq [re (leaves (get pred "matches"))]
        (try (js/RegExp. re) (catch :default _ (bad path (str "invalid regex " (pr-str re)))))))))

(def ^:private response-types #{"is" "effect" "clock" "proxy" "fault"})
(def ^:private faults #{"CONNECTION_RESET_BY_PEER" "RANDOM_DATA_THEN_CLOSE"})

(defn proxy-target-error
  "Why `to` cannot be a proxy target, or nil; `field` names it in the message. The
   proxy forwards each request's own path and query to the target's origin, so a
   path, query or fragment on the target would be silently dropped."
  [field to]
  (let [url (when (and (string? to) (re-find #"^https?://" to)) (try (js/URL. to) (catch :default _ nil)))]
    (cond
      (nil? url)                  (str field " must be an http(s) URL")
      (not= "/" (.-pathname url)) (str field " must not contain a path " (pr-str (.-pathname url)))
      (seq (.-search url))        (str field " must not contain a query " (pr-str (.-search url)))
      (str/includes? to "#")      (str field " must not contain a fragment"))))

(defn- check-behavior! [path b]
  (when-not (and (map? b) (= 1 (count b))) (bad path "a behavior must be an object with one key"))
  (let [[k v] (first b)]
    (case k
      "wait" (when-not (number? v) (bad path "wait must be a number of milliseconds"))
      "copy" (let [{:strs [from into using]} v]
               (only-keys! path v #{"from" "into" "using"})
               (when-not (or (string? from) (and (map? from) (= 1 (count from)) (string? (val (first from)))))
                 (bad path "copy.from must be a request field or {field: key}"))
               (when-not (string? into) (bad path "copy.into must be a token string"))
               (when-not (and (contains? #{"regex" "jsonpath"} (get using "method")) (string? (get using "selector")))
                 (bad path "copy.using must be {method: regex|jsonpath, selector}"))
               (only-keys! path using #{"method" "selector"})
               (when (= "jsonpath" (get using "method"))
                 (when-not (re-matches match/plain-selector (get using "selector"))
                   (bad path "unsupported jsonpath selector (plain paths only)"))))
      (bad path (str "unsupported behavior " (pr-str k))))))

(defn- header-value? [v]
  (or (string? v) (number? v) (boolean? v)
      (and (vector? v) (every? #(or (string? %) (number? %)) v))))

(defn- check-is! [path v]
  (when-not (map? v) (bad path "is must be an object"))
  (only-keys! path v #{"statusCode" "headers" "body" "_mode" "_proxyResponseTime"})
  (when-some [code (get v "statusCode")]
    (let [n (cond (integer? code) code (and (string? code) (re-matches #"\d+" code)) (js/parseInt code 10))]
      (when-not (and n (<= 100 n 599)) (bad path (str "is.statusCode must be an HTTP status from 100 to 599, got " (pr-str code))))))
  (when-some [hs (get v "headers")]
    (when-not (map? hs) (bad path "is.headers must be an object"))
    (doseq [[k hv] hs]
      (when-not (header-value? hv)
        (bad path (str "is.headers[" (pr-str k) "] must be a string, number or array of strings"))))))

(defn- check-response! [path r]
  (when-not (map? r) (bad path "a response must be an object"))
  (only-keys! path r (conj response-types "behaviors"))
  (let [types (filter response-types (keys r))]
    (when-not (= 1 (count types))
      (bad path (str "a response needs exactly one of " (str/join ", " (sort response-types)))))
    (let [v (get r (first types))]
      (case (first types)
        "is"     (check-is! path v)
        "effect" (when-let [es (seq (effect/errors v))] (bad (str path ".effect") (str/join "; " es)))
        "clock"  (when-not (true? v) (bad path "clock must be true"))
        "proxy"  (do (when-not (map? v) (bad path "proxy must be an object"))
                     (only-keys! path v #{"to" "mode" "predicateGenerators"})
                     (when-let [e (proxy-target-error "proxy.to" (get v "to"))] (bad path e))
                     (when-not (contains? #{nil "proxyOnce"} (get v "mode"))
                       (bad path "only proxyOnce proxies are supported"))
                     (doseq [g (get v "predicateGenerators")]
                       (when-not (and (map? g) (= ["matches"] (keys g)) (map? (get g "matches"))
                                      (every? true? (vals (get g "matches"))))
                         (bad path "predicateGenerators must be [{matches: {field: true}}]"))))
        "fault"  (when-not (faults v) (bad path (str "unsupported fault " (pr-str v))))))
    (when-some [bs (get r "behaviors")]
      (when-not (vector? bs) (bad path "behaviors must be an array"))
      (doseq [[j b] (map-indexed vector bs)]
        (check-behavior! (str path ".behaviors[" j "]") b)))))

(defn- validate! [spec]
  (when-not (map? spec) (bad "" "an imposter must be a JSON object"))
  (only-keys! "" spec (cond-> #{"protocol" "port" "name" "recordRequests" "stubs" "modules" "state"}
                        (= "grpc" (get spec "protocol")) (conj "descriptor")))
  (when-not ((some-fn nil? #(and (vector? %) (every? (fn [m] (and (string? m) (path/isAbsolute m))) %)))
             (get spec "modules"))
    (bad "" "modules must be absolute paths"))
  (when (contains? spec "state")
    (when-let [es (seq (effect/settings-errors (get spec "state")))] (bad "" (str/join "; " es))))
  (when-not (contains? #{"http" "grpc"} (get spec "protocol"))
    (bad "" (str "unsupported protocol " (pr-str (get spec "protocol")) " (http or grpc)")))
  (when (and (= "grpc" (get spec "protocol")) (not (map? (get spec "descriptor"))))
    (bad "" "protocol grpc needs a descriptor"))
  (let [port (get spec "port")]
    (when-not (and (integer? port) (< 0 port 65536)) (bad "" "port must be an integer from 1 to 65535")))
  (when-not ((some-fn nil? string?) (get spec "name")) (bad "" "name must be a string"))
  (when-not ((some-fn nil? boolean?) (get spec "recordRequests")) (bad "" "recordRequests must be a boolean"))
  (when-not ((some-fn nil? vector?) (get spec "stubs")) (bad "" "stubs must be an array"))
  (doseq [[i stub] (map-indexed vector (get spec "stubs"))
          :let [path (str "stubs[" i "]")]]
    (when-not (map? stub) (bad path "a stub must be an object"))
    (only-keys! path stub #{"predicates" "responses"})
    (doseq [k ["predicates" "responses"]]
      (when-not ((some-fn nil? vector?) (get stub k)) (bad path (str k " must be an array"))))
    (doseq [[j pred] (map-indexed vector (get stub "predicates"))]
      (check-predicate! (str path ".predicates[" j "]") pred))
    (doseq [[j r] (map-indexed vector (get stub "responses"))]
      (check-response! (str path ".responses[" j "]") r)
      (when-let [es (seq (effect/collection-errors (get r "effect") (get spec "state")))]
        (bad (str path ".responses[" j "].effect") (str/join "; " es)))
      (when (and (contains? r "clock") (not (number? (get-in spec ["state" "clock"]))))
        (bad (str path ".responses[" j "]") "a clock response needs the imposter's state.clock")))))

;; --- construction ------------------------------------------------------------

(defn- calls-in
  "The custom verbs the spec's effects call, in stub order."
  [spec]
  (distinct (for [s (get spec "stubs"), r (get s "responses"), c (effect/calls-of (get r "effect"))]
              (get c "verb"))))

(defn- verbs-of
  "The custom verbs of the spec's modules, read from disk on every post so an edited
   module takes effect when the scenario is run again. A verb an effect calls that no
   module exports fails the post."
  [spec]
  (try (modules/load-verbs (map vector (get spec "modules") (get spec "modules")) (calls-in spec))
       (catch :default e (bad "" (ex-message e)))))

(defn create
  "A live imposter (an atom) from a posted spec. Throws ex-info {:code \"bad data\"}
   naming the offending field when the spec steps outside the inventory."
  [spec]
  (validate! spec)
  (let [stubs (vec (get spec "stubs"))]
    (atom {:spec     (dissoc spec "stubs")
           :stubs    (vec (map-indexed (fn [i s] {:id i :spec s :cursor 0}) stubs))
           :next-id  (count stubs)
           :requests []
           :count    0
           :state    (effect/new-state)
           :verbs    (verbs-of spec)})))

;; --- behaviors -----------------------------------------------------------------

(defn- get-ci
  "A field by case-insensitive key; the first value of a repeated one."
  [m k]
  (when (map? m)
    (let [v (some (fn [[mk v]] (when (= (str/lower-case mk) (str/lower-case k)) v)) m)]
      (if (vector? v) (first v) v))))

(defn- copy-source [request from]
  (if (map? from)
    (let [[field key] (first from)] (get-ci (get-ci request field) key))
    (get-ci request from)))

(defn- copy-values
  "What a copy behavior splices in: the regex match groups, or the jsonpath hits."
  [request {:strs [from using]}]
  (let [src (copy-source request from)]
    (if (= "regex" (get using "method"))
      ;; Mountebank execs the regex on the raw value, so a missing field reads as the
      ;; string "undefined". Twins answer the same way, so keep it.
      (if-let [m (.exec (js/RegExp. (get using "selector")) (if (nil? src) js/undefined src))]
        (vec (array-seq m))
        [])
      (let [doc (try (js->clj (js/JSON.parse src)) (catch :default _ ::none))]
        (if (= ::none doc) [] (match/select (get using "selector") doc))))))

(defn- js-string [v] (if (nil? v) "null" (str v)))

(defn- replace-token [x token values]
  (let [rep (fn [s]
              (let [s (reduce-kv (fn [s i v] (str/replace s (str token "[" i "]") (js-string v)))
                                 s (vec values))]
                (if (seq values) (str/replace s token (js-string (first values))) s)))]
    (cond
      (string? x) (rep x)
      (map? x)    (into {} (map (fn [[k v]] [(rep k) (replace-token v token values)])) x)
      (vector? x) (mapv #(replace-token % token values) x)
      :else x)))

(defn- apply-behaviors
  "Run a response's behaviors in order. Copies rewrite the response; waits add up to
   the delay the server applies before writing it."
  [request response behaviors]
  (reduce (fn [[resp wait] b]
            (if-let [ms (get b "wait")]
              [resp (+ wait ms)]
              (let [c (get b "copy")]
                [(replace-token resp (get c "into") (copy-values request c)) wait])))
          [response 0]
          behaviors))

;; --- finishing -----------------------------------------------------------------

(defn- header-key
  "The key `headers` already uses for `name` (any case), or `name` itself."
  [headers name]
  (or (some #(when (= (str/lower-case %) (str/lower-case name)) %) (keys headers)) name))

(defn- finish
  "The wire form of a response, with Mountebank's defaults: status 200, an empty body,
   an object body as 4-space JSON, `Connection: close`, and any declared
   Content-Length recomputed."
  [{:strs [statusCode headers body _mode]}]
  (let [encoding (if (= "binary" _mode) "base64" "utf8")
        body     (cond
                   (or (map? body) (vector? body)) (js/JSON.stringify (clj->js body) nil 4)
                   (not (js/Boolean body))         ""
                   (= "base64" encoding)           (str/replace (str body) #"[^A-Za-z0-9=+/]+" "")
                   :else                           (str body))
        headers  (or headers {})
        cl       (header-key headers "Content-Length")]
    {:status   (if (js/Boolean statusCode) (js/parseInt statusCode 10) 200)
     :headers  (cond-> headers
                 (not (contains? headers (header-key headers "Connection"))) (assoc "Connection" "close")
                 (contains? headers cl) (assoc cl (.byteLength js/Buffer body encoding)))
     :body     body
     :encoding encoding}))

;; --- serving ---------------------------------------------------------------------

(defn- record-request! [imp request]
  (swap! imp (fn [s]
               (cond-> (update s :count inc)
                 (get-in s [:spec "recordRequests"])
                 (update :requests conj (assoc request "timestamp" (.toJSON (js/Date.))))))))

(defn- next-response!
  "The first stub whose predicates hold and that stub's next response (cycling
   through its responses), as [stub-id response]. No match is an empty `is`."
  [imp request]
  (let [stubs (:stubs @imp)
        i     (first (keep-indexed #(when (match/all? (get-in %2 [:spec "predicates"]) request) %1) stubs))]
    (if (nil? i)
      [nil {"is" {}}]
      (let [{:keys [id spec cursor]} (nth stubs i)
            rs (get spec "responses")]
        (swap! imp update-in [:stubs i :cursor] inc)
        [id (if (seq rs) (nth rs (mod cursor (count rs))) {"is" {}})]))))

(defn- generated-predicates [request generators]
  (vec (for [g generators, [field _] (get g "matches")]
         {"deepEquals" {field (get request field)}})))

(defn- record-proxied!
  "proxyOnce: save the upstream response as a new stub just before the proxy stub, so
   the next matching request replays it instead of going upstream."
  [imp proxy-stub-id request cfg response]
  (swap! imp (fn [s]
               (let [stubs (:stubs s)
                     at    (or (first (keep-indexed #(when (= proxy-stub-id (:id %2)) %1) stubs)) (count stubs))
                     stub  {:id (:next-id s) :cursor 0
                            :spec {"predicates" (generated-predicates request (get cfg "predicateGenerators"))
                                   "responses"  [{"is" response}]}}]
                 (-> s
                     (assoc :stubs (into (conj (subvec stubs 0 at) stub) (subvec stubs at)))
                     (update :next-id inc))))))

(defn respond
  "One request through the imposter: record it, pick the stub, resolve its next
   response. Returns a promise of {:status :headers :body :encoding :wait} or
   {:fault kind}. `proxy!` (cfg request) -> promise of an `is` map fetches upstream."
  [imp request {:keys [proxy!]}]
  (p/do
    (record-request! imp request)
    (let [[stub-id config] (next-response! imp request)
          behaviors (get config "behaviors")
          out (fn [resp]
                (let [[resp wait] (apply-behaviors request resp behaviors)]
                  (assoc (finish resp) :wait wait)))]
      (cond
        (contains? config "is")
        (out (get config "is"))

        (contains? config "effect")
        (let [{:keys [spec state verbs]} @imp]
          (out (effect/run (get config "effect") {:state state :request request :settings (get spec "state") :verbs verbs})))

        (contains? config "clock")
        (out (effect/clock (:state @imp) (get-in @imp [:spec "state" "clock"]) request))

        (contains? config "proxy")
        (let [cfg   (get config "proxy")
              start (js/Date.now)]
          (p/let [upstream (proxy! cfg request)]
            (let [resp (assoc upstream "_proxyResponseTime" (- (js/Date.now) start))
                  [resp wait] (apply-behaviors request resp behaviors)]
              (record-proxied! imp stub-id request cfg resp)
              (assoc (finish resp) :wait wait))))

        :else
        {:fault (get config "fault")}))))

(defn clear-requests! [imp]
  (swap! imp assoc :requests []))

(defn port [imp] (get-in @imp [:spec "port"]))
(defn protocol [imp] (get-in @imp [:spec "protocol"]))

(defn to-json
  "The imposter as the admin API shows it. `:list?` is the summary row;
   `:replayable?` drops requests and proxy timings; `:remove-proxies?` drops proxy
   responses and any stub left with none. Every form but the summary keeps `modules`
   and `state`, so a replayable imposter posts back as it was."
  [imp {:keys [list? replayable? remove-proxies?]}]
  (let [{:keys [spec stubs requests] n :count} @imp
        header (cond-> (select-keys spec ["protocol" "port" "name" "modules"])
                 (not replayable?) (assoc "numberOfRequests" n))
        body   (merge header (select-keys spec ["state" "descriptor"]))
        strip  (fn [r] (cond-> r (contains? r "is") (update "is" dissoc "_proxyResponseTime")))
        proxy? (fn [r] (contains? r "proxy"))
        stubs  (cond->> (mapv :spec stubs)
                 replayable?     (mapv #(update % "responses" (partial mapv strip)))
                 remove-proxies? (mapv #(update % "responses" (partial filterv (complement proxy?))))
                 remove-proxies? (filterv #(seq (get % "responses"))))]
    (cond
      list?       (dissoc header "modules")
      replayable? (assoc body "recordRequests" (boolean (get spec "recordRequests")) "stubs" stubs)
      :else       (assoc body "recordRequests" (boolean (get spec "recordRequests"))
                         "requests" requests "stubs" stubs))))
