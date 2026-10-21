(ns mocksys.kit-flags-test
  "Each kit declares the flags it honors and their types. A flag a kit would
   silently drop is reported instead, a command-line value is parsed by its
   declared type, and the oauth2 kit honors --token-ttl."
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

;; --- declared flag types --------------------------------------------------------

(def ^:private typed-kit
  (kits/parse-kit "
name: typed
flags:
  ttl: { type: number }
  limit: { default: 5, type: number }
  port-ish: 3600
  label: null
  strict: false
  tags: [a, b]
  key: { type: file }
  people: { type: json }
contract:
  genes:
    - oauth2-provider: { token-ttl: '{ttl}' }
" "/home/kits/typed.yaml" "typed"))

(defn- no-files [f] (throw (js/Error. (str "no such file: " f))))

(defn- files [m] (fn [f] (or (get m f) (no-files f))))

(defn- parse-error [kit opts]
  (try (kits/parse-flags kit opts no-files) nil
       (catch :default e (.-message e))))

(deftest a-flag-declares-its-type-or-takes-its-default's
  (is (= {:ttl      {:default nil :type :number}
          :limit    {:default 5 :type :number}
          :port-ish {:default 3600 :type :number}
          :label    {:default nil :type :string}
          :strict   {:default false :type :boolean}
          :tags     {:default ["a" "b"] :type :json}
          :key      {:default nil :type :file}
          :people   {:default nil :type :json}}
         (:flags typed-kit))))

(deftest a-null-default-number-flag-parses-as-a-number
  (testing "the value is a number"
    (is (= {:ttl 60} (kits/parse-flags typed-kit {:ttl "60"} no-files))))
  (testing "so the kit compiles"
    (let [c ((:build typed-kit) "typed/typed" (kits/parse-flags typed-kit {:ttl "60"} no-files))]
      (is (= [] (:errors (contract/validate c))))
      (is (= 60 (get-in c ["genes" 0 "oauth2-provider" "token-ttl"]))))))

(deftest values-parse-by-declared-type
  (testing "a null default with no type is a string"
    (is (= {:label "60"} (kits/parse-flags typed-kit {:label "60"} no-files))))
  (testing "a boolean takes true, false, or no value"
    (is (= {:strict true} (kits/parse-flags typed-kit {:strict true} no-files)))
    (is (= {:strict false} (kits/parse-flags typed-kit {:strict "false"} no-files)))
    (is (= "--strict needs true or false, got maybe" (parse-error typed-kit {:strict "maybe"}))))
  (testing "a number rejects a non-number"
    (is (= "--limit needs a number, got lots" (parse-error typed-kit {:limit "lots"}))))
  (testing "a file flag reads the file's text; a json flag parses it"
    (is (= {:key "PEM" :people [{"id" 1}]}
           (kits/parse-flags typed-kit {:key "k.pem" :people "p.json"}
                             (files {"k.pem" "PEM" "p.json" "[{\"id\": 1}]"})))))
  (testing "an unreadable or invalid file is named"
    (is (= "could not read --key as a file: k.pem" (parse-error typed-kit {:key "k.pem"})))
    (is (= "could not read --people as a JSON file: p.json"
           (try (kits/parse-flags typed-kit {:people "p.json"} (files {"p.json" "not json"})) nil
                (catch :default e (.-message e))))))
  (testing "options that are not kit flags pass through untouched"
    (is (= {} (kits/parse-flags typed-kit {:scenario "a/b" :port "9000"} no-files)))))

(deftest a-non-boolean-flag-needs-a-value
  (testing "a bare --provider no longer becomes the provider `true`"
    (is (= "--provider needs a value" (parse-error (kit "oauth2") {:provider true}))))
  (testing "a bare number or file flag"
    (is (= "--ttl needs a value" (parse-error typed-kit {:ttl true})))
    (is (= "--key needs a value" (parse-error typed-kit {:key true})))))

(deftest the-built-in-kits-declare-their-types
  (testing "okta reads --users as JSON, --signing-key as text, and the rest by type"
    (is (= {:users [{"id" "u9"}] :signing-key "PEM" :rate-limit 5 :rate-window 10 :token-ttl 60
            :issuer "http://x"}
           (kits/parse-flags (kit "okta")
                             {:users "u.json" :signing-key "k.pem" :rate-limit "5" :rate-window "10"
                              :token-ttl "60" :issuer "http://x"}
                             (files {"u.json" "[{\"id\": \"u9\"}]" "k.pem" "PEM"})))))
  (testing "oauth2 reads --users as JSON and --token-ttl as a number"
    (is (= {:users [{"id" 7}] :token-ttl 60 :provider "codeberg"}
           (kits/parse-flags (kit "oauth2") {:users "u.json" :token-ttl "60" :provider "codeberg"}
                             (files {"u.json" "[{\"id\": 7}]"}))))))

(deftest a-malformed-flag-declaration-is-refused
  (let [kit-with (fn [flags] (str "name: x\nflags: " flags "\ncontract: { operations: [] }\n"))]
    (is (thrown-with-msg? js/Error #"flag 'x'.*type 'integer'"
                          (kits/parse-kit (kit-with "{ x: { type: integer } }") "x.yaml" "x")))
    (is (thrown-with-msg? js/Error #"flag 'x'.*typo"
                          (kits/parse-kit (kit-with "{ x: { default: 1, typo: 2 } }") "x.yaml" "x")))
    (is (thrown-with-msg? js/Error #"flag 'x'.*default"
                          (kits/parse-kit (kit-with "{ x: { default: abc, type: number } }") "x.yaml" "x")))))
