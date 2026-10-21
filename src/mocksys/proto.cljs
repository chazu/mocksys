(ns mocksys.proto
  "A grpc contract's `proto`, read on the CLI side. The file loads with protobufjs
  into a descriptor (plain JSON data) that `compile!` embeds in the imposter, so the
  daemon never reads the scenario's files and a bundled scenario recompiles from
  what it carries.

  Imports resolve the way `protoc -I <dir>` resolves them, against the directory of
  the contract's proto, whichever file imports them. Google's well-known types
  (google/protobuf/*.proto) are built in. Field names are kept as written, so a
  contract's JSON bodies and predicates use the names in the .proto."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["protobufjs" :as protobuf]))

(defn- kind [^js m]
  (case [(boolean (.-requestStream m)) (boolean (.-responseStream m))]
    [false false] "unary"
    [false true]  "server-streaming"
    [true false]  "client-streaming"
    [true true]   "bidi-streaming"))

(defn- services [^js ns]
  (mapcat (fn [^js o]
            (cond (instance? protobuf/Service o)   [o]
                  (instance? protobuf/Namespace o) (services o)))
          (.-nestedArray ns)))

(defn- rpcs [^js root]
  (into {} (for [^js svc (services root)
                 ^js m   (.-methodsArray svc)]
             [(str (subs (.-fullName svc) 1) "/" (.-name m)) {:kind (kind m)}])))

(defn- read-error [^js e]
  (if (= "ENOENT" (.-code e))
    (str "cannot read proto " (.-path e))
    (ex-message e)))

(defn read-proto
  "Load the proto at `file` and the files it imports. Returns {:descriptor (the
   protobufjs JSON form) :files (the local files read, absolute) :methods
   {\"pkg.Service/Method\" {:kind}}}, the kind being unary, server-streaming,
   client-streaming or bidi-streaming. Throws naming the file on a missing
   file or import, or a syntax error (with its line)."
  [file]
  (let [file    (path/resolve file)
        include (path/dirname file)
        root    (protobuf/Root.)]
    (set! (.-resolvePath root)
          (fn [_origin target]
            (if (path/isAbsolute target) target (path/join include target))))
    (try
      (.loadSync root file #js {:keepCase true})
      (.resolveAll root)
      (catch :default e (throw (js/Error. (read-error e)))))
    {:descriptor (js->clj (.toJSON root))
     :files      (vec (filter #(path/isAbsolute %) (.-files root)))
     :methods    (rpcs root)}))

(defn grpc? [contract] (= "grpc" (get contract "protocol")))

(defn contract-file
  "The absolute path of a grpc contract's proto, which is relative to `dir`."
  [contract dir]
  (path/resolve dir (get contract "proto")))

(defn- rpc-errors [proto-name methods {:strs [id request]}]
  (let [rpc (get request "rpc")]
    (when (string? rpc)
      (let [[svc _] (str/split rpc #"/" 2)
            m       (get methods rpc)
            in-svc  (sort (for [k (keys methods) :when (str/starts-with? k (str svc "/"))]
                            (second (str/split k #"/" 2))))]
        (cond
          m nil
          (seq in-svc) (str id ": rpc " rpc " is not in " proto-name " (" svc " has " (str/join ", " in-svc) ")")
          :else (str id ": rpc " rpc " is not in " proto-name " (services: "
                     (str/join ", " (sort (distinct (map #(first (str/split % #"/" 2)) (keys methods))))) ")"))))))

(defn- batch-merge-warnings
  "A client stream's body is the array of its messages, so a collection verb that
   merges it as a record stores the array: a warning for each such merge."
  [methods {:strs [id request effect]}]
  (when (= "client-streaming" (:kind (get methods (get request "rpc"))))
    (let [batch (set (for [[v spec] (get effect "bind") :when (get spec "bodyJson")] v))]
      (for [[verb v] (concat (map #(vector "create" %) (get-in effect ["create" "merge"]))
                             [["update" (get-in effect ["update" "mergeVar"])]])
            :when (batch v)]
        (str id ": " verb " merges " v ", the client stream's body, which is the array of its messages, "
             "not one message; the record would hold the array")))))

(defn contract-problems
  "What `validate` finds wrong with a grpc contract's proto, as {:errors :warnings}:
   errors when the proto does not load or an operation's rpc is not in it, warnings
   when an operation merges a client stream's batch into a record. `operations`
   should be the expanded ones. Empty for an http contract, or a grpc one with no
   proto named (contract/validate reports that)."
  ([contract dir] (contract-problems contract (get contract "operations") dir))
  ([contract operations dir]
   (if-not (and (grpc? contract) (string? (get contract "proto")))
     {:errors [] :warnings []}
     (let [loaded (try (read-proto (contract-file contract dir))
                       (catch :default e {:error (ex-message e)}))]
       (if-let [e (:error loaded)]
         {:errors [(str "proto: " e)] :warnings []}
         {:errors   (vec (keep #(rpc-errors (get contract "proto") (:methods loaded) %) operations))
          :warnings (vec (mapcat #(batch-merge-warnings (:methods loaded) %) operations))})))))

(defn with-descriptor
  "`imposter` lowered from `contract`, with the descriptor of its proto when the
   contract is grpc. Throws when the proto does not load."
  [imposter contract dir]
  (if (grpc? contract)
    (let [file (contract-file contract dir)]
      (try (assoc imposter "descriptor" (:descriptor (read-proto file)))
           (catch :default e (throw (js/Error. (str "proto " (get contract "proto") ": " (ex-message e)))))))
    imposter))

(defn files-outside
  "The local files a grpc contract's proto reads that are not under `dir`, so a
   bundle of `dir` would not carry them. Empty for an http contract."
  [contract dir]
  (if-not (grpc? contract)
    []
    (let [file (contract-file contract dir)
          dir  (str (path/resolve dir) path/sep)]
      (if-not (fs/existsSync file)
        [file]
        (vec (remove #(str/starts-with? % dir) (:files (read-proto file))))))))

(defn files-inside
  "The local files a grpc contract's proto reads that are under `dir`: the proto and
   its imports there. When the proto does not load, the proto alone if it exists.
   Empty for an http contract."
  [contract dir]
  (if-not (grpc? contract)
    []
    (let [file  (contract-file contract dir)
          dir   (str (path/resolve dir) path/sep)
          files (try (:files (read-proto file))
                     (catch :default _ (when (fs/existsSync file) [file])))]
      (vec (filter #(str/starts-with? % dir) files)))))
