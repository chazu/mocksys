(ns mocksys.tls
  "TLS for mocks, on the CLI side. mocksys keeps one local CA in $MOCKSYS_HOME/tls/
  and issues a mock a fresh certificate for localhost, 127.0.0.1 and ::1 each time
  `run` posts it. The daemon receives the PEM text in the post and reads no key
  files, and imposter.json, which pack and publish copy, only says `tls: true`. A
  contract may instead name its own cert and key files in the scenario directory.

  node-forge builds the certificates from RSA keys that node's crypto generates, so
  issuing works in the bun binary and needs no openssl."
  (:require [clojure.string :as str]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:https" :as https]
            ["node:path" :as path]
            ["node-forge" :as forge]
            [mocksys.store :as store]))

(def ^:private pki (.-pki forge))
(def ^:private day (* 24 3600 1000))
(def ^:private ca-days (* 10 365))
;; Apple's and Chrome's clients refuse a server certificate valid for more than 398 days.
(def ^:private leaf-days 397)
(def ^:private renew-days 30)
(def ^:private ca-name #js [#js {:name "commonName" :value "mocksys local CA"}
                            #js {:name "organizationName" :value "mocksys"}])

(defn ca-dir [] (path/join store/root "tls"))
(defn ca-file [] (path/join (ca-dir) "ca.pem"))
(defn- key-file [] (path/join (ca-dir) "ca.key"))

(defn- keypair []
  (let [pem (-> (crypto/generateKeyPairSync "rsa" #js {:modulusLength 2048})
                .-privateKey
                (.export #js {:type "pkcs8" :format "pem"}))
        k   (.privateKeyFromPem pki pem)]
    {:pem pem :private k :public (.setRsaPublicKey pki (.-n k) (.-e k))}))

(defn- certificate
  "An unsigned certificate for `public`, valid from an hour before `now` (a client
   in a VM or container may lag) until `not-after`."
  [public now not-after]
  (let [^js cert (.createCertificate pki)]
    (set! (.-publicKey cert) public)
    ;; a positive 128-bit serial
    (set! (.-serialNumber cert) (str "01" (.toString (crypto/randomBytes 15) "hex")))
    (set! (.. cert -validity -notBefore) (js/Date. (- now 3600000)))
    (set! (.. cert -validity -notAfter) (js/Date. not-after))
    cert))

(defn- new-ca [now]
  (let [{:keys [pem private public]} (keypair)
        ^js cert (certificate public now (+ now (* ca-days day)))]
    (.setSubject cert ca-name)
    (.setIssuer cert ca-name)
    (.setExtensions cert #js [#js {:name "basicConstraints" :cA true :pathLenConstraint 0 :critical true}
                              #js {:name "keyUsage" :keyCertSign true :cRLSign true :critical true}
                              #js {:name "subjectKeyIdentifier"}])
    (.sign cert private (.create (.. forge -md -sha256)))
    {:cert (.certificateToPem pki cert) :key pem}))

(defn- unusable
  "Throw for a CA file that is there but cannot be used. mocksys never deletes it:
   the user may have put it there, and running mocks hold certificates it issued."
  [file why]
  (throw (js/Error. (str "tls: cannot use " file " (" why "). The local CA needs ca.pem and its "
                         "unencrypted RSA key ca.key, both PEM. Fix the file, or move " (ca-dir)
                         " aside and mocksys makes a new CA, which clients must then trust"))))

(defn- read-ca-file
  "The text of `file` and what `parse` makes of it, or nil when it is missing."
  [file parse]
  (when-let [text (try (fs/readFileSync file "utf8")
                       (catch :default e (when-not (= "ENOENT" (.-code e)) (unusable file (ex-message e)))))]
    [text (try (parse text) (catch :default e (unusable file (ex-message e))))]))

(defn- read-ca
  "The CA on disk, or {:missing file} when ca.pem or ca.key is not there."
  []
  (let [[cert-pem ^js cert] (read-ca-file (ca-file) #(.certificateFromPem pki %))
        [key-pem ^js k]     (read-ca-file (key-file) #(.privateKeyFromPem pki %))]
    (cond
      (nil? cert-pem) {:missing "ca.pem"}
      (nil? key-pem)  {:missing "ca.key"}
      (not (.equals (.. cert -publicKey -n) (.-n k))) (unusable (key-file) "it is not the key of ca.pem")
      :else {:cert cert-pem :key key-pem :forge-cert cert :forge-key k})))

(defn- load-ca
  "{:ca ca} for the CA on disk, or {:problem why} it must be replaced: it is
   missing, or expires within renew-days of `now`. Throws for a CA that is there
   but unusable, since replacing it would lose it."
  [now]
  (let [{:keys [missing ^js forge-cert] :as ca} (read-ca)]
    (if missing
      {:problem (str missing " is missing")}
      (let [ends (.. forge-cert -validity -notAfter)]
        (if (< (.getTime ends) (+ now (* renew-days day)))
          {:problem (str "it expires " (.toISOString ends))}
          {:ca ca})))))

(defn- gone? [e] (= "ENOENT" (.-code e)))

(defn- tighten!
  "Re-tighten the CA's modes. False when the CA has gone meanwhile, moved aside by
   another CLI replacing it."
  []
  (try (fs/chmodSync (ca-dir) 8r700)
       (fs/chmodSync (key-file) 8r600)
       true
       (catch :default e (if (gone? e) false (throw e)))))

(defn- move-aside!
  "Rename ca-dir to tls.bak-<time> beside it, keeping the old CA. Returns the new
   name, or nil when another CLI replacing it too moved it first."
  []
  (let [bak (str (ca-dir) ".bak-" (str/replace (.toISOString (js/Date.)) #"[-:.]" ""))]
    (try (fs/renameSync (ca-dir) bak)
         bak
         (catch :default e (when-not (gone? e) (throw e))))))

(defn- install!
  "Write a new CA into a private directory beside ca-dir, then rename it into
   place, so a crash leaves no half-made CA. When another CLI renamed its own into
   place first, this one is dropped and both go on with that one."
  [now]
  (let [dir (ca-dir)
        tmp (str dir ".new-" (.toString (crypto/randomBytes 4) "hex"))
        {:keys [cert key]} (new-ca now)]
    (fs/mkdirSync store/root #js {:recursive true})
    (fs/mkdirSync tmp #js {:mode 8r700})
    (try
      ;; git honours a nested .gitignore, so neither `publish` nor a project repo
      ;; holding MOCKSYS_HOME commits the CA's key
      (fs/writeFileSync (path/join tmp ".gitignore") "# mocksys's local CA: its key must stay on this machine\n*\n")
      (fs/writeFileSync (path/join tmp "ca.key") key #js {:mode 8r600})
      (fs/writeFileSync (path/join tmp "ca.pem") cert)
      (fs/renameSync tmp dir)
      (catch :default e
        (fs/rmSync tmp #js {:recursive true :force true})
        (when-not (#{"ENOTEMPTY" "EEXIST"} (.-code e)) (throw e))))))

(defn ensure-ca!
  "The local CA, created on first use: {:cert :key} PEM, plus the parsed forms
   `issue` signs with. A CA that is missing a file, or expires within 30 days, is
   moved aside to tls.bak-<time> and replaced; :replaced then says why, and stderr
   says that clients must trust the new one and mocks must be run again. A CA that
   is there but cannot be read or parsed is an error that names the file.

   Each pass rereads what is on disk, so CLIs creating or replacing the CA at once
   all end up with whichever CA was renamed into place last."
  ([] (ensure-ca! {}))
  ([{:keys [now] :or {now (js/Date.now)}}]
   (loop [tries 5 replaced nil]
     (when (zero? tries)
       (throw (js/Error. (str "tls: the local CA in " (ca-dir) " kept changing while mocksys read it; run the command again"))))
     (let [{:keys [ca problem]} (when (fs/existsSync (ca-dir)) (load-ca now))]
       (cond
         (and ca (tighten!)) (cond-> ca replaced (assoc :replaced replaced))
         ca (recur (dec tries) replaced)
         :else
         (do (when-let [bak (and problem (move-aside!))]
               (.write js/process.stderr
                       (str "mocksys: replaced the local CA in " (ca-dir) " (" problem "); the old one is now " bak
                            ". Clients that trusted the old ca.pem must trust the new one, and a mock running"
                            " with a certificate from the old CA keeps it until you `mocksys run` it again\n")))
             (install! now)
             (recur (dec tries) (or problem replaced))))))))

(defn issue
  "A certificate for localhost, 127.0.0.1 and ::1 with a new key, signed by `ca`
   (from ensure-ca!): {\"cert\" \"key\"} as PEM. It ends no later than the CA."
  [{:keys [^js forge-cert forge-key]}]
  (let [now  (js/Date.now)
        {:keys [pem public]} (keypair)
        ^js cert (certificate public now (min (+ now (* leaf-days day))
                                              (.getTime (.. forge-cert -validity -notAfter))))]
    (.setSubject cert #js [#js {:name "commonName" :value "localhost"}])
    (.setIssuer cert (.. forge-cert -subject -attributes))
    (.setExtensions cert #js [#js {:name "basicConstraints" :cA false :critical true}
                              #js {:name "keyUsage" :digitalSignature true :keyEncipherment true :critical true}
                              #js {:name "extKeyUsage" :serverAuth true}
                              #js {:name "subjectAltName"
                                   :altNames #js [#js {:type 2 :value "localhost"}
                                                  #js {:type 7 :ip "127.0.0.1"}
                                                  #js {:type 7 :ip "::1"}]}
                              #js {:name "authorityKeyIdentifier"
                                   :keyIdentifier (.getBytes (.generateSubjectKeyIdentifier forge-cert))}])
    (.sign cert forge-key (.create (.. forge -md -sha256)))
    {"cert" (.certificateToPem pki cert) "key" pem}))

;; --- a contract's `tls` ------------------------------------------------------------

(defn- fail [msg] (throw (js/Error. (str "tls: " msg))))

(defn- inside? [dir file] (str/starts-with? file (str dir path/sep)))

(defn- scenario-file
  "`f` relative to the scenario directory `dir`, which pack and publish carry, so a
   file outside it would be left behind."
  [dir f]
  (when (nil? dir) (fail (str f " needs the scenario directory to resolve against")))
  (let [file (path/resolve dir f)]
    (when-not (inside? (path/resolve dir) file) (fail (str f " is outside the scenario directory " dir)))
    (when-not (fs/existsSync file) (fail (str f ": no such file")))
    (when-not (inside? (fs/realpathSync dir) (fs/realpathSync file))
      (fail (str f " is outside the scenario directory " dir " (it links to " (fs/realpathSync file) ")")))
    (path/relative (path/resolve dir) file)))

(defn declaration
  "A contract's `tls` as imposter.json records it: nil (plain), true (a certificate
   from the local CA), or {\"cert\" \"key\"}, paths relative to the scenario directory
   `dir`. Throws on anything else."
  [dir v]
  (cond
    (or (nil? v) (false? v)) nil
    (true? v)                true
    (and (map? v) (= #{"cert" "key"} (set (keys v))) (every? string? (vals v)))
    (into {} (for [[k f] v] [k (scenario-file dir f)]))
    :else (fail "expected true, or {cert, key} files in the scenario directory")))

(defn credentials
  "What the daemon serves for a `declaration`: {\"cert\" \"key\"} PEM text, issued
   from the local CA for true, read from the scenario directory `dir` for files."
  [dir decl]
  (if (true? decl)
    (issue (ensure-ca!))
    (into {} (for [[k f] decl] [k (fs/readFileSync (path/resolve dir f) "utf8")]))))

;; --- the CLI's own calls to a mock -------------------------------------------------

(defn fetch-own
  "`fetch` for a mock the CLI found through its own daemon. Over https it skips the
   certificate check, since a contract's own certificate may come from a CA mocksys
   does not know. Node's fetch cannot skip it, so that request goes through
   node:https and resolves a Response built from the reply. Never follows a
   redirect, as `fetch` with redirect: manual."
  [url ^js init]
  (if-not (str/starts-with? url "https:")
    (js/fetch url (js/Object.assign #js {} init #js {:redirect "manual"}))
    (js/Promise.
     (fn [resolve reject]
       (let [^js rq (https/request url #js {:method             (or (.-method init) "GET")
                                            :headers            (or (.-headers init) #js {})
                                            :rejectUnauthorized false}
                                   (fn [^js res]
                                     (let [chunks #js []]
                                       (.on res "data" #(.push chunks %))
                                       (.on res "end"
                                            (fn []
                                              (let [buf (js/Buffer.concat chunks)]
                                                (resolve (js/Response.
                                                          (when (pos? (.-length buf)) buf)
                                                          #js {:status  (.-statusCode res)
                                                               :headers (clj->js (partition 2 (array-seq (.-rawHeaders res))))}))))))))]
         (.on rq "error" reject)
         (when (some? (.-body init)) (.write rq (.-body init)))
         (.end rq))))))
