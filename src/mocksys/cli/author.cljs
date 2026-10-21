(ns mocksys.cli.author
  "Contract authoring: import, compile, new, validate, examples, use, add, fault,
  parameterize. Each edits contract.yaml and recompiles."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [mocksys.store :as store]
            [mocksys.service :as service]
            [mocksys.contract :as contract]
            [mocksys.openapi :as openapi]
            [mocksys.lifecycle :as lifecycle]
            [mocksys.cli.util :refer [die parse-kv parse-conditions]]))

(defn- gene-op-refusal [op-id gene]
  (str "operation '" op-id "' comes from gene '" gene "' and cannot be edited; change "
       "the gene's params under `genes:`, or author an operation with the same request "
       "(authored operations are served first)"))

(defn cmd-import
  "Seed scenarios from an OpenAPI/Swagger spec — one per operation, schema + named
   examples, plus a synthesized service profile:
     import openapi <spec> [--service NAME] [--target URL]"
  [{:keys [pos opts]}]
  (let [kind (first pos)
        spec (second pos)]
    (when-not (= kind "openapi")
      (die "import supports: import openapi <spec> [--service NAME] [--target URL]"))
    (when-not spec (die "import openapi needs a spec file: import openapi <spec.yaml|json|url>"))
    (p/let [{:keys [service template scenarios]}
            (openapi/parse-spec spec {:service (when (string? (:service opts)) (:service opts))
                                      :target  (when (string? (:target opts)) (:target opts))})]
      (when (empty? scenarios) (die (str "no operations found in " spec)))
      (store/write-service! service template)
      (doseq [{:keys [scenario contract]} scenarios]
        (store/write-contract! scenario contract)
        (lifecycle/compile! scenario))
      (println (str "⇣ imported " (count scenarios) " operation(s) into service '" service "'"))
      (when (:default_target template) (println (str "  target: " (:default_target template))))
      (when (seq (:redact_headers template))
        (println (str "  redacting: " (str/join ", " (:redact_headers template)))))
      (doseq [{:keys [scenario]} scenarios] (println (str "  · " scenario)))
      (println)
      (println (str "  run one:       mocksys run " (:scenario (first scenarios))))
      (println (str "  pick examples: mocksys examples " (:scenario (first scenarios)))))))

(defn cmd-compile
  "Rebuild imposter.json from contract.yaml (normally automatic on `run`):
     compile <scenario>"
  [{:keys [pos]}]
  (let [scenario (first pos)]
    (when-not scenario (die "compile needs a scenario: compile <name>"))
    (if (lifecycle/ensure-contract! scenario)
      (let [imposter (lifecycle/compile! scenario)]
        (println (str "⚙ compiled '" scenario "' — " (count (get imposter "stubs")) " stub(s)")))
      (die (str "no contract for '" scenario "' — `new`, `add`, `import`, or `record`+`freeze` it first")))))

(defn cmd-new
  "Scaffold a blank contract to hand-author after reading API docs/source:
     new <service/name> [--service S]"
  [{:keys [pos opts]}]
  (let [scenario (first pos)]
    (when-not scenario (die "new needs a scenario: new <service/name>"))
    (when (store/has-contract? scenario)
      (die (str "'" scenario "' already has a contract — edit it or `compile` it")))
    (let [svc (or (when (string? (:service opts)) (:service opts)) (service/service-of scenario))]
      (store/write-contract! scenario (contract/scaffold scenario svc))
      (lifecycle/compile! scenario)
      (println (str "✚ scaffolded '" scenario "'"))
      (println (str "  edit:    " store/root "/" scenario "/contract.yaml"))
      (println (str "  then:    mocksys validate " scenario "  &&  mocksys run " scenario)))))

(defn cmd-validate
  "Structurally check a contract and flag examples that diverge from their schema:
     validate <scenario>   (exit 1 on errors)"
  [{:keys [pos]}]
  (let [scenario (first pos)]
    (when-not scenario (die "validate needs a scenario: validate <name>"))
    (let [c (or (lifecycle/ensure-contract! scenario) (die (str "no contract for '" scenario "'")))
          {:keys [errors warnings]} (contract/validate c)]
      (println (str "# validate: " scenario))
      (doseq [w warnings] (println (str "  ⚠ " w)))
      (doseq [e errors]   (println (str "  ✗ " e)))
      (when (and (empty? errors) (empty? warnings))
        (println (str "  ✓ valid — " (count (get (contract/expand c) "operations")) " operation(s)")))
      (when (seq errors)
        (binding [*print-fn* *print-err-fn*] (println (str (count errors) " error(s)")))
        (js/process.exit 1)))))

(defn cmd-examples
  "List a contract's operations, responses and which examples are in play:
     examples <scenario> [--op ID]"
  [{:keys [pos opts]}]
  (let [scenario (first pos)]
    (when-not scenario (die "examples needs a scenario: examples <name> [--op ID]"))
    (let [c  (or (lifecycle/ensure-contract! scenario) (die (str "no contract for '" scenario "'")))
          op (when (string? (:op opts)) (:op opts))
          rows (contract/examples-overview c op)]
      (println (str "# examples: " scenario))
      (doseq [{:keys [id gene method path responses]} rows]
        (println (str "  " id "  (" method " " path ")" (when gene (str "  [gene " gene "]"))))
        (doseq [{:keys [status schema? examples]} responses]
          (println (str "    " status (when schema? "  [schema]")))
          (doseq [{:keys [name select]} examples]
            (println (str "      " (if select "[x]" "[ ]") " " name)))))
      (println)
      (println (str "  toggle:  mocksys use " scenario " --op ID --example NAME [--only|--off]")))))

(defn cmd-use
  "Choose which example(s) are in play for an operation (selected examples become
   the mock's responses; `--only` swaps, e.g. happy -> error):
     use <scenario> --op ID --example NAME [--off] [--only]"
  [{:keys [pos opts]}]
  (let [scenario (first pos)
        op       (:op opts)
        ex       (:example opts)]
    (when-not scenario (die "use needs a scenario: use <name> --op ID --example NAME"))
    (when-not (string? op)  (die "use needs --op ID  (see `examples <name>`)"))
    (when-not (string? ex)  (die "use needs --example NAME  (see `examples <name>`)"))
    (let [c   (or (lifecycle/ensure-contract! scenario) (die (str "no contract for '" scenario "'")))
          ids (map #(get % "id") (get c "operations"))]
      (when-not (some #{op} ids)
        (when-let [gene (contract/gene-of c op)]
          (die (gene-op-refusal op gene)))
        (die (str "no operation '" op "' in '" scenario "'. known: " (str/join ", " ids))))
      (when (empty? (contract/example-locations c op ex))
        (die (str "no example '" ex "' on operation '" op "' — see `examples " scenario "`")))
      (let [on?  (not (:off opts))
            c'   (contract/set-selection c op ex on? (boolean (:only opts)))]
        (store/write-contract! scenario c')
        (lifecycle/compile! scenario)
        (println (str (if on? "✓ selected" "✓ deselected") " " ex " on " op
                      (when (:only opts) " (only)") " in '" scenario "'"))))))

;; --- authoring: add / fault / parameterize (all edit the contract) --------

(defn- body-value
  "Resolve a response body from --body FILE (parsed as JSON when possible) or
   --text STRING. Returns [value json?] or [nil nil]."
  [opts]
  (cond
    (:body opts) (let [raw (store/slurp (:body opts))]
                   (try [(js->clj (js/JSON.parse raw)) true]
                        (catch :default _ [raw false])))
    (:text opts) [(:text opts) false]
    :else        [nil nil]))

(defn cmd-add
  "Hand-author an endpoint (appends an operation to the contract, then compiles):
     add <scenario> --request 'METHOD /path' [--status N] [--body FILE | --text STR]
       [--header 'K=V,K2=V2']         response headers (e.g. a redirect Location)
       [--when-query 'k=v,present']   only match when these query conditions hold
       [--when-header 'k=v,present']  only match when these header conditions hold
   Request conditions let several operations share a path and branch on content;
   order operations specific-first (mountebank serves the first matching stub)."
  [{:keys [pos opts]}]
  (let [scenario (first pos)
        request  (:request opts)]
    (when-not scenario (die "add needs a scenario: add <name> --request 'METHOD /path'"))
    (when-not (string? request) (die "add needs --request 'METHOD /path'"))
    (let [[method path] (str/split (str/trim request) #"\s+" 2)
          status        (js/parseInt (or (:status opts) 200))
          [body json?]  (body-value opts)
          extras        {:resp-headers (parse-kv (when (string? (:header opts)) (:header opts)))
                         :query        (parse-conditions (when (string? (:when-query opts)) (:when-query opts)))
                         :headers      (parse-conditions (when (string? (:when-header opts)) (:when-header opts)))}
          existing      (lifecycle/ensure-contract! scenario)
          svc           (service/service-of scenario)
          c             (contract/add-operation existing scenario svc method path status body json? extras)]
      (store/write-contract! scenario c)
      (let [imposter (lifecycle/compile! scenario)]
        (println (str "✚ added " (str/upper-case method) " " path " -> " status
                      (when (seq (:query extras))   (str "  [?" (str/join "," (keys (:query extras))) "]"))
                      (when (seq (:headers extras)) (str "  [hdr " (str/join "," (keys (:headers extras))) "]"))
                      " to '" scenario "'  (" (count (get imposter "stubs")) " operation(s))"))))))

(defn cmd-fault
  "Layer a transport fault onto a scenario, stored on the contract so it survives
   recompiles. --clear removes it:
     fault <scenario> [--status N] [--latency MS] [--timeout] [--drop-connection] [--malformed-json] [--clear]"
  [{:keys [pos opts]}]
  (let [scenario (first pos)
        knobs    (select-keys opts [:status :latency :timeout :drop-connection :malformed-json])]
    (when-not scenario (die "fault needs a scenario: fault <name> --status N | --latency MS | --clear"))
    (when (and (empty? knobs) (not (:clear opts)))
      (die "fault needs one of --status --latency --timeout --drop-connection --malformed-json (or --clear)"))
    (let [c     (or (lifecycle/ensure-contract! scenario) (die (str "no contract for '" scenario "'")))
          fault (if (:clear opts)
                  {}
                  (merge (get c "fault") (into {} (map (fn [[k v]] [(name k) v]) knobs))))
          c'    (assoc c "fault" fault)]
      (store/write-contract! scenario c')
      (lifecycle/compile! scenario)
      (if (:clear opts)
        (println (str "cleared fault on '" scenario "'"))
        (println (str "faulted '" scenario "' - " (str/join ", " (map name (keys knobs))) " (all operations)"))))))

(defn cmd-parameterize
  "Loosen an operation's exact path to a {param} template so it serves any id; the
   contract compiles templated paths to regex predicates automatically:
     parameterize <scenario> --path '/repos/{owner}/{repo}/issues'"
  [{:keys [pos opts]}]
  (let [scenario (first pos)
        tmpl     (:path opts)]
    (when-not scenario (die "parameterize needs a scenario: parameterize <name> --path '/a/{x}/b'"))
    (when-not (string? tmpl) (die "parameterize needs --path '/template/{with}/{vars}'"))
    (let [c (or (lifecycle/ensure-contract! scenario) (die (str "no contract for '" scenario "'")))
          {:keys [contract changed refused]} (contract/parameterize c tmpl)]
      (cond
        refused
        (die (gene-op-refusal (:op refused) (:gene refused)))
        (zero? changed)
        (println (str "no operations matched " tmpl " - nothing parameterized"))
        :else
        (do (store/write-contract! scenario contract)
            (lifecycle/compile! scenario)
            (println (str "parameterized " changed " operation(s) in '" scenario "' to " tmpl)))))))

