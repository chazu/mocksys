(ns mocksys.grpc-cli-test
  "A grpc scenario in a throwaway store: compile embeds the proto's descriptor,
   validate checks rpcs against the proto, a bundle carries the proto or refuses,
   and the address a consumer gets has no http:// scheme."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [mocksys.store :as store]
            [mocksys.lifecycle :as lifecycle]
            [mocksys.bundle :as bundle]
            [mocksys.grpc-fixture :as fx]))

(def ^:private contract-yaml "
service: t
scenario: t/kv
protocol: grpc
proto: ./kv.proto
operations:
  - id: get
    request: { rpc: kv.Store/Get }
    responses:
      - status: 200
        examples: { ok: { select: true, body: { key: a } } }
")

(defn- home-with
  "A fresh store holding `files` ({path-under-root text})."
  [files]
  (let [home (fs/mkdtempSync (path/join (os/tmpdir) "mocksys-home-"))]
    (doseq [[f text] files]
      (fs/mkdirSync (path/dirname (path/join home f)) #js {:recursive true})
      (fs/writeFileSync (path/join home f) text))
    home))

(def ^:private kv-files
  {"t/kv/contract.yaml" contract-yaml
   "t/kv/kv.proto"      fx/kv-proto
   "t/kv/types.proto"   fx/types-proto})

(defn- in-store [home f]
  (with-redefs [store/root home store/services-dir (path/join home "services")]
    (f)))

(defn- thrown [f] (try (f) nil (catch :default e (ex-message e))))

(deftest compile-embeds-the-descriptor
  (let [home (home-with kv-files)]
    (in-store home
      (fn []
        (let [imp  (lifecycle/compile! "t/kv")
              disk (js->clj (js/JSON.parse (fs/readFileSync (path/join home "t/kv/imposter.json") "utf8")))]
          (is (= "grpc" (get imp "protocol")))
          (is (map? (get-in imp ["descriptor" "nested" "kv" "nested" "Store"])))
          (is (= (get imp "descriptor") (get disk "descriptor")) "imposter.json carries it too"))))))

(deftest compile-fails-naming-a-missing-proto
  (let [home (home-with (dissoc kv-files "t/kv/kv.proto"))]
    (in-store home
      (fn []
        (is (= (str "proto ./kv.proto: cannot read proto " (path/join home "t/kv/kv.proto"))
               (thrown #(lifecycle/compile! "t/kv"))))))))

(deftest validate-checks-rpcs-against-the-proto
  (let [home (home-with (update kv-files "t/kv/contract.yaml" str/replace "kv.Store/Get" "kv.Store/Watch"))]
    (in-store home
      (fn []
        (is (= ["get: rpc kv.Store/Watch is server-streaming; only unary rpcs are served"]
               (:errors (lifecycle/validate "t/kv"))))))))

(deftest a-bundle-carries-the-proto
  (let [home (home-with kv-files)
        out  (path/join home "kv.mock.tgz")]
    (in-store home #(do (lifecycle/compile! "t/kv") (bundle/pack! "t/kv" out)))
    (let [elsewhere (home-with {})]
      (in-store elsewhere
        (fn []
          (is (= ["t/kv"] (bundle/unpack! out)))
          (is (= "grpc" (get (lifecycle/compile! "t/kv") "protocol")) "it recompiles from what it carried"))))))

(deftest a-bundle-refuses-a-proto-it-would-not-carry
  (let [home (home-with (-> kv-files
                            (dissoc "t/kv/types.proto")
                            (assoc "shared/types.proto" fx/types-proto)
                            (update "t/kv/kv.proto" str/replace "import \"types.proto\"" "import \"../../shared/types.proto\"")))]
    (in-store home
      (fn []
        (is (= (str "cannot pack 't/kv': its proto reads " (path/join home "shared/types.proto")
                    ", outside the scenario directory; copy it into " (path/join home "t/kv"))
               (do (lifecycle/compile! "t/kv")
                 (thrown #(bundle/pack! "t/kv" (path/join home "x.tgz"))))))
        (is (not (fs/existsSync (path/join home "x.tgz"))) "nothing is written")))))

(deftest the-address-of-a-grpc-mock-has-no-scheme
  (let [home (home-with (assoc kv-files "web/hello/contract.yaml"
                               "operations: [{id: a, request: {method: GET, path: /a}, responses: [{status: 200}]}]"))]
    (in-store home
      (fn []
        (is (= "localhost:9000" (lifecycle/address "t/kv" 9000)))
        (is (= "http://localhost:9000" (lifecycle/address "web/hello" 9000)))))))