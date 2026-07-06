(ns mocksys.render-test
  "TDD for the terminal `render` verb: an HTML page built from LIVE collection state
   (optionally filtered), each row through a `row` template and joined into `wrap` at
   {rows}, returned as text/html. Row templates see dotted row paths AND bound request
   vars — this is the dynamic OAuth account picker."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [mocksys.harness :as h]))

(def ^:private colls
  {"users" {"idField" "id"
            "seed" [{"id" "00u1" "status" "ACTIVE" "profile" {"login" "alice@x"}}
                    {"id" "00u2" "status" "ACTIVE" "profile" {"login" "bob@x"}}
                    {"id" "00u3" "status" "STAGED" "profile" {"login" "carol@x"}}]}})

(def ^:private picker-op
  {"id" "picker"
   "request" {"method" "GET" "path" "/oauth2/v1/authorize"}
   "effect" {"bind" {"cid" {"query" "client_id"}}
             "render" {"collection" "users"
                       "filter" "status eq \"ACTIVE\""
                       "row" "<a href=\"/authorize?client_id={cid}&login={profile.login}&uid={id}\">{profile.login}</a>"
                       "wrap" "<!doctype html><h2>Sign in</h2>{rows}"}}})

(deftest render-builds-an-html-page-from-live-rows
  (let [r (h/run picker-op {:path "/oauth2/v1/authorize" :query {:client_id "app1"}}
                 {:collections colls})]
    (testing "returns 200 text/html"
      (is (= 200 (:status r)))
      (is (str/starts-with? (get-in r [:headers "Content-Type"]) "text/html")))
    (testing "the wrap frames the joined rows"
      (is (str/starts-with? (:body r) "<!doctype html><h2>Sign in</h2>")))
    (testing "ACTIVE rows are rendered with dotted row fields"
      (is (str/includes? (:body r) "login=alice@x"))
      (is (str/includes? (:body r) "uid=00u1"))
      (is (str/includes? (:body r) "login=bob@x")))
    (testing "the filter excludes the STAGED user"
      (is (not (str/includes? (:body r) "carol@x"))))
    (testing "row templates also see bound request vars"
      (is (str/includes? (:body r) "client_id=app1")))))

(deftest render-reflects-runtime-inserts
  (let [state (h/fresh-state)
        create-op {"id" "create"
                   "request" {"method" "POST" "path" "/api/v1/users"}
                   "effect" {"bind" {"incoming" {"bodyJson" true}}
                             "mint" {"newid" {"prefix" "00u"}}
                             "create" {"collection" "users" "merge" ["incoming"]
                                       "body" {"id" "{newid}" "status" "ACTIVE"} "status" 201}}}]
    (h/run create-op {:path "/api/v1/users"
                      :body {:profile {:login "dana@x"}}} {:collections colls} state)
    (testing "a user created at runtime shows up in the rendered picker"
      (let [r (h/run picker-op {:path "/oauth2/v1/authorize" :query {:client_id "app1"}}
                     {:collections colls} state)]
        (is (str/includes? (:body r) "login=dana@x"))))))
