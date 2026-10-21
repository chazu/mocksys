(ns mocksys.runtime
  "The mocksys daemon. An admin API on 127.0.0.1 creates, lists, reads and deletes
  imposters; each imposter is an HTTP server on the loopback addresses that answers
  through `mocksys.runtime.imposter`. The admin routes are exactly the ones
  `mocksys.mb` calls:

    GET    /                                  identify the daemon
    GET    /imposters                         list [{protocol port name numberOfRequests}]
    POST   /imposters                         create (201), or 400 with {errors}
    GET    /imposters/:port                   full, or ?replayable=true&removeProxies=true
    DELETE /imposters/:port                   stop and remove (idempotent)
    DELETE /imposters/:port/savedRequests     forget recorded requests"
  (:require [promesa.core :as p]
            [clojure.string :as str]
            ["node:http" :as http]
            ["node:https" :as https]
            [mocksys.runtime.imposter :as imposter]))

(def identity-name "mocksys")

;; --- requests ------------------------------------------------------------------

(defn- read-body [^js node-req]
  (js/Promise.
   (fn [resolve reject]
     (let [chunks #js []]
       (.on node-req "data" #(.push chunks %))
       (.on node-req "end" #(resolve (.toString (js/Buffer.concat chunks) "utf8")))
       (.on node-req "error" reject)))))

(defn- headers-of
  "Headers keyed as the client sent them; a repeated header becomes a vector."
  [raw]
  (reduce (fn [m [k v]]
            (let [cur (get m k)]
              (assoc m k (cond (nil? cur) v (vector? cur) (conj cur v) :else [cur v]))))
          {}
          (partition 2 (array-seq raw))))

(defn- query-of [^js url]
  (reduce (fn [m [k v]]
            (let [cur (get m k)]
              (assoc m k (cond (nil? cur) v (vector? cur) (conj cur v) :else [cur v]))))
          {}
          (map vec (array-seq (js/Array.from (.entries (.-searchParams url)))))))

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

(defn- serve! [imp ^js node-req ^js res]
  (-> (p/let [body (read-body node-req)
              out  (imposter/respond imp (request-of node-req body)
                                     {:proxy! #(proxy-to! %1 %2 (.-url node-req))})
              _    (when (pos? (:wait out 0)) (p/delay (:wait out)))]
        (if-let [kind (:fault out)]
          (fault! (.-socket node-req) kind)
          (do (.writeHead res (:status out) (clj->js (:headers out)))
              (.end res (:body out) (:encoding out)))))
      (p/catch (fn [e] (write-json! res 500 {"errors" [{"code" "response error" "message" (ex-message e)}]})))))

(defn- tracked-server
  "An HTTP server that remembers its open sockets, so stopping it can cut them
   (a response may be parked in a long `wait`)."
  [handler]
  (let [^js server (http/createServer handler)
        sockets    (atom #{})]
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

(defn- close! [{:keys [^js server sockets]}]
  (js/Promise.
   (fn [resolve]
     (.close server (fn [_] (resolve nil)))
     (doseq [^js s @sockets] (.destroy s)))))

(defn- in-use [port]
  (ex-info (str "Port " port " is already in use") {:code "resource conflict"}))

(defn- start-imposter!
  "Listen on 127.0.0.1 and, where the host has IPv6, ::1, so a client that resolves
   `localhost` to either address reaches the mock."
  [imp]
  (let [port    (imposter/port imp)
        handler (fn [rq rs] (serve! imp rq rs))]
    (-> (p/let [v4 (listen! (tracked-server handler) port "127.0.0.1")]
          (-> (listen! (tracked-server handler) port "::1")
              (p/then (fn [v6] [v4 v6]))
              (p/catch (fn [^js e]
                         (if (#{"EADDRNOTAVAIL" "EAFNOSUPPORT"} (.-code e))
                           [v4]
                           (p/do (close! v4) (p/rejected e)))))))
        (p/catch (fn [^js e]
                   (throw (if (= "EADDRINUSE" (.-code e)) (in-use port) e)))))))

;; --- admin API -----------------------------------------------------------------

(defn- error-body [code message] {"errors" [{"code" code "message" message}]})

(defn- create! [registry body]
  (let [spec (try (js->clj (js/JSON.parse body))
                  (catch :default _ (throw (ex-info "the imposter is not valid JSON" {:code "bad data"}))))
        imp  (imposter/create spec)
        port (imposter/port imp)]
    (when (contains? @registry port) (throw (in-use port)))
    (swap! registry assoc port {:imp imp :servers []})
    (-> (p/let [servers (start-imposter! imp)]
          (swap! registry assoc-in [port :servers] servers)
          [201 (imposter/to-json imp {})])
        (p/catch (fn [e] (swap! registry dissoc port) (throw e))))))

(defn- delete! [registry port]
  (if-let [{:keys [imp servers]} (get @registry port)]
    (do (swap! registry dissoc port)
        (p/let [_ (p/all (map close! servers))]
          [200 (imposter/to-json imp {:replayable? true})]))
    [200 {}]))

(defn- route
  "[status body] for one admin call."
  [registry method path query body]
  (let [segs (vec (remove str/blank? (str/split path #"/")))
        port (when (= "imposters" (first segs)) (some-> (get segs 1) js/parseInt))
        imp  (when port (:imp (get @registry port)))
        flag #(= "true" (get query %))]
    (cond
      (and (= method "GET") (empty? segs))
      [200 {"name" identity-name "pid" (.-pid js/process)}]

      (and (= segs ["imposters"]) (= method "GET"))
      [200 {"imposters" (mapv #(imposter/to-json (:imp %) {:list? true}) (vals @registry))}]

      (and (= segs ["imposters"]) (= method "POST"))
      (create! registry body)

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

(defn- admin-handler
  "The admin API takes code (injections) and runs it, so only local, non-browser
   callers may use it: a request carrying an Origin, or addressed to any host other
   than the loopback name, is refused. That stops a web page from posting an
   imposter directly or through DNS rebinding."
  [registry]
  (fn [^js node-req ^js res]
    (let [hs (.-headers node-req)]
      (if (or (aget hs "origin") (not (local-host? (aget hs "host"))))
        (write-json! res 403 (error-body "forbidden" "the admin API only serves local, non-browser clients"))
        (-> (p/let [body      (read-body node-req)
                    url       (js/URL. (.-url node-req) "http://localhost")
                    [st resp] (route registry (.-method node-req) (.-pathname url) (query-of url) body)]
              (write-json! res st resp))
            (p/catch (fn [e]
                       (let [code (or (:code (ex-data e)) "internal error")]
                         (write-json! res (if (= code "internal error") 500 400) (error-body code (ex-message e)))))))))))

(defn start!
  "Run the daemon's admin API on `host`:`port` (port 0 picks one). Resolves
   {:port :stop!}; `stop!` closes the admin API and every imposter."
  [{:keys [host port]}]
  (let [registry (atom {})
        admin    (tracked-server (admin-handler registry))]
    (p/let [_ (listen! admin port host)]
      {:port  (.-port (.address ^js (:server admin)))
       :stop! (fn []
                (p/let [_ (p/all (map close! (mapcat :servers (vals @registry))))]
                  (reset! registry {})
                  (close! admin)))})))
