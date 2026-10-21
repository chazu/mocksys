(ns mocksys.grpc-contract-test
  "A `protocol: grpc` contract: operations name an rpc, which lowers to the POST
   path a gRPC call arrives on, and `validate` keeps rpc and method/path apart."
  (:require [clojure.test :refer [deftest testing is]]
            [mocksys.contract :as contract]))

(defn- op [id request]
  {"id" id "request" request
   "responses" [{"status" 200 "examples" {"ok" {"select" true "body" {"key" "a"}}}}]})

(defn- grpc [& ops] {"scenario" "t/kv" "protocol" "grpc" "proto" "./kv.proto" "operations" (vec ops)})

(deftest an-rpc-lowers-to-its-post-path
  (let [imp (contract/lower (grpc (op "get" {"rpc" "kv.Store/Get" "body" {"$.key" "a"}})))]
    (is (= "grpc" (get imp "protocol")))
    (is (= [{"deepEquals" {"method" "POST"}}
            {"deepEquals" {"path" "/kv.Store/Get"}}
            {"equals" {"body" "a"} "jsonpath" {"selector" "$.key"}}]
           (get-in imp ["stubs" 0 "predicates"])))
    (is (= {"statusCode" 200 "body" {"key" "a"}} (get-in imp ["stubs" 0 "responses" 0 "is"])))))

(deftest an-http-contract-still-lowers-to-http
  (is (= "http" (get (contract/lower {"operations" [(op "a" {"method" "GET" "path" "/a"})]}) "protocol"))))

(deftest validate-keeps-rpc-and-http-apart
  (testing "a grpc contract of rpcs is valid"
    (is (= [] (:errors (contract/validate (grpc (op "get" {"rpc" "kv.Store/Get"})))))))
  (testing "a grpc operation may not name a method or path"
    (is (= ["get: a grpc operation names request.rpc, not request.method or request.path"]
           (:errors (contract/validate (grpc (op "get" {"rpc" "kv.Store/Get" "method" "GET" "path" "/x"})))))))
  (testing "a grpc operation needs an rpc of the form pkg.Service/Method"
    (is (= ["get: request.rpc missing (pkg.Service/Method)"]
           (:errors (contract/validate (grpc (op "get" {}))))))
    (is (= ["get: request.rpc must look like pkg.Service/Method, got \"kv.Store.Get\""]
           (:errors (contract/validate (grpc (op "get" {"rpc" "kv.Store.Get"})))))))
  (testing "a grpc contract names its proto"
    (is (= ["proto: protocol grpc needs a proto file (a path relative to the scenario directory)"]
           (:errors (contract/validate (dissoc (grpc (op "get" {"rpc" "kv.Store/Get"})) "proto"))))))
  (testing "an http operation may not name an rpc"
    (is (= ["get: request.rpc needs protocol: grpc"]
           (:errors (contract/validate {"operations" [(op "get" {"rpc" "kv.Store/Get" "method" "GET" "path" "/x"})]})))))
  (testing "an unknown protocol"
    (is (= ["protocol: unsupported \"ws\" (http or grpc)"]
           (:errors (contract/validate {"protocol" "ws" "operations" [(op "a" {"method" "GET" "path" "/a"})]}))))))
