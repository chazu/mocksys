(ns mocksys.tls-daemon-test
  "Imposters posted with `tls` on the real daemon, called by clients that trust
   only the mocksys CA: HTTPS on both loopback addresses, gRPC over TLS, the
   virtual clock over TLS, plain clients refused, and no key material in what the
   admin API shows."
  (:require [clojure.test :refer [deftest testing is async]]
            [clojure.string :as str]
            [promesa.core :as p]
            ["node:fs" :as fs]
            ["node:https" :as https]
            ["node:http" :as http]
            ["node:os" :as os]
            ["node:path" :as path]
            ["@grpc/grpc-js" :as grpc]
            ["@grpc/proto-loader" :as loader]
            [mocksys.proto :as proto]
            [mocksys.store :as store]
            [mocksys.tls :as tls]
            [mocksys.runtime.grpc :as rgrpc]
            [mocksys.conform :as conform]
            [mocksys.grpc-fixture :as fx]))

(defn- fresh-ca
  "A CA in a throwaway store: {:ca (ensure-ca!) :creds (a mock certificate)}."
  []
  (let [home (fs/mkdtempSync (path/join (os/tmpdir) "mocksys-tls-"))]
    (with-redefs [store/root home]
      (let [ca (tls/ensure-ca!)]
        {:ca ca :creds (tls/issue ca)}))))

(defn- get-https
  "GET `path` from `host`:`port` trusting `ca` (nil: only the system's roots);
   resolves {:status :body} or {:error code}."
  [host port path ca]
  (js/Promise.
   (fn [resolve]
     (let [rq (.get https (clj->js (cond-> {:host host :port port :path path :servername "localhost"} ca (assoc :ca ca)))
                    (fn [^js res]
                      (let [chunks #js []]
                        (.on res "data" #(.push chunks %))
                        (.on res "end" #(resolve {:status (.-statusCode res)
                                                  :body (.toString (js/Buffer.concat chunks) "utf8")})))))]
       (.on rq "error" (fn [^js e] (resolve {:error (or (.-code e) (.-message e))})))))))

(defn- get-http [port path]
  (js/Promise.
   (fn [resolve]
     (let [rq (.get http #js {:host "127.0.0.1" :port port :path path}
                    (fn [^js res] (.resume res) (resolve {:status (.-statusCode res)})))]
       (.on rq "error" (fn [^js e] (resolve {:error (or (.-code e) (.-message e))})))))))

(def ^:private hello-stub
  {"predicates" [{"equals" {"path" "/hello"}}] "responses" [{"is" {"statusCode" 200 "body" "hi"}}]})

(deftest an-http-imposter-with-tls-serves-https
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [{:keys [ca creds]} (fresh-ca)
                port  (fx/free-port)
                made  (fx/admin-call (str admin "/imposters") "POST"
                                     {"protocol" "http" "port" port "name" "t/secure" "stubs" [hello-stub] "tls" creds})
                v4    (get-https "127.0.0.1" port "/hello" (:cert ca))
                v6    (get-https "::1" port "/hello" (:cert ca))
                named (get-https "localhost" port "/hello" (:cert ca))
                untrusting (get-https "127.0.0.1" port "/hello" nil)
                plain (get-http port "/hello")
                shown (fx/admin-call (str admin "/imposters/" port) "GET" nil)
                again (fx/admin-call (str admin "/imposters/" port "?replayable=true") "GET" nil)]
          (is (= 201 (:status made)) (pr-str (:json made)))
          (testing "a client that trusts the mocksys CA gets the stub's answer"
            (is (= {:status 200 :body "hi"} v4))
            (is (= {:status 200 :body "hi"} named))
            (is (or (= {:status 200 :body "hi"} v6) (= "EADDRNOTAVAIL" (:error v6)))
                "::1, where the host has it"))
          (testing "a client that does not trust it refuses the certificate"
            (is (= "UNABLE_TO_VERIFY_LEAF_SIGNATURE" (:error untrusting))))
          (testing "plain HTTP gets no answer"
            (is (some? (:error plain))))
          (testing "the admin API says tls without echoing the key"
            (is (true? (get-in shown [:json "tls"])))
            (is (true? (get-in again [:json "tls"])))
            (is (not (str/includes? (js/JSON.stringify (clj->js (:json again))) "PRIVATE KEY")))))))))

(deftest the-cli-reaches-its-own-tls-mock
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [{:keys [creds]} (fresh-ca)
                port  (fx/free-port)
                _     (fx/admin-call (str admin "/imposters") "POST"
                                     {"protocol" "http" "port" port "tls" creds
                                      "stubs" [{"predicates" [{"equals" {"method" "POST" "path" "/echo"}}]
                                                "responses" [{"is" {"statusCode" 201 "headers" {"X-Seen" "${b}"}
                                                                    "body" "got ${b}"}
                                                              "behaviors" [{"copy" {"from" "body" "into" "${b}"
                                                                                    "using" {"method" "regex" "selector" ".+"}}}]}]}
                                               {"predicates" [{"equals" {"path" "/away"}}]
                                                "responses" [{"is" {"statusCode" 302 "headers" {"Location" "/elsewhere"}}}]}]})
                base  (str "https://localhost:" port)
                echo  (conform/fire base {"method" "POST" "path" "/echo" "body" "ping"} tls/fetch-own)
                away  (conform/fire base {"path" "/away"} tls/fetch-own)
                escape (p/catch (conform/fire base {"path" "@evil.invalid/x"} tls/fetch-own) ex-message)
                plain  (p/catch (conform/fire (str "http://localhost:" port) {"path" "@evil.invalid/x"} tls/fetch-own)
                                ex-message)]
          (testing "conform's twin requests go over https"
            (is (= 201 (:status echo)))
            (is (= "got ping" (:body echo)))
            (is (= "ping" (get-in echo [:headers "x-seen"]))))
          (testing "without following a redirect"
            (is (= 302 (:status away)))
            (is (= "/elsewhere" (get-in away [:headers "location"]))))
          (testing "and only to this machine, so a corpus path cannot send it elsewhere unverified"
            (is (= (str "refusing to call https://localhost:" port "@evil.invalid/x: evil.invalid is not this machine")
                   escape))
            (is (= (str "refusing to call http://localhost:" port "@evil.invalid/x: evil.invalid is not this machine")
                   plain))))))))

(deftest a-bad-tls-field-is-refused
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [{:keys [creds]} (fresh-ca)
                other (:creds (fresh-ca))
                port  (fx/free-port)
                post  #(fx/admin-call (str admin "/imposters") "POST" {"protocol" "http" "port" port "stubs" [] "tls" %})
                flag  (post true)
                text  (post {"cert" "not a certificate" "key" (get creds "key")})
                pair  (post {"cert" (get creds "cert") "key" (get other "key")})
                extra (post (assoc creds "ca" "x"))]
          (doseq [r [flag text pair extra]]
            (is (= 400 (:status r)))
            (is (= "bad data" (get-in r [:json "errors" 0 "code"]))))
          (is (= "tls must be {cert, key}, PEM text; mocksys run issues them" (get-in flag [:json "errors" 0 "message"])))
          (is (str/starts-with? (get-in text [:json "errors" 0 "message"]) "tls: "))
          (is (str/starts-with? (get-in pair [:json "errors" 0 "message"]) "tls: "))
          (is (= "tls must be {cert, key}, PEM text; mocksys run issues them" (get-in extra [:json "errors" 0 "message"]))))))))

(defn- kv-client [dir port creds]
  (let [def  (.loadSync loader (path/join dir "kv.proto") #js {:keepCase true :longs js/String :enums js/String
                                                               :defaults true :includeDirs #js [dir]})
        Ctor (.. (.loadPackageDefinition grpc def) -kv -Store)]
    (Ctor. (str "localhost:" port) creds)))

(defn- get-key [^js c k]
  (js/Promise.
   (fn [resolve]
     (.Get c #js {:key k} #js {:deadline (+ (js/Date.now) 5000)}
           (fn [err reply] (resolve (if err {:code (.-code err)} {:ok (js->clj reply)})))))))

(deftest a-grpc-imposter-with-tls-serves-grpc-over-tls
  (async done
    (fx/with-daemon done
      (fn [admin]
        (p/let [{:keys [ca creds]} (fresh-ca)
                dir   (fx/kv-dir)
                port  (fx/free-port)
                made  (fx/admin-call (str admin "/imposters") "POST"
                                     {"protocol" "grpc" "port" port "name" "t/kv" "tls" creds
                                      "descriptor" (:descriptor (proto/read-proto (path/join dir "kv.proto")))
                                      "stubs" [{"predicates" [{"equals" {"path" "/kv.Store/Get"}}]
                                                "responses" [{"is" {"statusCode" 200 "body" {"key" "a" "value" "apple"}}}]}
                                               {"predicates" [{"equals" {"path" "/_mocksys/clock"}}]
                                                "responses" [{"is" {"statusCode" 200 "body" {"now" 42}}}]}]})
                trusting (kv-client dir port (.createSsl (.-credentials grpc) (js/Buffer.from (:cert ca))))
                plain    (kv-client dir port (.createInsecure (.-credentials grpc)))
                warned   (atom [])
                on-warn  (fn [^js w] (swap! warned conj (.-code w)))]
          (.on js/process "warning" on-warn)
          (-> (p/let [ok    (get-key trusting "a")
                      nope  (get-key plain "a")
                      clock (rgrpc/clock-call port {} true)]
                (is (= 201 (:status made)) (pr-str (:json made)))
                (testing "a client with the CA as its root gets the reply"
                  (is (= "apple" (get-in ok [:ok "value"]))))
                (testing "an insecure client cannot connect"
                  (is (= (fx/status "UNAVAILABLE") (:code nope))))
                (testing "mocksys clock reaches a twin's clock over TLS"
                  (is (= {"now" 42} clock))
                  (is (not-any? #{"DEP0123"} @warned) "naming localhost to TLS, not an IP address")))
              (p/finally (fn []
                           (.off js/process "warning" on-warn)
                           (.close trusting)
                           (.close plain)))))))))
