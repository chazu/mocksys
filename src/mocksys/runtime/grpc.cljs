(ns mocksys.runtime.grpc
  "gRPC imposters: a grpc-js server per imposter, built from the descriptor the
  imposter carries. Every unary method of the proto is registered; a call becomes
  the request the stubs already match on (POST /pkg.Service/Method, metadata as
  headers, the decoded message as a JSON body), so matching, injection and state
  are the HTTP imposter's. Streaming methods, and methods the proto does not
  declare, answer UNIMPLEMENTED.

  The outcome maps back as follows. A 2xx JSON object body is the reply message;
  a JSON array fills the reply type's one repeated field. Any other status is a
  gRPC error: a body {grpcCode, message} picks the code (a name or a number),
  otherwise `http->grpc` does. Response headers become initial metadata. A fault
  is UNAVAILABLE."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            ["@grpc/grpc-js" :as grpc]
            ["@grpc/proto-loader" :as loader]
            [mocksys.runtime.imposter :as imposter]))

(def ^:private codes (js->clj (.-status grpc)))

(def ^:private loader-options
  "How a message reads as JSON: int64 and bytes (base64) as strings, so no
   precision is lost; enums by name; unset fields at their proto3 defaults."
  #js {:longs js/String :enums js/String :bytes js/String :defaults true :oneofs true})

(def http->grpc
  "The gRPC code for an HTTP status, the inverse of google.rpc.Code's HTTP
   mapping. A status not listed is UNKNOWN."
  {400 "INVALID_ARGUMENT" 401 "UNAUTHENTICATED" 403 "PERMISSION_DENIED" 404 "NOT_FOUND"
   409 "ALREADY_EXISTS" 429 "RESOURCE_EXHAUSTED" 499 "CANCELLED" 500 "INTERNAL"
   501 "UNIMPLEMENTED" 503 "UNAVAILABLE" 504 "DEADLINE_EXCEEDED"})

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

(defn outcome->reply
  "The gRPC answer for an imposter outcome ({:status :headers :body} or {:fault}):
   {:value reply-object} or {:error {:code :details}}, each with :metadata (the
   response headers). `repeated` names the reply type's only repeated field, if it
   has exactly one."
  [{:keys [status body fault headers]} repeated]
  (let [meta (when-not fault (headers->metadata headers))
        doc  (if (str/blank? body) #js {} (parse body))]
    (cond
      fault
      {:error {:code (codes "UNAVAILABLE") :details (str "fault " fault)}}

      (<= 200 status 299)
      (cond
        (and (object? doc) (not (array? doc))) {:metadata meta :value doc}
        (and (array? doc) repeated)            {:metadata meta :value (js-obj repeated doc)}
        :else {:metadata meta
               :error {:code (codes "INTERNAL")
                       :details (str "the mock's reply is not a JSON object"
                                     (when (array? doc) (str " (a JSON array needs a reply type with one repeated field)"))
                                     ": " (subs body 0 (min 80 (count body))))}})

      :else
      (let [doc  (when (and (object? doc) (not (array? doc))) (js->clj doc))
            msg  (get doc "message")]
        {:metadata meta
         :error {:code    (or (code-of (get doc "grpcCode")) (codes (get http->grpc status "UNKNOWN")))
                 :details (cond (string? msg)        msg
                                (not (str/blank? body)) body
                                :else                 (str "HTTP " status))}}))))

(defn- repeated-field
  "The name of the reply type's only repeated field, or nil."
  [^js md]
  (let [^js reply (.. md -responseType -type)
        fields    (filter #(= "LABEL_REPEATED" (.-label ^js %)) (array-seq (.-field reply)))]
    (when (= 1 (count fields)) (.-name ^js (first fields)))))

(defn- no-proxy [_ _]
  (p/rejected (js/Error. "proxy responses are not served over grpc")))

(defn- handler [imp ^js md]
  (let [path     (.-path md)
        repeated (repeated-field md)]
    (fn [^js call callback]
      (-> (p/let [out (imposter/respond imp (request-of call path) {:proxy! no-proxy})
                  _   (when (pos? (:wait out 0)) (p/delay (:wait out)))]
            (let [{:keys [metadata value error]} (outcome->reply out repeated)]
              (when metadata (.sendMetadata call metadata))
              (if error
                (callback (clj->js error))
                (callback nil value))))
          (p/catch (fn [e] (callback #js {:code (codes "INTERNAL") :details (ex-message e)})))))))

;; --- servers -------------------------------------------------------------------

(defn- package-definition [descriptor]
  (try (.fromJSON loader (clj->js descriptor) loader-options)
       (catch :default e (bad (str "descriptor: " (ex-message e))))))

(defn- services
  "[[service-definition {method handler}]] for each service; only unary methods get
   a handler, so grpc-js answers the rest UNIMPLEMENTED."
  [imp pkg-def]
  (for [k (js-keys pkg-def)
        :let [^js svc (aget pkg-def k)]
        :when (nil? (.-format svc))]
    [svc (into {} (for [m (js-keys svc)
                        :let [^js md (aget svc m)]
                        :when (not (or (.-requestStream md) (.-responseStream md)))]
                    [m (handler imp md)]))]))

(defn- bind! [^js server addr]
  (js/Promise.
   (fn [resolve reject]
     (.bindAsync server addr (.createInsecure grpc/ServerCredentials)
                 (fn [err port] (if err (reject err) (resolve port)))))))

(defn- check-stubs! [imp]
  (doseq [s (:stubs @imp), r (get-in s [:spec "responses"]) :when (contains? r "proxy")]
    (bad (str "stubs[" (:id s) "]: proxy responses are not supported for protocol grpc"))))

(defn start!
  "Serve `imp` over gRPC on 127.0.0.1 and, where the host has IPv6, ::1. Resolves
   [{:stop f}], where `f` closes the server and resolves when it has."
  [imp]
  (check-stubs! imp)
  (let [pkg-def      (package-definition (get-in @imp [:spec "descriptor"]))
        ^js server   (grpc/Server.)
        port         (imposter/port imp)
        stop         (fn [] (.forceShutdown server) (p/resolved nil))]
    (doseq [[svc impl] (services imp pkg-def)]
      (.addService server svc (clj->js impl)))
    (-> (p/let [_ (bind! server (str "127.0.0.1:" port))
                _ (p/catch (bind! server (str "[::1]:" port)) (fn [_] nil))]
          [{:stop stop}])
        (p/catch (fn [e]
                   (.forceShutdown server)
                   (throw (if (str/includes? (str (ex-message e)) "EADDRINUSE")
                            (ex-info (str "Port " port " is already in use") {:code "resource conflict"})
                            e)))))))
