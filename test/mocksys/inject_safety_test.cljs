(ns mocksys.inject-safety-test
  "Contract values that reach code in the Mountebank daemon must be rejected at
   compile time, and `validate` must report them: numbers spliced unquoted into
   generated JS, and JSONPath selectors (jsonpath-plus evaluates script expressions)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [mocksys.inject :as inject]
            [mocksys.contract :as contract]))

(def ^:private payload "200,body:require('child_process').execSync('id').toString()")

(defn- gen-error [op]
  (try (inject/gen op nil) nil
       (catch :default e (.-message e))))

(deftest code-shaped-numbers-are-rejected
  (testing "respond.status"
    (let [msg (gen-error {"id" "evil" "effect" {"respond" {"status" payload}}})]
      (is (some? msg))
      (is (str/includes? msg "status must be a number"))))
  (testing "a terminal collection verb's status"
    (is (some? (gen-error {"id" "l" "effect" {"list" {"collection" "users" "status" payload}}}))))
  (testing "store.ttl, limit.window and a now offset"
    (is (some? (gen-error {"id" "t" "effect" {"store" {"bucket" "b" "key" "k" "ttl" "1;process.exit()"}
                                              "respond" {"status" 200}}})))
    (is (some? (gen-error {"id" "w" "effect" {"limit" {"bucket" "b" "key" "k" "window" "60;x()"}
                                              "respond" {"status" 200}}})))
    (is (some? (gen-error {"id" "n" "effect" {"bind" {"t" {"now" {"add" "1;x()"}}}
                                              "respond" {"status" 200}}})))))

(deftest numbers-still-compile
  (is (nil? (gen-error {"id" "ok" "effect" {"store" {"bucket" "b" "key" "k" "ttl" 60}
                                            "respond" {"status" 201}}}))))

(deftest validate-reports-the-bad-field
  (let [c {"operations" [{"id" "evil"
                          "request" {"method" "GET" "path" "/x"}
                          "effect" {"respond" {"status" payload}}}]}
        {:keys [errors]} (contract/validate c)]
    (is (some #(str/includes? % "evil: status must be a number") errors))))

(def ^:private script-selector "$..[?(@.constructor.constructor('return process')().exit())]")

(defn- lower-error [c]
  (try (contract/lower c) nil
       (catch :default e (.-message e))))

(deftest jsonpath-selectors-must-be-plain
  (testing "a body predicate selector with a script expression"
    (let [msg (lower-error {"operations" [{"id" "evil"
                                           "request" {"method" "POST" "path" "/x"
                                                      "body" {script-selector "y"}}
                                           "responses" [{"status" 200 "examples" {"a" {"select" true}}}]}]})]
      (is (some? msg))
      (is (str/includes? msg "evil: unsupported JSONPath selector"))))
  (testing "a {{request.body...}} template"
    (is (some? (lower-error {"operations" [{"id" "t"
                                            "request" {"method" "POST" "path" "/x"}
                                            "responses" [{"status" 200
                                                          "examples" {"a" {"select" true
                                                                           "body" {"v" "{{request.body.a[?(@.x)]}}"}}}}]}]}))))
  (testing "plain paths still compile"
    (is (nil? (lower-error {"operations" [{"id" "ok"
                                           "request" {"method" "POST" "path" "/x"
                                                      "body" {"$.user.id" "1" "$.items[0]" {"present" true}
                                                              "$['grant_type']" "code"}}
                                           "responses" [{"status" 200
                                                         "examples" {"a" {"select" true
                                                                          "body" {"v" "{{request.body.user.id}}"}}}}]}]})))))
