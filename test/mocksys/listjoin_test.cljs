(ns mocksys.listjoin-test
  "TDD for the terminal `listJoin` verb: filter a membership collection to one
   side, hydrate each row into the object on the other side, drop rows whose target
   is gone, and cursor-paginate over the HYDRATED ids (same after/limit/link
   contract as `list`)."
  (:require [clojure.test :refer [deftest testing is]]
            [mocksys.harness :as h]))

(def ^:private colls
  {"users"  {"idField" "id"
             "seed" [{"id" "00u1" "profile" {"login" "alice@x"}}
                     {"id" "00u2" "profile" {"login" "bob@x"}}]}
   "groups" {"idField" "id"
             "seed" [{"id" "00g1" "profile" {"name" "Admins"}}
                     {"id" "00g2" "profile" {"name" "Users"}}]}
   "memberships"
   {"idField" "id"
    "seed" [{"id" "00g1:00u1" "gid" "00g1" "uid" "00u1"}
            {"id" "00g1:00u2" "gid" "00g1" "uid" "00u2"}
            {"id" "00g2:00u1" "gid" "00g2" "uid" "00u1"}
            ;; a dangling membership: uid has no user row → must be dropped
            {"id" "00g1:ghost" "gid" "00g1" "uid" "ghost"}]}})

(def ^:private group-users-op
  {"id" "group-users"
   "request" {"method" "GET" "path" "/api/v1/groups/{gid}/users"}
   "effect" {"bind" {"gid" {"pathRegex" "/groups/([^/]+)/users$"}
                     "after" {"query" "after"} "lim" {"query" "limit"}}
             "listJoin" {"via" "memberships" "byField" "gid" "byValue" "{gid}"
                         "hydrate" "users" "hydrateVia" "uid"
                         "after" "{after}" "limit" "{lim}"
                         "link" "</api/v1/groups/{gid}/users?after={next}>; rel=\"next\""}}})

(def ^:private user-groups-op
  {"id" "user-groups"
   "request" {"method" "GET" "path" "/api/v1/users/{uid}/groups"}
   "effect" {"bind" {"uid" {"pathRegex" "/users/([^/]+)/groups$"}}
             "listJoin" {"via" "memberships" "byField" "uid" "byValue" "{uid}"
                         "hydrate" "groups" "hydrateVia" "gid"}}})

(deftest group-users-hydrates-and-drops-dangling
  (let [r (h/run group-users-op {:path "/api/v1/groups/00g1/users"} {:collections colls})]
    (testing "returns the hydrated user objects for the group, dangling ref dropped"
      (is (= 200 (:status r)))
      (is (= 2 (count (:json r))))
      (is (= ["00u1" "00u2"] (mapv #(get % "id") (:json r))))
      (is (= "alice@x" (get-in (first (:json r)) ["profile" "login"]))))))

(deftest walks-the-relation-both-ways
  (testing "user→groups hydrates the group objects the user belongs to"
    (let [r (h/run user-groups-op {:path "/api/v1/users/00u1/groups"} {:collections colls})]
      (is (= 200 (:status r)))
      (is (= ["00g1" "00g2"] (mapv #(get % "id") (:json r))))
      (is (= "Admins" (get-in (first (:json r)) ["profile" "name"]))))))

(deftest paginates-over-hydrated-ids
  (testing "limit=1 returns the first hydrated id and a Link to the next page"
    (let [r (h/run group-users-op {:path "/api/v1/groups/00g1/users" :query {:limit "1"}}
                   {:collections colls})]
      (is (= ["00u1"] (mapv #(get % "id") (:json r))))
      (is (= "</api/v1/groups/00g1/users?after=00u1>; rel=\"next\""
             (get-in r [:headers "Link"])))))
  (testing "after the first id returns the rest, with no further Link"
    (let [r (h/run group-users-op {:path "/api/v1/groups/00g1/users" :query {:after "00u1" :limit "1"}}
                   {:collections colls})]
      (is (= ["00u2"] (mapv #(get % "id") (:json r))))
      (is (nil? (get-in r [:headers "Link"]))))))
