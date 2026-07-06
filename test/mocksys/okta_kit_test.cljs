(ns mocksys.okta-kit-test
  "Integration: the okta kit composes the membership gene over its real users/groups
   seed, validates cleanly, and lowers to an imposter. Then drive assign→list
   through the harness with the kit's own resolved collections."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
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

;; --- Feature 3: userinfo over the live users collection ---

(deftest oidc-users-seed-is-gone
  (testing "the derived oidc_users seed is retired — OIDC reads the live collection"
    (is (nil? (get-in okta ["seed" "oidc_users"])))))

(defn- code-of [resp] (second (re-find #"code=([^&]+)" (get-in resp [:headers "Location"]))))

(deftest scim-created-user-can-log-in
  (let [colls (resolved-colls)
        state (h/fresh-state)
        picker (op "authorize-picker")
        picker-req {:path "/oauth2/v1/authorize" :query {:client_id "app" :redirect_uri "https://cb" :state "s"}}
        ;; 1. create a brand-new user via SCIM (Okta default status: STAGED)
        c (h/run (op "create-users")
                 {:path "/api/v1/users"
                  :body {"profile" {"login" "dana@example.com" "email" "dana@example.com"
                                    "firstName" "Dana" "lastName" "Poe"}}}
                 {:collections colls} state)
        uid (get-in c [:json "id"])]
    (testing "the create minted an id and defaulted to STAGED"
      (is (= 201 (:status c)))
      (is (str/starts-with? uid "00u"))
      (is (= "STAGED" (get-in c [:json "status"]))))
    (testing "while STAGED, the user is not offered in the picker (Q7)"
      (is (not (str/includes? (:body (h/run picker picker-req {:collections colls} state))
                              "dana@example.com"))))
    ;; 2. activate the user (PATCH status → ACTIVE)
    (h/run (op "update-users") {:path (str "/api/v1/users/" uid) :body {"status" "ACTIVE"}}
           {:collections colls} state)
    (testing "once ACTIVE, the new user appears in the live picker"
      (is (str/includes? (:body (h/run picker picker-req {:collections colls} state))
                         "login=dana@example.com")))
    (testing "and can drive authorize→token→userinfo, sub = the minted id"
      (let [a (h/run (op "authorize") {:path "/oauth2/v1/authorize"
                                       :query {:login "dana@example.com" :uid uid :state "s" :redirect_uri "https://cb"}}
                     {:collections colls} state)
            t (h/run (op "access-token") {:path "/oauth2/v1/token" :body {:code (code-of a) :scope "openid email"}}
                     {:collections colls} state)
            u (h/run (op "userinfo") {:path "/oauth2/v1/userinfo"
                                      :headers {:authorization (str "Bearer " (get-in t [:json "access_token"]))}}
                     {:collections colls} state)]
        (is (= 302 (:status a)))
        (is (= 200 (:status u)))
        (is (= uid (get-in u [:json "sub"])))
        (is (= "dana@example.com" (get-in u [:json "email"])))
        (is (= "Dana Poe" (get-in u [:json "name"])))))))

(deftest staged-seed-user-cannot-log-in
  (testing "carol (STAGED in the seed) is refused a code at authorize"
    (is (= 403 (:status (h/run (op "authorize")
                               {:path "/oauth2/v1/authorize"
                                :query {:login "carol@example.com" :uid "00u3" :state "s" :redirect_uri "https://cb"}}
                               {:collections (resolved-colls)} (h/fresh-state)))))))
