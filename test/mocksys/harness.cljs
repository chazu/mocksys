(ns mocksys.harness
  "Test-only helpers. Run an operation's `effect` against a fake request over a
   persistent `state` object. Tests assert on the *runtime behavior* (the response a
   served request would get), not on how an effect is represented.

   While the generated JavaScript and the interpreter both exist, every run goes
   through both: the codegen over a shadow copy of `state`, the interpreter over
   `state` itself. Each run asserts that the two answer alike (status, headers in
   order, body bytes, or the error message) and leave the same state behind. The
   counts print when the test process exits."
  (:require [clojure.test :refer [is]]
            [mocksys.inject :as inject]
            [mocksys.effect :as effect]
            [mocksys.runtime.imposter :as imposter]
            [clojure.string :as str]))

(defn compile-fn
  "Eval the generated injection source for `op` into a callable JS function.
   `opts` may carry :seed, :collections, :clock-start, :issuer and :random-seed
   (all optional), and :verbs, the JS object of custom verbs a `call` reaches."
  [op opts]
  (imposter/compile-injection (inject/gen op opts) (:verbs opts)))

(defn settings-of
  "The imposter `state` settings that `opts` describe."
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
  #js {})

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

(defonce compared (atom {:runs 0 :differences 0}))

(defonce ^:private report
  (.once js/process "exit"
         (fn [] (let [{:keys [runs differences]} @compared]
                  (when (pos? runs)
                    (println (str "differential: " runs " effect runs through codegen and interpreter, "
                                  differences " difference(s)")))))))

(def ^:private shadows (js/WeakMap.))

(defn- shadow-of [state]
  (or (.get shadows state)
      (let [s (js/structuredClone state)] (.set shadows state s) s)))

(defn- outcome [f]
  (try (let [r (f)] [:ok (get r "statusCode") (vec (get r "headers")) (get r "body")])
       (catch :default e [:error (ex-message e)])))

(defn- both!
  "The interpreter's response (`interpreted` over `state`), after asserting that the
   codegen (`generated` over the shadow state) answers alike and leaves the same state."
  [label state generated interpreted]
  (let [shadow (shadow-of state)
        old    (outcome #(generated shadow))
        new    (outcome #(interpreted state))
        same?  (and (= old new) (= (js/JSON.stringify shadow) (js/JSON.stringify state)))]
    (swap! compared #(cond-> (update % :runs inc) (not same?) (update :differences inc)))
    (is (= old new) (str label ": codegen and interpreter answer alike"))
    (is (= (js/JSON.stringify shadow) (js/JSON.stringify state)) (str label ": and leave the same state"))
    (if (= :error (first new))
      (throw (js/Error. (second new)))
      (let [[_ status headers body] new] {"statusCode" status "headers" (into {} headers) "body" body}))))

(defn- run-both [op request opts state]
  (is (= [] (effect/errors (get op "effect"))) (str (get op "id") ": the effect validates"))
  (both! (get op "id") state
         #(imposter/run-injection (compile-fn op opts) request %)
         #(effect/run (get op "effect") {:state % :request request :settings (settings-of opts) :verbs (:verbs opts)})))

(defn clock!
  "Send `body` (a map) to the twin's clock control over `state`, whose clock starts
   at `start`. Returns the parsed {now iso}."
  [state start body]
  (let [request (request-of {:method "POST" :path "/_mocksys/clock" :body body})
        src     (get-in (inject/clock-control-stub start) ["responses" 0 "inject"])
        r       (both! "clock" state
                       #(imposter/run-injection (imposter/compile-injection src) request %)
                       #(effect/clock % start request))]
    (js->clj (js/JSON.parse (get r "body")))))

(defn run
  "Run `op`'s effect once against `req` over `state`. Returns
   {:status :headers :body :json} where :json is the parsed body when the response
   is JSON, else nil. Share `state` across calls to test statefulness."
  ([op req] (run op req {} (fresh-state)))
  ([op req opts] (run op req opts (fresh-state)))
  ([op req opts state]
   (let [r       (run-both op (request-of req) opts state)
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
