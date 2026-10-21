(ns mocksys.lower-state-test
  "`lower` gives a stateful contract's imposter the `state` settings its effects run
   over: the seed, each collection with its seed rows, the clock's start, the id
   stream's seed and the issuer."
  (:require [clojure.test :refer [deftest testing is]]
            [mocksys.contract :as contract]))

(def ^:private people [{"login" "ann" "name" "Ann"} {"login" "bo" "name" "Bo"}])

(defn- op [effect]
  {"id" "op" "request" {"method" "GET" "path" "/x"} "effect" effect})

(def ^:private stateful
  {"scenario"   "t/t"
   "issuer"     "http://twin.test"
   "seed"       {"people" people "clients" [{"id" "c1"}]}
   "state"      {"clock"       {"start" "2024-01-01T00:00:00Z"}
                 "randomSeed"  42
                 "collections" {"users"  {"idField" "login" "seed" "people"}
                                "groups" {"seed" "nothing"}}}
   "operations" [(op {"list" {"collection" "users"}})]})

(deftest lower-emits-the-state-effects-run-over
  (is (= {"seed"        {"people" people "clients" [{"id" "c1"}]}
          "collections" {"users"  {"idField" "login" "seed" people}
                         "groups" {"seed" []}}
          "clock"       1704067200
          "randomSeed"  42
          "issuer"      "http://twin.test"}
         (get (contract/lower stateful) "state"))))

(deftest the-clock-is-there-only-when-something-reads-it
  (testing "a limit turns it on at the fixed default start"
    (is (= 1700000000 (get-in (contract/lower {"operations" [(op {"limit" {"bucket" "rl"} "respond" {}})]})
                              ["state" "clock"]))))
  (testing "a plain respond leaves it off"
    (is (= {} (get (contract/lower {"operations" [(op {"respond" {}})]}) "state")))))

(deftest a-static-contract-has-no-state
  (is (not (contains? (contract/lower {"operations" [{"id" "s" "request" {"method" "GET" "path" "/s"}
                                                      "responses" [{"status" 200 "examples" {"e" {"select" true "body" {}}}}]}]})
                      "state"))))

(deftest an-effect-names-only-declared-collections
  (let [errors #(:errors (contract/validate {"state" {"collections" {"users" {}}} "operations" [(op %)]}))]
    (is (= ["op: create.collection names users2, which state.collections does not declare"]
           (errors {"create" {"collection" "users2"}})))
    (is (= ["op: find[1].collection names groups, which state.collections does not declare"]
           (errors {"find" [{"collection" "users" "key" "k"} {"collection" "groups" "key" "k"}] "respond" {}})))
    (is (= ["op: listJoin.via names members, which state.collections does not declare"]
           (errors {"listJoin" {"via" "members" "byField" "g" "byValue" "1" "hydrate" "users" "hydrateVia" "u"}})))
    (is (= [] (errors {"link" {"collection" "users" "id" "k"}})))
    (is (= ["op: link.collection names users, which state.collections does not declare"]
           (:errors (contract/validate {"operations" [(op {"link" {"collection" "users" "id" "k"}})]})))
        "with no collections declared at all")))
