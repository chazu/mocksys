(ns mocksys.grpc-messages-test
  "How messages read as JSON on the real daemon, seen from a real grpc-js client: a
   request carries only the fields the caller set, and a reply is checked against
   its type before it is sent."
  (:require [clojure.test :refer [deftest testing is async]]
            [promesa.core :as p]
            ["node:path" :as path]
            [mocksys.contract :as contract]
            [mocksys.proto :as proto]
            [mocksys.grpc-fixture :as fx]))

(def ^:private rec-contract
  {"scenario" "t/rec" "protocol" "grpc" "proto" "./rec.proto"
   "seed" {"recs" [{"id" "r1" "version" "5" "flag" true "tags" ["a"]}]}
   "state" {"collections" {"recs" {"idField" "id" "seed" "recs"}}}
   "operations"
   [{"id" "update" "request" {"rpc" "rec.Recs/Update"}
     "effect" {"bind" {"id" {"body" "id"} "incoming" {"bodyJson" true}}
               "update" {"collection" "recs" "key" "{id}" "mergeVar" "incoming"}}}
    {"id" "get" "request" {"rpc" "rec.Recs/Get"}
     "effect" {"bind" {"id" {"body" "id"}}
               "get" {"collection" "recs" "key" "{id}"}}}]})

(defn- serve-rec!
  "POST the rec contract (or `imposter`, a lowered one) on a free port; resolves
   [port client admin-response]."
  ([admin] (serve-rec! admin nil {}))
  ([admin imposter client-opts]
   (p/let [dir  (fx/rec-dir)
           port (fx/free-port)
           imp  (assoc (or imposter (contract/lower rec-contract port))
                       "port" port "recordRequests" true
                       "descriptor" (:descriptor (proto/read-proto (path/join dir "rec.proto"))))
           made (fx/admin-call (str admin "/imposters") "POST" imp)]
     [port (fx/connect (path/join dir "rec.proto") "rec.Recs" port client-opts) made])))

(deftest a-request-carries-only-the-fields-the-caller-set
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [[port c made] (serve-rec! admin)
                upd  (fx/unary c "Update" {:id "r1" :display_name "Rex"})
                got  (fx/unary c "Get" {:id "r1"})
                full (fx/admin-call (str admin "/imposters/" port) "GET" nil)]
          (fx/close c)
          (is (= 201 (:status made)) (pr-str (:json made)))
          (is (= "Rex" (get-in upd [:ok "display_name"])))
          (testing "an update merges the fields sent and leaves the stored ones"
            (is (= {"id" "r1" "version" "5" "flag" true "tags" ["a"] "display_name" "Rex"}
                   (select-keys (:ok got) ["id" "version" "flag" "tags" "display_name"]))))
          (testing "the recorded body holds what the caller sent, without defaults"
            (is (= {"id" "r1" "display_name" "Rex"}
                   (js->clj (js/JSON.parse (get-in full [:json "requests" 0 "body"])))))))))))
