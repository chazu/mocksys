(ns mocksys.contract
  "The contract is mocksys' canonical source of truth. A scenario's contract.yaml
  describes its operations in agent vocabulary — method/path, the formal request &
  response *schemas*, and a set of *named, individually selectable examples*. The
  Mountebank imposter.json is a build artifact: `lower` compiles a contract down to
  it, `lift` reverse-engineers a contract from a recorded imposter.

  Shape (string keys throughout, so embedded JSON Schemas / bodies round-trip):

    {\"service\" \"github\" \"scenario\" \"github/create-issue\" \"origin\" \"openapi\"
     \"fault\" {}                                  ; optional transport fault overlay
     \"operations\"
     [{\"id\" \"createIssue\" \"summary\" \"...\"
       \"request\"  {\"method\" \"POST\" \"path\" \"/repos/{owner}/{repo}/issues\"
                   \"match\" [\"method\" \"path\"] \"bodySchema\" {...}}
       \"responses\" [{\"status\" 201 \"headers\" {...} \"bodySchema\" {...}
                    \"examples\" {\"happy\" {\"select\" true \"body\" {...}}}}]}]}

  Selected examples become the stub's responses (Mountebank round-robins a
  multi-response stub), so authoring an unhappy path is just selecting an error
  example instead of the happy one."
  (:require [clojure.string :as str]
            [mocksys.inject :as inject]
            [mocksys.kits :as kits]
            ["openapi-sampler" :as sampler]))

;; --- path templating ------------------------------------------------------

(defn templated?
  "A path carrying `{param}` segments (OpenAPI style)."
  [path]
  (and (string? path) (str/includes? path "{")))

(defn path->regex
  "`/repos/{owner}/{repo}/issues` -> ^/repos/[^/]+/[^/]+/issues$  (each {param}
   matches one path segment). Literal chars are escaped."
  [t]
  (let [sentinel "\u0000"]
    (str "^" (-> t
                 (str/replace #"\{[^}]+\}" sentinel)
                 (str/replace #"[.+?^${}()|\[\]\\]" "\\\\$&")
                 (str/replace sentinel "[^/]+"))
         "$")))

(defn slug
  "A filesystem/url-safe token from arbitrary text."
  [s]
  (-> (str s)
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

;; --- genes: the authored `genes:` list -> operations ------------------------

(defn- gene-entry-errors
  "Problems with the `i`th `genes:` entry, as messages."
  [i entry]
  (if-not (and (map? entry) (= 1 (count entry)))
    [(str "genes[" i "]: expected a one-key map {gene-name: {params}}, got " (pr-str entry))]
    (let [[gname ps] (first entry)
          gene       (get kits/genes gname)
          at         (str "genes[" i "] " gname)]
      (cond
        (nil? gene)
        [(str at ": unknown gene '" gname "' (known: " (str/join ", " (sort (keys kits/genes))) ")")]
        (not (or (nil? ps) (map? ps)))
        [(str at ": params must be a map, got " (pr-str ps))]
        :else
        (let [declared (map first (:params gene))
              given    (set (map keyword (keys ps)))]
          (concat
           (for [k (sort given) :when (not (some #{k} declared))]
             (str at ": unknown param '" (name k) "' (params: " (str/join ", " (map name declared)) ")"))
           (for [k (sort (:required gene)) :when (not (given k))]
             (str at ": missing required param '" (name k) "'"))))))))

(defn gene-errors
  "Every problem with a contract's `genes:` list (unknown gene, unknown or missing
   param, malformed entry), as messages. Empty when there are none."
  [contract]
  (let [gs (get contract "genes")]
    (cond
      (nil? gs)             []
      (not (sequential? gs)) ["genes: expected a list of {gene-name: {params}} entries"]
      :else                  (vec (mapcat gene-entry-errors (range) gs)))))

(defn- gene-fragment
  "Build one valid `genes:` entry, tagging each operation with the gene's name."
  [entry]
  (let [[gname ps] (first entry)
        frag       ((:build (get kits/genes gname)) (update-keys (or ps {}) keyword))]
    (update frag :operations (fn [ops] (mapv #(assoc % "gene" gname) ops)))))

(defn expand
  "The effective contract: `genes:` expanded into operations appended after the
   authored ones, so an authored operation shadows a gene operation on the same
   request (first match wins). Gene collections and seed merge in, authored ones
   winning. Pure and idempotent; a contract without `genes:` comes back unchanged.
   Throws on an unknown gene or param."
  [contract]
  (if-not (contains? contract "genes")
    contract
    (let [errs (gene-errors contract)]
      (when (seq errs) (throw (js/Error. (str/join "; " errs))))
      (apply kits/compose (dissoc contract "genes") (map gene-fragment (get contract "genes"))))))

(defn gene-of
  "The gene contributing operation `op-id`, or nil when that id is authored (an
   authored operation wins the id) or unknown."
  [contract op-id]
  (get (first (filter #(= op-id (get % "id")) (get (expand contract) "operations"))) "gene"))

;; --- lower: contract -> imposter ------------------------------------------

(defn- path-predicate [path]
  (if (templated? path)
    {"matches" {"path" (path->regex path)}}
    {"deepEquals" {"path" path}}))

(defn- as-cond
  "Normalize a predicate condition: a bare scalar means `equals`; a map is taken
   verbatim. Supported operator keys: present | equals | matches."
  [c]
  (if (map? c) c {"equals" c}))

(defn- field-predicates
  "Compile a `{field condition}` map into Mountebank predicates scoped to
   `selector` (\"query\" or \"headers\"). condition is `{present bool}`,
   `{equals v}`, `{matches regex}`, or a bare scalar (= equals)."
  [selector spec]
  (for [[field c] spec
        :let [c (as-cond c)]]
    (cond
      (contains? c "present") {"exists"  {selector {field (boolean (get c "present"))}}}
      (contains? c "matches") {"matches" {selector {field (get c "matches")}}}
      :else                   {"equals"  {selector {field (get c "equals")}}})))

(def ^:private plain-jsonpath
  #"^\$(?:\.[A-Za-z0-9_-]+|\.\*|\[\d+\]|\[\*\]|\['[A-Za-z0-9_ .-]+'\])*$")

(defn- jsonpath!
  "A JSONPath selector taken from the contract, if it is a plain path ($.a.b[0],
   $['k'], [*]). Mountebank evaluates selectors with jsonpath-plus, whose filter and
   script expressions run code, so nothing richer may reach it from a contract."
  [selector]
  (if (and (string? selector) (re-matches plain-jsonpath selector))
    selector
    (throw (js/Error. (str "unsupported JSONPath selector " (pr-str selector)
                           ": use a plain path such as $.user.id or $.items[0]")))))

(defn- body-predicates
  "Compile a `{jsonpath-selector condition}` map into Mountebank JSONPath body
   predicates (for JSON request bodies). Same condition vocabulary as above."
  [spec]
  (for [[selector c] spec
        :let [c        (as-cond c)
              selector (jsonpath! selector)]]
    (cond
      (contains? c "present") {"exists"  {"body" (boolean (get c "present"))} "jsonpath" {"selector" selector}}
      (contains? c "matches") {"matches" {"body" (get c "matches")}           "jsonpath" {"selector" selector}}
      :else                   {"equals"  {"body" (get c "equals")}            "jsonpath" {"selector" selector}})))

(defn- predicates-for
  "Build the Mountebank predicate array for an operation's request. `match`
   selects which of method/path are matched (default both); `query`/`headers`/`body`
   add request-conditional predicates so multiple operations can share a path and
   branch on request content (mountebank ANDs predicates; stub order = contract
   order, first match wins)."
  [{:strs [method path match query headers body]}]
  (let [fields (set (or (seq match) ["method" "path"]))]
    (-> (cond-> []
          (contains? fields "method") (conj {"deepEquals" {"method" (str/upper-case method)}})
          (contains? fields "path")   (conj (path-predicate path)))
        (into (field-predicates "query" query))
        (into (field-predicates "headers" headers))
        (into (body-predicates body)))))

(defn- sample-body
  "A minimal valid body sampled from a JSON Schema (gap-fill when no example is
   selected). nil when there's no schema."
  [schema]
  (when schema
    (try (js->clj (sampler/sample (clj->js schema)))
         (catch :default _ nil))))

(defn- is-response
  "Build an `{is {...}}` Mountebank response from a status, headers and body."
  [status headers body]
  {"is" (cond-> {"statusCode" status}
          (seq headers)  (assoc "headers" headers)
          (some? body)   (assoc "body" body))})

(defn- selected-examples
  "All `select:true` examples across an operation's responses, in declared order,
   as [{:status :headers :body}]. Example bodies win over schema."
  [responses]
  (for [r responses
        [_ ex] (get r "examples")
        :when (get ex "select")]
    {:status  (get r "status")
     :headers (or (get ex "headers") (get r "headers"))
     :body    (get ex "body")}))

(defn- operation-responses
  "Mountebank `responses` array for one operation: every selected example, or — if
   none are selected — one sampled from the first response's schema."
  [{:strs [responses]}]
  (let [chosen (selected-examples responses)]
    (if (seq chosen)
      (mapv (fn [{:keys [status headers body]}] (is-response status headers body)) chosen)
      (let [r (first responses)]
        [(is-response (get r "status") (get r "headers") (sample-body (get r "bodySchema")))]))))

(defn- apply-fault
  "Overlay a transport fault onto a single `{is ...}` response. Faults express the
   small vocabulary from `fault`: status override, latency/timeout (wait behavior),
   dropped connection, malformed bytes."
  [resp fault]
  (cond
    (empty? fault) resp
    (get fault "drop-connection") {"fault" "CONNECTION_RESET_BY_PEER"}
    (get fault "malformed-json")  {"fault" "RANDOM_DATA_THEN_CLOSE"}
    :else
    (let [is   (cond-> (get resp "is" {})
                 (get fault "status") (assoc "statusCode" (js/parseInt (get fault "status"))))
          wait (cond (get fault "timeout") 120000
                     (get fault "latency") (js/parseInt (get fault "latency")))]
      (cond-> (assoc resp "is" is)
        ;; append, don't clobber — copy behaviors from templating may already be here
        wait (update "behaviors" (fnil conj []) {"wait" wait})))))

;; --- response templating: {{request...}} -> Mountebank copy behaviors ----------
;; A response body/header may reference the *incoming request* with
;; {{request.query.X}} / {{request.headers.X}} / {{request.body.X}} / {{request.path}}
;; / {{request.method}}. Each becomes a Mountebank `copy` behavior that splices the
;; live request value into the response at serve time — dynamic responses with no
;; injection (e.g. echo `state` back on a redirect Location).

(def ^:private template-re
  #"\{\{\s*request\.(query|headers|body|path|method)(?:\.([^}\s]+))?\s*\}\}")

(defn- token-for [source field]
  (str "${" source (when (seq field) (str "_" (str/replace field #"[^A-Za-z0-9]+" "_"))) "}"))

(defn- copy-for [source field token]
  (let [using (if (= source "body")
                {"method" "jsonpath" "selector" (jsonpath! (str "$." field))}
                {"method" "regex" "selector" ".+"})
        from  (case source
                "query"   {"query" field}
                "headers" {"headers" field}
                source)]                       ; "path" | "method" | "body"
    {"copy" {"from" from "into" token "using" using}}))

(defn- walk-templates
  "Replace {{request...}} tokens in every string within `x`, recording the matching
   copy behavior into atom `acc` (keyed by token, so repeats dedup). Returns `x`
   with each token literal swapped for its Mountebank ${...} replacement token."
  [x acc]
  (cond
    (string? x)
    (str/replace x template-re
                 (fn [m]
                   (let [[_ source field] m
                         token (token-for source field)]
                     (swap! acc assoc token (copy-for source field token))
                     token)))
    (map? x)    (into {} (map (fn [[k v]] [k (walk-templates v acc)])) x)
    (vector? x) (mapv #(walk-templates % acc) x)
    :else x))

(defn- templatize
  "Lift {{request...}} templates out of an `{is {...}}` response into copy behaviors."
  [resp]
  (let [acc (atom {})
        is' (walk-templates (get resp "is") acc)
        copies (vals @acc)]
    (cond-> (assoc resp "is" is')
      (seq copies) (update "behaviors" (fnil into []) copies))))

(defn lower
  "Compile a contract into a Mountebank imposter. `port` is carried through if a
   prior artifact had one (ensure-running! reassigns at run time regardless)."
  ([contract] (lower contract nil))
  ([contract port]
   (let [contract (expand contract)
         fault (get contract "fault")
         seed  (get contract "seed")
         ;; collection declarations ({name {idField, seed}}) — resolve each `seed`
         ;; name to its actual array so the injection can embed + seed it once.
         colls (into {} (for [[name spec] (get-in contract ["state" "collections"])]
                          [name {"idField" (get spec "idField")
                                 "seed"    (get seed (get spec "seed"))}]))
         clock?      (inject/contract-uses-clock? contract)
         clock-start (when clock? (inject/clock-start contract))
         issuer      (get contract "issuer")
         stubs (mapv (fn [op]
                       (try
                         {"predicates" (predicates-for (get op "request"))
                          "responses"
                          (if (inject/stateful? op)
                            ;; stateful op: a generated injection over the shared state
                            [{"inject" (inject/gen op {:seed seed :collections colls
                                                       :clock-start clock-start :issuer issuer
                                                       :random-seed (get-in contract ["state" "randomSeed"])})}]
                            (mapv #(-> % templatize (apply-fault fault))
                                  (operation-responses op)))}
                         (catch :default e
                           (throw (js/Error. (str (get op "id" "<no id>") ": " (.-message e)))))))
                     (get contract "operations"))
         ;; a twin that uses a virtual clock gets a built-in /_mocksys/clock control op
         stubs (cond-> stubs
                 clock? (conj (inject/clock-control-stub clock-start)))]
     (cond-> {"protocol" "http" "stubs" stubs "name" (get contract "scenario")}
       port (assoc "port" port)))))

;; --- lift: recorded imposter -> contract ----------------------------------

(defn- pred-field [stub field]
  (some #(some (fn [op-map] (get op-map field)) (vals %)) (get stub "predicates")))

(defn- stub->operation [stub i]
  (let [method   (or (pred-field stub "method") "GET")
        path     (or (pred-field stub "path") "/")
        resps    (get stub "responses")
        single?  (= 1 (count resps))]
    {"id"      (slug (str method "-" path "-" i))
     "summary" (str method " " path)
     "request" {"method" (str/upper-case method)
                "path"   path
                "match"  ["method" "path"]}
     "responses"
     (->> resps
          (map-indexed
           (fn [j r]
             (let [is (get r "is" {})
                   nm (if single? "recorded" (str "recorded-" (inc j)))]
               (cond-> {"status"   (get is "statusCode" 200)
                        "examples" {nm (cond-> {"select" true}
                                         (some? (get is "body")) (assoc "body" (get is "body")))}}
                 (seq (get is "headers")) (assoc "headers" (get is "headers"))))))
          vec)}))

(defn lift
  "Reverse-engineer a contract from a (scrubbed) imposter — used by `freeze` and the
   lazy migration of legacy imposter-only scenarios."
  [imposter scenario service]
  {"service"    service
   "scenario"   scenario
   "origin"     "recorded"
   "operations" (vec (map-indexed #(stub->operation %2 %1) (get imposter "stubs")))})

;; --- scaffold: empty contract for hand-authoring --------------------------

(defn scaffold [scenario service]
  {"service"    service
   "scenario"   scenario
   "origin"     "authored"
   "operations"
   [{"id"      "example"
     "summary" "Describe this endpoint"
     "request" {"method" "GET" "path" "/example" "match" ["method" "path"]}
     "responses"
     [{"status"   200
       "headers"  {"Content-Type" "application/json"}
       "examples" {"happy" {"select" true "body" {"ok" true}}}}]}]})

;; --- authoring helpers (used by `add`) ------------------------------------

(defn add-operation
  "Append a hand-authored operation to a contract (creating a fresh contract when
   `contract` is nil). `extras` may carry `:resp-headers` (a map merged into the
   response headers — e.g. a redirect's Location) and `:query`/`:headers`
   (request-match condition maps so the operation branches on request content)."
  ([contract scenario service method path status body json?]
   (add-operation contract scenario service method path status body json? nil))
  ([contract scenario service method path status body json? extras]
   (let [{:keys [resp-headers query headers]} extras
         base (or contract {"service" service "scenario" scenario "origin" "authored"
                            "operations" []})
         ctype (when (some? body) {"Content-Type" (if json? "application/json" "text/plain")})
         resp-hdrs (merge ctype resp-headers)
         taken (set (map #(get % "id") (get (expand base) "operations")))
         base-id (slug (str method "-" path))
         id    (if (contains? taken base-id)
                 (first (remove taken (map #(str base-id "-" %) (iterate inc 2))))
                 base-id)
         op   {"id"      id
               "summary" (str (str/upper-case method) " " path)
               "request" (cond-> {"method" (str/upper-case method) "path" path "match" ["method" "path"]}
                           (seq query)   (assoc "query" query)
                           (seq headers) (assoc "headers" headers))
               "responses"
               [(cond-> {"status" status
                         "examples" {"default" (cond-> {"select" true}
                                                 (some? body) (assoc "body" body))}}
                  (seq resp-hdrs) (assoc "headers" resp-hdrs))]}]
     (update base "operations" (fnil conj []) op))))

;; --- example selection (used by `use`) ------------------------------------

(defn- op-by-id [contract op-id]
  (some (fn [op] (when (= op-id (get op "id")) op)) (get contract "operations")))

(defn example-locations
  "Every [response-index example-name] pair where `ex-name` appears in operation
   `op-id`. Used to detect ambiguity across status codes."
  [contract op-id ex-name]
  (let [op (op-by-id contract op-id)]
    (for [[ri r] (map-indexed vector (get op "responses"))
          :when (contains? (get r "examples") ex-name)]
      [ri ex-name])))

(defn set-selection
  "Return contract with example `ex-name` of operation `op-id` set to `on?`. With
   `only?`, first clear every example in that operation (the happy<->error swap)."
  [contract op-id ex-name on? only?]
  (update contract "operations"
          (fn [ops]
            (mapv (fn [op]
                    (if (= op-id (get op "id"))
                      (update op "responses"
                              (fn [responses]
                                (mapv (fn [r]
                                        (update r "examples"
                                                (fn [exs]
                                                  (into {}
                                                        (map (fn [[nm ex]]
                                                               [nm (cond
                                                                     (= nm ex-name) (assoc ex "select" on?)
                                                                     only?          (assoc ex "select" false)
                                                                     :else          ex)])
                                                             exs)))))
                                      responses)))
                      op))
                  ops))))

(defn examples-overview
  "Agent-readable view of a contract's operations -> responses -> examples."
  [contract op-filter]
  (for [op (get (expand contract) "operations")
        :when (or (nil? op-filter) (= op-filter (get op "id")))]
    {:id     (get op "id")
     :gene   (get op "gene")
     :method (get-in op ["request" "method"])
     :path   (get-in op ["request" "path"])
     :responses
     (for [r (get op "responses")]
       {:status   (get r "status")
        :schema?  (some? (get r "bodySchema"))
        :examples (for [[nm ex] (get r "examples")]
                    {:name nm :select (boolean (get ex "select"))})})}))

;; --- validation -----------------------------------------------------------

(defn- type-ok? [t v]
  (case t
    "object"  (map? v)
    "array"   (vector? v)
    "string"  (string? v)
    "integer" (number? v)
    "number"  (number? v)
    "boolean" (boolean? v)
    "null"    (nil? v)
    true))

(defn- kind
  "A readable type name for an example value (for validation messages)."
  [v]
  (cond (map? v) "object" (vector? v) "array" (string? v) "string"
        (number? v) "number" (boolean? v) "boolean" (nil? v) "null" :else "value"))

(defn- schema-violations
  "Lightweight, ajv-free conformance: top-level type, required object props, and a
   one-level recurse into properties/items. Enough to flag example<->schema drift."
  [schema value path]
  (let [t (get schema "type")]
    (cond
      (nil? schema) []
      (and t (not (type-ok? t value)))
      [(str path ": expected " t ", got " (kind value))]
      (= t "object")
      (concat
       (for [req (get schema "required") :when (not (contains? value req))]
         (str path "." req ": required by schema but missing from example"))
       (mapcat (fn [[k subschema]]
                 (when (contains? value k)
                   (schema-violations subschema (get value k) (str path "." k))))
               (get schema "properties")))
      (and (= t "array") (vector? value) (get schema "items"))
      (mapcat #(schema-violations (get schema "items") % (str path "[]")) value)
      :else [])))

(defn- validate-expanded
  [contract]
  (let [ops (get contract "operations")
        errs (atom []) warns (atom [])
        err! #(swap! errs conj %) warn! #(swap! warns conj %)]
    (when (empty? ops) (err! "contract has no operations"))
    (doseq [op ops]
      (let [id  (or (get op "id") "<no id>")
            req (get op "request")
            m   (get req "method") p (get req "path")
            effect (get op "effect")
            responses (get op "responses")]
        (when-not (string? m) (err! (str id ": request.method missing")))
        (when-not (and (string? p) (str/starts-with? p "/"))
          (err! (str id ": request.path missing or not absolute")))
        (when (and effect
                   (not (get effect "respond"))
                   (not (some #(get effect %)
                              ["create" "get" "update" "remove" "list"
                               "link" "unlink" "listJoin" "render"])))
          (err! (str id ": effect has no `respond` or collection action "
                     "(create/get/update/remove/list/link/unlink/listJoin/render)")))
        (try (lower (assoc contract "operations" [op]))
             (catch :default e (err! (.-message e))))
        (when (and (not effect) (empty? responses)) (err! (str id ": no responses")))
        (doseq [r responses]
          (when-not (number? (get r "status"))
            (err! (str id ": response.status must be a number"))))
        (let [any-selected? (seq (selected-examples responses))
              any-schema?   (some #(get % "bodySchema") responses)]
          (when-not (or effect any-selected? any-schema?)
            (err! (str id ": no selected example and no bodySchema — nothing to serve"))))
        ;; advisory: selected examples vs their response schema
        (doseq [r responses
                [nm ex] (get r "examples")
                :when (and (get ex "select") (get r "bodySchema") (contains? ex "body"))
                v (schema-violations (get r "bodySchema") (get ex "body") (str id "/" nm))]
          (warn! v))))
    {:errors @errs :warnings @warns}))

(defn validate
  "Returns {:errors [...] :warnings [...]}. Errors block compile; warnings (e.g. an
   example diverging from its schema) are advisory. Checks `genes:` first, then the
   expanded operations."
  [contract]
  (let [gerrs (gene-errors contract)]
    (if (seq gerrs)
      {:errors gerrs :warnings []}
      (validate-expanded (expand contract)))))
