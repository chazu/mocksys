(ns mocksys.sign-test
  "TDD for the read-phase `sign` verb: build a compact RS256 JWT into a variable that
   a later `respond` returns. Claims are TYPED — string (interp/dotted), {now}→number,
   {split}→array, {baseUrl}→origin, literal→itself. We verify the signature against
   the public key with node crypto and assert the decoded org-server claim shape."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [mocksys.harness :as h]
            [mocksys.inject :as inject]
            ["crypto" :as crypto]))

(def ^:private kp (.generateKeyPairSync crypto "rsa" #js {:modulusLength 2048}))
(def ^:private priv-pem (.export (.-privateKey kp) #js {:type "pkcs8" :format "pem"}))
(def ^:private pub-key (.-publicKey kp))

(defn- b64url->buf [s]
  (js/Buffer.from (-> s (str/replace "-" "+") (str/replace "_" "/")) "base64"))

(defn- decode-claims [jwt]
  (js->clj (js/JSON.parse (.toString (b64url->buf (second (str/split jwt #"\.")))))))

(defn- decode-header [jwt]
  (js->clj (js/JSON.parse (.toString (b64url->buf (first (str/split jwt #"\.")))))))

(defn- rs256-valid? [jwt pub]
  (let [[h p s] (str/split jwt #"\.")
        v (.createVerify crypto "RSA-SHA256")]
    (.update v (str h "." p))
    (.verify v pub (b64url->buf s))))

(def ^:private sign-op
  {"id" "token"
   "request" {"method" "POST" "path" "/oauth2/v1/token"}
   "effect" {"bind" {"login" {"query" "login"} "client_id" {"query" "cid"}
                     "scope" {"query" "scope"} "uid" {"query" "uid"}}
             "mint" {"jti" {"prefix" "AT."}}
             "sign" {"as" "access_token" "key" priv-pem
                     "header" {"alg" "RS256" "kid" "test-kid" "typ" "JWT"}
                     "claims" {"ver" 1
                               "jti" "{jti}"
                               "iss" {"baseUrl" true}
                               "aud" {"baseUrl" true}
                               "sub" "{login}"
                               "cid" "{client_id}"
                               "uid" "{uid}"
                               "scp" {"split" "{scope}" "on" " "}
                               "iat" {"now" true}
                               "exp" {"now" {"add" 3600}}
                               "amr" ["pwd"]}}
             "respond" {"status" 200 "json" {"access_token" "{access_token}"}}}})

(deftest sign-forces-the-clock
  (testing "an op that signs is treated as clock-using (else iat/exp date to 1970)"
    (is (true? (inject/contract-uses-clock? {"operations" [sign-op]})))))

(deftest sign-produces-a-verifiable-rs256-jwt
  (let [r   (h/run sign-op
                   {:path "/oauth2/v1/token"
                    :query {:login "alice@x" :cid "app1" :scope "openid email" :uid "00u1"}
                    :headers {:host "acme.okta.com"}}
                   {:clock-start 1700000000})
        jwt (get-in r [:json "access_token"])
        hdr (decode-header jwt)
        cl  (decode-claims jwt)]
    (testing "the JWT is a well-formed, signature-valid RS256 token"
      (is (= 3 (count (str/split jwt #"\."))))
      (is (= "RS256" (get hdr "alg")))
      (is (= "test-kid" (get hdr "kid")))
      (is (rs256-valid? jwt pub-key)))
    (testing "org-server access-token claim shape (sub=login, aud=origin, cid=client_id)"
      (is (= "alice@x" (get cl "sub")))
      (is (= "app1" (get cl "cid")))
      (is (= "00u1" (get cl "uid")))
      (is (= "http://acme.okta.com" (get cl "iss")))
      (is (= "http://acme.okta.com" (get cl "aud"))))
    (testing "typed claims: scp is an array, ver a number, amr a literal array"
      (is (= ["openid" "email"] (get cl "scp")))
      (is (= 1 (get cl "ver")))
      (is (= ["pwd"] (get cl "amr"))))
    (testing "iat/exp read the virtual clock as numbers"
      (is (= 1700000000 (get cl "iat")))
      (is (= 1700003600 (get cl "exp"))))))
