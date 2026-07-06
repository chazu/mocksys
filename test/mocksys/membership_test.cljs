(ns mocksys.membership-test
  "TDD for the terminal membership verbs `link` (idempotent assign, 204) and
   `unlink` (miss-tolerant unassign, 204). A membership is its own collection whose
   rows carry a composite id `{a}:{b}`. We inspect the relation with a plain `list`."
  (:require [clojure.test :refer [deftest testing is]]
            [mocksys.harness :as h]))

(def ^:private colls
  {"users"       {"idField" "id" "seed" [{"id" "00u1"} {"id" "00u2"}]}
   "groups"      {"idField" "id" "seed" [{"id" "00g1"}]}
   "memberships" {"idField" "id" "seed" []}})

(def ^:private link-op
  {"id" "assign"
   "request" {"method" "PUT" "path" "/api/v1/groups/{gid}/users/{uid}"}
   "effect" {"bind" {"gid" {"pathRegex" "/groups/([^/]+)/users/[^/]+$"}
                     "uid" {"pathRegex" "/users/([^/]+)$"}}
             "link" {"collection" "memberships" "id" "{gid}:{uid}"
                     "row" {"gid" "{gid}" "uid" "{uid}"} "status" 204}}})

(def ^:private unlink-op
  {"id" "unassign"
   "request" {"method" "DELETE" "path" "/api/v1/groups/{gid}/users/{uid}"}
   "effect" {"bind" {"gid" {"pathRegex" "/groups/([^/]+)/users/[^/]+$"}
                     "uid" {"pathRegex" "/users/([^/]+)$"}}
             "unlink" {"collection" "memberships" "id" "{gid}:{uid}" "status" 204}}})

(def ^:private list-memberships-op
  {"id" "peek"
   "request" {"method" "GET" "path" "/peek"}
   "effect" {"list" {"collection" "memberships"}}})

(defn- memberships [state]
  (:json (h/run list-memberships-op {:path "/peek"} {:collections colls} state)))

(deftest link-assigns-a-membership
  (let [state (h/fresh-state)
        r (h/run link-op {:path "/api/v1/groups/00g1/users/00u1"} {:collections colls} state)]
    (testing "link returns 204 with an empty body"
      (is (= 204 (:status r)))
      (is (= "" (:body r))))
    (testing "the membership row now exists with the composite id and both sides"
      (let [rows (memberships state)]
        (is (= 1 (count rows)))
        (is (= "00g1:00u1" (get (first rows) "id")))
        (is (= "00g1" (get (first rows) "gid")))
        (is (= "00u1" (get (first rows) "uid")))))))

(deftest link-is-idempotent
  (let [state (h/fresh-state)]
    (h/run link-op {:path "/api/v1/groups/00g1/users/00u1"} {:collections colls} state)
    (h/run link-op {:path "/api/v1/groups/00g1/users/00u1"} {:collections colls} state)
    (testing "re-assigning the same pair is a no-op — still one row"
      (is (= 1 (count (memberships state)))))))

(deftest unlink-removes-and-is-miss-tolerant
  (let [state (h/fresh-state)]
    (h/run link-op {:path "/api/v1/groups/00g1/users/00u1"} {:collections colls} state)
    (testing "unlink removes the membership and returns 204"
      (let [r (h/run unlink-op {:path "/api/v1/groups/00g1/users/00u1"} {:collections colls} state)]
        (is (= 204 (:status r)))
        (is (= "" (:body r)))
        (is (= 0 (count (memberships state))))))
    (testing "unlinking a non-member still returns 204 (no 404)"
      (let [r (h/run unlink-op {:path "/api/v1/groups/00g1/users/00u2"} {:collections colls} state)]
        (is (= 204 (:status r)))))))
