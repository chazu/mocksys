(ns mocksys.runtime.grpc
  "gRPC imposters: a grpc-js server per imposter, built from the descriptor the
  imposter carries. Every unary method of the proto is registered; a call becomes
  the request the stubs already match on (POST /pkg.Service/Method, metadata as
  headers, the decoded message as a JSON body), so matching, injection and state
  are the HTTP imposter's. Streaming methods, and methods the proto does not
  declare, answer UNIMPLEMENTED.

  The outcome maps back as follows. A 2xx JSON object body is the reply message,
  checked against the reply type by mocksys.runtime.proto-json; a JSON array fills
  the reply type's one repeated field. A reply that does not fit its type is
  INTERNAL, and the daemon log (stderr) says why. Any other status is a
  gRPC error: a body {grpcCode, message} picks the code (a name or a number),
  otherwise `http->grpc` does. Response headers become initial metadata. A fault
  is UNAVAILABLE."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            ["@grpc/grpc-js" :as grpc]
            ["@grpc/proto-loader" :as loader]
            ["protobufjs" :as protobuf]
            [mocksys.runtime.imposter :as imposter]
            [mocksys.runtime.proto-json :as pj]))

(def ^:private codes (js->clj (.-status grpc)))

(def ^:private loader-options
  "How a request reads as JSON: int64 and bytes (base64) as strings, so no
   precision is lost; enums by name; only the fields the message carries, so an
   update that merges the request leaves the fields it did not send."
  #js {:longs js/String :enums js/String :bytes js/String :defaults false :oneofs false})

(def http->grpc
  "The gRPC code for an HTTP status, the inverse of google.rpc.Code's HTTP mapping
   where it names one code for a status. 408, 412, 422 and 502 are not in that
   mapping; they take the code whose meaning they share (a timeout, a failed
   precondition, a bad argument, an unreachable upstream). A status not listed is
   UNKNOWN."
  {400 "INVALID_ARGUMENT" 401 "UNAUTHENTICATED" 403 "PERMISSION_DENIED" 404 "NOT_FOUND"
   408 "DEADLINE_EXCEEDED" 409 "ALREADY_EXISTS" 412 "FAILED_PRECONDITION"
   422 "INVALID_ARGUMENT" 429 "RESOURCE_EXHAUSTED" 499 "CANCELLED" 500 "INTERNAL"
   501 "UNIMPLEMENTED" 502 "UNAVAILABLE" 503 "UNAVAILABLE" 504 "DEADLINE_EXCEEDED"})

(defn- bad [msg] (throw (ex-info msg {:code "bad data"})))

;; --- calls -> requests ---------------------------------------------------------

(defn- metadata->headers [^js md]
  (into {} (for [[k vs] (js->clj (.toJSON md))
                 :let [vs (mapv #(if (js/Buffer.isBuffer %) (.toString % "base64") %) vs)]]
             [k (if (= 1 (count vs)) (first vs) vs)])))

(defn- request-of [^js call path]
  {"method"  "POST"
   "path"    path
   "query"   {}
   "headers" (metadata->headers (.-metadata call))
   "body"    (js/JSON.stringify (.-request call))})

;; --- outcomes -> replies -------------------------------------------------------

(def ^:private hop-headers #{"connection" "content-length" "content-type" "transfer-encoding" "keep-alive" "te" "host"})

(defn- headers->metadata [headers]
  (let [md (grpc/Metadata.)]
    (doseq [[k v] headers
            :let [k (str/lower-case k)]
            :when (not (or (hop-headers k) (str/starts-with? k "grpc-") (str/starts-with? k ":")))
            v (if (vector? v) v [v])]
      (try (.add md k (if (str/ends-with? k "-bin") (js/Buffer.from (str v) "base64") (str v)))
           (catch :default _ nil)))
    md))

(defn- code-of [c]
  (let [n (cond (string? c) (get codes (str/upper-case c))
                (integer? c) c)]
    (when (and n (< 0 n 17)) n)))

(defn- parse [text] (try (js/JSON.parse text) (catch :default _ ::none)))

(defn- repeated-field
  "The name of the reply type's only repeated field, or nil. A map field is not
   one, though the descriptor marks it repeated."
  [^js reply-type]
  (let [fields (filter #(and (.-repeated ^js %) (not (.-map ^js %))) (.-fieldsArray reply-type))]
    (when (= 1 (count fields)) (.-name ^js (first fields)))))

(defn- internal [details] {:code (codes "INTERNAL") :details details :log? true})

(defn- reply-of
  "The reply for a 2xx body `doc`, checked against `reply-type` (see
   mocksys.runtime.proto-json): {:value} or {:error}, and :unknown, the keys no
   field declares."
  [doc body ^js reply-type]
  (let [repeated (repeated-field reply-type)
        doc      (if (and (array? doc) repeated) (js-obj repeated doc) doc)
        type     (subs (.-fullName reply-type) 1)]
    (if (and (object? doc) (not (array? doc)))
      (let [{:keys [value problems unknown]} (pj/encode reply-type doc)]
        (if (seq problems)
          {:error (internal (str "the mock's reply does not encode as " type ": " (str/join "; " problems)))}
          {:value value :unknown unknown}))
      (let [shown (if (= ::none doc) body (js/JSON.stringify doc))]
        {:error (internal (str "the mock's reply is not a JSON object"
                               (when (array? doc) (str " (a JSON array needs a reply type with one repeated field; " type " has "
                                                       (if (seq (filter #(.-repeated ^js %) (.-fieldsArray reply-type))) "several" "none") ")"))
                               ": " (subs shown 0 (min 80 (count shown)))))}))))

(defn- outcome->reply
  "The gRPC answer for an imposter outcome ({:status :headers :body} or {:fault}):
   {:value reply-object :unknown [paths]} or {:error {:code :details :log?}}, with
   :metadata (the response headers) and :log, lines for the daemon log. An error
   with :log? is the mock's own fault, so it is logged too."
  [{:keys [status body fault headers]} reply-type]
  (let [meta (when-not fault (headers->metadata headers))
        doc  (if (str/blank? body) #js {} (parse body))]
    (cond
      fault
      {:error {:code (codes "UNAVAILABLE") :details (str "fault " fault)}}

      (<= 200 status 299)
      (assoc (reply-of doc body reply-type) :metadata meta)

      :else
      (let [obj (when (and (object? doc) (not (array? doc))) (js->clj doc))]
        {:metadata meta
         :error {:code    (or (code-of (get obj "grpcCode")) (codes (get http->grpc status "UNKNOWN")))
                 :details (or (some #(when (string? %) %) [(get obj "message") (get obj "error")])
                              (when (and (= ::none doc) (not (str/blank? body))) body)
                              (str "HTTP " status))}}))))

(defn- log! [line] (.write js/process.stderr (str line "\n")))

(defn- no-proxy [_ _]
  (p/rejected (js/Error. "proxy responses are not served over grpc")))

(defn- handler
  "Answer calls to `md` from `imp`. A reply's unknown keys are logged once for each
   set of them, since every call to a twin answers from the same records."
  [imp ^js md ^js reply-type]
  (let [path   (.-path md)
        say    #(log! (str "grpc mock on port " (imposter/port imp) ", " path ": " %))
        warned (atom #{})]
    (fn [^js call callback]
      (-> (p/let [out (imposter/respond imp (request-of call path) {:proxy! no-proxy})
                  _   (when (pos? (:wait out 0)) (p/delay (:wait out)))]
            (let [{:keys [metadata value error unknown]} (outcome->reply out reply-type)
                  keys (into (sorted-set) (map #(str/replace % #"\[\d+\]" "[]")) unknown)]
              (when (and (seq keys) (not (@warned keys)))
                (swap! warned conj keys)
                (say (str "dropped reply field(s) " (subs (.-fullName reply-type) 1) " does not declare: " (str/join ", " keys))))
              (when (:log? error) (say (:details error)))
              (when metadata (.sendMetadata call metadata))
              (if error
                (callback (clj->js (dissoc error :log?)))
                (callback nil value))))
          (p/catch (fn [e] (callback #js {:code (codes "INTERNAL") :details (ex-message e)})))))))

;; --- the virtual clock ------------------------------------------------------------
;; A twin with a virtual clock has a stub at POST /_mocksys/clock (see
;; inject/clock-control-stub). No proto declares that path, so every grpc imposter
;; also serves it as a raw method whose messages are JSON text, and `mocksys clock`
;; calls it through `clock-call`.

(def ^:private clock-path "/_mocksys/clock")
(defn- text-in [^js buf] (.toString buf "utf8"))
(defn- text-out [s] (js/Buffer.from (str s) "utf8"))

(defn- clock-handler [imp]
  (fn [^js call callback]
    (-> (p/let [out (imposter/respond imp {"method" "POST" "path" clock-path "query" {} "headers" {}
                                           "body" (.-request call)}
                                      {:proxy! no-proxy})]
          (callback nil (:body out)))
        (p/catch (fn [e] (callback #js {:code (codes "INTERNAL") :details (ex-message e)}))))))

(defn clock-call
  "POST `body` to the clock of the grpc twin on `port`; resolves the parsed reply."
  [port body]
  (let [^js client (grpc/Client. (str "127.0.0.1:" port) (.createInsecure grpc/credentials))]
    (js/Promise.
     (fn [resolve reject]
       (.makeUnaryRequest client clock-path text-out text-in (js/JSON.stringify (clj->js body))
                          (fn [^js err reply]
                            (.close client)
                            (if err
                              (reject (js/Error. (.-details err)))
                              (resolve (try (js->clj (js/JSON.parse reply)) (catch :default _ nil))))))))))

;; --- servers -------------------------------------------------------------------

(defn- package-definition
  "The descriptor read by proto-loader, which grpc-js serves from, and by
   protobufjs, which knows the types: [pkg-def root]."
  [descriptor]
  (try [(.fromJSON loader (clj->js descriptor) loader-options)
        (.resolveAll (.fromJSON protobuf/Root (clj->js descriptor)))]
       (catch :default e (bad (str "descriptor: " (ex-message e))))))

(defn- services
  "[[service-definition {method handler}]] for each service; only unary methods get
   a handler, so grpc-js answers the rest UNIMPLEMENTED. `root` is the protobufjs
   reading of the same descriptor, which knows each reply's type."
  [imp pkg-def ^js root]
  (for [k (js-keys pkg-def)
        :let [^js svc (aget pkg-def k)]
        :when (nil? (.-format svc))]
    [svc (into {} (for [m (js-keys svc)
                        :let [^js md  (aget svc m)
                              ^js rpc (aget (.-methods (.lookupService root k)) m)]
                        :when (not (or (.-requestStream md) (.-responseStream md)))]
                    [m (handler imp md (.-resolvedResponseType rpc))]))]))

(defn- bind! [^js server addr]
  (js/Promise.
   (fn [resolve reject]
     (.bindAsync server addr (.createInsecure grpc/ServerCredentials)
                 (fn [err port] (if err (reject err) (resolve port)))))))

(defn- no-ipv6?
  "Whether a bind failed because the host has no ::1, which is the one failure the
   ::1 bind may have. grpc-js puts the cause in the message, not in a code."
  [e]
  (boolean (re-find #"EADDRNOTAVAIL|EAFNOSUPPORT" (str (ex-message e)))))

(defn- check-stubs! [imp]
  (doseq [s (:stubs @imp), r (get-in s [:spec "responses"]) :when (contains? r "proxy")]
    (bad (str "stubs[" (:id s) "]: proxy responses are not supported for protocol grpc"))))

(defn start!
  "Serve `imp` over gRPC on 127.0.0.1 and, where the host has IPv6, ::1, as the HTTP
   imposters do: a port taken on either address fails the start. Resolves
   [{:stop f}], where `f` closes the server and resolves when it has."
  [imp]
  (check-stubs! imp)
  (let [[pkg-def root] (package-definition (get-in @imp [:spec "descriptor"]))
        ^js server     (grpc/Server.)
        port           (imposter/port imp)
        stop           (fn [] (.forceShutdown server) (p/resolved nil))]
    (doseq [[svc impl] (services imp pkg-def root)]
      (.addService server svc (clj->js impl)))
    (.register server clock-path (clock-handler imp) text-out text-in "unary")
    (-> (p/let [_ (bind! server (str "127.0.0.1:" port))
                _ (p/catch (bind! server (str "[::1]:" port))
                           (fn [e] (when-not (no-ipv6? e) (throw e))))]
          [{:stop stop}])
        (p/catch (fn [e]
                   (.forceShutdown server)
                   (throw (if (str/includes? (str (ex-message e)) "EADDRINUSE")
                            (ex-info (str "Port " port " is already in use") {:code "resource conflict"})
                            e)))))))
