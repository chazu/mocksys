(ns mocksys.oidc-live-test
  "TDD for the `oidc-handshake` fork on `:user-source`. The LIVE path (okta) keys
   authorize/token/userinfo on the mutable `users` collection: a render picker over
   live rows, a uid carried through the code→token buckets, and userinfo shaped from
   a `find`+dotted `respond`. The SEED path (oauth2) must stay byte-for-byte behavior."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [mocksys.harness :as h]
            [mocksys.kits :as kits]))

(def ^:private colls
  {"users" {"idField" "id"
            "seed" [{"id" "00u1" "status" "ACTIVE"
                     "profile" {"login" "alice@x" "email" "alice@x" "firstName" "Alice" "lastName" "Ng"}}
                    {"id" "00u3" "status" "STAGED"
                     "profile" {"login" "carol@x" "email" "carol@x" "firstName" "Carol" "lastName" "Diaz"}}]}})

(def ^:private live-gene
  (kits/oidc-handshake
   {:authz "/oauth2/v1/authorize" :token "/oauth2/v1/token" :userinfo "/oauth2/v1/userinfo"
    :user-source {"collection" "users" "login-field" "profile.login" "active-only" true
                  "claims" {"sub" "{user.id}"
                           "email" "{user.profile.email}"
                           "preferred_username" "{user.profile.login}"
                           "name" "{user.profile.firstName} {user.profile.lastName}"
                           "email_verified" true}}}))

(defn- lop [id] (some #(when (= id (get % "id")) %) (:operations live-gene)))
(defn- code-of [resp] (second (re-find #"code=([^&]+)" (get-in resp [:headers "Location"]))))

(deftest live-flow-authorize-token-userinfo
  (let [state (h/fresh-state)
        a (h/run (lop "authorize")
                 {:path "/oauth2/v1/authorize"
                  :query {:login "alice@x" :uid "00u1" :state "s" :redirect_uri "https://cb"}}
                 {:collections colls} state)
        code (code-of a)
        t (h/run (lop "access-token")
                 {:path "/oauth2/v1/token" :body {:code code :scope "openid email"}}
                 {:collections colls} state)
        tok (get-in t [:json "access_token"])
        u (h/run (lop "userinfo")
                 {:path "/oauth2/v1/userinfo" :headers {:authorization (str "Bearer " tok)}}
                 {:collections colls} state)]
    (testing "authorize 302s with a code"
      (is (= 302 (:status a)))
      (is (str/starts-with? code "code_")))
    (testing "token issues an access_token"
      (is (= 200 (:status t)))
      (is (str/starts-with? tok "mockoauth_")))
    (testing "userinfo shapes OIDC claims from the live user row via dotted paths"
      (is (= 200 (:status u)))
      (is (= "00u1" (get-in u [:json "sub"])))
      (is (= "alice@x" (get-in u [:json "email"])))
      (is (= "alice@x" (get-in u [:json "preferred_username"])))
      (is (= "Alice Ng" (get-in u [:json "name"])))
      (is (= true (get-in u [:json "email_verified"]))))))

(deftest live-authorize-refuses-non-active
  (testing "a STAGED user cannot be issued a code (Q7 issuance gate)"
    (is (= 403 (:status (h/run (lop "authorize")
                               {:path "/oauth2/v1/authorize"
                                :query {:login "carol@x" :uid "00u3" :state "s" :redirect_uri "https://cb"}}
                               {:collections colls} (h/fresh-state)))))))

(deftest live-userinfo-401-for-deleted-user
  (testing "a token that resolves to a uid no longer in the collection → 401"
    (let [state (clj->js {"tokens" {"tok_gone" "00uGONE"}})
          u (h/run (lop "userinfo")
                   {:path "/oauth2/v1/userinfo" :headers {:authorization "Bearer tok_gone"}}
                   {:collections colls} state)]
      (is (= 401 (:status u))))))

(deftest live-picker-renders-active-rows
  (let [r (h/run (lop "authorize-picker")
                 {:path "/oauth2/v1/authorize"
                  :query {:client_id "app1" :redirect_uri "https://cb" :state "s"}}
                 {:collections colls})]
    (testing "text/html listing the ACTIVE user with a login+uid link that preserves params"
      (is (str/starts-with? (get-in r [:headers "Content-Type"]) "text/html"))
      (is (str/includes? (:body r) "uid=00u1"))
      (is (str/includes? (:body r) "login=alice@x"))
      (is (str/includes? (:body r) "client_id=app1")))
    (testing "the STAGED user is not offered"
      (is (not (str/includes? (:body r) "carol@x"))))))

;; --- the SEED path (oauth2) must be unchanged ---

(def ^:private seed-gene
  (kits/oidc-handshake
   {:authz "/login/oauth/authorize" :token "/login/oauth/access_token" :userinfo "/user"
    :seed-name "users"
    :users [{"id" 1001 "login" "octocat" "email" "octocat@x"}]}))

(defn- sop [id] (some #(when (= id (get % "id")) %) (:operations seed-gene)))

(deftest seed-path-unchanged
  (let [state (h/fresh-state)
        seed  {"users" [{"id" 1001 "login" "octocat" "email" "octocat@x"}]}
        a (h/run (sop "authorize")
                 {:path "/login/oauth/authorize" :query {:login "octocat" :state "s" :redirect_uri "https://cb"}}
                 {:seed seed} state)
        code (code-of a)
        t (h/run (sop "access-token")
                 {:path "/login/oauth/access_token" :body {:code code :scope "read"}}
                 {:seed seed} state)
        tok (get-in t [:json "access_token"])
        u (h/run (sop "userinfo")
                 {:path "/user" :headers {:authorization (str "Bearer " tok)}}
                 {:seed seed} state)]
    (testing "seed userinfo still resolves the whole seed user via jsonVar"
      (is (= 200 (:status u)))
      (is (= "octocat" (get-in u [:json "login"])))
      (is (= 1001 (get-in u [:json "id"]))))))
