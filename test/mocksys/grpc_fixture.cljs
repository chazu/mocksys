(ns mocksys.grpc-fixture
  "Test-only protos on disk and a grpc-js client for them. The client loads the
   proto with proto-loader on its own, so a test checks the daemon against an
   independent reading of the same file."
  (:require [promesa.core :as p]
            ["node:fs" :as fs]
            ["node:net" :as net]
            ["node:os" :as os]
            ["node:path" :as path]
            ["@grpc/grpc-js" :as grpc]
            ["@grpc/proto-loader" :as loader]
            [mocksys.daemon-fixture :as daemon]))

(defn free-port
  "A port nothing listens on now."
  []
  (js/Promise.
   (fn [resolve]
     (let [srv (.createServer net)]
       (.listen srv 0 (fn [] (let [port (.-port (.address srv))] (.close srv #(resolve port)))))))))

(defn admin-call
  "Call the daemon's admin API; resolves {:status :json}."
  [url method body]
  (p/let [res  (js/fetch url (clj->js (cond-> {:method method :headers (merge {"Content-Type" "application/json"} (daemon/auth url))}
                                         body (assoc :body (js/JSON.stringify (clj->js body))))))
          text (.text res)]
    {:status (.-status res) :json (try (js->clj (js/JSON.parse text)) (catch :default _ nil))}))

(def with-daemon daemon/with-daemon)

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
  rpc PutMany (stream Item) returns (Items);
  rpc Sync (stream Item) returns (stream Item);
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

(def rec-proto "
syntax = \"proto3\";
package rec;
import \"google/protobuf/timestamp.proto\";

service Recs {
  rpc Put (Rec) returns (Rec);
  rpc Update (Rec) returns (Rec);
  rpc Get (RecKey) returns (Rec);
  rpc List (RecKey) returns (RecList);
}

enum Tier { FREE = 0; PRO = 1; }
message RecKey { string id = 1; }
message Rec {
  string id = 1;
  int64 version = 2;
  bool flag = 3;
  repeated string tags = 4;
  string display_name = 5;
  Tier tier = 6;
  int32 count = 7;
  oneof choice { string a = 8; int32 b = 9; }
  map<string, string> labels = 10;
  google.protobuf.Timestamp at = 11;
  Rec child = 12;
}
message RecList { repeated Rec recs = 1; map<string, string> meta = 2; }
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
(defn rec-dir [] (write-tree! {"rec.proto" rec-proto}))

(defn- service-ctor [proto-file svc keep-case]
  (let [def (.loadSync loader proto-file #js {:keepCase keep-case :longs js/String :enums js/String
                                              :defaults true
                                              :includeDirs #js [(path/dirname proto-file)]})
        pkg (.loadPackageDefinition grpc def)]
    (reduce (fn [o k] (aget o k)) pkg (.split svc "."))))

(defn connect
  "A grpc-js client for service `svc` (e.g. \"kv.Store\") of `proto-file`, on
   127.0.0.1:`port`. It names fields as the proto does, or with `:camel? true` in
   lowerCamelCase, grpc-js's default (keepCase false)."
  ([proto-file svc port] (connect proto-file svc port {}))
  ([proto-file svc port {:keys [camel?]}]
   (let [Ctor (service-ctor proto-file svc (not camel?))]
     (Ctor. (str "127.0.0.1:" port) (.createInsecure (.-credentials grpc))))))

(defn unary
  "Call `method` on `client` with `msg` (a map), optional metadata (a map) and
   options ({:deadline-ms n}, 5000 by default). Resolves {:ok reply-map :metadata
   {k v}} or {:code n :details s}."
  ([client method msg] (unary client method msg {} {}))
  ([client method msg md] (unary client method msg md {}))
  ([^js client method msg md {:keys [deadline-ms] :or {deadline-ms 5000}}]
   (js/Promise.
    (fn [resolve]
      (let [meta (grpc/Metadata.)
            got  (atom {})]
        (doseq [[k v] md] (.add meta k v))
        (let [^js call (.call (aget client method) client (clj->js msg) meta
                              #js {:deadline (+ (js/Date.now) deadline-ms)}
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

(defn- collect
  "Resolve what the readable `call` delivers: {:messages [maps] :metadata {k v}},
   plus :code and :details when it ends with an error."
  [^js call resolve]
  (let [got (atom {:messages [] :metadata {}})]
    (.on call "metadata" (fn [^js m] (swap! got assoc :metadata (js->clj (.getMap m)))))
    (.on call "data" (fn [m] (swap! got update :messages conj (js->clj m))))
    (.on call "error" (fn [^js e] (resolve (assoc @got :code (.-code e) :details (.-details e)))))
    (.on call "end" (fn [] (resolve @got)))))

(defn server-stream
  "Call the server-streaming `method` with `msg`; resolves as `collect`."
  [^js client method msg]
  (js/Promise.
   (fn [resolve]
     (collect (.call (aget client method) client (clj->js msg)) resolve))))

(defn client-stream
  "Send `msgs` on the client-streaming `method` and half-close; resolves as `unary`."
  [^js client method msgs]
  (js/Promise.
   (fn [resolve]
     (let [^js call (.call (aget client method) client
                           (fn [err reply]
                             (resolve (if err {:code (.-code err) :details (.-details err)} {:ok (js->clj reply)}))))]
       (doseq [m msgs] (.write call (clj->js m)))
       (.end call)))))

(defn bidi
  "Open the bidirectional `method`. Returns {:call :done}: write to `call` with
   `send!`, half-close with `.end`; `done` resolves as `collect`."
  [^js client method]
  (let [^js call (.call (aget client method) client)]
    {:call call :done (js/Promise. (fn [resolve] (collect call resolve)))}))

(defn send! [^js call msg] (.write call (clj->js msg)))

(defn next-message
  "Resolve the next message `call` reads, as a map."
  [^js call]
  (js/Promise. (fn [resolve] (.once call "data" (fn [m] (resolve (js->clj m)))))))

(defn close [^js client] (.close client))

(defn capturing-stderr
  "Call `f`, which returns a promise, while collecting what is written to stderr,
   the daemon's log. Resolves [result text]."
  [f]
  (let [^js err js/process.stderr
        orig    (.-write err)
        got     (atom [])]
    (set! (.-write err) (fn [chunk & _] (swap! got conj (str chunk)) true))
    (-> (p/let [r (f)] [r (apply str @got)])
        (p/finally #(set! (.-write err) orig)))))

(def status (js->clj (.-status grpc)))
