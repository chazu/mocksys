(ns mocksys.cli-kit-test
  "`mocksys kit` against a throwaway store: a kit whose contract does not validate
   writes nothing, a valid user kit's typed flags reach a compiled imposter, and a
   directory kit's modules are copied into the scenario and nowhere else."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["js-yaml" :as yaml]
            [mocksys.cli.util :as util]
            [mocksys.cli.twins :as twins]
            [mocksys.store :as store]))

(defn- tmp [prefix] (fs/mkdtempSync (path/join (os/tmpdir) prefix)))

(defn- store!
  "A fresh store whose kits/ holds `kit-files` ({relative-path text}). Returns its root."
  [kit-files]
  (let [home (tmp "mocksys-home-")]
    (fs/mkdirSync (path/join home "kits"))
    (doseq [[f text] kit-files
            :let [file (path/join home "kits" f)]]
      (fs/mkdirSync (path/dirname file) #js {:recursive true})
      (fs/writeFileSync file text))
    home))

(defn- in-store
  "Run `cmd` with `pos` and `opts` against the store at `home`. Returns {:home :died
   :out}; :died is the message `die` would print."
  [home cmd pos opts]
  (with-redefs [util/die          (fn [msg] (throw (ex-info msg {:die true})))
                store/root         home
                store/kits-dir     (path/join home "kits")
                store/services-dir (path/join home "services")]
    (let [out  (atom [])
          died (binding [*print-fn* #(swap! out conj %)]
                 (try (cmd {:pos pos :opts opts}) nil
                      (catch :default e
                        (if (:die (ex-data e)) (.-message e) (throw e)))))]
      {:home home :died died :out (str/join "\n" @out)})))

(defn- kit-in [home pos opts] (in-store home twins/cmd-kit pos opts))

(defn- run-kit [kit-files pos opts] (kit-in (store! kit-files) pos opts))

(def ^:private clashing-kit "
name: clash
contract:
  genes:
    - crud-collection: { coll: users, base: /a }
    - crud-collection: { coll: users, base: /b }
")

(deftest an-invalid-kit-writes-nothing
  (let [{:keys [home died]} (run-kit {"clash.yaml" clashing-kit} ["clash"] {})]
    (testing "kit dies naming the problem"
      (is (some? died))
      (is (str/includes? (str died) "duplicate operation id")))
    (testing "no contract.yaml or imposter.json was written"
      (is (not (fs/existsSync (path/join home "clash" "clash" "contract.yaml"))))
      (is (not (fs/existsSync (path/join home "clash" "clash" "imposter.json")))))))

(def ^:private ttl-kit "
name: ttl
flags: { ttl: { type: number } }
contract:
  genes:
    - oauth2-provider: { token-ttl: '{ttl}' }
")

(deftest a-typed-user-kit-flag-compiles
  (let [{:keys [home died]} (run-kit {"ttl.yaml" ttl-kit} ["ttl"] {:ttl "60"})
        imposter (path/join home "ttl" "ttl" "imposter.json")]
    (is (nil? died))
    (is (fs/existsSync imposter))
    (is (str/includes? (fs/readFileSync imposter "utf8") "60"))))

;; --- kits that ship modules -----------------------------------------------------

(defn- hooks-kit [& modules]
  (str "
flags: { word: hi }
contract:
  modules: [" (str/join ", " (map #(str "'" % "'") modules)) "]
  operations:
    - id: shout
      request: { method: GET, path: /shout }
      effect: { call: { verb: upper, args: { w: '{word}' }, as: r }, respond: { status: 200, text: '{r}' } }
"))

(def ^:private verbs "module.exports = { verbs: { upper({ args }) { return args.w.toUpperCase(); } } };")

(defn- slurp [file] (fs/readFileSync file "utf8"))

(defn- scenario-file [home & parts] (apply path/join home "hooks" "hooks" parts))

(defn- nothing-written? [home]
  (not (fs/existsSync (scenario-file home "contract.yaml"))))

(defn- tree
  "Everything under `file`, links not followed: relative path -> the file's text,
   :dir, or the link's target. {} when nothing is there."
  [file]
  (let [st (try (fs/lstatSync file) (catch :default _ nil))]
    (cond
      (nil? st)             {}
      (.isSymbolicLink st)  {"" (str "-> " (fs/readlinkSync file))}
      (.isDirectory st)     (into {"" :dir}
                                  (for [e (map str (fs/readdirSync file))
                                        [p v] (tree (path/join file e))]
                                    [(if (= "" p) e (path/join e p)) v]))
      :else                 {"" (slurp file)})))

(defn- refused
  "Run the hooks kit with `opts` against `home`, as kit-in does, adding :untouched?,
   whether the scenario directory is exactly as it was before."
  [home opts]
  (let [before (tree (scenario-file home))
        result (kit-in home ["hooks"] opts)]
    (assoc result :untouched? (= before (tree (scenario-file home))))))

(def ^:private lower-verbs "module.exports = { verbs: { lower({ args }) { return args.w.toLowerCase(); } } };")

(defn- two-module-store
  "A hooks kit shipping ./a.js and ./lib/b.js."
  []
  (store! {"hooks/kit.yaml" (hooks-kit "./a.js" "./lib/b.js")
           "hooks/a.js"     verbs
           "hooks/lib/b.js" lower-verbs}))

(deftest a-directory-kit-copies-its-modules-into-the-scenario
  (let [{:keys [home died out]} (run-kit {"hooks/kit.yaml" (hooks-kit "./lib/verbs.js")
                                          "hooks/lib/verbs.js" verbs}
                                         ["hooks"] {})]
    (is (nil? died))
    (testing "the module lands at its path relative to the kit, under the scenario"
      (is (= verbs (slurp (scenario-file home "lib" "verbs.js")))))
    (testing "the compiled imposter names it relative to the scenario"
      (is (= ["lib/verbs.js"] (get (js->clj (js/JSON.parse (slurp (scenario-file home "imposter.json")))) "modules"))))
    (testing "kit says the scenario runs the module's JavaScript, as unpack does"
      (is (str/includes? out "! hooks/hooks runs JavaScript from its modules (lib/verbs.js) in the mocksys daemon")))))

(deftest a-kit-module-must-stay-inside-the-kit-directory
  (testing "a path that climbs out"
    (let [{:keys [home died]} (run-kit {"hooks/kit.yaml" (hooks-kit "../outside.js") "outside.js" verbs} ["hooks"] {})]
      (is (str/includes? (str died) "modules: ../outside.js is outside the kit directory"))
      (is (nothing-written? home))))
  (testing "a link that points out"
    (let [home (store! {"hooks/kit.yaml" (hooks-kit "./linked.js") "outside.js" verbs})]
      (fs/symlinkSync (path/join home "kits" "outside.js") (path/join home "kits" "hooks" "linked.js"))
      (let [{:keys [died]} (kit-in home ["hooks"] {})]
        (is (str/includes? (str died) "modules: ./linked.js is outside the kit directory"))
        (is (nothing-written? home))))))

(defn- hooks-store []
  (store! {"hooks/kit.yaml" (hooks-kit "./lib/verbs.js") "hooks/lib/verbs.js" verbs}))

(deftest kit-never-writes-outside-the-scenario-directory
  (testing "through a linked directory in the scenario"
    (let [home      (hooks-store)
          elsewhere (tmp "mocksys-elsewhere-")]
      (fs/mkdirSync (scenario-file home) #js {:recursive true})
      (fs/symlinkSync elsewhere (scenario-file home "lib"))
      (doseq [opts [{} {:force true}]]
        (let [{:keys [died]} (kit-in home ["hooks"] opts)]
          (is (str/includes? (str died) "outside") (str "refused with " opts))
          (is (empty? (fs/readdirSync elsewhere)))
          (is (nothing-written? home))))))
  (testing "through a link to nothing where the module goes"
    (let [home      (hooks-store)
          elsewhere (tmp "mocksys-elsewhere-")]
      (fs/mkdirSync (scenario-file home "lib") #js {:recursive true})
      (fs/symlinkSync (path/join elsewhere "planted.js") (scenario-file home "lib" "verbs.js"))
      (let [{:keys [died]} (kit-in home ["hooks"] {:force true})]
        (is (some? died))
        (is (not (fs/existsSync (path/join elsewhere "planted.js"))))
        (is (nothing-written? home))))))

(deftest rerunning-a-kit-keeps-a-hand-edited-module
  (let [home     (hooks-store)
        module   (scenario-file home "lib" "verbs.js")
        contract (scenario-file home "contract.yaml")]
    (is (nil? (:died (kit-in home ["hooks"] {}))))
    (testing "a module the kit already put there is no conflict"
      (is (nil? (:died (kit-in home ["hooks"] {:word "yo"}))))
      (is (str/includes? (slurp contract) "yo") "the contract still follows the flags"))
    (fs/writeFileSync module "// edited by hand")
    (fs/appendFileSync contract "# mine\n")
    (testing "a module edited by hand stops the kit before it writes anything"
      (let [{:keys [died]} (kit-in home ["hooks"] {})]
        (is (str/includes? (str died) "lib/verbs.js"))
        (is (str/includes? (str died) "--force"))
        (is (= "// edited by hand" (slurp module)))
        (is (str/includes? (slurp contract) "# mine"))))
    (testing "--force replaces it, and says so"
      (let [{:keys [died out]} (kit-in home ["hooks"] {:force true})]
        (is (nil? died))
        (is (= verbs (slurp module)))
        (is (str/includes? out "replaced lib/verbs.js"))))))

(deftest kits-marks-the-kits-that-ship-modules
  (let [{:keys [out]} (in-store (hooks-store) twins/cmd-kits [] {})
        line (some #(when (str/includes? % "· hooks") %) (str/split-lines out))]
    (is (str/includes? (str line) "ships modules: ./lib/verbs.js"))
    (is (not (str/includes? (some #(when (str/includes? % "· okta") %) (str/split-lines out)) "ships modules")))))

(deftest kit-refuses-a-module-whose-directory-is-a-file
  (let [home (two-module-store)]
    (fs/mkdirSync (scenario-file home) #js {:recursive true})
    (fs/writeFileSync (scenario-file home "lib") "a plain file")
    (let [{:keys [died untouched?]} (refused home {:force true})]
      (is (str/includes? (str died) "lib/b.js"))
      (is (str/includes? (str died) "not a directory"))
      (is untouched? "neither the contract nor a.js was written"))))

(deftest the-contract-names-where-the-modules-land
  (doseq [[what module] [["a path through the kit's own name" (fn [_] "../hooks/a.js")]
                         ["an absolute path inside the kit" (fn [home] (path/join home "kits" "hooks" "a.js"))]]]
    (testing what
      (let [home (store! {"hooks/a.js" verbs})]
        (fs/writeFileSync (path/join home "kits" "hooks" "kit.yaml") (hooks-kit (module home)))
        (let [{:keys [died]} (kit-in home ["hooks"] {})]
          (is (nil? died))
          (is (= verbs (slurp (scenario-file home "a.js"))))
          (is (= ["a.js"] (get (js->clj (yaml/load (slurp (scenario-file home "contract.yaml")))) "modules")))
          (is (= ["a.js"] (get (js->clj (js/JSON.parse (slurp (scenario-file home "imposter.json")))) "modules"))))))))
