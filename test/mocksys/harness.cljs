(ns mocksys.harness
  "Test-only helpers. Compile an operation's `effect` to its Mountebank injection
   (via `inject/gen`), eval that JS source into a live function, and run it against
   a fake request over a persistent `state` object. Tests assert on the *runtime
   behavior* of the generated JS — not on the CLJS emit strings — so they catch the
   bugs that actually reach a served response.

   `eval` runs in this module's scope, exactly as Mountebank's responseResolver
   does at serve time (see the mocksys-mb-inject-require note), so `require` and the
   Node built-ins the prelude/`sign` rely on are reachable."
  (:require [mocksys.inject :as inject]
            [clojure.string :as str]))

(defn compile-fn
  "Eval the generated injection source for `op` into a callable JS function.
   `opts` may carry :seed, :collections, :clock-start, :issuer and :random-seed
   (all optional)."
  [op opts]
  (js/eval (str "(" (inject/gen op opts) ")")))

(defn fresh-state
  "A blank per-imposter state object. Pass the SAME one across several `run` calls
   to exercise statefulness (mint→store→consume, create→get, link→listJoin, ...)."
  []
  #js {})

(defn- req->js
  "Build the Mountebank `request` object the injection reads. `req` is a map with
   optional :method :path :query :headers :body. A map :body stands in for a parsed
   JSON body; a string :body stands in for a raw (e.g. form-encoded) body."
  [req]
  (clj->js
   {"method"  (get req :method "GET")
    "path"    (get req :path "/")
    "query"   (get req :query {})
    "headers" (get req :headers {})
    "body"    (get req :body)}))

(defn run
  "Compile `op` to its injection and invoke it once against `req` over `state`.
   Returns {:status :headers :body :json} where :json is the parsed body when the
   response is JSON, else nil. Share `state` across calls to test statefulness."
  ([op req] (run op req {} (fresh-state)))
  ([op req opts] (run op req opts (fresh-state)))
  ([op req opts state]
   (let [f       (compile-fn op opts)
         r       (.call f nil (req->js req) state nil)
         headers (js->clj (.-headers r))
         body    (.-body r)
         ct      (get headers "Content-Type" "")
         json    (when (and (string? body) (seq body) (str/includes? ct "json"))
                   (try (js->clj (js/JSON.parse body)) (catch :default _ nil)))]
     {:status (.-statusCode r) :headers headers :body body :json json})))
