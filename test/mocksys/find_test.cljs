(ns mocksys.find-test
  "TDD for the `find` verb — a read-phase, non-terminal collection lookup that binds
   one row (or a picked sub-field) into a variable and falls through to the rest of
   the effect; a miss short-circuits. Also the foreign-key guard idiom (a list of
   finds whose bound vars are ignored)."
  (:require [clojure.test :refer [deftest testing is]]
            [mocksys.harness :as h]))

(def ^:private colls
  {"users"  {"idField" "id"
             "seed" [{"id" "00u1" "status" "ACTIVE" "profile" {"login" "alice@x" "email" "alice@x"}}
                     {"id" "00u2" "status" "ACTIVE" "profile" {"login" "bob@x" "email" "bob@x"}}]}
   "groups" {"idField" "id"
             "seed" [{"id" "00g1" "profile" {"name" "Admins"}}]}})

;; --- find by key: binds the whole row, non-terminal (op continues to respond) ---

(def ^:private find-by-key-op
  {"id" "who"
   "request" {"method" "GET" "path" "/api/v1/users/{uid}/self"}
   "effect" {"bind" {"uid" {"pathRegex" "/users/([^/]+)/self$"}}
             "find" {"collection" "users" "key" "{uid}" "as" "user"
                     "miss" {"status" 401 "json" {"errorCode" "E0000007"}}}
             "respond" {"status" 200 "jsonVar" "user"}}})

(deftest find-by-key
  (testing "a hit binds the row and the op falls through to respond"
    (let [r (h/run find-by-key-op {:path "/api/v1/users/00u1/self"} {:collections colls})]
      (is (= 200 (:status r)))
      (is (= "00u1" (get-in r [:json "id"])))
      (is (= "alice@x" (get-in r [:json "profile" "email"])))))
  (testing "a miss short-circuits with the miss response (respond never runs)"
    (let [r (h/run find-by-key-op {:path "/api/v1/users/nope/self"} {:collections colls})]
      (is (= 401 (:status r)))
      (is (= "E0000007" (get-in r [:json "errorCode"]))))))

;; --- find by secondary (dotted) field, with an optional `pick` ---

(def ^:private find-by-field-op
  {"id" "by-login"
   "request" {"method" "GET" "path" "/lookup"}
   "effect" {"bind" {"login" {"query" "login"}}
             "find" {"collection" "users" "field" "profile.login" "eq" "{login}"
                     "as" "uid" "pick" "id"
                     "miss" {"status" 404 "json" {"errorCode" "E0000007"}}}
             "respond" {"status" 200 "json" {"userId" "{uid}"}}}})

(deftest find-by-field-with-pick
  (testing "scans by a dotted field and picks a single sub-field into the var"
    (let [r (h/run find-by-field-op {:query {:login "bob@x"}} {:collections colls})]
      (is (= 200 (:status r)))
      (is (= "00u2" (get-in r [:json "userId"])))))
  (testing "no matching row short-circuits with the miss"
    (let [r (h/run find-by-field-op {:query {:login "nobody@x"}} {:collections colls})]
      (is (= 404 (:status r))))))

;; --- the foreign-key guard: a LIST of finds, bound vars ignored ---

(def ^:private fk-guard-op
  {"id" "guard"
   "request" {"method" "PUT" "path" "/api/v1/groups/{gid}/users/{uid}"}
   "effect" {"bind" {"gid" {"pathRegex" "/groups/([^/]+)/users/[^/]+$"}
                     "uid" {"pathRegex" "/users/([^/]+)$"}}
             "find" [{"collection" "groups" "key" "{gid}" "as" "_g"
                      "miss" {"status" 404 "json" {"errorCode" "E0000007"}}}
                     {"collection" "users" "key" "{uid}" "as" "_u"
                      "miss" {"status" 404 "json" {"errorCode" "E0000007"}}}]
             "respond" {"status" 200 "json" {"ok" "yes"}}}})

(deftest find-as-foreign-key-guard
  (testing "both sides present → the guard passes and the op responds"
    (let [r (h/run fk-guard-op {:path "/api/v1/groups/00g1/users/00u1"} {:collections colls})]
      (is (= 200 (:status r)))
      (is (= "yes" (get-in r [:json "ok"])))))
  (testing "a bad group id → 404 on the first guard"
    (let [r (h/run fk-guard-op {:path "/api/v1/groups/bad/users/00u1"} {:collections colls})]
      (is (= 404 (:status r)))))
  (testing "a bad user id → 404 on the second guard"
    (let [r (h/run fk-guard-op {:path "/api/v1/groups/00g1/users/bad"} {:collections colls})]
      (is (= 404 (:status r))))))
