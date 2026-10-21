(ns mocksys.scim-filter-test
  "The `list` verb's SCIM filter, run through the generated injection: quoted
   values may contain keywords, the comparison ops Okta clients use for incremental
   sync work, and a malformed filter is a 400 instead of an empty page."
  (:require [clojure.test :refer [deftest testing is]]
            [mocksys.harness :as h]
            [mocksys.kits :as kits]))

(def ^:private colls
  {"users" {"idField" "id"
            "seed" [{"id" "00u1" "status" "ACTIVE" "lastUpdated" "2024-03-01T00:00:00.000Z"
                     "profile" {"lastName" "Smith and Sons" "login" "a@corp.com"}}
                    {"id" "00u2" "status" "SUSPENDED" "lastUpdated" "2022-01-01T00:00:00.000Z"
                     "profile" {"lastName" "Ng" "login" "b@other.org"}}
                    {"id" "00u3" "status" "STAGED" "lastUpdated" "2023-06-01T00:00:00.000Z"
                     "profile" {"lastName" "Diaz" "login" "c@corp.com"}}]}})

(def ^:private list-op
  {"id" "list-users"
   "request" {"method" "GET" "path" "/api/v1/users"}
   "effect" {"bind" {"q" {"query" "filter"}}
             "list" {"collection" "users" "filter" "{q}"}}})

(defn- ids [filter]
  (let [r (h/run list-op {:query {:filter filter}} {:collections colls})]
    (when (= 200 (:status r)) (mapv #(get % "id") (:json r)))))

(deftest filter-grammar
  (testing "a quoted value containing `and` is one value"
    (is (= ["00u1"] (ids "profile.lastName eq \"Smith and Sons\""))))
  (testing "gt over ISO timestamps (incremental sync)"
    (is (= ["00u1" "00u3"] (ids "lastUpdated gt \"2023-01-01T00:00:00.000Z\""))))
  (testing "ne, ew, and precedence of and over or"
    (is (= ["00u2" "00u3"] (ids "status ne \"ACTIVE\"")))
    (is (= ["00u1" "00u3"] (ids "profile.login ew \"@corp.com\"")))
    (is (= ["00u1" "00u2"] (ids "status eq \"SUSPENDED\" or status eq \"ACTIVE\" and profile.login sw \"a\""))))
  (testing "parentheses and not"
    (is (= ["00u3"] (ids "not (status eq \"ACTIVE\" or status eq \"SUSPENDED\")"))))
  (testing "no filter lists everything"
    (is (= ["00u1" "00u2" "00u3"] (ids "")))))

(deftest malformed-filter-is-a-400
  (testing "default: a SCIM invalidFilter error carrying the parser message"
    (let [r (h/run list-op {:query {:filter "status zz \"ACTIVE\""}} {:collections colls})]
      (is (= 400 (:status r)))
      (is (= "invalidFilter" (get-in r [:json "scimType"])))
      (is (re-find #"unknown operator zz" (get-in r [:json "detail"])))))
  (testing "an unterminated string"
    (is (= 400 (:status (h/run list-op {:query {:filter "status eq \"ACT"}} {:collections colls}))))))

(deftest okta-twin-answers-with-its-own-envelope
  (let [gene (kits/crud-collection {:coll "users" :base "/api/v1/users" :seed-name "users"})
        op   (some #(when (= "list-users" (get % "id")) %) (:operations gene))
        r    (h/run op {:path "/api/v1/users" :query {:filter "status eq"}} {:collections colls})]
    (is (= 400 (:status r)))
    (is (= "E0000031" (get-in r [:json "errorCode"])))))
