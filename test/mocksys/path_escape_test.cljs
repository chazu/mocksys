(ns mocksys.path-escape-test
  "A templated path, and an `assert --saw` glob, keep their literal characters
   literal: a dot matches a dot."
  (:require [clojure.test :refer [deftest is]]
            [mocksys.cli.serve :as serve]
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

(deftest an-assert-glob-is-literal-but-for-its-stars
  (let [saw? #(boolean (re-matches (serve/glob->re %1) %2))]
    (is (saw? "/todo.v1.Todos/*" "/todo.v1.Todos/GetTodo"))
    (is (not (saw? "/todo.v1.Todos/*" "/todoXv1.Todos/GetTodo")))
    (is (saw? "/a(b)+c/[x]$" "/a(b)+c/[x]$"))
    (is (not (saw? "/a(b)+c" "/abbc")))))
