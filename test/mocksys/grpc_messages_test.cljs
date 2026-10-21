(ns mocksys.grpc-messages-test
  "How messages read as JSON on the real daemon, seen from a real grpc-js client: a
   request carries only the fields the caller set, and a reply is checked against
   its type before it is sent."
  (:require [clojure.test :refer [deftest testing is async]]
            [clojure.string :as str]
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

(defn- stubbed
  "An imposter answering `rpc` (\"rec.Recs/Get\") with the `is` response."
  [& rpc-is]
  {"protocol" "grpc" "name" "t/rec"
   "stubs" (vec (for [[rpc is] (partition 2 rpc-is)]
                  {"predicates" [{"deepEquals" {"path" (str "/" rpc)}}] "responses" [{"is" is}]}))})

(deftest a-map-field-is-not-the-repeated-field-an-array-fills
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [[_ c made] (serve-rec! admin (stubbed "rec.Recs/List" {"statusCode" 200 "body" [{"id" "r1"} {"id" "r2"}]}) {})
                lst        (fx/unary c "List" {})]
          (fx/close c)
          (is (= 201 (:status made)) (pr-str (:json made)))
          (is (= ["r1" "r2"] (map #(get % "id") (get-in lst [:ok "recs"]))) (pr-str lst)))))))

(defn- get-by-id
  "An imposter whose rec.Recs/Get answers request id k with a 200 and body v, for
   each [k v] of `bodies`, and whose List answers `list-body`."
  [bodies list-body]
  {"protocol" "grpc" "name" "t/rec"
   "stubs" (conj (vec (for [[k body] bodies]
                        {"predicates" [{"deepEquals" {"path" "/rec.Recs/Get"}}
                                       {"equals" {"body" k} "jsonpath" {"selector" "$.id"}}]
                         "responses" [{"is" {"statusCode" 200 "body" body}}]}))
                 {"predicates" [{"deepEquals" {"path" "/rec.Recs/List"}}]
                  "responses" [{"is" {"statusCode" 200 "body" list-body}}]})})

(def ^:private codes fx/status)

(deftest a-reply-is-checked-against-its-type
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [bodies {"camel"  {"id" "camel" "displayName" "Rex"}
                        "loose"  {"id" "loose" "count" "7" "version" 5 "tier" 1 "labels" {"k" "v"}
                                  "child" {"id" "c" "tier" "PRO"} "at" "2026-10-21T10:00:00.5Z" "flag" nil}
                        "flag"   {"id" "flag" "flag" "yes"}
                        "count"  {"id" "count" "count" "seven"}
                        "tier"   {"id" "tier" "tier" "GOLD"}
                        "tags"   {"id" "tags" "tags" "a"}
                        "child"  {"id" "child" "child" "c"}
                        "at"     {"id" "at" "at" "yesterday"}
                        "deep"   {"id" "deep" "child" {"child" {"count" 1.5}}}
                        "extra"  {"id" "extra" "colour" "red" "child" {"id" "c" "size" 2}}}
                [port c made] (serve-rec! admin (get-by-id bodies [{"id" "a"} {"id" "b" "flag" 1}]) {})
                call  (fn [k] (fx/unary c "Get" {:id k}))
                [rs log] (fx/capturing-stderr
                          #(p/let [rs (p/all (map call ["camel" "loose" "flag" "count" "tier" "tags"
                                                        "child" "at" "deep" "extra"]))
                                   again (call "extra")
                                   lst   (fx/unary c "List" {})]
                             (conj (vec rs) again lst)))
                [camel loose flag cnt tier tags child at deep extra again lst] rs]
          (fx/close c)
          (is (= 201 (:status made)) (pr-str (:json made)))
          (testing "a field may be named as in the proto or in lowerCamelCase"
            (is (= "Rex" (get-in camel [:ok "display_name"])) (pr-str camel)))
          (testing "JSON forms proto3 allows encode: numeric strings, enum numbers, an RFC 3339 Timestamp, null"
            (is (= {"count" 7 "version" "5" "tier" "PRO" "labels" {"k" "v"} "flag" false
                    "at" {"seconds" "1792576800" "nanos" 500000000}}
                   (select-keys (:ok loose) ["count" "version" "tier" "labels" "flag" "at"]))
                (pr-str loose))
            (is (= {"id" "c" "tier" "PRO"} (select-keys (get-in loose [:ok "child"]) ["id" "tier"]))))
          (testing "a value of the wrong type is INTERNAL, naming the field and the problem"
            (doseq [[r field problem] [[flag "flag" "bool"] [cnt "count" "integer"] [tier "tier" "GOLD"]
                                       [tags "tags" "array"] [child "child" "object"] [at "at" "RFC 3339"]
                                       [deep "child.child.count" "integer"]
                                       [lst "recs[1].flag" "bool"]]]
              (is (= (codes "INTERNAL") (:code r)) (pr-str field r))
              (is (re-find (re-pattern (str "\\b" (str/replace field #"[.\[\]]" "\\$&") ": [^;]*" problem))
                           (str (:details r)))
                  (pr-str field r))
              (is (str/includes? log (str (:details r))) (str "the daemon log has " field))))
          (testing "a key no field declares is dropped, with one warning in the daemon log"
            (is (= "extra" (get-in extra [:ok "id"])) (pr-str extra))
            (is (= "extra" (get-in again [:ok "id"])))
            (is (= 1 (count (re-seq #"colour" log))) log)
            (is (str/includes? log "child.size"))
            (is (str/includes? log (str port)) "the warning names the mock")))))))
