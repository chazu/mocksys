(ns mocksys.interp-test
  "TDD for the dotted-path `interp` upgrade: `{a.b.c}` walks into a bound object
   (missing links → \"\"), while flat `{name}` keeps working. This is what shapes
   OIDC claims from a found user row in a plain `respond`, replacing the old
   `project` verb."
  (:require [clojure.test :refer [deftest testing is]]
            [mocksys.harness :as h]))

(def ^:private colls
  {"users" {"idField" "id"
            "seed" [{"id" "00u1" "status" "ACTIVE"
                     "profile" {"login" "alice@x" "email" "alice@x"
                                "firstName" "Alice" "lastName" "Ng"}}]}})

(def ^:private claims-op
  {"id" "claims"
   "request" {"method" "GET" "path" "/api/v1/users/{uid}/claims"}
   "effect" {"bind" {"uid" {"pathRegex" "/users/([^/]+)/claims$"}}
             "find" {"collection" "users" "key" "{uid}" "as" "user"
                     "miss" {"status" 401 "json" {"errorCode" "E0000007"}}}
             "respond" {"status" 200
                        "json" {"sub"     "{user.id}"
                                "email"   "{user.profile.email}"
                                "name"    "{user.profile.firstName} {user.profile.lastName}"
                                "flat"    "{uid}"
                                "missing" "{user.profile.middleName}"}}}})

(deftest dotted-paths-resolve-from-a-bound-object
  (let [r (h/run claims-op {:path "/api/v1/users/00u1/claims"} {:collections colls})]
    (testing "single-segment dotted read"
      (is (= "00u1" (get-in r [:json "sub"]))))
    (testing "two-segment dotted read"
      (is (= "alice@x" (get-in r [:json "email"]))))
    (testing "two dotted reads composed in one template string"
      (is (= "Alice Ng" (get-in r [:json "name"]))))
    (testing "a plain flat var still interpolates"
      (is (= "00u1" (get-in r [:json "flat"]))))
    (testing "a missing dotted link interpolates to empty string"
      (is (= "" (get-in r [:json "missing"]))))))
