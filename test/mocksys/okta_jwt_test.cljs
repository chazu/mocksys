(ns mocksys.okta-jwt-test
  "Acceptance (§3.6): the okta twin is a real OIDC issuer. It publishes a JWKS and a
   discovery doc, and its token endpoint mints RS256 access/id JWTs that verify
   against that JWKS with the correct org-server claim shape."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [mocksys.harness :as h]
            [mocksys.kits :as kits]
            [mocksys.contract :as contract]
            ["crypto" :as crypto]))

(def ^:private okta (kits/okta "okta/twin" nil {}))
(defn- op [id] (some #(when (= id (get % "id")) %) (get okta "operations")))
(defn- resolved-colls []
  (let [seed (get okta "seed")]
    (into {} (for [[n spec] (get-in okta ["state" "collections"])]
               [n {"idField" (get spec "idField") "seed" (get seed (get spec "seed"))}]))))

(def ^:private jwks (get-in (op "jwks") ["responses" 0 "examples" "jwks" "body"]))
(defn- jwk->pub [jwk] (.createPublicKey crypto #js {:key (clj->js jwk) :format "jwk"}))

(defn- b64url->buf [s] (js/Buffer.from (-> s (str/replace "-" "+") (str/replace "_" "/")) "base64"))
(defn- claims-of [jwt] (js->clj (js/JSON.parse (.toString (b64url->buf (second (str/split jwt #"\.")))))))
(defn- rs256-valid? [jwt pub]
  (let [[h p s] (str/split jwt #"\.")
        v (.createVerify crypto "RSA-SHA256")]
    (.update v (str h "." p))
    (.verify v pub (b64url->buf s))))
(defn- code-of [r] (second (re-find #"code=([^&]+)" (get-in r [:headers "Location"]))))

(deftest okta-publishes-jwks-and-discovery
  (testing "JWKS exposes an RS256 signing key"
    (let [k (first (get jwks "keys"))]
      (is (= "RSA" (get k "kty")))
      (is (= "RS256" (get k "alg")))
      (is (= "sig" (get k "use")))
      (is (string? (get k "kid")))
      (is (string? (get k "n")))))
  (testing "discovery advertises the endpoints (host-templated) and RS256"
    (let [cfg (get-in (op "openid-config") ["responses" 0 "examples" "cfg" "body"])]
      (is (str/includes? (get cfg "token_endpoint") "/oauth2/v1/token"))
      (is (str/includes? (get cfg "jwks_uri") "/oauth2/v1/keys"))
      (is (str/includes? (get cfg "issuer") "{{request.headers.host}}"))
      (is (= ["RS256"] (get cfg "id_token_signing_alg_values_supported"))))))

(deftest okta-token-mints-verifiable-jwts
  (let [colls (resolved-colls)
        state (h/fresh-state)
        pub   (jwk->pub (first (get jwks "keys")))
        a (h/run (op "authorize")
                 {:path "/oauth2/v1/authorize"
                  :query {:login "alice@example.com" :uid "00u1" :state "s" :redirect_uri "https://cb"}}
                 {:collections colls} state)
        t (h/run (op "access-token")
                 {:path "/oauth2/v1/token"
                  :headers {:host "acme.okta.com"}
                  :body {:code (code-of a) :client_id "app1" :scope "openid email"}}
                 {:collections colls :clock-start 1700000000} state)
        at  (get-in t [:json "access_token"])
        idt (get-in t [:json "id_token"])]
    (testing "the token response is Bearer with both tokens"
      (is (= 200 (:status t)))
      (is (= "Bearer" (get-in t [:json "token_type"])))
      (is (string? at))
      (is (string? idt)))
    (testing "both JWTs verify against the published JWKS"
      (is (rs256-valid? at pub))
      (is (rs256-valid? idt pub)))
    (testing "access-token claims: org-server shape (sub=login, aud=origin, cid, uid, scp array)"
      (let [c (claims-of at)]
        (is (= "alice@example.com" (get c "sub")))
        (is (= "app1" (get c "cid")))
        (is (= "00u1" (get c "uid")))
        (is (= "http://acme.okta.com" (get c "iss")))
        (is (= "http://acme.okta.com" (get c "aud")))
        (is (= ["openid" "email"] (get c "scp")))
        (is (= 1700000000 (get c "iat")))))
    (testing "id-token claims: mirror mapping (sub=uid, aud=client_id) + email"
      (let [c (claims-of idt)]
        (is (= "00u1" (get c "sub")))
        (is (= "app1" (get c "aud")))
        (is (= "alice@example.com" (get c "email")))
        (is (= true (get c "email_verified")))
        (is (= ["pwd"] (get c "amr")))))))

(deftest okta-still-validates-and-lowers
  (testing "the JWT-bearing contract still validates and lowers"
    (is (empty? (:errors (contract/validate okta))))
    (is (seq (get (contract/lower okta) "stubs")))))
