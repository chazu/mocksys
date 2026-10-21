(ns mocksys.assert-test
  "What `assert` reports on a miss, with and without requests dropped from the log."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [mocksys.cli.serve :as serve]))

(deftest a-miss-counts-what-was-recorded
  (is (= "✗ did NOT see GET /x  (2 request(s) recorded)"
         (serve/assert-miss "demo/hello" "GET /x" 2 0))))

(deftest a-miss-says-when-dropped-requests-could-hold-the-call
  (let [out (serve/assert-miss "demo/hello" "GET /x" 3 7)]
    (is (str/starts-with? out "✗ did NOT see GET /x  (3 request(s) recorded)"))
    (is (str/includes? out "7 older request(s) were dropped"))
    (is (str/includes? out "MOCKSYS_MAX_RECORDED_REQUESTS"))
    (is (str/includes? out "mocksys requests demo/hello --clear") "and how to assert over a fresh log")))
