(ns mocksys.lifecycle
  "A scenario's lifecycle: the contract is canonical and the imposter is a build
  artifact. `compile!` lowers one to the other; `ensure-contract!` lifts a
  legacy/recorded imposter into a contract on demand; `ensure-running!` posts it
  to the mocksys daemon."
  (:require [promesa.core :as p]
            [mocksys.mb :as mb]
            [mocksys.store :as store]
            [mocksys.service :as service]
            [mocksys.contract :as contract]
            [mocksys.proto :as proto]
            [mocksys.inject :as inject]))

(defn compile!
  "Lower a scenario's contract to imposter.json and refresh mock.yaml. Returns the
   imposter. Carries the prior artifact's port so a running mock keeps its slot."
  [scenario]
  (let [contract* (store/read-contract scenario)
        port      (when (store/exists? scenario)
                    (try (get (store/read-imposter scenario) "port") (catch :default _ nil)))
        imposter  (proto/with-descriptor (contract/lower contract* port) contract* (store/scenario-dir scenario))
        prof      (service/effective scenario)]
    (store/write-imposter! scenario imposter)
    (store/write-mock! scenario (merge (or (store/read-mock scenario) {})
                                       {:scenario scenario
                                        :service  (:service prof)
                                        :port     port
                                        :env      (:env prof)
                                        :origin   (get contract* "origin")
                                        :stubs    (count (get imposter "stubs"))}))
    imposter))

(defn ensure-contract!
  "The scenario's contract, lifting one from a recorded/legacy imposter if none
   exists yet. nil when there's nothing to work from."
  [scenario]
  (or (store/read-contract scenario)
      (when (store/exists? scenario)
        (let [prof (service/effective scenario)
              c    (contract/lift (store/read-imposter scenario) scenario (:service prof))]
          (store/write-contract! scenario c)
          c))))

(defn validate
  "`contract/validate` for a scenario's contract, plus, for a grpc contract, the
   checks that need its proto on disk. nil when the scenario has no contract."
  [scenario]
  (when-let [c (ensure-contract! scenario)]
    (let [result (contract/validate c)]
      (if (seq (contract/gene-errors c))
        result
        (update result :errors into
                (proto/contract-errors c (get (contract/expand c) "operations") (store/scenario-dir scenario)))))))

(defn address
  "Where a consumer reaches `scenario` on `port`: a URL for HTTP, and host:port for
   gRPC, which is the form gRPC clients take as a target."
  [scenario port]
  (if (proto/grpc? (store/read-contract scenario))
    (str "localhost:" port)
    (str "http://localhost:" port)))

(defn ensure-compiled!
  "Rebuild imposter.json from the contract (lifting one first for a legacy
   imposter-only scenario) and return it; nil when the scenario doesn't exist.
   Always recompiles, never trusts an existing imposter.json: a bundled, pulled or
   hand-edited artifact could carry injected JS that mocksys didn't generate, and
   the daemon runs injections with full Node access."
  [scenario]
  (when (ensure-contract! scenario)
    (compile! scenario)))

(defn ensure-running!
  "Make `scenario` live and return its port. Resolution order: explicit --port >
   the already-running port > a port *pinned* on the contract > a fresh free port.
   An explicit --port is persisted onto the contract so it stays pinned. Always
   recompiles from the contract and (re)posts with recordRequests=true so
   `requests`/`assert` have data."
  [scenario port-opt]
  (let [imposter  (or (ensure-compiled! scenario)
                      (throw (js/Error. (str "no scenario '" scenario "' in " store/root))))
        contract  (store/read-contract scenario)
        pinned    (get contract "port")
        stateful? (inject/imposter-stateful? imposter)]
    (when (and stateful? (let [v (.-MOCKSYS_NO_INJECTION js/process.env)] (and v (not= v ""))))
      (throw (js/Error. (str "'" scenario "' is a stateful mock (it runs generated injections), but "
                                "MOCKSYS_NO_INJECTION is set. Unset it to run stateful scenarios."))))
    (p/let [_        (mb/ensure-up!)
            live     (mb/list-imposters)
            existing (first (filter #(= (:name %) scenario) live))
            port     (cond
                       port-opt (js/parseInt port-opt)
                       existing (:port existing)
                       pinned   pinned
                       :else    (mb/free-port))]
      (mb/delete-imposter! port)
      (let [imp (assoc imposter "port" port "name" scenario "recordRequests" true)]
        (p/let [_ (mb/post-imposter! imp)]
          (when (and port-opt contract (not= port pinned))
            (store/write-contract! scenario (assoc contract "port" port)))
          port)))))

(defn watch-scenario!
  "Recompile + reload `scenario` whenever its contract.yaml changes (debounced).
   Keeps the process alive — the Tilt-style save-and-reload dev loop."
  [scenario]
  (let [timer (atom nil)]
    (println (str "  ⊙ watching contract.yaml — edits recompile + reload (Ctrl-C to stop)"))
    (store/watch-contract!
     scenario
     (fn []
       (when @timer (js/clearTimeout @timer))
       (reset! timer
               (js/setTimeout
                (fn []
                  (-> (ensure-running! scenario nil)
                      (p/then (fn [port] (println (str "  ↻ reloaded '" scenario "' @ http://localhost:" port))))
                      (p/catch (fn [e] (binding [*print-fn* *print-err-fn*]
                                         (println (str "  ✗ reload failed: " (.-message e))))))))
                150))))))

(defn scenario-port-pin!
  "The scenario's pinned port, allocating+persisting a free one if it has none.
   Used by emitters that must name a stable port without starting the mock."
  [scenario]
  (p/let [c      (store/read-contract scenario)
          pinned (get c "port")
          port   (or pinned (mb/free-port))]
    (when (and (not pinned) c)
      (store/write-contract! scenario (assoc c "port" port)))
    port))

