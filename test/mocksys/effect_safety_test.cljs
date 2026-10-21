(ns mocksys.effect-safety-test
  "Code-shaped contract values must be refused before they reach the daemon, and
   `validate` must report them: strings where an effect takes a number, and JSONPath
   selectors richer than a plain path."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [mocksys.effect :as effect]
            [mocksys.contract :as contract]))

(def ^:private payload "200,body:require('child_process').execSync('id').toString()")

(defn- effect-error [op]
  (first (effect/errors (get op "effect"))))

(deftest code-shaped-numbers-are-rejected
  (testing "respond.status"
    (let [msg (effect-error {"id" "evil" "effect" {"respond" {"status" payload}}})]
      (is (some? msg))
      (is (str/includes? msg "respond.status must be an HTTP status"))))
  (testing "a terminal collection verb's status"
    (is (some? (effect-error {"id" "l" "effect" {"list" {"collection" "users" "status" payload}}}))))
  (testing "store.ttl, limit.window and a now offset"
    (is (some? (effect-error {"id" "t" "effect" {"store" {"bucket" "b" "key" "k" "ttl" "1;process.exit()"}
                                              "respond" {"status" 200}}})))
    (is (some? (effect-error {"id" "w" "effect" {"limit" {"bucket" "b" "key" "k" "window" "60;x()"}
                                              "respond" {"status" 200}}})))
    (is (some? (effect-error {"id" "n" "effect" {"bind" {"t" {"now" {"add" "1;x()"}}}
                                              "respond" {"status" 200}}})))))

(deftest numbers-still-compile
  (is (nil? (effect-error {"id" "ok" "effect" {"store" {"bucket" "b" "key" "k" "ttl" 60}
                                            "respond" {"status" 201}}}))))

(deftest validate-reports-the-bad-field
  (let [c {"operations" [{"id" "evil"
                          "request" {"method" "GET" "path" "/x"}
                          "effect" {"respond" {"status" payload}}}]}
        {:keys [errors]} (contract/validate c)]
    (is (some #(str/includes? % "evil: respond.status must be an HTTP status") errors))))

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
