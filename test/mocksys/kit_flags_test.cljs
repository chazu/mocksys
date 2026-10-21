(ns mocksys.kit-flags-test
  "Each kit declares the flags it honors. A flag a kit would silently drop is
   reported instead, and the oauth2 kit honors --token-ttl."
  (:require [clojure.test :refer [deftest testing is]]
            [mocksys.kits :as kits]
            [mocksys.contract :as contract]))

(defn- kit [kind] (get kits/builtin-kits kind))

(deftest unsupported-flags-are-reported
  (testing "oauth2 has no CRUD ops to rate-limit"
    (is (= [:rate-limit] (kits/unsupported-flags (kit "oauth2") {:rate-limit "5" :token-ttl "60"}))))
  (testing "okta honors every kit flag"
    (is (empty? (kits/unsupported-flags (kit "okta") {:rate-limit "5" :rate-window "10" :token-ttl "60"
                                                      :issuer "http://x" :signing-key "k.pem" :users "u.json"}))))
  (testing "scenario and port are not kit flags"
    (is (empty? (kits/unsupported-flags (kit "oauth2") {:scenario "oauth/x" :port "9000"})))))

(deftest oauth2-tokens-expire-with-token-ttl
  (let [build (:build (kit "oauth2"))
        c     (contract/expand (build "oauth/github" {:provider "github" :token-ttl 60}))
        tok   (some #(when (= "access-token" (get % "id")) %) (get c "operations"))]
    (testing "the token store carries the ttl"
      (is (= 60 (get-in tok ["effect" "store" "ttl"]))))
    (testing "the token response advertises expires_in"
      (is (= 60 (get-in tok ["effect" "respond" "json" "expires_in"]))))))

(deftest every-built-in-kit-builds-a-valid-contract
  (doseq [[kname {:keys [build]}] kits/builtin-kits]
    (testing kname
      (is (= [] (:errors (contract/validate (build (str kname "/" kname) {}))))))))
