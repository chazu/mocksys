(ns mocksys.openapi
  "Import an OpenAPI/Swagger spec into mocksys contracts — one scenario per
  operation. The formal schemas stay primary; spec-provided examples are imported
  *by name* (and a schema-sampled `generated` example fills gaps), so an agent can
  later pick which are in play with `use`.

  `parse-spec` does no disk I/O: it returns {:service :template :scenarios [...]} and
  lets core own the writing/compilation, keeping store layout in one place."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [mocksys.contract :as contract]
            ["openapi-sampler" :as sampler]
            ["@apidevtools/swagger-parser$default" :as SwaggerParser]))

(def ^:private http-methods #{"get" "put" "post" "delete" "patch" "head" "options"})

(defn- sample [schema]
  (when schema
    (try (js->clj (sampler/sample (clj->js schema)))
         (catch :default _ nil))))

;; --- target / security from the spec --------------------------------------

(defn- server-url [spec]
  (or (get-in spec ["servers" 0 "url"])                    ; OpenAPI 3
      (when-let [host (get spec "host")]                   ; Swagger 2
        (str (or (first (get spec "schemes")) "https") "://" host (get spec "basePath" "")))))

(defn- redact-headers [spec]
  (let [schemes (vals (or (get-in spec ["components" "securitySchemes"]) ; v3
                          (get spec "securityDefinitions")))]            ; v2
    (->> schemes
         (keep (fn [s]
                 (cond
                   (and (= "apiKey" (get s "type")) (= "header" (get s "in"))) (get s "name")
                   (#{"http" "oauth2" "openIdConnect"} (get s "type"))         "authorization")))
         (cons "authorization")
         (map str/lower-case)
         distinct vec)))

(defn- service-template [service spec target]
  {:service          service
   :protocol         "http"
   :default_target   target
   :base_url_env     [(str (str/upper-case (str/replace (contract/slug service) "-" "_")) "_API_URL")]
   :redact_headers   (redact-headers spec)
   :volatile_headers []})

;; --- operations -> contract -----------------------------------------------

(defn- pick-content
  "Choose a response/request media type, preferring JSON."
  [content]
  (when (seq content)
    (or (find content "application/json")
        (->> content (filter #(str/includes? (key %) "json")) first)
        (first content))))

(defn- examples-for
  "Named examples for one response media type: the spec's `examples` map, else its
   single `example`, else one `generated` from the schema. All start unselected."
  [media schema]
  (let [named (get media "examples")]
    (cond
      (seq named) (into {} (map (fn [[nm ex]] [nm {"select" false "body" (get ex "value")}]) named))
      (contains? media "example") {"default" {"select" false "body" (get media "example")}}
      schema      {"generated" {"select" false "body" (sample schema)}}
      :else       {})))

(defn- response->map [code resp]
  (let [status (js/parseInt code)
        [ct media] (pick-content (get resp "content"))
        schema (get media "schema")]
    (when-not (js/isNaN status)
      (cond-> {"status" status "examples" (examples-for media schema)}
        schema (assoc "bodySchema" schema)
        ct     (assoc "headers" {"Content-Type" ct})))))

(defn- mark-default-selected
  "Pick the default in-play example: the first example of the lowest 2xx response
   that has one (else the first response with any example)."
  [responses]
  (let [has-ex?  #(seq (get % "examples"))
        ok2xx?   #(<= 200 (get % "status") 299)
        target   (or (->> responses (filter #(and (ok2xx? %) (has-ex? %))) (sort-by #(get % "status")) first)
                     (->> responses (filter has-ex?) first))]
    (if-not target
      responses
      (let [ex-name (ffirst (get target "examples"))]
        (mapv (fn [r]
                (if (identical? r target)
                  (update r "examples" (fn [exs]
                                         (into {} (map (fn [[nm ex]]
                                                         [nm (assoc ex "select" (= nm ex-name))]) exs))))
                  r))
              responses)))))

(defn- request-body-schema [op]
  (let [[_ media] (pick-content (get-in op ["requestBody" "content"]))]
    (get media "schema")))

(defn- operation->op [method path op]
  (let [op-id (or (get op "operationId") (str method "-" path))]
    {:scenario-slug (contract/slug op-id)
     :op {"id"      (contract/slug op-id)
          "summary" (or (get op "summary") (get op "description") (str (str/upper-case method) " " path))
          "request" (cond-> {"method" (str/upper-case method) "path" path "match" ["method" "path"]}
                      (request-body-schema op) (assoc "bodySchema" (request-body-schema op)))
          "responses" (->> (get op "responses")
                           (keep (fn [[code resp]] (response->map code resp)))
                           vec
                           mark-default-selected)}}))

(defn- spec->scenarios [spec service]
  (for [[path path-item] (get spec "paths")
        [method op] path-item
        :when (and (http-methods method) (map? op))]
    (let [{:keys [scenario-slug op]} (operation->op method path op)
          scenario (str service "/" scenario-slug)]
      {:scenario scenario
       :contract {"service" service "scenario" scenario "origin" "openapi" "operations" [op]}})))

(defn parse-spec
  "Dereference `spec-file` and return {:service :template :scenarios [{:scenario :contract}]}.
   opts: {:service NAME :target URL}."
  [spec-file {:keys [service target]}]
  (p/let [api (.dereference SwaggerParser spec-file)]
    (let [spec (try (js->clj api)
                    (catch :default _
                      (throw (js/Error. (str "could not read '" spec-file
                                             "' — it may contain circular $ref schemas (unsupported)")))) )
          svc (contract/slug (or service (get-in spec ["info" "title"]) "api"))
          tgt (or target (server-url spec))]
      {:service   svc
       :template  (service-template svc spec tgt)
       :scenarios (vec (spec->scenarios spec svc))})))
