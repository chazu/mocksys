(ns mocksys.service
  "Service templates: opinionated starting points for common external systems.

  A template does NOT model an API. It only knows how to safely record, scrub and
  hand off traffic for that service: which target to default to, which headers
  carry secrets, which env var the consumer reads for the base URL.

  `effective` composes a template with the base sets in mocksys.analyze, so a
  scenario under a service inherits both. Scenarios are named `service/name`, so
  the service is just the first path segment of the scenario id."
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [mocksys.analyze :as analyze]
            [mocksys.store :as store]))

(def templates
  {"github"
   {:service       "github"
    :protocol      "http"
    :default_target "https://api.github.com"
    :base_url_env  ["GITHUB_API_URL" "GH_HOST"]
    :redact_headers   ["authorization" "x-github-token"]
    :volatile_headers ["x-github-request-id" "x-ratelimit-reset" "x-ratelimit-remaining"]}

   "gitlab"
   {:service       "gitlab"
    :protocol      "http"
    :default_target "https://gitlab.com/api/v4"
    :base_url_env  ["GITLAB_API_URL" "CI_API_V4_URL"]
    :redact_headers   ["authorization" "private-token" "job-token"]
    :volatile_headers ["x-request-id" "ratelimit-reset"]}

   "aws-s3"
   {:service       "aws-s3"
    :protocol      "http"
    :default_target "https://s3.amazonaws.com"
    :base_url_env  ["AWS_S3_ENDPOINT" "AWS_ENDPOINT_URL"]
    :redact_headers   ["authorization" "x-amz-security-token" "x-amz-content-sha256"]
    :volatile_headers ["x-amz-request-id" "x-amz-id-2" "x-amz-date"]}

   "stripe"
   {:service       "stripe"
    :protocol      "http"
    :default_target "https://api.stripe.com"
    :base_url_env  ["STRIPE_API_BASE"]
    :redact_headers   ["authorization" "stripe-account"]
    :volatile_headers ["request-id" "idempotency-key"]}

   "generic-http"
   {:service       "generic-http"
    :protocol      "http"
    :default_target nil
    :base_url_env  ["BASE_URL"]
    :redact_headers   []
    :volatile_headers []}})

(defn service-of
  "The service a scenario belongs to: the first segment of a `service/name` id,
   or nil for a flat scenario."
  [scenario]
  (when (and scenario (str/includes? scenario "/"))
    (first (str/split scenario #"/"))))

(defn write-template!
  "Materialize a built-in template under .mocks/services/. Throws on unknown."
  [name]
  (if-let [tmpl (templates name)]
    (do (store/write-service! name tmpl) tmpl)
    (throw (js/Error. (str "unknown service template '" name "'. known: "
                           (str/join ", " (sort (keys templates))))))))

(defn effective
  "The effective profile for a scenario's service: base sets ∪ the service's
   template/saved profile. Always returns usable sets even with no service."
  [scenario]
  (let [service (service-of scenario)
        prof    (or (store/read-service service) (templates service))]
    {:service  service
     :redact   (set/union analyze/base-redact-headers
                          (set (map str/lower-case (:redact_headers prof))))
     :volatile (set/union analyze/base-volatile-headers
                          (set (map str/lower-case (:volatile_headers prof))))
     :env      (vec (:base_url_env prof))
     :target   (:default_target prof)}))
