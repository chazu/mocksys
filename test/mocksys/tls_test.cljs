(ns mocksys.tls-test
  "The local CA under $MOCKSYS_HOME/tls and the certificates it issues: created once
   with private file modes and kept out of git, reused, replaced near expiry; a
   mock's certificate verifies against it for localhost, 127.0.0.1 and ::1; and a
   contract's `tls` declaration, including a bring-your-own pair."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [mocksys.store :as store]
            [mocksys.tls :as tls]))

(defn- fresh-home [] (fs/mkdtempSync (path/join (os/tmpdir) "mocksys-tls-")))

(defn- in-store [home f]
  (with-redefs [store/root home] (f)))

(defn- mode [f] (bit-and (.-mode (fs/statSync f)) 8r777))

(defn- thrown [f] (try (f) nil (catch :default e (ex-message e))))

(def ^:private day (* 24 3600 1000))

(deftest the-ca-is-created-once-with-private-modes
  (let [home (fresh-home)]
    (in-store home
      (fn []
        (let [first-ca (tls/ensure-ca!)
              dir      (path/join home "tls")]
          (is (= (path/join dir "ca.pem") (tls/ca-file)))
          (is (= 8r700 (mode dir)) "the directory is the owner's alone")
          (is (= 8r600 (mode (path/join dir "ca.key"))) "so is the CA's private key")
          (is (str/includes? (fs/readFileSync (path/join dir "ca.key") "utf8") "PRIVATE KEY"))
          (let [ca (crypto/X509Certificate. (fs/readFileSync (tls/ca-file)))]
            (is (.-ca ca) "ca.pem is a CA certificate")
            (is (> (js/Date.parse (.-validTo ca)) (+ (js/Date.now) (* 9 365 day))) "that lasts about ten years"))
          (testing "a second use reads the same CA"
            (is (= (:cert first-ca) (:cert (tls/ensure-ca!)))))
          (testing "a loosened mode is tightened again"
            (fs/chmodSync (path/join dir "ca.key") 8r644)
            (tls/ensure-ca!)
            (is (= 8r600 (mode (path/join dir "ca.key"))))))))))

(deftest git-never-sees-the-ca
  (let [home (fresh-home)]
    (in-store home #(tls/ensure-ca!))
    (cp/execFileSync "git" #js ["init" "-q"] #js {:cwd home})
    (let [status (.toString (cp/execFileSync "git" #js ["status" "--porcelain" "--untracked-files=all"] #js {:cwd home}))]
      (is (= "" (str/trim status)) "publish adds the store with git add -A, which must skip tls/"))))

(defn- backups
  "The ca.pem of each CA moved aside in `home`, oldest first."
  [home]
  (->> (fs/readdirSync home) (filter #(str/starts-with? % "tls.bak-")) sort
       (mapv #(let [f (path/join home % "ca.pem")] (when (fs/existsSync f) (fs/readFileSync f "utf8"))))))

(deftest an-expiring-or-missing-ca-is-replaced-and-kept
  (let [home (fresh-home)]
    (in-store home
      (fn []
        (let [old (tls/ensure-ca!)]
          (testing "near the end of its life"
            (let [later (tls/ensure-ca! {:now (+ (js/Date.now) (* 3640 day))})]
              (is (not= (:cert old) (:cert later)))
              (is (str/includes? (str (:replaced later)) "expires"))
              (is (= [(:cert old)] (backups home)) "the old CA is moved aside, not deleted")))
          (testing "with its key gone"
            (let [before (fs/readFileSync (tls/ca-file) "utf8")]
              (fs/unlinkSync (path/join home "tls" "ca.key"))
              (let [again (tls/ensure-ca!)]
                (is (= "ca.key is missing" (:replaced again)))
                (is (fs/existsSync (path/join home "tls" "ca.key")))
                (is (= before (last (backups home))) "what was left of it is kept too")))))))))

(defn- ec-key []
  (-> (crypto/generateKeyPairSync "ec" #js {:namedCurve "P-256"}) .-privateKey
      (.export #js {:type "pkcs8" :format "pem"})))

(defn- encrypted-key []
  (-> (crypto/generateKeyPairSync "rsa" #js {:modulusLength 2048}) .-privateKey
      (.export #js {:type "pkcs8" :format "pem" :cipher "aes-256-cbc" :passphrase "secret"})))

(deftest an-unusable-ca-is-never-deleted
  (doseq [[label file text] [["an EC key" "ca.key" (ec-key)]
                             ["an encrypted key" "ca.key" (encrypted-key)]
                             ["another CA's key" "ca.key" (:key (in-store (fresh-home) tls/ensure-ca!))]
                             ["a garbled certificate" "ca.pem" "-----BEGIN CERTIFICATE-----\nnope\n-----END CERTIFICATE-----\n"]]]
    (testing label
      (let [home (fresh-home)
            dir  (path/join home "tls")]
        (in-store home
          (fn []
            (tls/ensure-ca!)
            (fs/writeFileSync (path/join dir file) text)
            (let [cert-before (fs/readFileSync (path/join dir "ca.pem") "utf8")
                  msg         (thrown tls/ensure-ca!)]
              (is (str/includes? (str msg) (path/join dir file)) "the error names the file")
              (is (str/includes? (str msg) (str "move " dir " aside")) "and the fix")
              (is (= text (fs/readFileSync (path/join dir file) "utf8")) "the hand-placed file is left as it was")
              (is (= cert-before (fs/readFileSync (path/join dir "ca.pem") "utf8")))
              (is (= [] (backups home)) "nothing is replaced"))))))))

(deftest an-unreadable-ca-key-is-never-deleted
  (let [home (fresh-home)
        key  (path/join home "tls" "ca.key")]
    (in-store home
      (fn []
        (tls/ensure-ca!)
        (fs/chmodSync key 0)
        (try
          (let [msg (thrown tls/ensure-ca!)]
            (is (str/includes? (str msg) key))
            (is (str/includes? (str msg) "EACCES"))
            (is (= [] (backups home))))
          (finally (fs/chmodSync key 8r600)))
        (is (str/includes? (fs/readFileSync key "utf8") "PRIVATE KEY") "the key is still there")))))

(deftest a-mock-certificate-verifies-against-the-ca
  (let [home (fresh-home)]
    (in-store home
      (fn []
        (let [ca   (tls/ensure-ca!)
              {:strs [cert key]} (tls/issue ca)
              leaf (crypto/X509Certificate. cert)
              root (crypto/X509Certificate. (:cert ca))]
          (is (.verify leaf (.-publicKey root)) "signed by the CA")
          (is (.checkIssued leaf root))
          (is (not (.-ca leaf)))
          (is (= "localhost" (.checkHost leaf "localhost")))
          (is (= "127.0.0.1" (.checkIP leaf "127.0.0.1")))
          (is (= "::1" (.checkIP leaf "::1")))
          (is (nil? (.checkHost leaf "example.com")))
          (is (<= (js/Date.parse (.-validTo leaf)) (+ (js/Date.now) (* 398 day)))
              "a leaf stays within the 398 days clients accept")
          (is (.checkPrivateKey leaf (crypto/createPrivateKey key)) "the key is the certificate's"))))))

(deftest a-contract-declares-tls
  (let [home (fresh-home)
        dir  (path/join home "t" "secure")]
    (fs/mkdirSync dir #js {:recursive true})
    (fs/writeFileSync (path/join dir "cert.pem") "c")
    (fs/writeFileSync (path/join dir "key.pem") "k")
    (fs/writeFileSync (path/join home "outside.pem") "x")
    (is (nil? (tls/declaration dir nil)))
    (is (nil? (tls/declaration dir false)))
    (is (true? (tls/declaration dir true)))
    (is (= {"cert" "cert.pem" "key" "key.pem"} (tls/declaration dir {"cert" "./cert.pem" "key" "key.pem"}))
        "paths are kept relative to the scenario directory, as modules are")
    (is (= "tls: expected true, or {cert, key} files in the scenario directory"
           (thrown #(tls/declaration dir "yes"))))
    (is (= "tls: expected true, or {cert, key} files in the scenario directory"
           (thrown #(tls/declaration dir {"cert" "cert.pem"}))))
    (is (= (str "tls: ../../outside.pem is outside the scenario directory " dir)
           (thrown #(tls/declaration dir {"cert" "../../outside.pem" "key" "key.pem"}))))
    (is (= "tls: nope.pem: no such file"
           (thrown #(tls/declaration dir {"cert" "cert.pem" "key" "nope.pem"}))))))

(deftest credentials-are-what-the-daemon-serves
  (let [home (fresh-home)
        dir  (path/join home "t" "secure")]
    (fs/mkdirSync dir #js {:recursive true})
    (in-store home
      (fn []
        (testing "true issues a certificate from the CA"
          (let [{:strs [cert key]} (tls/credentials dir true)]
            (is (.verify (crypto/X509Certificate. cert)
                         (.-publicKey (crypto/X509Certificate. (fs/readFileSync (tls/ca-file))))))
            (is (str/includes? key "PRIVATE KEY"))))
        (testing "a declared pair is read from the scenario directory"
          (fs/writeFileSync (path/join dir "cert.pem") "CERT")
          (fs/writeFileSync (path/join dir "key.pem") "KEY")
          (is (= {"cert" "CERT" "key" "KEY"} (tls/credentials dir {"cert" "cert.pem" "key" "key.pem"}))))))))
