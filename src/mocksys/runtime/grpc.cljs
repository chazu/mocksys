(ns mocksys.runtime.grpc
  "gRPC imposters: a grpc-js server per imposter, built from the descriptor the
  imposter carries. Every method of the proto is registered; a call becomes the
  request the stubs already match on (POST /pkg.Service/Method, metadata as
  headers, the decoded message as a JSON body), so matching, injection and state
  are the HTTP imposter's. A client stream's request body is the array of its
  messages; each message of a bidirectional stream is a request of its own.
  Methods the proto does not declare answer UNIMPLEMENTED. Every server also
  serves reflection (mocksys.runtime.grpc-reflection).

  The outcome maps back as follows. A 2xx JSON object body is the reply message,
  checked against the reply type by mocksys.runtime.proto-json; a JSON array fills
  the reply type's one repeated field, or for a server or bidirectional stream is
  the messages to send. A reply that does not fit its type is INTERNAL, and the
  daemon log (stderr) says why. Any other status is a gRPC error: a body
  {grpcCode, message} picks the code (a name or a number), otherwise `http->grpc`
  does, and a stream sends the body's `messages` before it. Response headers become
  initial metadata. A fault is UNAVAILABLE.

  A proxyOnce proxy (what `mocksys record` posts) forwards each call upstream with
  grpc-js and saves the outcome as a stub of that shape, the status as HTTP by
  `grpc->http`, the code as grpcCode and the messages a stream sent before it
  failed as `messages`. The upstream call carries the client's deadline, and is
  cancelled when the client cancels, which saves nothing. A bidirectional stream
  is not recorded."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            ["@grpc/grpc-js" :as grpc]
            ["@grpc/proto-loader" :as loader]
            ["protobufjs" :as protobuf]
            [mocksys.runtime.grpc-reflection :as reflection]
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

(defn- request-of
  "The request for a call to `path` whose message (or array of messages) is `msg`."
  [^js call path msg]
  {"method"  "POST"
   "path"    path
   "query"   {}
   "headers" (metadata->headers (.-metadata call))
   "body"    (js/JSON.stringify msg)})

;; --- outcomes -> replies -------------------------------------------------------

(def ^:private hop-headers #{"connection" "content-length" "content-type" "transfer-encoding" "keep-alive" "te" "host" "date"})

(defn- transport-header?
  "Whether HTTP/2 or gRPC sets header `k` (lower case) itself, so it is not metadata."
  [k]
  (or (hop-headers k) (str/starts-with? k "grpc-") (str/starts-with? k ":")))

(defn- headers->metadata
  "Response headers as metadata: [md refused], where `refused` are log lines for
   the headers grpc-js does not accept (a key outside [0-9a-z_.-], a value that is
   not printable ASCII)."
  [headers]
  (let [md      (grpc/Metadata.)
        refused (atom [])]
    (doseq [[k v] headers
            :let [k (str/lower-case k)]
            :when (not (transport-header? k))
            v (if (vector? v) v [v])]
      (try (.add md k (if (str/ends-with? k "-bin") (js/Buffer.from (str v) "base64") (str v)))
           (catch :default e
             (swap! refused conj (str "dropped response header " (pr-str k) ", which grpc-js refuses as metadata: "
                                      (ex-message e))))))
    [md @refused]))

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

(defn- internal
  "An INTERNAL answer for a mock whose reply cannot be sent; the daemon log says
   the same, since the mock's author may not be the one reading the error."
  [details]
  {:error {:code (codes "INTERNAL") :details details} :log [details]})

(defn- one-message? [doc] (and (object? doc) (not (array? doc))))

(defn- replies-of
  "The messages for a 2xx body `doc`, checked against `reply-type` (see
   mocksys.runtime.proto-json): {:values [message] :unknown [paths of keys no field
   declares]}, or an `internal` answer. A unary reply is one message; a JSON array
   fills the reply type's one repeated field. A stream's array is its messages, in
   order, and an object is one message."
  [doc body ^js reply-type stream?]
  (let [repeated (repeated-field reply-type)
        type     (subs (.-fullName reply-type) 1)
        docs     (cond (one-message? doc)            [doc]
                       (and stream? (array? doc))    (vec doc)
                       (and (array? doc) repeated)   [(js-obj repeated doc)])]
    (if docs
      (let [rs       (map-indexed #(pj/encode reply-type %2 (if stream? (str "[" %1 "]") "")) docs)
            problems (mapcat :problems rs)]
        (if (seq problems)
          (internal (str "the mock's reply does not encode as " type ": " (str/join "; " problems)))
          {:values (mapv :value rs) :unknown (vec (mapcat :unknown rs))}))
      (let [shown (if (= ::none doc) body (js/JSON.stringify doc))]
        (internal (str "the mock's reply is not a JSON object" (when stream? " or array")
                       (when (array? doc) (str " (a JSON array needs a reply type with one repeated field; " type " has "
                                               (if (seq (filter #(.-repeated ^js %) (.-fieldsArray reply-type))) "several" "none") ")"))
                       ": " (subs shown 0 (min 80 (count shown)))))))))

(defn- outcome->reply
  "The gRPC answer for an imposter outcome ({:status :headers :body} or {:fault}):
   {:values [reply-object] :unknown [paths]} or {:error {:code :details}}, with
   :metadata (the response headers) and :log, lines for the daemon log. A blank
   body is an empty reply, or for a stream no messages."
  [{:keys [status body fault headers]} reply-type stream?]
  (let [[meta refused] (when-not fault (headers->metadata headers))
        doc            (cond (not (str/blank? body)) (parse body)
                             stream?                 #js []
                             :else                   #js {})]
    (cond
      fault
      {:error {:code (codes "UNAVAILABLE") :details (str "fault " fault)}}

      (<= 200 status 299)
      (-> (replies-of doc body reply-type stream?)
          (assoc :metadata meta)
          (update :log #(into (vec refused) %)))

      :else
      (let [obj  (when (one-message? doc) (js->clj doc))
            msgs (when (one-message? doc) (.-messages ^js doc))
            sent (when (and stream? (array? msgs)) (replies-of msgs body reply-type true))]
        (if (:error sent)
          sent
          (merge sent
                 {:metadata meta
                  :log      (into (vec refused) (:log sent))
                  :error {:code    (or (code-of (get obj "grpcCode")) (codes (get http->grpc status "UNKNOWN")))
                          :details (or (some #(when (string? %) %) [(get obj "message") (get obj "error")])
                                       (when (and (= ::none doc) (not (str/blank? body))) body)
                                       (str "HTTP " status))}}))))))

(defn- log! [line] (.write js/process.stderr (str line "\n")))

(defn- no-proxy [_ _]
  (p/rejected (js/Error. "proxy responses are not served over grpc")))

(defn- asker
  "A fn from a call and one of its requests to a promise of the answer `imp` gives
   it (see `outcome->reply`), once the response's wait is over. A reply's unknown
   keys are logged once for each set of them, since every call to a twin answers
   from the same records. `(proxy-for call)` answers a proxy response (see
   imposter/respond)."
  [imp ^js md ^js reply-type proxy-for]
  (let [path    (.-path md)
        stream? (boolean (.-responseStream md))
        say     #(log! (str "grpc mock on port " (imposter/port imp) ", " path ": " %))
        warned  (atom #{})]
    (fn [call request]
      (p/let [out (imposter/respond imp request {:proxy! (proxy-for call)})
              _   (when (pos? (:wait out 0)) (p/delay (:wait out)))]
        (let [{:keys [unknown log] :as answer} (outcome->reply out reply-type stream?)
              dropped (into (sorted-set) (map #(str/replace % #"\[\d+\]" "[]")) unknown)]
          (when (and (seq dropped) (not (@warned dropped)))
            (swap! warned conj dropped)
            (say (str "dropped reply field(s) " (subs (.-fullName reply-type) 1) " does not declare: "
                      (str/join ", " dropped))))
          (run! say log)
          answer)))))

(defn- call-error
  "The gRPC error for a failed answer: the :grpc-code its ex-data names, or INTERNAL."
  [e]
  #js {:code (codes (:grpc-code (ex-data e) "INTERNAL")) :details (ex-message e)})

(defn- reply!
  "Answer a call that takes one reply (unary, client streaming) with `callback`."
  [ask ^js call request callback]
  (-> (p/let [{:keys [metadata values error]} (ask call request)]
        (when metadata (.sendMetadata call metadata))
        (if error (callback (clj->js error)) (callback nil (first values))))
      (p/catch #(callback (call-error %)))))

(defn- unary-handler [ask path]
  (fn [^js call callback]
    (reply! ask call (request-of call path (.-request call)) callback)))

(defn- client-stream-handler
  "Collect the call's messages; the request is the array of them."
  [ask path]
  (fn [^js call callback]
    (let [msgs #js []]
      (.on call "data" #(.push msgs %))
      (.on call "end" #(reply! ask call (request-of call path msgs) callback)))))

(defn- server-stream-handler
  "Answer the one request with every message of the reply, then the error if any."
  [ask path]
  (fn [^js call]
    (-> (p/let [{:keys [metadata values error]} (ask call (request-of call path (.-request call)))]
          (when metadata (.sendMetadata call metadata))
          (run! #(.write call %) values)
          (if error (.emit call "error" (clj->js error)) (.end call)))
        (p/catch #(.emit call "error" (call-error %))))))

(defn- bidi-handler
  "Each inbound message is one request, answered in order with its reply's
   messages. The stream ends when the client half-closes and every reply is sent,
   or at the first error. The first reply's headers are the stream's metadata."
  [ask path]
  (fn [^js call]
    (let [turns (atom (p/resolved nil))
          over? (atom false)
          md?   (atom false)
          stop! (fn [err] (when-not @over? (reset! over? true) (if err (.emit call "error" err) (.end call))))
          turn  (fn [msg]
                  (fn [_]
                    (when-not @over?
                      (-> (p/let [{:keys [metadata values error]} (ask call (request-of call path msg))]
                            (when-not @over?
                              (when (and metadata (not @md?)) (reset! md? true) (.sendMetadata call metadata))
                              (run! #(.write call %) values)
                              (when error (stop! (clj->js error)))))
                          (p/catch #(stop! (call-error %)))))))]
      (.on call "data" (fn [msg] (swap! turns p/then (turn msg))))
      (.on call "end" (fn [] (swap! turns p/then (fn [_] (stop! nil)))))
      (.on call "cancelled" #(reset! over? true)))))

;; --- recording: forwarding a call upstream ---------------------------------------

(def grpc->http
  "The HTTP status google.rpc.Code maps each gRPC code to. A recorded error's stub
   carries it next to the code, which picks the code on replay."
  {"OK" 200 "CANCELLED" 499 "UNKNOWN" 500 "INVALID_ARGUMENT" 400 "DEADLINE_EXCEEDED" 504
   "NOT_FOUND" 404 "ALREADY_EXISTS" 409 "PERMISSION_DENIED" 403 "RESOURCE_EXHAUSTED" 429
   "FAILED_PRECONDITION" 400 "ABORTED" 409 "OUT_OF_RANGE" 400 "UNIMPLEMENTED" 501
   "INTERNAL" 500 "UNAVAILABLE" 503 "DATA_LOSS" 500 "UNAUTHENTICATED" 401})

(def ^:private code-names (into {} (map (fn [[k v]] [v k])) codes))

(defn- upstream-address
  "The grpc-js target for a proxy's `to`, an http:// origin."
  [to]
  (let [url (js/URL. to)]
    (str (.-hostname url) ":" (if (seq (.-port url)) (.-port url) 80))))

(defn- connected
  "Resolve `client` once its channel is ready, or reject as soon as it fails to
   connect, as the HTTP proxy does: a call on a channel that cannot connect fails
   UNAVAILABLE, which would be recorded as the upstream's answer."
  [^js client to]
  (let [^js ch  (.getChannel client)
        states  (.-connectivityState grpc)
        refused #(ex-info (str "Unable to connect to " (pr-str to)) {:grpc-code "UNAVAILABLE"})]
    (js/Promise.
     (fn [resolve reject]
       ((fn check []
          (let [st (.getConnectivityState ch true)]
            (condp = st
              (.-READY states)             (resolve client)
              (.-TRANSIENT_FAILURE states) (reject (refused))
              (.watchConnectivityState ch st (+ (js/Date.now) 10000)
                                       #(if % (reject (refused)) (check)))))))))))

(def ^:private upstream-ms
  "How long a recorded call may take when its client set no deadline, so that a
   stream that never ends is still recorded."
  60000)

(defn- call-upstream
  "Make the call `request` stands for on `client`, on behalf of the client's call
   `down`; resolves its outcome as an `is` response. A reply, or a server stream's
   messages as an array, is a 200 body; an error is the status `grpc->http` gives
   its code, with a {grpcCode, message} body. The upstream's initial metadata
   becomes the headers. When `down` is cancelled, or its deadline passes, the
   upstream call is cancelled and the promise rejects, so nothing is recorded."
  [^js client ^js md ^js down request]
  (js/Promise.
   (fn [resolve reject]
     (let [path    (.-path md)
           due     (js/Number (.getDeadline down))
           ;; a moment past the client's own deadline, so that passing it ends the
           ;; call as a cancel, which is not recorded, not as the upstream's
           ;; DEADLINE_EXCEEDED, which would be
           opts    #js {:deadline (if (js/isFinite due) (+ due 1000) (+ (js/Date.now) upstream-ms))}
           ser     (.-requestSerialize md)
           de      (.-responseDeserialize md)
           meta    (first (headers->metadata (dissoc (get request "headers") "user-agent")))
           msg     (js/JSON.parse (get request "body"))
           headers (atom {})
           ok      #(resolve {"statusCode" 200 "headers" @headers "body" (js->clj %)})
           fail    (fn [^js e & [sent]]
                     (let [code (get code-names (.-code e) "UNKNOWN")]
                       (resolve {"statusCode" (grpc->http code) "headers" @headers
                                 "body" (cond-> {"grpcCode" code "message" (.-details e)}
                                          (seq sent) (assoc "messages" (js->clj sent)))})))
           done    (fn [err reply] (if err (fail err) (ok reply)))
           ^js call (cond
                      (.-requestStream md)  (let [^js c (.makeClientStreamRequest client path ser de meta opts done)]
                                              (run! #(.write c %) msg)
                                              (.end c)
                                              c)
                      (.-responseStream md) (let [^js c (.makeServerStreamRequest client path ser de msg meta opts)
                                                  msgs  #js []]
                                              (.on c "data" #(.push msgs %))
                                              (.on c "end" #(ok msgs))
                                              (.on c "error" #(fail % msgs))
                                              c)
                      :else                 (.makeUnaryRequest client path ser de msg meta opts done))
           gone    (fn []
                     (reject (ex-info "the client cancelled the call" {:grpc-code "CANCELLED"}))
                     (.cancel call))]
       (.on call "metadata" #(reset! headers (into {} (remove (comp transport-header? key)) (metadata->headers %))))
       (if (.-cancelled down) (gone) (.on down "cancelled" gone))))))

(defn- forwarder
  "A fn from a client's call to `md` to the `proxy!` that sends its requests
   upstream, to the proxy's `to`, through `client-for` (to -> grpc-js client)."
  [^js md client-for]
  (fn [down]
    (fn [cfg request]
      (if (and (.-requestStream md) (.-responseStream md))
        (p/rejected (ex-info (str "mocksys does not record bidirectional streams; " (.-path md) " is one")
                             {:grpc-code "UNIMPLEMENTED"}))
        (p/let [client (connected (client-for (get cfg "to")) (get cfg "to"))]
          (call-upstream client md down request))))))

(defn- handler
  "The grpc-js handler for `md`, by its kind, answering from `imp`."
  [imp ^js md ^js reply-type client-for]
  (let [ask  (asker imp md reply-type (forwarder md client-for))
        path (.-path md)]
    (case [(boolean (.-requestStream md)) (boolean (.-responseStream md))]
      [false false] (unary-handler ask path)
      [true false]  (client-stream-handler ask path)
      [false true]  (server-stream-handler ask path)
      [true true]   (bidi-handler ask path))))

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
  "[[service-definition {method handler}]] for each service. `root` is the
   protobufjs reading of the same descriptor, which knows each reply's type."
  [imp pkg-def ^js root client-for]
  (for [k (js-keys pkg-def)
        :let [^js svc (aget pkg-def k)]
        :when (nil? (.-format svc))]
    [svc (into {} (for [m (js-keys svc)
                        :let [^js md  (aget svc m)
                              ^js rpc (aget (.-methods (.lookupService root k)) m)]]
                    [m (handler imp md (.-resolvedResponseType rpc) client-for)]))]))

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
  (doseq [s (:stubs @imp), r (get-in s [:spec "responses"])
          :when (str/starts-with? (str (get-in r ["proxy" "to"])) "https:")]
    (bad (str "stubs[" (:id s) "]: a grpc proxy forwards without TLS, so proxy.to must be an http:// origin"))))

(defn start!
  "Serve `imp` over gRPC on 127.0.0.1 and, where the host has IPv6, ::1, as the HTTP
   imposters do: a port taken on either address fails the start. Resolves
   [{:stop f}], where `f` closes the server and resolves when it has."
  [imp]
  (check-stubs! imp)
  (let [[pkg-def root] (package-definition (get-in @imp [:spec "descriptor"]))
        ^js server     (grpc/Server.)
        port           (imposter/port imp)
        clients        (atom {})
        client-for     (fn [to]
                         (or (get @clients to)
                             (get (swap! clients assoc to (grpc/Client. (upstream-address to)
                                                                       (.createInsecure grpc/credentials)))
                                  to)))
        stop           (fn []
                         (.forceShutdown server)
                         (run! #(.close ^js %) (vals @clients))
                         (p/resolved nil))]
    (doseq [[svc impl] (services imp pkg-def root client-for)]
      (.addService server svc (clj->js impl)))
    (reflection/add! server pkg-def)
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
