(ns mocksys.disk-kits-test
  "A kit is a YAML file: a contract template plus flags with defaults. A string that
   is exactly \"{flag}\" takes the flag's value with its type. Built-ins ship inlined;
   user kits come from $MOCKSYS_HOME/kits and shadow a built-in of the same name."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [mocksys.harness :as h]
            [mocksys.kits :as kits]
            [mocksys.store :as store]
            [mocksys.contract :as contract]))

(def ^:private template-kit "
name: t
doc: a test kit
flags: { ttl: 3600, base: /api/v1/users, tags: [a, b], strict: true, none: null }
contract:
  summary: '{base}'
  seed: { tags: '{tags}', none: '{none}' }
  state: { clock: { start: '{ttl}' }, strict: '{strict}' }
  genes:
    - crud-collection: { coll: users, base: '{base}', id-prefix: 'u-{base}' }
  operations:
    - id: echo
      request: { method: GET, path: /echo }
      effect: { mint: { token: { prefix: t_ } }, respond: { status: 200, json: { t: '{token}' } } }
")

(def ^:private t (kits/parse-kit template-kit "/home/kits/t.yaml" "t"))

(deftest whole-string-flags-keep-their-type
  (let [c ((:build t) "demo/t" {})]
    (testing "a string flag"
      (is (= "/api/v1/users" (get c "summary")))
      (is (= "/api/v1/users" (get-in c ["genes" 0 "crud-collection" "base"]))))
    (testing "a number, a list, a boolean and a null keep their types"
      (is (= 3600 (get-in c ["state" "clock" "start"])))
      (is (= ["a" "b"] (get-in c ["seed" "tags"])))
      (is (= true (get-in c ["state" "strict"])))
      (is (contains? (get c "seed") "none"))
      (is (nil? (get-in c ["seed" "none"]))))
    (testing "a flag inside a longer string is left alone"
      (is (= "u-{base}" (get-in c ["genes" 0 "crud-collection" "id-prefix"]))))
    (testing "an effect variable that is not a flag is left alone"
      (is (= "{token}" (get-in c ["operations" 0 "effect" "respond" "json" "t"]))))))

(deftest a-given-flag-overrides-its-default
  (let [c ((:build t) "demo/t" {:ttl 60 :base "/people"})]
    (is (= 60 (get-in c ["state" "clock" "start"])))
    (is (= "/people" (get c "summary"))))
  (testing "an unset (nil) flag keeps its default"
    (is (= 3600 (get-in ((:build t) "demo/t" {:ttl nil}) ["state" "clock" "start"])))))

(deftest the-kit-sets-provenance
  (let [c ((:build t) "acme/directory" {})]
    (is (= "acme" (get c "service")))
    (is (= "acme/directory" (get c "scenario")))
    (is (= "kit" (get c "origin")))))

(deftest a-kit-without-a-summary-uses-its-doc
  (let [k (kits/parse-kit "name: d\ndoc: the doc\ncontract: { operations: [] }\n" "x.yaml" "d")]
    (is (= "the doc" (get ((:build k) "d/d" {}) "summary")))))

(deftest a-kit-rejects-flags-it-does-not-declare
  (testing "an undeclared flag is reported"
    (is (= [:bogus] (kits/unsupported-flags t {:bogus "1" :ttl "5"}))))
  (testing "scenario and port belong to `kit` itself"
    (is (empty? (kits/unsupported-flags t {:scenario "x/y" :port "9000"}))))
  (testing "a code kit rejects them too"
    (is (= [:bogus] (kits/unsupported-flags (get kits/builtin-kits "okta") {:bogus true :token-ttl "60"})))))

(deftest a-malformed-kit-is-refused
  (is (thrown-with-msg? js/Error #"contract" (kits/parse-kit "name: x\nflags: {}\n" "x.yaml" "x")))
  (is (thrown-with-msg? js/Error #"flags" (kits/parse-kit "name: x\nflags: [a]\ncontract: {}\n" "x.yaml" "x"))))

;; --- user kits on disk --------------------------------------------------------

(def ^:private user-oauth2 "
name: oauth2
doc: my own oauth2
flags: {}
contract: { operations: [] }
")

(deftest user-kits-join-and-shadow-the-built-ins
  (let [{reg :kits warnings :warnings}
        (kits/with-user-kits [["/home/kits/oauth2.yaml" user-oauth2]
                              ["/home/kits/directory.yaml" "doc: unnamed\ncontract: { operations: [] }\n"]])]
    (testing "a user kit with a built-in's name replaces it, with a warning"
      (is (= "/home/kits/oauth2.yaml" (get-in reg ["oauth2" :source])))
      (is (some #(and (str/includes? % "oauth2") (str/includes? % "shadows")) warnings)))
    (testing "a kit without a name is named after its file"
      (is (= "unnamed" (get-in reg ["directory" :doc]))))
    (testing "built-ins nobody shadows stay"
      (is (= "built-in" (get-in reg ["okta" :source]))))))

(deftest a-broken-user-kit-is-skipped-with-a-warning
  (let [{reg :kits warnings :warnings}
        (kits/with-user-kits [["/home/kits/bad.yaml" "contract: [unclosed"]
                              ["/home/kits/good.yaml" "contract: { operations: [] }\n"]])]
    (is (not (contains? reg "bad")))
    (is (contains? reg "good"))
    (is (some #(str/includes? % "/home/kits/bad.yaml") warnings))))

(deftest kit-files-reads-yaml-files-from-a-directory
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "mocksys-kits-"))]
    (fs/writeFileSync (path/join dir "b.yaml") "name: b\n")
    (fs/writeFileSync (path/join dir "a.yaml") "name: a\n")
    (fs/writeFileSync (path/join dir "notes.txt") "not a kit")
    (is (= [[(path/join dir "a.yaml") "name: a\n"] [(path/join dir "b.yaml") "name: b\n"]]
           (store/kit-files dir)))
    (testing "a missing directory has no kits"
      (is (= [] (store/kit-files (path/join dir "absent")))))))

(deftest two-user-kits-with-one-name-warn-which-wins
  (let [{reg :kits warnings :warnings}
        (kits/with-user-kits [["/home/kits/a.yaml" "name: twin\ndoc: from a\ncontract: { operations: [] }\n"]
                              ["/home/kits/b.yaml" "name: twin\ndoc: from b\ncontract: { operations: [] }\n"]])]
    (is (= "from b" (get-in reg ["twin" :doc])))
    (is (= ["user kit 'twin' is declared by /home/kits/a.yaml and /home/kits/b.yaml; using /home/kits/b.yaml"]
           warnings))))

(deftest an-unreadable-kit-file-is-skipped-with-a-warning
  (let [{reg :kits warnings :warnings}
        (kits/with-user-kits [["/home/kits/gone.yaml" nil "EACCES: permission denied"]
                              ["/home/kits/good.yaml" "contract: { operations: [] }\n"]])]
    (is (contains? reg "good"))
    (is (= ["skipped kit /home/kits/gone.yaml: EACCES: permission denied"] warnings))))

(deftest a-yml-kit-is-named-after-its-file
  (let [{reg :kits} (kits/with-user-kits [["/home/kits/short.yml" "contract: { operations: [] }\n"]])]
    (is (contains? reg "short"))))

(deftest kit-files-skips-what-it-cannot-read
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "mocksys-kits-"))]
    (fs/writeFileSync (path/join dir "a.yml") "name: a\n")
    (fs/mkdirSync (path/join dir "b.yaml"))
    (fs/writeFileSync (path/join dir "c.yaml") "name: c\n")
    (fs/chmodSync (path/join dir "c.yaml") 0)
    (let [files (store/kit-files dir)]
      (testing "a .yml file is a kit file"
        (is (= [(path/join dir "a.yml") "name: a\n"] (first files))))
      (testing "a directory and an unreadable file come back with a reason instead of throwing"
        (is (= [(path/join dir "b.yaml") nil "not a file"] (second files)))
        (is (= [(path/join dir "c.yaml") nil] (take 2 (nth files 2))))
        (is (string? (nth (nth files 2) 2)))))
    (fs/chmodSync (path/join dir "c.yaml") 420)))

;; --- the built-in oauth2 kit is YAML ------------------------------------------

(def ^:private oauth2 (get kits/builtin-kits "oauth2"))

(deftest oauth2-is-a-yaml-kit
  (is (= "built-in" (:source oauth2)))
  (is (= {:provider {:default "github" :type :string}
          :users {:default nil :type :json}
          :token-ttl {:default nil :type :number}}
         (:flags oauth2)))
  (testing "its contract.yaml keeps the genes form"
    (is (seq (get ((:build oauth2) "oauth/github" {}) "genes")))))

(defn- code-of [resp] (second (re-find #"code=([^&]+)" (get-in resp [:headers "Location"]))))

(deftest oauth2-codeberg-handshake-end-to-end
  (let [c     (contract/expand ((:build oauth2) "oauth/codeberg" {:provider "codeberg"}))
        state (h/fresh-state)
        a     (h/run-op c "authorize" {:path "/codeberg/login/oauth/authorize"
                                       :query {:login "forgejo" :state "s" :redirect_uri "https://cb"}} state)
        t     (h/run-op c "access-token" {:method "POST" :path "/codeberg/login/oauth/access_token"
                                          :body {:code (code-of a) :scope "read"}} state)
        u     (h/run-op c "userinfo" {:path "/codeberg/api/v1/user"
                                      :headers {:authorization (str "Bearer " (get-in t [:json "access_token"]))}}
                        state)]
    (testing "the provider shapes the paths and the default accounts"
      (is (= 302 (:status a)))
      (is (= 200 (:status t)))
      (is (= 200 (:status u)))
      (is (= {"id" 2001 "login" "forgejo" "email" "forgejo@codeberg.local"} (:json u))))
    (testing "the account index lists the provider's accounts"
      (let [index (some #(when (= "index" (get % "id")) %) (get c "operations"))]
        (is (str/includes? (get-in index ["responses" 0 "examples" "index" "body"]) "berguser"))))))

(deftest oauth2-users-flag-replaces-the-accounts
  (let [mine [{"id" 7 "login" "me" "email" "me@x"}]
        c    (contract/expand ((:build oauth2) "oauth/github" {:users mine}))]
    (is (= mine (get-in c ["seed" "users"])))
    (is (str/includes? (get-in (some #(when (= "authorize-picker" (get % "id")) %) (get c "operations"))
                               ["responses" 0 "examples" "picker" "body"])
                       "login=me"))))

(deftest the-directory-example-kit-builds-a-valid-twin
  (let [kit (kits/parse-kit (fs/readFileSync "examples/kits/directory.yaml" "utf8")
                            "examples/kits/directory.yaml" "directory")
        c   ((:build kit) "directory/directory" {:users-base "/scim/v2/Users" :rate-limit 5})
        ops (get (contract/expand c) "operations")
        op  (fn [id] (some #(when (= id (get % "id")) %) ops))]
    (is (= {:errors [] :warnings []} (contract/validate c)))
    (testing "a string flag lands in every gene that names it"
      (is (= "/scim/v2/Users" (get-in (op "list-users") ["request" "path"])))
      (is (= "/scim/v2/Users/{uid}/groups" (get-in (op "list-user-groups") ["request" "path"]))))
    (testing "a number flag stays a number"
      (is (= 5 (get-in (op "list-users") ["effect" "limit" "max"]))))))
