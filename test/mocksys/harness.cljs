(ns mocksys.harness
  "Test-only helpers. Compile an operation's `effect` to its injection (via
   `inject/gen`), turn that JS source into a live function, and run it against a fake
   request over a persistent `state` object. Tests assert on the *runtime behavior*
   of the generated JS, not on the CLJS emit strings, so they catch the bugs that
   actually reach a served response.

   Compiling and calling go through the native runtime's own injection runner, so a
   test exercises the same code path (and the same `require`) as a served request."
  (:require [mocksys.inject :as inject]
            [mocksys.runtime.imposter :as imposter]
            [clojure.string :as str]))

(defn compile-fn
  "Eval the generated injection source for `op` into a callable JS function.
   `opts` may carry :seed, :collections, :clock-start, :issuer and :random-seed
   (all optional), and :verbs, the JS object of custom verbs a `call` reaches."
  [op opts]
  (imposter/compile-injection (inject/gen op opts) (:verbs opts)))

(defn fresh-state
  "A blank per-imposter state object. Pass the SAME one across several `run` calls
   to exercise statefulness (mint→store→consume, create→get, link→listJoin, ...)."
  []
  #js {})

(defn- request-of
  "The request map the runtime hands an injection. `req` has optional :method :path
   :query :headers :body. A map :body is sent as JSON text and a string :body as is
   (e.g. form-encoded), since a served request's body is always a string."
  [req]
  (let [body (get req :body)]
    {"method"  (get req :method "GET")
     "path"    (get req :path "/")
     "query"   (get req :query {})
     "headers" (get req :headers {})
     "body"    (cond (map? body) (js/JSON.stringify (clj->js body)) (nil? body) "" :else body)}))

(defn run
  "Compile `op` to its injection and invoke it once against `req` over `state`.
   Returns {:status :headers :body :json} where :json is the parsed body when the
   response is JSON, else nil. Share `state` across calls to test statefulness."
  ([op req] (run op req {} (fresh-state)))
  ([op req opts] (run op req opts (fresh-state)))
  ([op req opts state]
   (let [r       (imposter/run-injection (compile-fn op opts) (request-of req) state)
         headers (get r "headers")
         body    (get r "body")
         ct      (get headers "Content-Type" "")
         json    (when (and (string? body) (seq body) (str/includes? ct "json"))
                   (try (js->clj (js/JSON.parse body)) (catch :default _ nil)))]
     {:status (get r "statusCode") :headers headers :body body :json json})))

(defn contract-opts
  "The `inject/gen` opts for an effective contract: each collection's seed name
   resolved to its array, exactly as `contract/lower` does."
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
