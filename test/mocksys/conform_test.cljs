(ns mocksys.conform-test
  "TDD for the conform JWT-aware normalizer (Q5). A raw compact JWT differs every run
   (random jti, clock-moved iat/exp, and a signature over all of it), so a body diff
   must compare DECODED claims minus the volatile ones — not the raw string, and not a
   blanket --ignore that would mask real claim drift (aud/sub/scp)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [mocksys.conform :as conform]))

(defn- b64url [s]
  (-> (.toString (js/Buffer.from s "utf8") "base64")
      (str/replace "+" "-") (str/replace "/" "_") (str/replace #"=+$" "")))

(defn- jwt [claims]
  ;; header.payload.signature — only the payload matters to the normalizer
  (str "aaa." (b64url (js/JSON.stringify (clj->js claims))) ".sig"))

(defn- body [m] (js/JSON.stringify (clj->js m)))

(deftest volatile-only-jwt-differences-do-not-diff
  (testing "two tokens with the same claims but different jti/iat/exp/signature agree"
    (let [twin {:status 200 :body (body {"access_token" (jwt {"sub" "alice" "cid" "app" "scp" ["openid"]
                                                              "jti" "AT.aaa" "iat" 1700000000 "exp" 1700003600})})}
          live {:status 200 :body (body {"access_token" (jwt {"sub" "alice" "cid" "app" "scp" ["openid"]
                                                              "jti" "AT.zzz" "iat" 1700009999 "exp" 1700013599})})}]
      (is (nil? (conform/diff-one twin live {}))))))

(deftest real-claim-drift-still-diffs
  (testing "a different meaningful claim (sub) is NOT masked — it diffs"
    (let [twin {:status 200 :body (body {"access_token" (jwt {"sub" "alice" "jti" "A" "iat" 1 "exp" 2})})}
          live {:status 200 :body (body {"access_token" (jwt {"sub" "bob"   "jti" "B" "iat" 9 "exp" 9})})}]
      (is (some? (conform/diff-one twin live {})))))
  (testing "a different scp array still diffs"
    (let [twin {:status 200 :body (body {"access_token" (jwt {"sub" "a" "scp" ["openid" "email"] "jti" "A" "iat" 1 "exp" 2})})}
          live {:status 200 :body (body {"access_token" (jwt {"sub" "a" "scp" ["openid"]         "jti" "B" "iat" 9 "exp" 9})})}]
      (is (some? (conform/diff-one twin live {}))))))

(deftest non-jwt-bodies-unaffected
  (testing "an ordinary JSON body still diffs by value as before"
    (let [twin {:status 200 :body (body {"login" "octocat"})}
          live {:status 200 :body (body {"login" "hubot"})}]
      (is (some? (conform/diff-one twin live {}))))
    (let [twin {:status 200 :body (body {"login" "octocat"})}
          live {:status 200 :body (body {"login" "octocat"})}]
      (is (nil? (conform/diff-one twin live {}))))))
