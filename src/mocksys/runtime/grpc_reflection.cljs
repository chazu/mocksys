(ns mocksys.runtime.grpc-reflection
  "Server reflection, v1 and v1alpha, for a grpc imposter, so grpcurl and Postman
  can list its services and call them without the proto. @grpc/reflection answers
  each request from the imposter's package definition. Its own addToServer reads
  reflection.proto from beside its sources, a path the bun binary bakes in from the
  machine that built it, so the service is registered here from the text below."
  (:require [clojure.string :as str]
            ["@grpc/proto-loader" :as loader]
            ["@grpc/reflection/build/src/implementations/reflection-v1" :refer [ReflectionV1Implementation]]
            ["protobufjs" :as protobuf]))

(def ^:private reflection-proto
  "syntax = \"proto3\";
package grpc.reflection.VERSION;
service ServerReflection {
  rpc ServerReflectionInfo(stream ServerReflectionRequest) returns (stream ServerReflectionResponse);
}
message ServerReflectionRequest {
  string host = 1;
  oneof message_request {
    string file_by_filename = 3;
    string file_containing_symbol = 4;
    ExtensionRequest file_containing_extension = 5;
    string all_extension_numbers_of_type = 6;
    string list_services = 7;
  }
}
message ExtensionRequest { string containing_type = 1; int32 extension_number = 2; }
message ServerReflectionResponse {
  string valid_host = 1;
  ServerReflectionRequest original_request = 2;
  oneof message_response {
    FileDescriptorResponse file_descriptor_response = 4;
    ExtensionNumberResponse all_extension_numbers_response = 5;
    ListServiceResponse list_services_response = 6;
    ErrorResponse error_response = 7;
  }
}
message FileDescriptorResponse { repeated bytes file_descriptor_proto = 1; }
message ExtensionNumberResponse { string base_type_name = 1; repeated int32 extension_number = 2; }
message ListServiceResponse { repeated ServiceResponse service = 1; }
message ServiceResponse { string name = 1; }
message ErrorResponse { int32 error_code = 1; string error_message = 2; }
")

(def ^:private service-definitions
  "The ServerReflection service of each version. The loader options are the ones
   @grpc/reflection reads its requests and writes its responses in."
  (delay
    (for [version ["v1" "v1alpha"]
          :let [root (.-root (protobuf/parse (str/replace reflection-proto "VERSION" version)))
                def  (.fromJSON loader (.toJSON root)
                                #js {:longs js/String :enums js/String :bytes js/Array
                                     :defaults true :oneofs true})]]
      (aget def (str "grpc.reflection." version ".ServerReflection")))))

(defn add!
  "Serve reflection on `server` for the services of `pkg-def`, proto-loader's
   reading of the imposter's descriptor."
  [^js server pkg-def]
  (let [^js answers (ReflectionV1Implementation. pkg-def)]
    (doseq [svc @service-definitions]
      (.addService server svc
                   #js {:ServerReflectionInfo
                        (fn [^js call]
                          (.on call "data" #(.write call (.handleServerReflectionRequest answers %)))
                          (.on call "end" #(.end call)))}))))
