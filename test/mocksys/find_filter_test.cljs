(ns mocksys.find-filter-test
  "TDD for an optional SCIM `filter` on `find`: the row must match the key/field AND
   satisfy the filter, else it's a miss. This is the Q7 issuance gate — authorize
   only mints a code for a user that is `status eq \"ACTIVE\"`."
  (:require [clojure.test :refer [deftest testing is]]
            [mocksys.harness :as h]))

(def ^:private colls
  {"users" {"idField" "id"
            "seed" [{"id" "00u1" "status" "ACTIVE" "profile" {"login" "alice@x"}}
                    {"id" "00u3" "status" "STAGED" "profile" {"login" "carol@x"}}]}})

(def ^:private gated-op
  {"id" "gate"
   "request" {"method" "GET" "path" "/api/v1/users/{uid}/gate"}
   "effect" {"bind" {"uid" {"pathRegex" "/users/([^/]+)/gate$"}}
             "find" {"collection" "users" "key" "{uid}"
                     "filter" "status eq \"ACTIVE\"" "as" "user"
                     "miss" {"status" 403 "json" {"errorCode" "E0000038"}}}
             "respond" {"status" 200 "jsonVar" "user"}}})

(deftest find-filter-gates-on-status
  (testing "an ACTIVE row matching the key passes the filter"
    (let [r (h/run gated-op {:path "/api/v1/users/00u1/gate"} {:collections colls})]
      (is (= 200 (:status r)))
      (is (= "00u1" (get-in r [:json "id"])))))
  (testing "a row that matches the key but fails the filter is a miss"
    (let [r (h/run gated-op {:path "/api/v1/users/00u3/gate"} {:collections colls})]
      (is (= 403 (:status r)))
      (is (= "E0000038" (get-in r [:json "errorCode"])))))
  (testing "a missing key is still a miss"
    (let [r (h/run gated-op {:path "/api/v1/users/nope/gate"} {:collections colls})]
      (is (= 403 (:status r))))))
