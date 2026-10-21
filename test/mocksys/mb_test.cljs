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
            [mocksys.runtime :as runtime]
            [mocksys.store :as store]))

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

;; --- which daemon a store uses ----------------------------------------------------

(defn- port-for
  "The admin port a CLI on `root` uses, with MOCKSYS_ADMIN_PORT set to `value`
   (nil unsets it)."
  [root value]
  (let [env  (.-env js/process)
        prev [store/root (.-MOCKSYS_ADMIN_PORT env)]]
    (set! store/root root)
    (if (nil? value) (js-delete env "MOCKSYS_ADMIN_PORT") (set! (.-MOCKSYS_ADMIN_PORT env) value))
    (try (mb/admin-port)
         (finally (set! store/root (first prev))
                  (if (nil? (second prev)) (js-delete env "MOCKSYS_ADMIN_PORT") (set! (.-MOCKSYS_ADMIN_PORT env) (second prev)))))))

(deftest each-store-has-its-own-admin-port
  (is (= 2626 (port-for store/default-root nil)) "the default store keeps 2626")
  (is (= 26739 (port-for "/work/a/.mocks" nil)) "another store's port comes from its path, the same in every process")
  (is (= 26939 (port-for "/work/b/.mocks" nil)) "so a second store has a daemon of its own")
  (is (every? #(<= 26000 % 26999) (map #(port-for (str "/work/" % "/.mocks") nil) (range 200)))
      "derived ports stay in 26000-26999")
  (is (= 9123 (port-for "/work/a/.mocks" "9123")) "MOCKSYS_ADMIN_PORT still wins"))

(deftest a-store-reached-through-a-link-keeps-its-port
  ;; process.cwd() is the physical path, so `./.mocks` and `$PWD/.mocks` differ
  ;; when the project sits behind a link (macOS's /tmp and /var are links)
  (let [real (fs/realpathSync (fs/mkdtempSync (path/join (os/tmpdir) "mocksys-mb-")))
        link (str real "-link")]
    (fs/symlinkSync real link)
    (is (= (port-for (path/join real "store") nil) (port-for (path/join link "store") nil))
        "a store that does not exist yet")
    (fs/mkdirSync (path/join real "store"))
    (is (= (port-for (path/join real "store") nil) (port-for (path/join link "store") nil))
        "and once it does")
    (fs/unlinkSync link)))

(deftest a-relative-store-is-resolved
  (is (= (path/join (js/process.cwd) ".mocks") (store/root-for "./.mocks"))
      "a CLI run from a subdirectory finds the same store and token file")
  (is (= (path/join (js/process.cwd) "a") (store/root-for "a/")))
  (is (= store/default-root (store/root-for nil)))
  (is (= store/default-root (store/root-for ""))))

;; --- ensure-up! on a private admin port ---------------------------------------

(defn- free-port []
  (js/Promise.
   (fn [resolve]
     (let [srv (.createServer net)]
       (.listen srv 0 "127.0.0.1" (fn [] (let [port (.-port (.address srv))] (.close srv #(resolve port)))))))))

(defn- tmp-log []
  (path/join (fs/mkdtempSync (path/join (os/tmpdir) "mocksys-mb-")) "daemon.log"))


(defn- tmp-dir [] (fs/mkdtempSync (path/join (os/tmpdir) "mocksys-mb-")))

(defn- with-admin-port
  "Run `(f port)` with MOCKSYS_ADMIN_PORT set to a free port (or `value`) and a
   fresh store, so the daemon's token file lands there, then restore both."
  ([done f] (p/let [port (free-port)] (with-admin-port done (str port) f)))
  ([done value f]
   (let [env  (.-env js/process)
         prev (.-MOCKSYS_ADMIN_PORT env)
         root store/root]
     (set! (.-MOCKSYS_ADMIN_PORT env) value)
     (set! store/root (tmp-dir))
     (-> (p/do (f value))
         (p/catch (fn [e] (is (nil? e) (str "unexpected rejection: " (ex-message e)))))
         (p/finally (fn []
                      (if (nil? prev) (js-delete env "MOCKSYS_ADMIN_PORT") (set! (.-MOCKSYS_ADMIN_PORT env) prev))
                      (set! store/root root)
                      (done)))))))

(defn- start-daemon!
  "The real runtime on `port`, publishing its token to the store as `mocksys daemon`
   does."
  [port]
  (runtime/start! {:host "127.0.0.1" :port (js/Number port) :token-file (mb/token-path port)}))

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
   `stoppable?` is false, like a daemon from before DELETE / existed). Given a
   `token`, it refuses every call but GET / that does not carry it. Resolves
   {:server :calls}."
  [port version stoppable? & [token]]
  (let [calls (atom [])
        srv   (atom nil)]
    (p/let [s (serve-on port
                        (fn [^js req ^js res]
                          (swap! calls conj [(.-method req) (.-url req)])
                          (let [reply (fn [st body] (.writeHead res st #js {"Content-Type" "application/json"})
                                        (.end res (js/JSON.stringify (clj->js body))))
                                call  [(.-method req) (.-url req)]]
                            (cond
                              (and token (not= call ["GET" "/"])
                                   (not= (str "Bearer " token) (.. req -headers -authorization)))
                              (reply 401 {"errors" [{"code" "unauthorized"}]})

                              (= call ["GET" "/"])
                              (reply 200 (cond-> {"name" "mocksys" "pid" 4242} version (assoc "version" version)))

                              (= call ["GET" "/imposters"])
                              (reply 200 {"imposters" [{"port" 9999 "name" "okta/okta"}]})

                              (and (= call ["DELETE" "/"]) stoppable?)
                              (do (.on res "finish" #(let [^js s @srv] (.close s) (.closeAllConnections s)))
                                  (reply 200 {"stopped" 1}))

                              :else
                              (reply 404 {})))))]
      (reset! srv s)
      {:server s :calls calls})))

(defn- in-process-spawn
  "A spawn! that starts the real runtime in this process on the admin port, and
   records the daemon so the test can stop it."
  [daemons]
  (fn [_]
    (-> (start-daemon! (mb/admin-port))
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
        (p/let [d       (start-daemon! port)
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
                d    (start-daemon! port)
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

;; --- the admin token ------------------------------------------------------------

(deftest calls-carry-the-token-the-daemon-published
  (async done
    (with-admin-port done
      (fn [port]
        (p/let [d     (start-daemon! port)
                mock  (free-port)
                made  (outcome (mb/post-imposter! {"protocol" "http" "port" mock "name" "demo/hello" "stubs" []}))
                live  (mb/list-imposters)]
          (is (= :resolved made))
          (is (= [{:port mock :name "demo/hello"}] live))
          ((:stop! d)))))))

(defn- another-store-error [port]
  (str "the mocksys daemon on 127.0.0.1:" port " belongs to another store or user"))

(deftest a-daemon-of-another-store-is-named
  (async done
    (with-admin-port done
      (fn [port]
        (p/let [other (path/join (tmp-dir) "daemon.token")
                d     (runtime/start! {:host "127.0.0.1" :port (js/Number port) :token-file other})
                ls    (outcome (mb/list-imposters))
                post  (outcome (mb/post-imposter! {"protocol" "http" "port" 1 "stubs" []}))
                stop  (outcome (mb/stop-daemon!))
                up    (mb/up?)
                _     (fs/writeFileSync (mb/token-path port) "stale")
                stale (outcome (mb/list-imposters))]
          (doseq [out [ls post stop stale]]
            (is (str/includes? (str out) (another-store-error port)))
            (is (str/includes? (str out) "MOCKSYS_ADMIN_PORT") "the error says how to get a daemon of your own"))
          (is (str/includes? (str ls) (mb/token-path port)) "and names the token file it read")
          (is (true? up) "a refused stop leaves the daemon running")
          ((:stop! d)))))))

(deftest replacing-a-daemon-needs-its-token
  (async done
    (with-admin-port done
      (fn [port]
        (p/let [spawned (atom 0)
                warned  (atom [])
                old     (old-daemon port "0.0.0-old" true "theirs")
                out     (outcome (mb/ensure-up! {:spawn! (fn [_] (swap! spawned inc) nil) :log (tmp-log)
                                                 :warn!  #(swap! warned conj %)}))]
          (.close (:server old))
          (is (str/includes? (str out) (another-store-error port)))
          (is (not (some #{["DELETE" "/"]} @(:calls old))) "it is not asked to stop without the token")
          (is (= [0 []] [@spawned @warned]) "nothing is started or announced"))))))

(deftest two-daemons-can-share-a-store
  (async done
    (with-admin-port done
      (fn [port]
        (p/let [port2 (free-port)
                d1    (start-daemon! port)
                d2    (start-daemon! port2)
                one   (outcome (mb/list-imposters))
                _     (set! (.-MOCKSYS_ADMIN_PORT (.-env js/process)) (str port2))
                two   (outcome (mb/list-imposters))]
          (is (= [:resolved :resolved] [one two]) "each daemon's token has its own file")
          (is (not= (mb/token-path port) (mb/token-path port2)))
          (p/all [((:stop! d1)) ((:stop! d2))]))))))
