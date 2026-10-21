(ns mocksys.prototype-test
  "Names from an effect, the imposter's settings or a request are only keys: none of
   them reaches Object.prototype, so one imposter cannot change what another answers."
  (:require [clojure.test :refer [deftest testing is async]]
            [clojure.string :as str]
            [promesa.core :as p]
            [mocksys.contract :as contract]
            [mocksys.effect :as effect]
            [mocksys.runtime.imposter :as imp]))

(defn- spec [stubs & {:as extra}]
  (merge {"protocol" "http" "port" 4545 "stubs" stubs} extra))

(defn- req [& {:as r}]
  (merge {"method" "GET" "path" "/" "query" {} "headers" {} "body" ""} r))

(defn- new-state
  "The state the daemon gives a new imposter. The effects below run on it directly,
   past the checks a post would make, to show the runtime holds on its own."
  []
  (:state @(imp/create (spec []))))

(defn- run [effect state settings request]
  (effect/run effect {:state state :request request :settings settings}))

(defn- prototype-after
  "Run `f`, then put back any Object.prototype member it added or removed so a
   failure here cannot break the tests after it. Returns the members it changed."
  [f]
  (let [proto   (.-prototype js/Object)
        before  (js/Object.getOwnPropertyDescriptors proto)
        names   #(set (js/Object.getOwnPropertyNames %))
        changed (volatile! nil)]
    (try (f)
         (finally
           (let [added   (remove (names before) (names proto))
                 removed (remove (names proto) (names before))]
             (doseq [k added] (js-delete proto k))
             (doseq [k removed] (js/Object.defineProperty proto k (unchecked-get before k)))
             (vreset! changed (vec (sort (concat added removed)))))))
    @changed))

(deftest a-store-bucket-cannot-reach-another-imposter
  (let [other (new-state)
        seen  (atom nil)]
    (is (= [] (prototype-after
               #(do (run {"bind"    {"k" {"query" "k"} "v" {"query" "v"}}
                          "store"   {"bucket" "__proto__" "key" "{k}" "value" "{v}"}
                          "respond" {"status" 200}}
                         (new-state) {} (req "path" "/pollute" "query" {"k" "polluted" "v" "yes"}))
                    (reset! seen (run {"respond" {"text" "[{polluted}]"}} other {} (req))))))
        "Object.prototype is unchanged")
    (is (= "[]" (get @seen "body")) "the other imposter's unbound {polluted} stays empty")))

(deftest a-limit-bucket-is-only-a-key
  (is (= [] (prototype-after
             #(run {"limit" {"bucket" "__proto__" "key" "k"} "respond" {"status" 200}} (new-state) {} (req))))))

(deftest a-collection-name-is-only-a-key
  (doseq [n ["__proto__" "constructor"]]
    (testing n
      (let [state    (new-state)
            settings {"collections" {n {"seed" []}}}
            send     #(get (run % state settings (req "query" {"id" "toString"})) "statusCode")]
        (is (= [] (prototype-after
                   #(is (= [201 204 404]
                           [(send {"bind" {"id" {"query" "id"}} "create" {"collection" n "body" {"id" "{id}"}}})
                            (send {"bind" {"id" {"query" "id"}} "remove" {"collection" n "key" "{id}"}})
                            (send {"bind" {"id" {"query" "id"}} "get" {"collection" n "key" "{id}"}})])))))))))

(def ^:private by-id {"pathRegex" "^/users/([^/]+)$"})

(defn- users-stub [method effect]
  {"predicates" [{"equals" {"method" method}}]
   "responses"  [{"effect" (assoc effect "bind" {"id" by-id})}]})

(deftest a-request-id-names-only-rows
  (async done
    (let [i  (imp/create (spec [(users-stub "GET" {"get" {"collection" "users" "key" "{id}"}})
                                (users-stub "PUT" {"update" {"collection" "users" "key" "{id}" "patch" {"x" 1}}})
                                (users-stub "DELETE" {"remove" {"collection" "users" "key" "{id}"}})
                                (users-stub "POST" {"find" {"collection" "users" "key" "{id}" "as" "u"}
                                                    "respond" {"jsonVar" "u"}})]
                               "state" {"collections" {"users" {"seed" [{"id" "00u1"}]}}}))
          ids ["constructor" "__proto__" "toString" "hasOwnProperty"]]
      (-> (p/all (for [m ["GET" "PUT" "DELETE" "POST"] id ids]
                   (p/let [o (imp/respond i (req "method" m "path" (str "/users/" id)) {})]
                     [m id (:status o)])))
          (p/then (fn [os]
                    (is (= (for [m ["GET" "PUT" "DELETE" "POST"] id ids] [m id 404]) os))
                    (p/let [o (imp/respond i (req "path" "/users/00u1") {})]
                      (is (= 200 (:status o)) "a real id still answers"))))
          (p/catch (fn [e] (is (nil? e) (ex-message e))))
          (p/finally done)))))

(deftest reserved-names-are-refused
  (let [refused? (fn [effect field]
                   (some #(and (str/starts-with? % field) (str/includes? % "__proto__, constructor and prototype"))
                         (effect/errors effect)))]
    (doseq [n ["__proto__" "constructor" "prototype"]]
      (testing n
        (is (refused? {"store" {"bucket" n "key" "k"} "respond" {}} "store.bucket"))
        (is (refused? {"limit" {"bucket" n} "respond" {}} "limit.bucket"))
        (is (refused? {"consume" {"bucket" "b" "key" "k" "as" n} "respond" {}} "consume.as"))
        (is (refused? {"lookup" {"bucket" n "key" "k"} "respond" {}} "lookup.bucket"))
        (is (refused? {"resolve" {"seed" "s" "by" "id" "as" n} "respond" {}} "resolve.as"))
        (is (refused? {"find" {"collection" n "key" "k"} "respond" {}} "find.collection"))
        (is (refused? {"find" [{"collection" "users" "key" "k" "as" n}] "respond" {}} "find[0].as"))
        (is (refused? {"sign" {"as" n "key" "pem"} "respond" {}} "sign.as"))
        (is (refused? {"create" {"collection" "users" "as" n}} "create.as"))
        (is (refused? {"get" {"collection" n "key" "k"}} "get.collection"))
        (is (refused? {"link" {"collection" n "id" "k"}} "link.collection"))
        (is (refused? {"listJoin" {"via" "m" "byField" "g" "byValue" "1" "hydrate" n "hydrateVia" "u"}} "listJoin.hydrate"))
        (is (refused? {"bind" {n {"query" "q"}} "respond" {}} (str "bind." n)))
        (is (refused? {"mint" {n {}} "respond" {}} (str "mint." n)))
        (is (some #(str/includes? % "__proto__, constructor and prototype")
                  (effect/settings-errors {"collections" {n {"seed" []}}}))
            "a collection name")
        (is (some #(str/includes? % "__proto__, constructor and prototype")
                  (effect/settings-errors {"collections" {"users" {"idField" n}}}))
            "an idField")))))

(deftest the-daemon-refuses-a-reserved-name-on-post
  (is (str/includes? (try (imp/create (spec [{"responses" [{"effect" {"store" {"bucket" "__proto__" "key" "k"} "respond" {}}}]}]))
                          nil (catch :default e (ex-message e)))
                     "stubs[0].responses[0].effect: store.bucket must be a string other than __proto__, constructor and prototype"))
  (is (str/includes? (try (imp/create (spec [] "state" {"collections" {"constructor" {"seed" []}}}))
                          nil (catch :default e (ex-message e)))
                     "state.collections.constructor")))

(deftest the-cli-refuses-a-reserved-name
  (let [errors #(:errors (contract/validate {"operations" [{"id" "op" "request" {"method" "GET" "path" "/x"} "effect" %2}]
                                             "state" {"collections" %1}}))]
    (is (some #(str/includes? % "op: bind.__proto__") (errors {} {"bind" {"__proto__" {"query" "q"}} "respond" {}})))
    (is (some #(str/includes? % "state.collections.constructor") (errors {"constructor" {}} {"respond" {}})))))
