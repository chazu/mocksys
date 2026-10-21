(ns mocksys.conform-fire-test
  "conform compares what the service answered, so a redirect is a response to diff,
   not a hop to follow. Following it would also fail outright when the Location names
   a host only the client app can reach (an OAuth redirect_uri)."
  (:require [clojure.test :refer [deftest is async]]
            [promesa.core :as p]
            ["node:http" :as http]
            [mocksys.conform :as conform]))

(deftest fire-returns-a-redirect-without-following-it
  (async done
    (let [srv (.createServer http (fn [_ res]
                                    (.writeHead res 302 #js {"Location" "http://app.invalid/cb?code=c1"})
                                    (.end res)))]
      (.listen srv 0 "127.0.0.1"
               (fn []
                 (let [base (str "http://127.0.0.1:" (.-port (.address srv)))]
                   (-> (p/let [r (conform/fire base {"method" "GET" "path" "/authorize"})]
                         (is (= 302 (:status r)))
                         (is (= "http://app.invalid/cb?code=c1" (get-in r [:headers "location"]))))
                       (p/catch (fn [e] (is (nil? e) (str "fire threw: " (.-message e)))))
                       (p/finally (fn [] (.close srv) (done))))))))))
