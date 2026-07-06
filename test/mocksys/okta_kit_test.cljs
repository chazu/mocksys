(ns mocksys.okta-kit-test
  "Integration: the okta kit composes the membership gene over its real users/groups
   seed, validates cleanly, and lowers to an imposter. Then drive assign→list
   through the harness with the kit's own resolved collections."
  (:require [clojure.test :refer [deftest testing is]]
            [mocksys.harness :as h]
            [mocksys.kits :as kits]
            [mocksys.contract :as contract]))

(def ^:private okta (kits/okta "okta/twin" nil {}))

(defn- op [id]
  (some #(when (= id (get % "id")) %) (get okta "operations")))

;; resolve each collection's seed *name* to its array, exactly as `lower` does, so
;; the harness sees the kit's real seed data.
(defn- resolved-colls []
  (let [seed (get okta "seed")]
    (into {} (for [[n spec] (get-in okta ["state" "collections"])]
               [n {"idField" (get spec "idField") "seed" (get seed (get spec "seed"))}]))))

(deftest okta-validates-and-lowers-with-membership
  (testing "the composed contract has no validation errors"
    (is (empty? (:errors (contract/validate okta)))))
  (testing "it lowers to an imposter without throwing"
    (let [imp (contract/lower okta)]
      (is (seq (get imp "stubs")))))
  (testing "the four membership ops are wired in"
    (is (every? some? (map op ["assign-membership" "unassign-membership"
                               "list-group-users" "list-user-groups"])))))

(deftest okta-membership-round-trips-over-real-seed
  (let [colls  (resolved-colls)
        state  (h/fresh-state)]
    (testing "assign the seeded alice (00u1) to the Everyone group (00g1)"
      (is (= 204 (:status (h/run (op "assign-membership")
                                 {:path "/api/v1/groups/00g1/users/00u1"}
                                 {:collections colls} state)))))
    (testing "listing the group's users hydrates alice's real profile"
      (let [r (h/run (op "list-group-users") {:path "/api/v1/groups/00g1/users"}
                     {:collections colls} state)]
        (is (= ["00u1"] (mapv #(get % "id") (:json r))))
        (is (= "alice@example.com"
               (get-in (first (:json r)) ["profile" "login"])))))
    (testing "assigning to a group id that isn't seeded 404s"
      (is (= 404 (:status (h/run (op "assign-membership")
                                 {:path "/api/v1/groups/00gX/users/00u1"}
                                 {:collections colls} state)))))))
