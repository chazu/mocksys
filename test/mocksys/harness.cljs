(ns mocksys.harness
  "Test-only helpers. Run an operation's `effect` against a fake request over a
   persistent `state` object, the way the daemon does. Tests assert on the *runtime
   behavior* (the response a served request would get), not on how an effect is
   represented."
  (:require [clojure.test :refer [is]]
            [mocksys.effect :as effect]
            [clojure.string :as str]))

(defn settings-of
  "The imposter `state` settings that `opts` describe: :seed, :collections,
   :clock-start, :issuer and :random-seed, all optional. :verbs is the JS object of
   custom verbs a `call` reaches."
  [{:keys [seed collections clock-start issuer random-seed]}]
  (cond-> {}
    seed        (assoc "seed" seed)
    collections (assoc "collections" collections)
    clock-start (assoc "clock" clock-start)
    random-seed (assoc "randomSeed" random-seed)
    issuer      (assoc "issuer" issuer)))

(defn fresh-state
  "A blank per-imposter state object. Pass the SAME one across several `run` calls
   to exercise statefulness (mint→store→consume, create→get, link→listJoin, ...)."
  []
  (effect/new-state))

(defn- request-of
  "The request map the runtime hands an effect. `req` has optional :method :path
   :query :headers :body. A map :body is sent as JSON text and a string :body as is
   (e.g. form-encoded), since a served request's body is always a string."
  [req]
  (let [body (get req :body)]
    {"method"  (get req :method "GET")
     "path"    (get req :path "/")
     "query"   (get req :query {})
     "headers" (get req :headers {})
     "body"    (cond (map? body) (js/JSON.stringify (clj->js body)) (nil? body) "" :else body)}))

(defn- run-effect [op request opts state]
  (let [settings (settings-of opts)]
    (is (= [] (effect/errors (get op "effect")) (effect/collection-errors (get op "effect") settings))
        (str (get op "id") ": the effect validates"))
    (effect/run (get op "effect") {:state state :request request :settings settings :verbs (:verbs opts)})))

(defn clock!
  "Send `body` (a map) to the twin's clock control over `state`, whose clock starts
   at `start`. Returns the parsed {now iso}."
  [state start body]
  (let [r (effect/clock state start (request-of {:method "POST" :path "/_mocksys/clock" :body body}))]
    (js->clj (js/JSON.parse (get r "body")))))

(defn run
  "Run `op`'s effect once against `req` over `state`. Returns
   {:status :headers :body :json} where :json is the parsed body when the response
   is JSON, else nil. Share `state` across calls to test statefulness."
  ([op req] (run op req {} (fresh-state)))
  ([op req opts] (run op req opts (fresh-state)))
  ([op req opts state]
   (let [r       (run-effect op (request-of req) opts state)
         headers (get r "headers")
         body    (get r "body")
         ct      (get headers "Content-Type" "")
         json    (when (and (string? body) (seq body) (str/includes? ct "json"))
                   (try (js->clj (js/JSON.parse body)) (catch :default _ nil)))]
     {:status (get r "statusCode") :headers headers :body body :json json})))

(defn contract-opts
  "The run opts for an effective contract: each collection's seed name resolved to
   its array, exactly as `contract/lower` does."
  [contract]
  (let [seed (get contract "seed")]
    {:seed seed
     :collections (into {} (for [[n spec] (get-in contract ["state" "collections"])]
                             [n {"idField" (get spec "idField") "seed" (get seed (get spec "seed"))}]))}))

(defn run-op
  "Run operation `op-id` of an effective contract against `req` over `state`."
  [contract op-id req state]
  (let [op (some #(when (= op-id (get % "id")) %) (get contract "operations"))]
    (when-not op (throw (js/Error. (str "no operation " op-id))))
    (run op req (contract-opts contract) state)))
