(ns mocksys.proto-test
  "Loading a contract's proto: imports, field names, the methods it declares, the
   local files it read, and the messages `validate` reports for a grpc contract."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            ["node:path" :as path]
            [mocksys.proto :as proto]
            [mocksys.grpc-fixture :as fx]))

(defn- thrown-message [f]
  (try (f) nil (catch :default e (ex-message e))))

(deftest loads-a-proto-with-its-imports
  (let [dir (fx/kv-dir)
        {:keys [descriptor files methods]} (proto/load-file (path/join dir "kv.proto"))]
    (testing "every rpc, with whether it is unary"
      (is (= {"kv.Store/Put"   {:unary? true  :kind "unary"}
              "kv.Store/Get"   {:unary? true  :kind "unary"}
              "kv.Store/List"  {:unary? true  :kind "unary"}
              "kv.Store/Watch" {:unary? false :kind "server-streaming"}}
             methods)))
    (testing "the local files it read, absolute; well-known google types are built in"
      (is (= #{(path/join dir "kv.proto") (path/join dir "types.proto")} (set files))))
    (testing "the descriptor is plain data naming the messages"
      (is (map? (get-in descriptor ["nested" "kv" "nested" "Item"]))))))

(deftest imports-resolve-from-the-root-protos-directory
  (let [dir (fx/write-tree! {"api.proto"     "syntax = \"proto3\"; package a; import \"sub/b.proto\";
                                              service S { rpc M (B) returns (C); }"
                             "sub/b.proto"   "syntax = \"proto3\"; package a; import \"common.proto\"; message B { C c = 1; }"
                             "common.proto"  "syntax = \"proto3\"; package a; message C { string display_name = 1; }"})
        {:keys [descriptor files]} (proto/load-file (path/join dir "api.proto"))]
    (is (= 3 (count files)) "sub/b.proto's import of common.proto resolves against the root's directory")
    (is (contains? (get-in descriptor ["nested" "a" "nested" "C" "fields"]) "display_name")
        "field names are kept as written")))

(deftest load-errors-name-the-problem
  (let [dir (fx/write-tree! {"bad.proto"     "syntax = \"proto3\";\nmessage M {\n  string x = ;\n}"
                             "orphan.proto"  "syntax = \"proto3\"; import \"nowhere.proto\"; message M {}"})]
    (testing "a missing file"
      (is (str/includes? (str (thrown-message #(proto/load-file (path/join dir "none.proto"))))
                         (str "cannot read proto " (path/join dir "none.proto")))))
    (testing "a syntax error names the file and line"
      (let [m (str (thrown-message #(proto/load-file (path/join dir "bad.proto"))))]
        (is (str/includes? m "bad.proto") m)
        (is (str/includes? m "line 3") m)))
    (testing "a missing import"
      (is (str/includes? (str (thrown-message #(proto/load-file (path/join dir "orphan.proto"))))
                         "nowhere.proto")))))

(defn- grpc-contract [ops]
  {"protocol" "grpc" "proto" "./kv.proto" "operations" ops})

(defn- op [id rpc] {"id" id "request" {"rpc" rpc}
                    "responses" [{"status" 200 "examples" {"ok" {"select" true "body" {}}}}]})

(deftest contract-errors-check-rpcs-against-the-proto
  (let [dir (fx/kv-dir)]
    (testing "a contract whose rpcs are unary methods of the proto has none"
      (is (= [] (proto/contract-errors (grpc-contract [(op "get" "kv.Store/Get")]) dir))))
    (testing "an rpc the proto does not declare"
      (is (= ["nope: rpc kv.Store/Nope is not in ./kv.proto (kv.Store has Get, List, Put, Watch)"]
             (proto/contract-errors (grpc-contract [(op "nope" "kv.Store/Nope")]) dir))))
    (testing "a service the proto does not declare"
      (is (= ["x: rpc kv.Other/Get is not in ./kv.proto (services: kv.Store)"]
             (proto/contract-errors (grpc-contract [(op "x" "kv.Other/Get")]) dir))))
    (testing "a streaming rpc"
      (is (= ["watch: rpc kv.Store/Watch is server-streaming; only unary rpcs are served"]
             (proto/contract-errors (grpc-contract [(op "watch" "kv.Store/Watch")]) dir))))
    (testing "a proto that does not load"
      (is (= [(str "proto: cannot read proto " (path/join dir "gone.proto"))]
             (proto/contract-errors (assoc (grpc-contract []) "proto" "gone.proto") dir))))
    (testing "an http contract has none to report"
      (is (= [] (proto/contract-errors {"operations" []} dir))))))
