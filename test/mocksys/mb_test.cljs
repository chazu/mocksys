(ns mocksys.mb-test
  "The daemon client's lifecycle against real listeners on a private admin port:
   what ensure-up! does about a foreign listener, a bad MOCKSYS_ADMIN_PORT, a daemon
   from another mocksys version and a daemon that dies on startup, and how the
   daemon is re-run from each kind of process."
  (:require [clojure.test :refer [deftest testing is async]]
            [clojure.string :as str]
            [promesa.core :as p]
            ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:net" :as net]
            ["node:os" :as os]
            ["node:path" :as path]
            [mocksys.mb :as mb]
            [mocksys.runtime :as runtime]))

(deftest daemon-command-per-process-kind
  (testing "the bun binary is its own runtime; its script path is virtual"
    (is (= ["/usr/local/bin/mocksys" ["daemon"]]
           (mb/daemon-command {:exec-path "/usr/local/bin/mocksys" :script "/$bunfs/root/mocksys" :bun? true}))))
  (testing "bun running a script re-runs the script"
    (is (= ["/opt/bun/bin/bun" ["/src/mocksys/out/mocksys.js" "daemon"]]
           (mb/daemon-command {:exec-path "/opt/bun/bin/bun" :script "/src/mocksys/out/mocksys.js" :bun? true}))))
  (testing "node re-runs the script"
    (is (= ["/usr/bin/node" ["/src/mocksys/bin/mocksys" "daemon"]]
           (mb/daemon-command {:exec-path "/usr/bin/node" :script "/src/mocksys/bin/mocksys" :bun? false})))))

;; --- ensure-up! on a private admin port ---------------------------------------

(defn- free-port []
  (js/Promise.
   (fn [resolve]
     (let [srv (.createServer net)]
       (.listen srv 0 "127.0.0.1" (fn [] (let [port (.-port (.address srv))] (.close srv #(resolve port)))))))))

(defn- tmp-log []
  (path/join (fs/mkdtempSync (path/join (os/tmpdir) "mocksys-mb-")) "daemon.log"))

(defn- with-admin-port
  "Run `(f port)` with MOCKSYS_ADMIN_PORT set to a free port (or `value`), then
   restore it."
  ([done f] (p/let [port (free-port)] (with-admin-port done (str port) f)))
  ([done value f]
   (let [env  (.-env js/process)
         prev (.-MOCKSYS_ADMIN_PORT env)]
     (set! (.-MOCKSYS_ADMIN_PORT env) value)
     (-> (p/do (f value))
         (p/catch (fn [e] (is (nil? e) (str "unexpected rejection: " (ex-message e)))))
         (p/finally (fn []
                      (if (nil? prev) (js-delete env "MOCKSYS_ADMIN_PORT") (set! (.-MOCKSYS_ADMIN_PORT env) prev))
                      (done)))))))

(defn- outcome
  "The promise's rejection message, or :resolved."
  [pr]
  (-> (p/do pr) (p/then (constantly :resolved)) (p/catch ex-message)))

(defn- serve-on
  "An HTTP server on 127.0.0.1:`port` answering every request with `handler`."
  [port handler]
  (js/Promise.
   (fn [resolve]
     (let [srv (.createServer http handler)]
       (.listen srv (js/Number port) "127.0.0.1" #(resolve srv))))))

(deftest a-foreign-listener-is-reported-not-replaced
  (async done
    (with-admin-port done
      (fn [port]
        (p/let [spawned (atom 0)
                srv     (serve-on port (fn [_ ^js res] (.end res "<html>not a daemon</html>")))
                out     (outcome (mb/ensure-up! {:spawn! (fn [_] (swap! spawned inc) nil) :log (tmp-log)}))]
          (.close srv)
          (is (str/includes? (str out) (str "port " port " is taken by something that is not the mocksys daemon")))
          (is (= 0 @spawned) "no daemon is started over it"))))))

(deftest an-invalid-admin-port-is-reported
  (async done
    (with-admin-port done "26x6"
      (fn [_]
        (p/let [spawned (atom 0)
                out     (outcome (mb/ensure-up! {:spawn! (fn [_] (swap! spawned inc) nil) :log (tmp-log)}))]
          (is (= "MOCKSYS_ADMIN_PORT must be a port number, got \"26x6\"" out))
          (is (= 0 @spawned) "a bad port is not read as a daemon that is down"))))))

(deftest a-daemon-that-dies-on-startup-reports-its-log
  (async done
    (with-admin-port done
      (fn [_]
        (p/let [log (tmp-log)
                t0  (js/Date.now)
                out (outcome (mb/ensure-up! {:log log
                                             :spawn! #(mb/spawn-daemon! [(.-execPath js/process)
                                                                         ["-e" "console.error('boom: bad config'); process.exit(3)"]] %)}))]
          (is (str/includes? (str out) "the mocksys daemon exited with status 3 before it came up"))
          (is (str/includes? (str out) "boom: bad config") "the log's last lines are in the error")
          (is (str/includes? (str out) log) "the error names the log")
          (is (< (- (js/Date.now) t0) 5000) "it fails as soon as the process exits, not after the timeout"))))))

(deftest a-daemon-that-cannot-be-run-says-why
  (async done
    (with-admin-port done
      (fn [_]
        (p/let [out (outcome (mb/ensure-up! {:log (tmp-log)
                                             :spawn! #(mb/spawn-daemon! ["/nonexistent/mocksys" ["daemon"]] %)}))]
          (is (str/includes? (str out) "the mocksys daemon could not start (/nonexistent/mocksys)"))
          (is (str/includes? (str out) "ENOENT")))))))

(deftest a-daemon-that-never-answers-times-out-with-its-log
  (async done
    (with-admin-port done
      (fn [port]
        (p/let [log (tmp-log)
                out (outcome (mb/ensure-up! {:log log :timeout-ms 600
                                             :spawn! #(mb/spawn-daemon! [(.-execPath js/process)
                                                                         ["-e" "console.log('listening soon'); setTimeout(() => {}, 3000)"]] %)}))]
          (is (str/includes? (str out) (str "the mocksys daemon did not come up on 127.0.0.1:" port)))
          (is (str/includes? (str out) "listening soon")))))))

(def ^:private this-version (.-version (js/require "../package.json")))

(defn- old-daemon
  "A stand-in for a daemon from another mocksys version on `port`: it identifies
   itself with `version`, lists one mock, and on DELETE / closes (unless
   `stoppable?` is false, like a daemon from before DELETE / existed). Resolves
   {:server :calls}."
  [port version stoppable?]
  (let [calls (atom [])
        srv   (atom nil)]
    (p/let [s (serve-on port
                        (fn [^js req ^js res]
                          (swap! calls conj [(.-method req) (.-url req)])
                          (let [reply (fn [st body] (.writeHead res st #js {"Content-Type" "application/json"})
                                        (.end res (js/JSON.stringify (clj->js body))))]
                            (case [(.-method req) (.-url req)]
                              ["GET" "/"]          (reply 200 (cond-> {"name" "mocksys" "pid" 4242}
                                                                version (assoc "version" version)))
                              ["GET" "/imposters"] (reply 200 {"imposters" [{"port" 9999 "name" "okta/okta"}]})
                              ["DELETE" "/"]       (if stoppable?
                                                     (do (.on res "finish" #(let [^js s @srv] (.close s) (.closeAllConnections s)))
                                                         (reply 200 {"stopped" 1}))
                                                     (reply 404 {}))
                              (reply 404 {})))))]
      (reset! srv s)
      {:server s :calls calls})))

(defn- in-process-spawn
  "A spawn! that starts the real runtime in this process on the admin port, and
   records the daemon so the test can stop it."
  [daemons]
  (fn [_]
    (-> (runtime/start! {:host "127.0.0.1" :port (mb/admin-port)})
        (p/then #(swap! daemons conj %)))
    nil))

(deftest a-daemon-from-another-version-is-replaced
  (async done
    (with-admin-port done
      (fn [port]
        (p/let [daemons (atom [])
                warned  (atom [])
                old     (old-daemon port "0.0.0-old" true)
                out     (outcome (mb/ensure-up! {:spawn! (in-process-spawn daemons) :log (tmp-log)
                                                 :warn!  #(swap! warned conj %)}))
                now     (mb/fetch-json "GET" "/")]
          (is (= :resolved out))
          (is (some #{["DELETE" "/"]} @(:calls old)) "the old daemon was asked to stop")
          (is (= this-version (get-in now [:body "version"])) "a daemon of this version answers now")
          (is (= 1 (count @warned)) "one line says so")
          (is (str/includes? (first @warned) (str "replacing the daemon on 127.0.0.1:" port)))
          (is (str/includes? (first @warned) (str "it runs mocksys 0.0.0-old, this is " this-version)))
          (is (str/includes? (first @warned) "its 1 running mock(s) stop: okta/okta"))
          (p/all (map #((:stop! %)) @daemons)))))))

(deftest a-daemon-of-this-version-is-kept
  (async done
    (with-admin-port done
      (fn [port]
        (p/let [d       (runtime/start! {:host "127.0.0.1" :port (js/Number port)})
                spawned (atom 0)
                warned  (atom [])
                out     (outcome (mb/ensure-up! {:spawn! (fn [_] (swap! spawned inc) nil) :log (tmp-log)
                                                 :warn!  #(swap! warned conj %)}))]
          (is (= :resolved out))
          (is (= [0 []] [@spawned @warned]) "nothing is restarted or printed")
          ((:stop! d)))))))

(deftest a-daemon-that-cannot-be-stopped-says-how
  (async done
    (with-admin-port done
      (fn [port]
        (p/let [old (old-daemon port nil false)
                out (outcome (mb/ensure-up! {:spawn! (fn [_] nil) :log (tmp-log) :warn! (fn [_])}))]
          (.close (:server old))
          (is (= "the mocksys daemon (pid 4242) is too old to be stopped this way; stop it with: kill 4242" out)))))))

(deftest stop-daemon-stops-it-and-names-its-mocks
  (async done
    (with-admin-port done
      (fn [port]
        (p/let [none (mb/stop-daemon!)
                d    (runtime/start! {:host "127.0.0.1" :port (js/Number port)})
                mock (free-port)
                _    (mb/post-imposter! {"protocol" "http" "port" mock "name" "demo/hello" "stubs" []})
                gone (mb/stop-daemon!)
                up   (mb/up?)
                wire (-> (js/fetch (str "http://127.0.0.1:" mock "/")) (p/then (constantly :served)) (p/catch (constantly :refused)))]
          (is (nil? none) "nothing to stop is not an error")
          (is (= [{:port mock :name "demo/hello"}] gone))
          (is (false? up) "the admin API is gone")
          (is (= :refused wire) "and so is the mock")
          (is (some? d)))))))
