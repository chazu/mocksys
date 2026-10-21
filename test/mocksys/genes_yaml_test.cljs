(ns mocksys.genes-yaml-test
  "A contract may list `genes:`. `contract/expand` turns them into operations that
   follow the authored ones, every reader of operations sees the expansion, and
   unknown genes or params are errors rather than silently ignored."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["js-yaml" :as yaml]
            [mocksys.harness :as h]
            [mocksys.kits :as kits]
            [mocksys.contract :as contract]))

(defn- ids [c] (mapv #(get % "id") (get c "operations")))

(def ^:private authored-list-users
  {"id" "my-list-users"
   "request" {"method" "GET" "path" "/api/v1/users"}
   "responses" [{"status" 200 "examples" {"fixed" {"select" true "body" []}}}]})

(def ^:private with-genes
  {"service" "demo" "scenario" "demo/x" "origin" "authored"
   "seed" {"users" [{"id" "u1" "status" "ACTIVE"}]}
   "operations" [authored-list-users]
   "genes" [{"crud-collection" {"coll" "users" "base" "/api/v1/users" "seed-name" "users"}}]})

(deftest gene-operations-follow-authored-ones
  (let [c (contract/expand with-genes)]
    (testing "authored ops come first, so they shadow gene ops on the same request"
      (is (= ["my-list-users" "create-users" "list-users" "get-users" "update-users" "delete-users"]
             (ids c))))
    (testing "each gene op names the gene it came from; authored ops do not"
      (is (= [nil "crud-collection"] (mapv #(get % "gene") (take 2 (get c "operations"))))))
    (testing "the gene's collection is declared and the authored seed is kept"
      (is (= {"idField" "id" "seed" "users"} (get-in c ["state" "collections" "users"])))
      (is (= [{"id" "u1" "status" "ACTIVE"}] (get-in c ["seed" "users"]))))
    (testing "the expansion no longer carries the genes form"
      (is (not (contains? c "genes"))))))

(deftest authored-state-wins-over-gene-state
  (let [c (contract/expand (assoc-in with-genes ["state" "collections" "users"]
                                     {"idField" "login" "seed" "users"}))]
    (is (= "login" (get-in c ["state" "collections" "users" "idField"])))))

(deftest a-contract-without-genes-is-unchanged
  (let [c {"service" "demo" "scenario" "demo/y" "operations" [authored-list-users]}]
    (is (= c (contract/expand c)))))

(deftest expand-is-idempotent
  (let [once (contract/expand with-genes)]
    (is (= once (contract/expand once)))))

(deftest every-reader-sees-gene-operations
  (testing "lower emits a stub per expanded operation"
    (is (= 6 (count (get (contract/lower with-genes) "stubs")))))
  (testing "examples lists gene operations with their gene"
    (let [rows (contract/examples-overview with-genes nil)]
      (is (= (ids (contract/expand with-genes)) (mapv :id rows)))
      (is (= "crud-collection" (:gene (second rows))))))
  (testing "validate checks the expansion"
    (is (empty? (:errors (contract/validate (dissoc with-genes "operations")))))))

(deftest unknown-genes-and-params-are-errors
  (testing "an unknown gene is named"
    (let [{:keys [errors]} (contract/validate (assoc with-genes "genes" [{"crud-colection" {}}]))]
      (is (some #(str/includes? % "unknown gene 'crud-colection'") errors))))
  (testing "an unknown param is named with its gene"
    (let [{:keys [errors]} (contract/validate
                            (assoc with-genes "genes" [{"crud-collection" {"coll" "users" "base" "/u"
                                                                           "id-prefx" "u"}}]))]
      (is (some #(and (str/includes? % "crud-collection") (str/includes? % "unknown param 'id-prefx'"))
                errors))))
  (testing "a missing required param is named"
    (let [{:keys [errors]} (contract/validate (assoc with-genes "genes" [{"crud-collection" {"coll" "users"}}]))]
      (is (some #(str/includes? % "missing required param 'base'") errors))))
  (testing "an entry that is not a one-key map is rejected"
    (let [{:keys [errors]} (contract/validate (assoc with-genes "genes" ["crud-collection"]))]
      (is (seq errors))))
  (testing "compiling refuses a contract with an unknown gene"
    (is (thrown-with-msg? js/Error #"unknown gene" (contract/lower (assoc with-genes "genes" [{"nope" {}}]))))))

(deftest gene-of-names-the-source-of-an-operation
  (testing "a gene operation names its gene"
    (is (= "crud-collection" (contract/gene-of with-genes "get-users"))))
  (testing "an authored operation has no gene, even when a gene op shares its id"
    (let [c (update with-genes "operations" conj (assoc authored-list-users "id" "get-users"))]
      (is (nil? (contract/gene-of c "get-users")))))
  (testing "an unknown id has no gene"
    (is (nil? (contract/gene-of with-genes "nope")))))

(deftest add-does-not-reuse-a-gene-operation-id
  (let [c (contract/add-operation (dissoc with-genes "operations") "demo/x" "demo"
                                  "GET" "/users" 200 nil false)
        added (last (get c "operations"))]
    (is (not (contains? (set (ids (contract/expand (dissoc with-genes "operations")))) (get added "id"))))))

;; --- params: required, shaped, and no collisions ------------------------------

(defn- errors-of [genes & [ops]]
  (:errors (contract/validate (cond-> {"service" "demo" "scenario" "demo/x" "genes" genes}
                                ops (assoc "operations" ops)))))

(deftest a-required-param-set-to-null-is-missing
  (is (= ["genes[0] crud-collection: missing required param 'base'"]
         (errors-of [{"crud-collection" {"coll" "users" "base" nil}}]))))

(deftest an-optional-param-set-to-null-is-unset
  (is (empty? (errors-of [{"crud-collection" {"coll" "users" "base" "/a" "id-prefix" nil
                                              "create-defaults" nil}}]))))

(deftest a-param-of-the-wrong-shape-is-named
  (testing "a number where a map belongs"
    (is (= ["genes[0] crud-collection: param 'create-defaults' must be a map"]
           (errors-of [{"crud-collection" {"coll" "users" "base" "/a" "create-defaults" 5}}]))))
  (testing "a map where a string belongs"
    (is (= ["genes[0] crud-collection: param 'base' must be a string"]
           (errors-of [{"crud-collection" {"coll" "users" "base" {"x" 1}}}]))))
  (testing "a string where a list belongs, in a later entry"
    (is (= ["genes[1] oauth2-provider: param 'users' must be a list"]
           (errors-of [{"membership" {}} {"oauth2-provider" {"users" "alice"}}]))))
  (testing "compiling refuses it with the same message instead of crashing"
    (is (thrown-with-msg? js/Error #"param 'create-defaults' must be a map"
                          (contract/lower {"genes" [{"crud-collection" {"coll" "users" "base" "/a"
                                                                        "create-defaults" 5}}]})))))

(deftest two-genes-declaring-one-collection-is-an-error
  (let [errs (errors-of [{"crud-collection" {"coll" "users" "base" "/a"}}
                         {"crud-collection" {"coll" "users" "base" "/b"}}])]
    (is (some #{"genes[1] crud-collection: collection 'users' is already declared by genes[0] crud-collection"}
              errs))))

(deftest duplicate-operation-ids-are-errors
  (testing "two genes over the same collection mint the same ids"
    (let [errs (errors-of [{"crud-collection" {"coll" "users" "base" "/a"}}
                           {"crud-collection" {"coll" "users" "base" "/b"}}])]
      (is (some #{"list-users: duplicate operation id (gene crud-collection, gene crud-collection)"} errs))))
  (testing "an authored operation reusing a gene operation's id"
    (let [errs (errors-of [{"crud-collection" {"coll" "users" "base" "/a"}}]
                          [(assoc authored-list-users "id" "list-users")])]
      (is (= ["list-users: duplicate operation id (authored, gene crud-collection)"] errs))))
  (testing "two authored operations with one id"
    (let [errs (:errors (contract/validate {"operations" [authored-list-users authored-list-users]}))]
      (is (= ["my-list-users: duplicate operation id (authored, authored)"] errs)))))

;; --- the registry describes params truthfully ---------------------------------

(def ^:private samples
  "A distinctive value per param name. A gene param missing here fails the test."
  {:coll "things" :base "/things" :id-field "tid" :seed-name "stuff" :id-prefix "t_"
   :create-defaults {"kind" "thing"} :not-found {"status" 410 "json" {}}
   :invalid-filter {"status" 422 "json" {}} :rate-limit {"max" 3 "window" 5}
   :authz "/a2" :token "/t2" :userinfo "/u2" :users [{"id" 9 "login" "z" "email" "z@z"}]
   :token-ttl 60 :user-source {"collection" "people"}
   :users-coll "people" :groups-coll "teams" :users-base "/people" :groups-base "/teams"
   :provider "codeberg"})

(def ^:private minimal
  {"crud-collection" {:coll "users" :base "/api/v1/users"}
   "oidc-handshake"  {:authz "/a" :token "/t" :userinfo "/u"}})

(deftest every-declared-param-changes-the-gene
  (doseq [[gname {:keys [params build]}] kits/genes
          [k _shape _doc] params]
    (testing (str gname " " (name k))
      (is (contains? samples k) "add a sample value for this param")
      (let [base (get minimal gname {})]
        (is (not= (build base) (build (assoc base k (get samples k))))
            "a declared param the gene ignores is not truthful")))))

(deftest every-sample-fits-its-declared-shape
  (doseq [[gname {:keys [params]}] kits/genes
          [k shape _doc] params]
    (testing (str gname " " (name k))
      (is (contains? kits/param-shapes shape) "a param declares a known shape")
      (is ((get kits/param-shapes shape) (get samples k)) "the sample value fits the declared shape"))))

(deftest required-params-are-declared-params
  (doseq [[gname {:keys [params required]}] kits/genes]
    (is (every? (set (map first params)) required) gname)))

;; --- the gene-only example ----------------------------------------------------

(def ^:private directory
  (js->clj (yaml/load (fs/readFileSync "examples/directory.yaml" "utf8"))))

(deftest the-directory-example-is-built-only-from-genes
  (testing "it has no authored operations and validates"
    (is (empty? (get directory "operations")))
    (is (= {:errors [] :warnings []} (contract/validate directory))))
  (let [c     (contract/expand directory)
        state (h/fresh-state)
        made  (h/run-op c "create-users" {:method "POST" :path "/api/v1/users"
                                          :body {"profile" {"login" "dan@example.com"}}} state)
        uid   (get-in made [:json "id"])]
    (testing "a created user takes the id prefix and the create defaults"
      (is (= 201 (:status made)))
      (is (str/starts-with? uid "u"))
      (is (= "STAGED" (get-in made [:json "status"]))))
    (testing "the created user can join a seeded group and is listed there"
      (is (= 204 (:status (h/run-op c "assign-membership"
                                    {:method "PUT" :path (str "/api/v1/groups/g1/users/" uid)} state))))
      (is (= [uid] (mapv #(get % "id")
                         (:json (h/run-op c "list-group-users" {:path "/api/v1/groups/g1/users"} state))))))
    (testing "a SCIM filter over the seed works"
      (is (= ["u1" "u2"]
             (mapv #(get % "id")
                   (:json (h/run-op c "list-users" {:path "/api/v1/users"
                                                    :query {:filter "status eq \"ACTIVE\""}} state))))))))
