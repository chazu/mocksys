(ns mocksys.inject-test
  "Harness sanity + regression base: exercise the *compiled* injection for effect
   verbs that already exist, so we know the harness runs real generated JS before
   any new-verb TDD lands on top of it."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [mocksys.harness :as h]))

(def ^:private users-coll
  {"users" {"idField" "id"
            "seed" [{"id" "00u1" "status" "ACTIVE" "profile" {"login" "a@x" "email" "a@x"}}]}})

(def ^:private get-op
  {"id" "get-user"
   "request" {"method" "GET" "path" "/api/v1/users/{id}"}
   "effect" {"bind" {"id" {"pathRegex" "/users/([^/]+)$"}}
             "get" {"collection" "users" "key" "{id}"
                    "miss" {"status" 404 "json" {"errorCode" "E0000007"}}}}})

(deftest get-verb-hit-and-miss
  (testing "a present row returns 200 with the row body"
    (let [r (h/run get-op {:path "/api/v1/users/00u1"} {:collections users-coll})]
      (is (= 200 (:status r)))
      (is (= "00u1" (get-in r [:json "id"])))
      (is (= "a@x" (get-in r [:json "profile" "login"])))))
  (testing "an absent row returns the miss response verbatim"
    (let [r (h/run get-op {:path "/api/v1/users/nope"} {:collections users-coll})]
      (is (= 404 (:status r)))
      (is (= "E0000007" (get-in r [:json "errorCode"]))))))

;; --- the OAuth code/token handshake, run across ONE shared state ---

(def ^:private authorize-op
  {"id" "authorize"
   "request" {"method" "GET" "path" "/authorize"}
   "effect" {"bind" {"login" {"query" "login"} "state" {"query" "state"}
                     "ruri" {"query" "redirect_uri"}}
             "mint" {"code" {"prefix" "code_"}}
             "store" {"bucket" "codes" "key" "{code}" "value" "{login}"}
             "respond" {"status" 302
                        "headers" {"Location" "{ruri}?code={code}&state={state}"}}}})

(def ^:private token-op
  {"id" "token"
   "request" {"method" "POST" "path" "/token"}
   "effect" {"bind" {"code" {"body" "code"} "scope" {"body" "scope"}}
             "consume" {"bucket" "codes" "key" "{code}" "as" "login"
                        "miss" {"status" 400 "json" {"error" "bad_verification_code"}}}
             "mint" {"token" {"prefix" "tok_"}}
             "store" {"bucket" "tokens" "key" "{token}" "value" "{login}"}
             "respond" {"status" 200
                        "json" {"access_token" "{token}" "token_type" "bearer"
                                "scope" "{scope}"}}}})

(defn- code-from-location [resp]
  (second (re-find #"code=([^&]+)" (get-in resp [:headers "Location"]))))

(deftest oauth-handshake-across-shared-state
  (let [state (h/fresh-state)
        auth  (h/run authorize-op
                     {:query {:login "octocat" :state "xyz" :redirect_uri "https://cb"}}
                     {} state)
        code  (code-from-location auth)]
    (testing "authorize mints a code and 302s with it in the Location"
      (is (= 302 (:status auth)))
      (is (str/starts-with? code "code_")))
    (testing "token consumes that code and issues a bearer token"
      (let [tok (h/run token-op {:body {:code code :scope "openid"}} {} state)]
        (is (= 200 (:status tok)))
        (is (str/starts-with? (get-in tok [:json "access_token"]) "tok_"))
        (is (= "openid" (get-in tok [:json "scope"])))))
    (testing "the code is single-use — re-consuming it misses with 400"
      (let [again (h/run token-op {:body {:code code :scope "openid"}} {} state)]
        (is (= 400 (:status again)))
        (is (= "bad_verification_code" (get-in again [:json "error"])))))))
