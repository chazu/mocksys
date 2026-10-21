(ns mocksys.grpc-reflection-test
  "Server reflection on the real daemon, asked with a real grpc-js client as grpcurl
   asks: list the services, fetch the files that declare one, and call it with a
   client built only from those files."
  (:require [clojure.test :refer [deftest testing is async]]
            [promesa.core :as p]
            ["node:path" :as path]
            ["@grpc/grpc-js" :as grpc]
            ["@grpc/proto-loader" :as loader]
            ["protobufjs/ext/descriptor" :as descriptor]
            [mocksys.proto :as proto]
            [mocksys.grpc-fixture :as fx]))

(defn- reflection-client
  "A client for grpc.reflection.`version`.ServerReflection on 127.0.0.1:`port`."
  [version port]
  (let [file (path/join (path/dirname (js/require.resolve "@grpc/reflection"))
                        "../proto/grpc/reflection" version "reflection.proto")
        def  (.loadSync loader file #js {:keepCase true :longs js/String :enums js/String
                                         :defaults true :oneofs true})
        Ctor (reduce aget (.loadPackageDefinition grpc def) ["grpc" "reflection" version "ServerReflection"])]
    (Ctor. (str "127.0.0.1:" port) (.createInsecure (.-credentials grpc)))))

(defn- ask
  "Send `requests` on one reflection stream; resolves the responses, in order."
  [^js client requests]
  (p/let [{:keys [call done]} (fx/bidi client "ServerReflectionInfo")
          _   (doseq [r requests] (fx/send! call r))
          _   (.end call)
          out done]
    (:messages out)))

(defn- client-from-reflection
  "A client for `svc` on `port` built from the file descriptors a reflection
   answer carries, the way grpcurl builds one."
  [answer svc port]
  (let [files (for [b (get-in answer ["file_descriptor_response" "file_descriptor_proto"])]
                (.decode descriptor/FileDescriptorProto (js/Buffer.from (clj->js b))))
        set   (.finish (.encode descriptor/FileDescriptorSet #js {:file (clj->js files)}))
        def   (.loadFileDescriptorSetFromBuffer loader set #js {:keepCase true :defaults true})
        Ctor  (reduce (fn [o k] (aget o k)) (.loadPackageDefinition grpc def) (.split svc "."))]
    (Ctor. (str "127.0.0.1:" port) (.createInsecure (.-credentials grpc)))))

(defn- service-names [answer]
  (set (map #(get % "name") (get-in answer ["list_services_response" "service"]))))

(deftest a-grpc-mock-serves-reflection
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [dir  (fx/kv-dir)
                port (fx/free-port)
                made (fx/admin-call (str admin "/imposters") "POST"
                                    {"protocol" "grpc" "port" port "name" "t/kv"
                                     "descriptor" (:descriptor (proto/read-proto (path/join dir "kv.proto")))
                                     "stubs" [{"predicates" [{"deepEquals" {"path" "/kv.Store/Get"}}]
                                               "responses" [{"is" {"statusCode" 200 "body" {"key" "from-a-stub"}}}]}]})
                v1      (reflection-client "v1" port)
                v1alpha (reflection-client "v1alpha" port)
                [listed found missing] (ask v1 [{:list_services ""}
                                                {:file_containing_symbol "kv.Store"}
                                                {:file_containing_symbol "kv.Nope"}])
                [alpha-listed]         (ask v1alpha [{:list_services ""}])
                reflected (client-from-reflection found "kv.Store" port)
                got       (fx/unary reflected "Get" {:key "a"})
                watch     (fx/server-stream reflected "Watch" {:key "a"})]
          (run! fx/close [v1 v1alpha reflected])
          (is (= 201 (:status made)) (pr-str (:json made)))
          (testing "list_services names the proto's services"
            (is (= #{"kv.Store"} (service-names listed))))
          (testing "v1alpha answers as v1 does"
            (is (= (service-names listed) (service-names alpha-listed))))
          (testing "a symbol the proto does not declare is NOT_FOUND"
            (is (= (get fx/status "NOT_FOUND") (get-in missing ["error_response" "error_code"]))))
          (testing "a client built only from the reflected files calls the mock"
            (is (= "from-a-stub" (get-in got [:ok "key"])) (pr-str got))
            (is (= [[] nil] [(:messages watch) (:code watch)]) "and knows Watch is a server stream")))))))
