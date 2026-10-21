(ns mocksys.runtime-match-test
  "Stub predicates as the native runtime evaluates them. Each case pins a Mountebank
   semantic the compiled imposters rely on, so a twin answers the same on both."
  (:require [clojure.test :refer [deftest testing is]]
            [mocksys.runtime.match :as m]))

(defn- req [& {:as r}]
  (merge {"method" "GET" "path" "/" "query" {} "headers" {} "body" ""} r))

(deftest deep-equals-on-method-and-path
  (let [preds [{"deepEquals" {"method" "GET"}} {"deepEquals" {"path" "/api/v1/users"}}]]
    (is (m/all? preds (req "path" "/api/v1/users")))
    (testing "values compare case-insensitively"
      (is (m/all? preds (req "method" "get" "path" "/API/v1/Users"))))
    (is (not (m/all? preds (req "path" "/api/v1/users/1"))) "a longer path is not equal")
    (is (not (m/all? preds (req "method" "POST" "path" "/api/v1/users"))))))

(deftest no-predicates-match-everything
  (is (m/all? [] (req "path" "/anything")))
  (is (m/all? nil (req "path" "/anything"))))

(deftest matches-runs-a-case-insensitive-regex
  (let [p {"matches" {"path" "^/api/v1/users/[^/]+$"}}]
    (is (m/matches? p (req "path" "/api/v1/users/00u1")))
    (is (m/matches? p (req "path" "/API/V1/USERS/00u1")))
    (is (not (m/matches? p (req "path" "/api/v1/users/00u1/groups")))))
  (testing "a regex keeps its metacharacters (values are not lowercased)"
    (is (m/matches? {"matches" {"query" {"id" "^\\S+$"}}} (req "query" {"id" "abc"})))
    (is (not (m/matches? {"matches" {"query" {"id" "^\\S+$"}}} (req "query" {"id" "a c"}))))))

(deftest equals-on-query-and-headers
  (testing "query field"
    (is (m/matches? {"equals" {"query" {"filter" "status eq \"ACTIVE\""}}}
                    (req "query" {"filter" "status eq \"active\""})))
    (is (not (m/matches? {"equals" {"query" {"filter" "x"}}} (req "query" {})))))
  (testing "a repeated query key matches if any value does"
    (is (m/matches? {"equals" {"query" {"tag" "b"}}} (req "query" {"tag" ["a" "b"]})))
    (is (not (m/matches? {"equals" {"query" {"tag" "c"}}} (req "query" {"tag" ["a" "b"]})))))
  (testing "header names are case-insensitive"
    (is (m/matches? {"equals" {"headers" {"Authorization" "Bearer abc"}}}
                    (req "headers" {"authorization" "Bearer abc"})))
    (is (not (m/matches? {"equals" {"headers" {"Authorization" "Bearer abc"}}}
                         (req "headers" {"authorization" "Bearer xyz"}))))))

(deftest exists-on-query-and-headers
  (let [present {"exists" {"query" {"after" true}}}
        absent  {"exists" {"query" {"after" false}}}]
    (is (m/matches? present (req "query" {"after" "00u2"})))
    (is (not (m/matches? present (req "query" {}))))
    (is (not (m/matches? present (req "query" {"after" ""}))) "an empty value does not exist")
    (is (m/matches? absent (req "query" {})))
    (is (m/matches? absent (req "query" {"after" ""})))
    (is (not (m/matches? absent (req "query" {"after" "x"})))))
  (is (m/matches? {"exists" {"headers" {"X-Api-Key" true}}} (req "headers" {"x-api-key" "k"}))))

(deftest jsonpath-body-predicates
  (let [body "{\"user\":{\"name\":\"Alice\",\"age\":30},\"items\":[{\"id\":\"a\"},{\"id\":\"b\"}]}"]
    (testing "equals on a selected value"
      (is (m/matches? {"equals" {"body" "alice"} "jsonpath" {"selector" "$.user.name"}} (req "body" body)))
      (is (not (m/matches? {"equals" {"body" "bob"} "jsonpath" {"selector" "$.user.name"}} (req "body" body)))))
    (testing "a number compares by its string form"
      (is (m/matches? {"equals" {"body" 30} "jsonpath" {"selector" "$.user.age"}} (req "body" body)))
      (is (m/matches? {"equals" {"body" "30"} "jsonpath" {"selector" "$.user.age"}} (req "body" body))))
    (testing "selector keys are case-insensitive"
      (is (m/matches? {"equals" {"body" "alice"} "jsonpath" {"selector" "$.User.Name"}} (req "body" body))))
    (testing "a multi-match selector is satisfied by any element"
      (is (m/matches? {"equals" {"body" "b"} "jsonpath" {"selector" "$.items[*].id"}} (req "body" body)))
      (is (m/matches? {"equals" {"body" "a"} "jsonpath" {"selector" "$.items[0].id"}} (req "body" body)))
      (is (not (m/matches? {"equals" {"body" "b"} "jsonpath" {"selector" "$.items[0].id"}} (req "body" body)))))
    (testing "exists"
      (is (m/matches? {"exists" {"body" true} "jsonpath" {"selector" "$.user"}} (req "body" body)))
      (is (not (m/matches? {"exists" {"body" true} "jsonpath" {"selector" "$.nope"}} (req "body" body))))
      (is (m/matches? {"exists" {"body" false} "jsonpath" {"selector" "$.nope"}} (req "body" body))))
    (testing "matches"
      (is (m/matches? {"matches" {"body" "^Ali"} "jsonpath" {"selector" "$.user.name"}} (req "body" body))))
    (testing "a non-JSON body selects nothing"
      (is (not (m/matches? {"equals" {"body" "x"} "jsonpath" {"selector" "$.a"}} (req "body" "a=x"))))
      (is (m/matches? {"exists" {"body" false} "jsonpath" {"selector" "$.a"}} (req "body" "a=x"))))))

(deftest select-plain-jsonpath
  (let [doc {"a" {"b" [10 20]} "k y" 1}]
    (is (= [[10 20]] (m/select "$.a.b" doc)))
    (is (= [20] (m/select "$.a.b[1]" doc)))
    (is (= [10 20] (m/select "$.a.b[*]" doc)))
    (is (= [1] (m/select "$['k y']" doc)))
    (is (= [doc] (m/select "$" doc)))
    (is (= [] (m/select "$.a.c" doc)))))
