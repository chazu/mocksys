(ns mocksys.cli-kit-test
  "`mocksys kit` against a throwaway store: a kit whose contract does not validate
   writes nothing, and a valid user kit's typed flags reach a compiled imposter."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [mocksys.cli.util :as util]
            [mocksys.cli.twins :as twins]
            [mocksys.store :as store]))

(defn- run-kit
  "Run `kit` with `args` in a fresh store holding `kit-files` ({file text}).
   Returns {:home :died :out}; :died is the message `die` would print."
  [kit-files pos opts]
  (let [home (fs/mkdtempSync (path/join (os/tmpdir) "mocksys-home-"))
        kdir (path/join home "kits")]
    (fs/mkdirSync kdir)
    (doseq [[f text] kit-files] (fs/writeFileSync (path/join kdir f) text))
    (with-redefs [util/die          (fn [msg] (throw (ex-info msg {:die true})))
                  store/root         home
                  store/kits-dir     kdir
                  store/services-dir (path/join home "services")]
      (let [out (atom [])
            died (binding [*print-fn* #(swap! out conj %)]
                   (try (twins/cmd-kit {:pos pos :opts opts}) nil
                        (catch :default e
                          (if (:die (ex-data e)) (.-message e) (throw e)))))]
        {:home home :died died :out (str/join @out)}))))

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
