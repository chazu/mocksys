(ns mocksys.list-link-test
  "`list`'s `link` template interpolates over every bound variable, like `listJoin`'s,
   so a next-page Link can carry the request's own `limit` or `filter`."
  (:require [clojure.test :refer [deftest testing is]]
            [mocksys.harness :as h]))

(def ^:private colls
  {"users" {"idField" "id"
            "seed" [{"id" "00u1"} {"id" "00u2"} {"id" "00u3"}]}})

(def ^:private list-op
  {"id" "list-users"
   "request" {"method" "GET" "path" "/api/v1/users"}
   "effect" {"bind" {"after" {"query" "after"} "lim" {"query" "limit"}}
             "list" {"collection" "users" "after" "{after}" "limit" "{lim}"
                     "link" "</api/v1/users?after={next}&limit={lim}>; rel=\"next\""}}})

(deftest link-sees-bound-variables
  (testing "the next-page Link carries the bound limit alongside the cursor"
    (let [r (h/run list-op {:path "/api/v1/users" :query {:limit "1"}} {:collections colls})]
      (is (= ["00u1"] (mapv #(get % "id") (:json r))))
      (is (= "</api/v1/users?after=00u1&limit=1>; rel=\"next\""
             (get-in r [:headers "Link"]))))))
