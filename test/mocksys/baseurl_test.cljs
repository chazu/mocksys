(ns mocksys.baseurl-test
  "TDD for the `baseUrl` bind source: `{ baseUrl: true }` yields the twin's own
   origin — `http://{Host}` when a Host header is present, else the embedded issuer.
   Feeds `iss`/`aud` in `sign` and any self-referential URL in a `respond`."
  (:require [clojure.test :refer [deftest testing is]]
            [mocksys.harness :as h]))

(def ^:private origin-op
  {"id" "whoami"
   "request" {"method" "GET" "path" "/origin"}
   "effect" {"bind" {"origin" {"baseUrl" true}}
             "respond" {"status" 200 "json" {"iss" "{origin}"}}}})

(deftest baseurl-derives-from-host
  (testing "with a Host header, origin is http://{Host}"
    (let [r (h/run origin-op {:path "/origin" :headers {:host "acme.okta.com"}} {})]
      (is (= "http://acme.okta.com" (get-in r [:json "iss"]))))))

(deftest baseurl-falls-back-to-issuer
  (testing "without a Host header, origin is the embedded issuer"
    (let [r (h/run origin-op {:path "/origin"} {:issuer "https://fallback.example"})]
      (is (= "https://fallback.example" (get-in r [:json "iss"]))))))
