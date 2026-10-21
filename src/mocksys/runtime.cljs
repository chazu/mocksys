(ns mocksys.runtime
  "The mocksys daemon. An admin API on 127.0.0.1 creates, lists, reads and deletes
  imposters; each imposter is an HTTP(S) server on the loopback addresses that answers
  through `mocksys.runtime.imposter`, and serves stream answers and WebSocket upgrades
  through `mocksys.runtime.stream`. The admin routes are exactly the ones
  `mocksys.mb` calls, and all but GET / need `Authorization: Bearer <token>`:

    GET    /                                  identify the daemon: {name version pid}
    DELETE /                                  stop every imposter and the admin API
    GET    /imposters                         list [{protocol port name numberOfRequests}]
    POST   /imposters                         create (201), or 400 with {errors}
    GET    /imposters/:port                   full, or ?replayable=true&removeProxies=true
    DELETE /imposters/:port                   stop and remove (idempotent)
    DELETE /imposters/:port/savedRequests     forget recorded requests"
  (:require [promesa.core :as p]
            [clojure.string :as str]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:https" :as https]
            ["node:path" :as path]
            [mocksys.runtime.imposter :as imposter]
            [mocksys.runtime.grpc :as grpc]
            [mocksys.runtime.stream :as stream]
            ["ws" :refer [WebSocketServer]]
            [mocksys.version :refer [version]]))

(def identity-name "mocksys")

;; --- requests ------------------------------------------------------------------

(defn- read-body [^js node-req]
  (js/Promise.
   (fn [resolve reject]
     (let [chunks #js []]
       (.on node-req "data" #(.push chunks %))
       (.on node-req "end" #(resolve (.toString (js/Buffer.concat chunks) "utf8")))
       (.on node-req "error" reject)))))

(defn- multi-map
  "[k v] pairs as a map; a repeated key collects its values in a vector."
  [pairs]
  (reduce (fn [m [k v]]
            (let [cur (get m k)]
              (assoc m k (cond (nil? cur) v (vector? cur) (conj cur v) :else [cur v]))))
          {}
          pairs))

(defn- headers-of
  "Headers keyed as the client sent them; a repeated header becomes a vector."
  [raw]
  (multi-map (partition 2 (array-seq raw))))

(defn- query-of [^js url]
  (multi-map (array-seq (js/Array.from (.entries (.-searchParams url))))))

(defn- request-of [^js node-req body]
  (let [url (js/URL. (.-url node-req) "http://localhost")]
    {"method"  (.-method node-req)
     "path"    (.-pathname url)
     "query"   (query-of url)
     "headers" (headers-of (.-rawHeaders node-req))
     "body"    body}))

;; --- proxying ------------------------------------------------------------------

(def ^:private binary-encodings ["gzip" "br" "compress" "deflate"])
(def ^:private binary-types
  ["audio/" "application/epub+zip" "application/gzip" "application/java-archive" "application/msword"
   "application/octet-stream" "application/pdf" "application/rtf" "application/vnd.ms-excel"
   "application/vnd.ms-fontobject" "application/vnd.ms-powerpoint" "application/vnd.visio"
   "application/x-shockwave-flash" "application/x-tar" "application/zip" "font/" "image/" "model/" "video/"])

(defn- binary? [^js headers]
  (let [enc (or (aget headers "content-encoding") "")
        ct  (or (aget headers "content-type") "")]
    (boolean (or (some #(str/includes? enc %) binary-encodings)
                 (some #(str/includes? ct %) binary-types)))))

(defn- proxy-headers
  "The client's headers, re-addressed to the upstream host. A body gets an explicit
   Content-Length so Node does not switch the upstream request to chunked."
  [request ^js url]
  (let [hs    (into {} (remove (fn [[k _]] (= "host" (str/lower-case k)))) (get request "headers"))
        has?  (fn [n] (some #(= n (str/lower-case %)) (keys hs)))
        body  (get request "body")]
    (cond-> (assoc hs "host" (.-host url))
      (and (seq body) (not (has? "transfer-encoding")) (not (has? "content-length")))
      (assoc "Content-Length" (.byteLength js/Buffer body)))))

(defn- proxy-to!
  "Send `request` to the proxy's upstream at `raw-url` (path and query exactly as
   received) and resolve its response as an `is` map. A JSON body is kept parsed and
   a binary one base64-encoded, as Mountebank records them."
  [cfg request raw-url]
  (js/Promise.
   (fn [resolve reject]
     (let [to     (get cfg "to")
           url    (js/URL. to)
           https? (= "https:" (.-protocol url))
           opts   #js {:method   (get request "method")
                       :hostname (str/replace (.-hostname url) #"^\[|\]$" "")
                       :port     (if (seq (.-port url)) (.-port url) (if https? 443 80))
                       :path     raw-url
                       :headers  (clj->js (proxy-headers request url))
                       :rejectUnauthorized false}
           ^js out ((if https? https/request http/request) opts)
           body   (get request "body")]
       (.once out "error"
              (fn [^js e]
                (reject (js/Error. (case (.-code e)
                                     ("ENOTFOUND" "EAI_AGAIN")     (str "Cannot resolve " (pr-str to))
                                     ("ECONNREFUSED" "ECONNRESET") (str "Unable to connect to " (pr-str to))
                                     (.-message e))))))
       (.once out "response"
              (fn [^js res]
                (let [chunks #js []]
                  (.on res "data" #(.push chunks %))
                  (.on res "end"
                       (fn []
                         (let [mode (if (binary? (.-headers res)) "binary" "text")
                               text (.toString (js/Buffer.concat chunks) (if (= "binary" mode) "base64" "utf8"))]
                           (resolve {"statusCode" (.-statusCode res)
                                     "headers"    (headers-of (.-rawHeaders res))
                                     "body"       (try (js->clj (js/JSON.parse text)) (catch :default _ text))
                                     "_mode"      mode})))))))
       (when (seq body) (.write out body))
       (.end out)))))

;; --- serving an imposter -------------------------------------------------------

(defn- write-json! [^js res status body]
  (.writeHead res status #js {"Content-Type" "application/json"})
  (.end res (js/JSON.stringify (clj->js body) nil 2)))

(defn- fault! [^js socket kind]
  (when (= "RANDOM_DATA_THEN_CLOSE" kind)
    (.write socket (js/Buffer.from "Htijy%@tWXJ/hQ#[Q:7G@dH4\"gu[QaX&" "utf-8")))
  (.destroy socket))

(defn- response-error [e] {"errors" [{"code" "response error" "message" (ex-message e)}]})

(defn- serve! [imp ^js node-req ^js res]
  (-> (p/let [body (read-body node-req)
              out  (imposter/respond imp (request-of node-req body)
                                     {:proxy! #(proxy-to! %1 %2 (.-url node-req))})
              _    (when (pos? (:wait out 0)) (p/delay (:wait out)))]
        (cond
          (:fault out)  (fault! (.-socket node-req) (:fault out))
          (:stream out) (stream/serve-http! imp res out)
          :else         (do (.writeHead res (:status out) (clj->js (:headers out)))
                            (.end res (:body out) (:encoding out)))))
      (p/catch (fn [e] (write-json! res 500 (response-error e))))))

(defn- write-raw!
  "Write `out` as a whole HTTP response on a socket the server handed over for an
   upgrade, then close it. Each header is checked as writeHead checks it, so a value
   from the request cannot split the response; a bad one throws before anything is
   written."
  [^js socket {:keys [status headers body encoding]}]
  (let [buf  (js/Buffer.from (or body "") (or encoding "utf8"))
        hs   (-> (into {} (remove (fn [[k _]] (#{"content-length" "connection"} (str/lower-case k)))) headers)
                 (assoc "Content-Length" (.-length buf) "Connection" "close"))]
    (doseq [[k v] hs, v (if (vector? v) v [v])]
      (http/validateHeaderName k)
      (http/validateHeaderValue k (str v)))
    (.write socket (str "HTTP/1.1 " status " " (or (aget http/STATUS_CODES status) "") "\r\n"
                        (apply str (for [[k v] hs, v (if (vector? v) v [v])] (str k ": " v "\r\n")))
                        "\r\n"))
    (.end socket buf)))

(defn- upgrade!
  "A request to switch protocols goes through the imposter like any other. A
   `websocket` answer accepts it; any other answer is written back as plain HTTP, so a
   gate in front of the websocket refuses with its own status."
  [imp ^js wss ^js node-req ^js socket head]
  (let [request (request-of node-req "")]
    (-> (p/let [out (imposter/respond imp request {:proxy! #(proxy-to! %1 %2 (.-url node-req))})
                _   (when (pos? (:wait out 0)) (p/delay (:wait out)))]
          (cond
            (:fault out)
            (fault! socket (:fault out))
            (= "websocket" (get-in out [:stream "kind"]))
            (.handleUpgrade wss node-req socket head #(stream/websocket! imp % request out))
            :else
            (write-raw! socket out)))
        (p/catch (fn [e] (write-raw! socket {:status 500 :headers {"Content-Type" "application/json"}
                                             :body (js/JSON.stringify (clj->js (response-error e)))}))))))

(defn- websocket-upgrade? [^js node-req]
  (= "websocket" (str/lower-case (or (.. node-req -headers -upgrade) ""))))

(defn- tracked-server
  "An HTTP server, or HTTPS with `tls` ({cert key} PEM), that remembers its open
   sockets, so stopping it can cut them (a response may be parked in a long `wait`).
   With `on-upgrade`, a WebSocket upgrade goes there. Any other Upgrade request, such
   as curl's h2c, stays an ordinary request with its body, except on a node without
   shouldUpgradeCallback (before 22.21 and 24.9), which hands it to `on-upgrade` with
   its body unread."
  [handler {:keys [tls on-upgrade]}]
  (let [opts       (cond-> {}
                     tls        (assoc :cert (get tls "cert") :key (get tls "key"))
                     on-upgrade (assoc :shouldUpgradeCallback websocket-upgrade?))
        ^js server (if tls
                     (https/createServer (clj->js opts) handler)
                     (http/createServer (clj->js opts) handler))
        sockets    (atom #{})]
    (when on-upgrade (.on server "upgrade" on-upgrade))
    (.on server "connection"
         (fn [^js s]
           (swap! sockets conj s)
           (.on s "error" (fn [_]))
           (.on s "close" #(swap! sockets disj s))))
    {:server server :sockets sockets}))

(defn- listen! [{:keys [^js server] :as t} port host]
  (js/Promise.
   (fn [resolve reject]
     (.once server "error" reject)
     (.listen server port host (fn [] (resolve t))))))

(defn- close! [{:keys [^js server sockets stop]}]
  (if stop
    (stop)
    (js/Promise.
     (fn [resolve]
       (.close server (fn [_] (resolve nil)))
       (doseq [^js s @sockets] (.destroy s))))))

(defn- in-use [port]
  (ex-info (str "Port " port " is already in use") {:code "resource conflict"}))

(defn- start-imposter!
  "Listen on 127.0.0.1 and, where the host has IPv6, ::1, so a client that resolves
   `localhost` to either address reaches the mock."
  [imp]
  (let [port    (imposter/port imp)
        handler (fn [rq rs] (serve! imp rq rs))
        wss     (WebSocketServer. #js {:noServer true :clientTracking false})
        server  #(tracked-server handler {:tls        (imposter/tls imp)
                                          :on-upgrade (when (imposter/websockets? imp)
                                                        (fn [rq socket head] (upgrade! imp wss rq socket head)))})]
    (-> (p/let [v4 (listen! (server) port "127.0.0.1")]
          (-> (listen! (server) port "::1")
              (p/then (fn [v6] [v4 v6]))
              (p/catch (fn [^js e]
                         (if (#{"EADDRNOTAVAIL" "EAFNOSUPPORT"} (.-code e))
                           [v4]
                           (p/do (close! v4) (p/rejected e)))))))
        (p/catch (fn [^js e]
                   (throw (if (= "EADDRINUSE" (.-code e)) (in-use port) e)))))))

;; --- admin API -----------------------------------------------------------------

(defn- error-body [code message] {"errors" [{"code" code "message" message}]})

(defn- create!
  "Register the imposter, then start its servers. The port is claimed in the
   registry before the servers listen, so a delete can land in between; the
   servers are only attached if the entry still holds this imposter."
  [registry imposter-opts body]
  (let [spec  (try (js->clj (js/JSON.parse body))
                   (catch :default _ (throw (ex-info "the imposter is not valid JSON" {:code "bad data"}))))
        imp   (imposter/create spec imposter-opts)
        port  (imposter/port imp)
        mine? #(identical? imp (get-in @registry [port :imp]))]
    (when (contains? @registry port) (throw (in-use port)))
    (swap! registry assoc port {:imp imp :servers []})
    (-> (p/do (if (= "grpc" (imposter/protocol imp)) (grpc/start! imp) (start-imposter! imp)))
        (p/catch (fn [e] (when (mine?) (swap! registry dissoc port)) (throw e)))
        (p/then (fn [servers]
                  (if (mine?)
                    (do (swap! registry assoc-in [port :servers] servers)
                        [201 (imposter/to-json imp {})])
                    (p/do (p/all (map close! servers))
                          (throw (ex-info (str "the imposter on port " port " was deleted while it started")
                                          {:code "resource conflict"})))))))))

(defn- delete! [registry port]
  (if-let [{:keys [imp servers]} (get @registry port)]
    (do (swap! registry dissoc port)
        (p/let [_ (p/all (map close! servers))]
          [200 (imposter/to-json imp {:replayable? true})]))
    [200 {}]))

(defn- route
  "[status body] for one admin call, or [status body after] where `after` runs once
   the response is sent."
  [registry imposter-opts stop! method path query body]
  (let [segs (vec (remove str/blank? (str/split path #"/")))
        port (when (= "imposters" (first segs)) (some-> (get segs 1) js/parseInt))
        imp  (when port (:imp (get @registry port)))
        flag #(= "true" (get query %))]
    (cond
      (and (= method "GET") (empty? segs))
      [200 {"name" identity-name "version" version "pid" (.-pid js/process)}]

      (and (= method "DELETE") (empty? segs))
      [200 {"stopped" (count @registry)} stop!]

      (and (= segs ["imposters"]) (= method "GET"))
      [200 {"imposters" (mapv #(imposter/to-json (:imp %) {:list? true}) (vals @registry))}]

      (and (= segs ["imposters"]) (= method "POST"))
      (create! registry imposter-opts body)

      (and (= 2 (count segs)) (= method "DELETE") port)
      (delete! registry port)

      (and (= 2 (count segs)) (= method "GET") imp)
      [200 (imposter/to-json imp {:replayable? (flag "replayable") :remove-proxies? (flag "removeProxies")})]

      (and (= 3 (count segs)) (= "savedRequests" (get segs 2)) (= method "DELETE") imp)
      (do (imposter/clear-requests! imp) [200 (imposter/to-json imp {})])

      :else
      [404 (error-body "no such resource" (str method " " path))])))

(defn- local-host? [host]
  (contains? #{"127.0.0.1" "localhost"} (str/replace (or host "") #":\d+$" "")))

(defn- authorized?
  "Whether the Authorization header carries `token`, compared in constant time."
  [token header]
  (let [want (js/Buffer.from (str "Bearer " token))
        got  (js/Buffer.from (or header ""))]
    (and (= (.-length want) (.-length got)) (crypto/timingSafeEqual want got))))

(defn- admin-handler
  "The admin API loads code (an imposter's modules) and runs it, so only local, non-browser
   callers may use it: a request carrying an Origin, or addressed to any host other
   than the loopback name, is refused. That stops a web page from posting an
   imposter directly or through DNS rebinding. Every call but GET / must also
   carry the daemon's token, which only the user who started it can read, so
   another local user cannot either."
  [registry imposter-opts token stop!]
  (fn [^js node-req ^js res]
    (let [hs  (.-headers node-req)
          url (try (js/URL. (.-url node-req) "http://localhost") (catch :default _ nil))]
      (cond
        (or (aget hs "origin") (not (local-host? (aget hs "host"))))
        (write-json! res 403 (error-body "forbidden" "the admin API only serves local, non-browser clients"))

        (nil? url)
        (do (.resume node-req)
            (write-json! res 400 (error-body "bad data" (str "not a valid URL: " (.-url node-req)))))

        (and (not (and (= "GET" (.-method node-req)) (= "/" (.-pathname url))))
             (not (authorized? token (aget hs "authorization"))))
        (do (.resume node-req)
            (write-json! res 401 (error-body "unauthorized" "the admin API needs the daemon's token")))

        :else
        (-> (p/let [body            (read-body node-req)
                    [st resp after] (route registry imposter-opts stop! (.-method node-req) (.-pathname url) (query-of url) body)]
              (when after (.once res "finish" after))
              (write-json! res st resp))
            (p/catch (fn [e]
                       (let [code (or (:code (ex-data e)) "internal error")]
                         (write-json! res (if (= code "internal error") 500 400) (error-body code (ex-message e)))))))))))

(defn max-recorded-requests
  "How many requests each imposter keeps: $MOCKSYS_MAX_RECORDED_REQUESTS, else
   10000. 0 is refused rather than read as \"keep none\": a mock that records and
   keeps nothing would fail every assert; turn recordRequests off instead."
  []
  (let [v (.-MOCKSYS_MAX_RECORDED_REQUESTS js/process.env)]
    (if (and v (not= v ""))
      (if (and (re-matches #"\d+" v) (pos? (js/parseInt v 10)))
        (js/parseInt v 10)
        (throw (js/Error. (str "MOCKSYS_MAX_RECORDED_REQUESTS must be a whole number above 0, got " (pr-str v)))))
      imposter/default-max-requests)))

(defn- write-token!
  "Write `token` to `file`, readable by this user only. The rename swaps the whole
   file in, so a client never reads half a token. The temp file is created afresh
   (`wx`), since a mode only applies to a new file and a link there would be followed."
  [file token]
  (fs/mkdirSync (path/dirname file) #js {:recursive true})
  (let [tmp (str file "." (.-pid js/process) ".tmp")]
    (fs/rmSync tmp #js {:force true})
    (fs/writeFileSync tmp token #js {:mode 0600 :flag "wx"})
    (fs/renameSync tmp file)))

(defn start!
  "Run the daemon's admin API on `host`:`port` (port 0 picks one) under a fresh
   random token, written to `token-file` once the port is ours, so a daemon that
   loses the port to another leaves that one's token in place. Each imposter keeps
   its newest `max-requests` requests. Resolves {:port :token :stop!}; `stop!`
   closes the admin API and every imposter. `DELETE /` runs the same stop, then
   `on-stop`."
  [{:keys [host port on-stop token-file max-requests] :or {max-requests imposter/default-max-requests}}]
  (let [registry (atom {})
        admin    (atom nil)
        token    (.toString (crypto/randomBytes 32) "hex")
        stop!    (fn []
                   (p/let [_ (p/all (map close! (mapcat :servers (vals @registry))))]
                     (reset! registry {})
                     (close! @admin)))]
    (reset! admin (tracked-server (admin-handler registry {:max-requests max-requests} token
                                                 #(p/then (stop!) (or on-stop identity)))
                                  {}))
    (p/let [_ (listen! @admin port host)]
      (when token-file (write-token! token-file token))
      {:port  (.-port (.address ^js (:server @admin)))
       :token token
       :stop! stop!})))
