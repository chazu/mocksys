(ns mocksys.path-escape-test
  "A templated path keeps its literal characters literal: a dot matches a dot."
  (:require [clojure.test :refer [deftest is]]
            [mocksys.contract :as contract]
            [mocksys.runtime.match :as match]))

(defn- served? [template path]
  (let [imp (contract/lower {"operations" [{"id" "a" "request" {"method" "GET" "path" template}
                                            "responses" [{"status" 200}]}]})]
    (match/all? (get-in imp ["stubs" 0 "predicates"])
                {"method" "GET" "path" path "query" {} "headers" {} "body" ""})))

(deftest a-dot-in-a-templated-path-is-literal
  (is (served? "/v1.0/users/{id}.json" "/v1.0/users/7.json"))
  (is (not (served? "/v1.0/users/{id}.json" "/v1x0/users/7.json"))))
