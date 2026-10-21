(ns mocksys.grpc-fixture
  "Test-only protos on disk and a grpc-js client for them. The client loads the
   proto with proto-loader on its own, so a test checks the daemon against an
   independent reading of the same file."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["@grpc/grpc-js" :as grpc]
            ["@grpc/proto-loader" :as loader]))

(def kv-proto "
syntax = \"proto3\";
package kv;
import \"google/protobuf/empty.proto\";
import \"types.proto\";

service Store {
  rpc Put (Item) returns (Item);
  rpc Get (Key) returns (Item);
  rpc List (google.protobuf.Empty) returns (Items);
  rpc Watch (Key) returns (stream Item);
}
")

(def types-proto "
syntax = \"proto3\";
package kv;

enum Kind { PLAIN = 0; SECRET = 1; }
message Key { string key = 1; }
message Item { string key = 1; string value = 2; int64 version = 3; Kind kind = 4; }
message Items { repeated Item items = 1; }
")

(defn write-tree!
  "A fresh temp dir holding `files` ({relative-path text}); returns the dir."
  [files]
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "mocksys-proto-"))]
    (doseq [[f text] files]
      (fs/mkdirSync (path/dirname (path/join dir f)) #js {:recursive true})
      (fs/writeFileSync (path/join dir f) text))
    dir))

(defn kv-dir [] (write-tree! {"kv.proto" kv-proto "types.proto" types-proto}))

(defn- service-ctor [proto-file svc]
  (let [def (.loadSync loader proto-file #js {:keepCase true :longs js/String :enums js/String
                                              :defaults true
                                              :includeDirs #js [(path/dirname proto-file)]})
        pkg (.loadPackageDefinition grpc def)]
    (reduce (fn [o k] (aget o k)) pkg (.split svc "."))))

(defn connect
  "A grpc-js client for service `svc` (e.g. \"kv.Store\") of `proto-file`, on
   127.0.0.1:`port`."
  [proto-file svc port]
  (let [Ctor (service-ctor proto-file svc)]
    (Ctor. (str "127.0.0.1:" port) (.createInsecure (.-credentials grpc)))))

(defn unary
  "Call `method` on `client` with `msg` (a map) and optional metadata (a map).
   Resolves {:ok reply-map :metadata {k v}} or {:code n :details s}."
  ([client method msg] (unary client method msg {}))
  ([^js client method msg md]
   (js/Promise.
    (fn [resolve]
      (let [meta (grpc/Metadata.)
            got  (atom {})]
        (doseq [[k v] md] (.add meta k v))
        (let [^js call (.call (aget client method) client (clj->js msg) meta
                              #js {:deadline (+ (js/Date.now) 5000)}
                              (fn [err reply]
                                (resolve (if err
                                           {:code (.-code err) :details (.-details err)}
                                           {:ok (js->clj reply) :metadata @got}))))]
          (.on call "metadata" (fn [^js m] (reset! got (js->clj (.getMap m)))))))))))

(defn raw-unary
  "Call an arbitrary method `path` (e.g. \"/kv.Store/Nope\") with an empty message.
   Resolves {:code n :details s} or {:ok true}."
  [port path]
  (let [c (grpc/Client. (str "127.0.0.1:" port) (.createInsecure (.-credentials grpc)))]
    (js/Promise.
     (fn [resolve]
       (.makeUnaryRequest c path identity identity (js/Buffer.alloc 0)
                          (fn [err _]
                            (.close c)
                            (resolve (if err {:code (.-code err) :details (.-details err)} {:ok true}))))))))

(defn streaming
  "Open the server-streaming `method` and resolve the status it ends with."
  [^js client method msg]
  (js/Promise.
   (fn [resolve]
     (let [^js call (.call (aget client method) client (clj->js msg))]
       (.on call "data" (fn [_]))
       (.on call "error" (fn [^js e] (resolve {:code (.-code e) :details (.-details e)})))
       (.on call "end" (fn [] (resolve {:ok true})))))))

(defn close [^js client] (.close client))

(def status (js->clj (.-status grpc)))
