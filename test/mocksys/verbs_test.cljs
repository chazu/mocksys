(ns mocksys.verbs-test
  "Custom verbs: a contract lists CommonJS `modules` that export `verbs`, and an effect
   runs one with `call`. Covers the generated injection (through the harness), what
   `lower` and `validate` accept, and the daemon loading modules on each post."
  (:require [clojure.test :refer [deftest testing is async]]
            [clojure.string :as str]
            [promesa.core :as p]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:net" :as net]
            [mocksys.harness :as h]
            [mocksys.contract :as contract]
            [mocksys.runtime :as runtime]))

;; --- the generated injection ---------------------------------------------------

(defn- verbs [m] (clj->js m))

(def ^:private shout-op
  {"id" "shout"
   "request" {"method" "GET" "path" "/shout"}
   "effect" {"bind" {"word" {"query" "w"}}
             "call" {"verb" "upper" "args" {"s" "{word}!" "n" 3} "as" "loud"}
             "respond" {"status" 200 "json" {"loud" "{loud}"}}}})

(deftest call-binds-what-the-verb-returns
  (let [seen (atom nil)
        vs   (verbs {"upper" (fn [^js ctx] (reset! seen (js->clj (.-args ctx))) (str/upper-case (.. ctx -args -s)))})
        r    (h/run shout-op {:path "/shout" :query {"w" "hey"}} {:verbs vs})]
    (is (= 200 (:status r)))
    (is (= {"loud" "HEY!"} (:json r)))
    (is (= {"s" "hey!" "n" 3} @seen) "args interpolate like other verbs; non-strings pass through")))

(deftest ctx-carries-request-state-vars-and-now
  (let [vs    (verbs {"peek" (fn [^js ctx]
                               (let [^js st (.-state ctx)]
                                 (set! (.-hits st) (inc (or (.-hits st) 0)))
                                 #js {:method (.. ctx -request -method)
                                      :sig    (aget (.. ctx -request -headers) "X-Sig")
                                      :body   (.. ctx -request -body)
                                      :who    (.. ctx -vars -who)
                                      :now    (.-now ctx)
                                      :hits   (.-hits st)}))})
        op    {"id" "peek"
               "request" {"method" "POST" "path" "/peek"}
               "effect" {"bind" {"who" {"const" "alice"}}
                         "call" {"verb" "peek" "as" "seen"}
                         "respond" {"status" 200 "jsonVar" "seen"}}}
        state (h/fresh-state)
        req   {:method "POST" :path "/peek" :headers {"X-Sig" "abc"} :body "raw=1"}
        opts  {:verbs vs :clock-start 1700000000}
        _     (h/run op req opts state)
        r     (h/run op req opts state)]
    (is (= {"method" "POST" "sig" "abc" "body" "raw=1" "who" "alice" "now" 1700000000 "hits" 2}
           (:json r))
        "state is the imposter's shared state, so it persists across requests")))

(deftest a-verb-can-end-the-request
  (let [vs (verbs {"gate" (fn [^js ctx]
                            (case (.. ctx -args -sig)
                              "good" true
                              "teapot" #js {:respond #js {:status 418 :headers #js {"X-Why" "tea"} :body "short and stout"}}
                              #js {:respond #js {:status 401 :json #js {:error "bad signature"}}}))})
        op {"id" "hook"
            "request" {"method" "POST" "path" "/hook"}
            "effect" {"bind" {"sig" {"header" "x-sig"}}
                      "call" {"verb" "gate" "args" {"sig" "{sig}"}}
                      "respond" {"status" 202 "json" {"ok" true}}}}
        run #(h/run op {:method "POST" :path "/hook" :headers {"x-sig" %}} {:verbs vs})]
    (testing "a plain value falls through to the rest of the effect"
      (is (= [202 {"ok" true}] ((juxt :status :json) (run "good")))))
    (testing "{respond: {json}} ends the request as JSON"
      (let [r (run "forged")]
        (is (= [401 {"error" "bad signature"}] ((juxt :status :json) r)))
        (is (= "application/json" (get-in r [:headers "Content-Type"])))))
    (testing "{respond: {body}} ends it with that text and those headers"
      (let [r (run "teapot")]
        (is (= [418 "short and stout" "tea"] [(:status r) (:body r) (get-in r [:headers "X-Why"])]))))))

(deftest call-runs-after-reads-and-before-mint
  (let [vs (verbs {"first"  (fn [^js ctx] (str "row " (.. ctx -vars -user -id)
                                           (when (.. ctx -vars -token) " and a token")))
                   "second" (fn [^js ctx] (str (.. ctx -vars -a) ", then second"))})
        op {"id" "order"
            "request" {"method" "GET" "path" "/order"}
            "effect" {"find" {"collection" "users" "key" "u1" "as" "user"}
                      "mint" {"token" {"prefix" "t_"}}
                      "call" [{"verb" "first" "as" "a"} {"verb" "second" "as" "b"}]
                      "respond" {"status" 200 "json" {"b" "{b}" "token" "{token}"}}}}
        r  (h/run op {:path "/order"} {:verbs vs :collections {"users" {"idField" "id" "seed" [{"id" "u1"}]}}})]
    (is (= "row u1, then second" (get-in r [:json "b"]))
        "a call sees find's row, not the minted token, and a list of calls runs in order")
    (is (str/starts-with? (get-in r [:json "token"]) "t_") "mint still runs after the calls")))

(defn- run-error [op vs]
  (try (h/run op {:path "/shout" :query {"w" "x"}} {:verbs vs}) nil
       (catch :default e (ex-message e))))

(deftest a-verb-that-fails-is-named
  (is (= "verb 'upper' threw: kaput"
         (run-error shout-op (verbs {"upper" (fn [_] (throw (js/Error. "kaput")))}))))
  (is (= "verb 'upper' returned a Promise; verbs must be synchronous"
         (run-error shout-op (verbs {"upper" (fn [_] (js/Promise.resolve 1))}))))
  (is (= "verb 'upper' is not exported by the scenario's modules"
         (run-error shout-op (verbs {})))))

;; --- lower and validate --------------------------------------------------------

(defn- scenario-dir!
  "A fresh directory holding `files` ({relative-name source}), as its real path."
  [files]
  (let [dir (fs/realpathSync (fs/mkdtempSync (path/join (os/tmpdir) "mocksys-verbs-")))]
    (doseq [[n src] files]
      (let [f (path/join dir n)]
        (fs/mkdirSync (path/dirname f) #js {:recursive true})
        (fs/writeFileSync f src)))
    dir))

(def ^:private upper-module
  "module.exports = { verbs: { upper(ctx) { return String(ctx.args.s).toUpperCase(); } } };")

(defn- shout-contract [modules]
  {"service" "demo" "scenario" "demo/shout" "modules" modules "operations" [shout-op]})

(defn- errors [c dir] (:errors (contract/validate c dir)))

(deftest lower-records-absolute-module-paths
  (let [dir (scenario-dir! {"lib/verbs.js" upper-module})
        imp (contract/lower (shout-contract ["./lib/verbs.js"]) nil dir)]
    (is (= [(path/join dir "lib/verbs.js")] (get imp "modules")))
    (is (= [] (errors (shout-contract ["./lib/verbs.js"]) dir)))
    (is (some #(= "/_mocksys/clock" (get-in % ["predicates" 0 "deepEquals" "path"])) (get imp "stubs"))
        "a call turns the virtual clock on, so ctx.now is the twin's time")))

(deftest modules-must-live-in-the-scenario-directory
  (let [outside (scenario-dir! {"verbs.js" upper-module})
        dir     (scenario-dir! {})]
    (fs/symlinkSync (path/join outside "verbs.js") (path/join dir "linked.js"))
    (doseq [m [(str "../" (path/basename outside) "/verbs.js") (path/join outside "verbs.js") "./linked.js"]]
      (is (some #(str/includes? % (str "modules: " m " is outside the scenario directory")) (errors (shout-contract [m]) dir))
          (str m " is refused"))))
  (is (thrown-with-msg? js/Error #"modules: .* needs the scenario directory"
                        (contract/lower (shout-contract ["./verbs.js"])))))

(deftest a-broken-module-is-named
  (let [dir (scenario-dir! {"none.js" "module.exports = {};"
                            "esm.js"  "export const verbs = {};"
                            "num.js"  "module.exports = { verbs: { upper: 1 } };"})]
    (is (some #(str/includes? % "modules: ./gone.js: no such file") (errors (shout-contract ["./gone.js"]) dir)))
    (is (some #(str/includes? % "modules: ./none.js exports no `verbs` object") (errors (shout-contract ["./none.js"]) dir)))
    (is (some #(str/includes? % "modules: ./esm.js does not load") (errors (shout-contract ["./esm.js"]) dir)))
    (is (some #(str/includes? % "CommonJS") (errors (shout-contract ["./esm.js"]) dir)))
    (is (some #(str/includes? % "modules: ./num.js: verb 'upper' is not a function") (errors (shout-contract ["./num.js"]) dir)))))

(deftest a-call-must-name-an-exported-verb
  (let [dir (scenario-dir! {"verbs.js" "module.exports = { verbs: { lower() {}, title() {} } };"})]
    (is (= ["shout: call: verb 'upper' is not exported by the modules (they export lower, title)"]
           (errors (shout-contract ["./verbs.js"]) dir)))
    (is (= ["shout: call: verb 'upper' needs a module that exports it; list one under `modules:`"]
           (errors (dissoc (shout-contract nil) "modules") dir)))
    (is (some #(str/includes? % "call needs a `verb` name")
              (errors (assoc-in (shout-contract ["./verbs.js"]) ["operations" 0 "effect" "call"] {"as" "x"}) dir)))))

(deftest two-modules-exporting-one-verb-is-an-error
  (let [dir (scenario-dir! {"a.js" upper-module "b.js" upper-module})]
    (is (some #(str/includes? % "modules: verb 'upper' is exported by both ./a.js and ./b.js")
              (errors (shout-contract ["./a.js" "./b.js"]) dir)))))

;; --- the daemon ----------------------------------------------------------------

(defn- free-port []
  (js/Promise.
   (fn [resolve]
     (let [srv (.createServer net)]
       (.listen srv 0 (fn [] (let [port (.-port (.address srv))] (.close srv #(resolve port)))))))))

(defn- call
  ([url] (call url {}))
  ([url {:keys [method body]}]
   (p/let [res  (js/fetch url (clj->js (cond-> {:method (or method "GET") :headers {"Content-Type" "application/json"}}
                                         body (assoc :body (if (string? body) body (js/JSON.stringify (clj->js body)))))))
           text (.text res)]
     {:status (.-status res)
      :text   text
      :json   (try (js->clj (js/JSON.parse text)) (catch :default _ nil))})))

(defn- with-daemon [done f]
  (p/let [d     (runtime/start! {:host "127.0.0.1" :port 0})
          admin (str "http://127.0.0.1:" (:port d))]
    (-> (p/do (f admin))
        (p/catch (fn [e] (is (nil? e) (str "unexpected rejection: " (ex-message e)))))
        (p/finally (fn [] (p/do ((:stop! d)) (done)))))))

(defn- post! [admin imposter port]
  (p/do (call (str admin "/imposters/" port) {:method "DELETE"})
        (call (str admin "/imposters") {:method "POST" :body (assoc imposter "port" port)})))

(deftest the-daemon-serves-module-verbs-fresh-on-each-post
  (async done
    (with-daemon done
      (fn [admin]
        (let [dir (scenario-dir! {"verbs.js" upper-module})
              c   (shout-contract ["./verbs.js"])
              url #(str "http://127.0.0.1:" % "/shout?w=hey")]
          (p/let [port   (free-port)
                  made   (post! admin (contract/lower c nil dir) port)
                  first* (call (url port))
                  _      (fs/writeFileSync (path/join dir "verbs.js")
                                           "module.exports = { verbs: { upper(ctx) { return 'v2 ' + ctx.args.s; } } };")
                  _      (post! admin (contract/lower c nil dir) port)
                  second (call (url port))]
            (is (= 201 (:status made)))
            (is (= {"loud" "HEY!"} (:json first*)))
            (is (= {"loud" "v2 hey!"} (:json second)) "an edited module is reloaded, not served from the require cache")))))))

(deftest the-daemon-names-a-module-or-verb-that-fails
  (async done
    (with-daemon done
      (fn [admin]
        (let [dir (scenario-dir! {"verbs.js" "module.exports = { verbs: { upper() { throw new Error('kaput'); } } };"})
              imp (contract/lower (shout-contract ["./verbs.js"]) nil dir)]
          (p/let [port   (free-port)
                  _      (post! admin imp port)
                  threw  (call (str "http://127.0.0.1:" port "/shout"))
                  _      (fs/writeFileSync (path/join dir "verbs.js") "module.exports = {")
                  broken (post! admin imp port)
                  rel    (post! admin (assoc imp "modules" ["verbs.js"]) port)]
            (is (= 500 (:status threw)))
            (is (= "verb 'upper' threw: kaput" (get-in threw [:json "errors" 0 "message"])))
            (is (= 400 (:status broken)))
            (is (str/includes? (get-in broken [:json "errors" 0 "message"])
                               (str "modules: " (path/join dir "verbs.js") " does not load")))
            (is (= 400 (:status rel)))
            (is (str/includes? (get-in rel [:json "errors" 0 "message"]) "modules must be absolute paths"))))))))

(deftest ctx-now-follows-the-virtual-clock
  (async done
    (with-daemon done
      (fn [admin]
        (let [dir (scenario-dir! {"verbs.js" "module.exports = { verbs: { upper(ctx) { return ctx.now; } } };"})]
          (p/let [port   (free-port)
                  _      (post! admin (contract/lower (shout-contract ["./verbs.js"]) nil dir) port)
                  before (call (str "http://127.0.0.1:" port "/shout"))
                  _      (call (str "http://127.0.0.1:" port "/_mocksys/clock") {:method "POST" :body {"advance" 60}})
                  after  (call (str "http://127.0.0.1:" port "/shout"))]
            (is (= "1700000000" (get-in before [:json "loud"])))
            (is (= "1700000060" (get-in after [:json "loud"])))))))))
