(ns mocksys.tls-cli-test
  "A contract's `tls` in a throwaway store: compile records it in imposter.json
   without key material, validate names a bad value, the address a consumer gets is
   https://, and `run` posts a certificate a client trusting the CA accepts."
  (:require [clojure.test :refer [deftest testing is async]]
            [clojure.string :as str]
            [promesa.core :as p]
            ["node:fs" :as fs]
            ["node:https" :as https]
            ["node:os" :as os]
            ["node:path" :as path]
            [mocksys.store :as store]
            [mocksys.lifecycle :as lifecycle]
            [mocksys.runtime :as runtime]
            [mocksys.tls :as tls]
            [mocksys.grpc-fixture :as fx]))

(defn- contract-yaml [tls-line]
  (str "service: t\nscenario: t/secure\n" tls-line "\n"
       "operations:\n"
       "  - id: hello\n"
       "    request: { method: GET, path: /hello }\n"
       "    responses:\n"
       "      - status: 200\n"
       "        examples: { ok: { select: true, body: { hi: there } } }\n"))

(defn- home-with [files]
  (let [home (fs/mkdtempSync (path/join (os/tmpdir) "mocksys-home-"))]
    (doseq [[f text] files]
      (fs/mkdirSync (path/dirname (path/join home f)) #js {:recursive true})
      (fs/writeFileSync (path/join home f) text))
    home))

(defn- in-store [home f]
  (with-redefs [store/root home store/services-dir (path/join home "services")]
    (f)))

(deftest compile-records-tls-without-key-material
  (let [home (home-with {"t/secure/contract.yaml" (contract-yaml "tls: true")})]
    (in-store home
      (fn []
        (lifecycle/compile! "t/secure")
        (let [text (fs/readFileSync (path/join home "t/secure/imposter.json") "utf8")]
          (is (true? (get (js->clj (js/JSON.parse text)) "tls")))
          (is (not (str/includes? text "PRIVATE KEY")))
          (is (not (fs/existsSync (tls/ca-dir))) "compiling issues nothing"))))))

(deftest compile-records-a-declared-pair-relative-to-the-scenario
  (let [home (home-with {"t/secure/contract.yaml" (contract-yaml "tls: { cert: ./certs/cert.pem, key: certs/key.pem }")
                         "t/secure/certs/cert.pem" "C"
                         "t/secure/certs/key.pem" "K"})]
    (in-store home
      (fn []
        (is (= {"cert" "certs/cert.pem" "key" "certs/key.pem"} (get (lifecycle/compile! "t/secure") "tls")))))))

(deftest validate-names-a-bad-tls
  (let [home (home-with {"t/secure/contract.yaml" (contract-yaml "tls: { cert: ../../elsewhere.pem, key: key.pem }")
                         "t/secure/key.pem" "K"
                         "elsewhere.pem" "C"})]
    (in-store home
      (fn []
        (is (= [(str "tls: ../../elsewhere.pem is outside the scenario directory " (path/join home "t/secure"))]
               (:errors (lifecycle/validate "t/secure"))))))))

(deftest the-address-of-a-tls-mock
  (let [home (home-with {"t/secure/contract.yaml" (contract-yaml "tls: true")
                         "t/kv/contract.yaml" "service: t\nscenario: t/kv\nprotocol: grpc\nproto: ./kv.proto\ntls: true\noperations: []\n"})]
    (in-store home
      (fn []
        (is (= "https://localhost:9000" (lifecycle/address "t/secure" 9000)))
        (is (= "localhost:9000" (lifecycle/address "t/kv" 9000)) "a gRPC target has no scheme, TLS or not")))))

(defn- get-https [port ca]
  (js/Promise.
   (fn [resolve]
     (let [rq (.get https #js {:host "localhost" :port port :path "/hello" :ca ca}
                    (fn [^js res]
                      (let [chunks #js []]
                        (.on res "data" #(.push chunks %))
                        (.on res "end" #(resolve {:status (.-statusCode res)
                                                  :body (js->clj (js/JSON.parse (.toString (js/Buffer.concat chunks) "utf8")))})))))]
       (.on rq "error" (fn [^js e] (resolve {:error (or (.-code e) (.-message e))})))))))

(defn- with-store-daemon
  "Run `f` with the store at `home` and a fresh in-process daemon on
   MOCKSYS_ADMIN_PORT, where the CLI looks for it. Report a rejection as a
   failure, then stop the daemon, restore the store and the variable, and call
   `done`."
  [done home f]
  (let [env    (.-env js/process)
        prev   (.-MOCKSYS_ADMIN_PORT env)
        saved  [store/root store/services-dir]
        daemon (atom nil)]
    (-> (p/let [admin (fx/free-port)
                _     (set! (.-MOCKSYS_ADMIN_PORT env) (str admin))
                _     (set! store/root home)
                _     (set! store/services-dir (path/join home "services"))
                d     (runtime/start! {:host "127.0.0.1" :port admin})
                _     (reset! daemon d)]
          (f))
        (p/catch (fn [e] (is (nil? e) (str "unexpected rejection: " (ex-message e)))))
        (p/finally (fn []
                     (p/do (when-let [d @daemon] ((:stop! d)))
                           (set! store/root (first saved))
                           (set! store/services-dir (second saved))
                           (if (nil? prev) (js-delete env "MOCKSYS_ADMIN_PORT") (set! (.-MOCKSYS_ADMIN_PORT env) prev))
                           (done)))))))

(deftest run-serves-a-certificate-from-the-ca
  (async done
    (with-store-daemon done (home-with {"t/secure/contract.yaml" (contract-yaml "tls: true")})
      (fn []
        (p/let [port (fx/free-port)
                _    (lifecycle/ensure-running! "t/secure" (str port))
                res  (get-https port (fs/readFileSync (tls/ca-file) "utf8"))]
          (is (= {:status 200 :body {"hi" "there"}} res)))))))
