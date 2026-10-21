(ns mocksys.membership-gene-test
  "TDD for the `membership` gene in kits.cljs: it should emit the four Okta group-
   membership operations (assign/unassign/list-group-users/list-user-groups) wired
   to the link/unlink/listJoin verbs, each FK-guarded so a bad group/user id 404s.
   We drive the gene's emitted ops through the effect harness."
  (:require [clojure.test :refer [deftest testing is]]
            [mocksys.harness :as h]
            [mocksys.kits :as kits]))

(def ^:private gene (kits/membership {}))

(defn- op [id]
  (some #(when (= id (get % "id")) %) (:operations gene)))

;; resolved collections for the harness (idField + inline seed arrays)
(def ^:private colls
  {"users"  {"idField" "id" "seed" [{"id" "00u1" "profile" {"login" "alice@x"}}
                                    {"id" "00u2" "profile" {"login" "bob@x"}}]}
   "groups" {"idField" "id" "seed" [{"id" "00g1" "profile" {"name" "Admins"}}]}
   "memberships" {"idField" "id" "seed" []}})

(deftest gene-declares-the-relation-collection
  (testing "the gene owns a memberships collection"
    (is (contains? (:collections gene) "memberships"))
    (is (= "id" (get-in gene [:collections "memberships" "idField"])))))

(deftest gene-emits-the-four-operations
  (testing "all four membership operations are present"
    (is (every? some? (map op ["assign-membership" "unassign-membership"
                               "list-group-users" "list-user-groups"])))))

(deftest assign-then-list-round-trips-through-the-gene
  (let [state  (h/fresh-state)
        assign (op "assign-membership")
        lgu    (op "list-group-users")]
    (testing "assigning a real user to a real group is 204"
      (let [r (h/run assign {:path "/api/v1/groups/00g1/users/00u1"} {:collections colls} state)]
        (is (= 204 (:status r)))))
    (testing "the group's user list now hydrates that user"
      (let [r (h/run lgu {:path "/api/v1/groups/00g1/users"} {:collections colls} state)]
        (is (= 200 (:status r)))
        (is (= ["00u1"] (mapv #(get % "id") (:json r))))
        (is (= "alice@x" (get-in (first (:json r)) ["profile" "login"])))))))

(deftest assign-fk-guards-both-sides
  (let [assign (op "assign-membership")]
    (testing "a bad group id 404s"
      (is (= 404 (:status (h/run assign {:path "/api/v1/groups/bad/users/00u1"}
                                 {:collections colls} (h/fresh-state))))))
    (testing "a bad user id 404s"
      (is (= 404 (:status (h/run assign {:path "/api/v1/groups/00g1/users/bad"}
                                 {:collections colls} (h/fresh-state))))))))

(deftest unassign-is-miss-tolerant-but-fk-guarded
  (let [unassign (op "unassign-membership")]
    (testing "unassigning a non-member (both ids valid) is still 204"
      (is (= 204 (:status (h/run unassign {:path "/api/v1/groups/00g1/users/00u2"}
                                 {:collections colls} (h/fresh-state))))))
    (testing "a bad group id still 404s"
      (is (= 404 (:status (h/run unassign {:path "/api/v1/groups/bad/users/00u1"}
                                 {:collections colls} (h/fresh-state))))))))

(deftest user-groups-walks-the-relation-back
  (let [state  (h/fresh-state)
        assign (op "assign-membership")
        lug    (op "list-user-groups")]
    (h/run assign {:path "/api/v1/groups/00g1/users/00u1"} {:collections colls} state)
    (testing "the user's group list hydrates the group"
      (let [r (h/run lug {:path "/api/v1/users/00u1/groups"} {:collections colls} state)]
        (is (= 200 (:status r)))
        (is (= ["00g1"] (mapv #(get % "id") (:json r))))
        (is (= "Admins" (get-in (first (:json r)) ["profile" "name"])))))))
