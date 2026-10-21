(ns mocksys.determinism-test
  "Minted ids come from a seeded stream in shared state: a fresh twin replays the
   same ids for the same requests, and `state.randomSeed` picks another stream."
  (:require [clojure.test :refer [deftest is]]
            [mocksys.harness :as h]))

(def ^:private create-op
  {"id" "create"
   "request" {"method" "POST" "path" "/things"}
   "effect" {"mint" {"newid" {"prefix" "t_"}}
             "create" {"collection" "things" "body" {"id" "{newid}"}}}})

(def ^:private colls {"things" {"idField" "id" "seed" []}})

(defn- mint-two [opts]
  (let [state (h/fresh-state)]
    (vec (for [_ (range 2)]
           (get-in (h/run create-op {:method "POST" :path "/things"} (assoc opts :collections colls) state)
                   [:json "id"])))))

(deftest same-requests-same-ids
  (let [a (mint-two {}) b (mint-two {})]
    (is (= a b))
    (is (not= (first a) (second a)))
    (is (re-matches #"t_[0-9a-f]{24}" (first a)))))

(deftest random-seed-selects-a-stream
  (is (not= (mint-two {}) (mint-two {:random-seed 42}))))
