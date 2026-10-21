(ns mocksys.scrub-test
  "`doctor --fix` cleans a contract's headers in place and adds nothing else."
  (:require [clojure.test :refer [deftest testing is]]
            [mocksys.scrub :as scrub]))

(deftest a-genes-only-contract-gains-no-operations-key
  (let [c {"genes" [{"crud-collection" {"coll" "users" "base" "/a"}}]}]
    (is (= c (:contract (scrub/scrub-contract c #{"authorization"} #{"date"}))))))

(deftest an-effect-operation-gains-no-responses-key
  (let [c {"operations" [{"id" "e" "effect" {"respond" {"status" 200}}}]}]
    (is (= c (:contract (scrub/scrub-contract c #{"authorization"} #{"date"}))))))

(deftest operation-headers-are-cleaned
  (let [c {"operations" [{"id" "x"
                          "responses" [{"status" 200
                                        "headers" {"Date" "today" "Authorization" "t" "X-Keep" "1"}}]}]}
        {:keys [contract redacted stripped]} (scrub/scrub-contract c #{"authorization"} #{"date"})]
    (testing "secrets are redacted and volatile headers dropped"
      (is (= {"Authorization" "REDACTED" "X-Keep" "1"}
             (get-in contract ["operations" 0 "responses" 0 "headers"]))))
    (is (= #{"authorization"} redacted))
    (is (= #{"date"} stripped))))
