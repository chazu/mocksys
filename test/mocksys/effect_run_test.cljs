(ns mocksys.effect-run-test
  "Effect behavior the other suites leave out: every bind source, the clock moving
   under TTLs and rate-limit windows, the clock control itself, resolve, update,
   remove, pagination, malformed filters, and the edges of ctx.respond."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [mocksys.harness :as h]))

(defn- op [effect] {"id" "op" "request" {"method" "GET" "path" "/"} "effect" effect})

(defn- echo [binds] (op {"bind" binds "respond" {"status" 200 "json" (into {} (for [k (keys binds)] [k (str "{" k "}")]))}}))

(deftest bind-sources
  (testing "form body fields decode, + is a space"
    (is (= {"a" "x y" "b" "/"} (:json (h/run (echo {"a" {"body" "a"} "b" {"body" "b"}}) {:method "POST" :body "a=x+y&b=%2F"})))))
  (testing "a JSON body field keeps its type until it is interpolated"
    (let [r (h/run (op {"bind" {"n" {"body" "n"}} "respond" {"text" "{n}"}}) {:method "POST" :body {"n" 5}})]
      (is (= "5" (:body r)))))
  (testing "bodyJson binds the whole object; unparseable is {}"
    (is (= {"a" [1 2]} (:json (h/run (op {"bind" {"b" {"bodyJson" true}} "respond" {"jsonVar" "b"}}) {:body {"a" [1 2]}}))))
    (is (= {} (:json (h/run (op {"bind" {"b" {"bodyJson" true}} "respond" {"jsonVar" "b"}}) {:body "{nope"})))))
  (testing "headers match any case; bearer strips Bearer and token"
    (is (= {"a" "abc" "b" "xyz" "c" "Basic q"}
           (:json (h/run (echo {"a" {"header" "Authorization" "bearer" true}
                                "b" {"header" "x-tok" "bearer" true}
                                "c" {"header" "x-basic" "bearer" true}})
                         {:headers {"authorization" "Bearer abc" "X-Tok" "token xyz" "x-basic" "Basic q"}})))))
  (testing "a missing query, header or path capture is empty"
    (is (= {"q" "" "h" "" "p" ""}
           (:json (h/run (echo {"q" {"query" "q"} "h" {"header" "x"} "p" {"pathRegex" "/u/([^/]+)$"}}) {:path "/x"})))))
  (testing "a const keeps its shape"
    (is (= {"k" [1 {"a" true}]} (:json (h/run (op {"bind" {"c" {"const" {"k" [1 {"a" true}]}}} "respond" {"jsonVar" "c"}}) {})))))
  (testing "now, now plus an offset, and now as ISO, on the virtual clock"
    (is (= {"t" "1700000000" "u" "1700000060" "i" "2023-11-14T22:13:20.000Z"}
           (:json (h/run (echo {"t" {"now" true} "u" {"now" {"add" 60}} "i" {"now" {"iso" true}}}) {} {:clock-start 1700000000})))))
  (testing "baseUrl is http://Host, else the issuer"
    (let [e (echo {"o" {"baseUrl" true}})]
      (is (= "http://localhost:9000" (get-in (h/run e {:headers {"Host" "localhost:9000"}}) [:json "o"])))
      (is (= "https://issuer.example" (get-in (h/run e {} {:issuer "https://issuer.example"}) [:json "o"])))))
  (testing "a malformed form body fails the request"
    (is (thrown-with-msg? js/Error #"URI malformed"
                          (h/run (echo {"a" {"body" "a"}}) {:method "POST" :body "%E0%A4%A=1"})))))

(def ^:private limited
  (op {"bind"  {"who" {"header" "x-who"}}
       "limit" {"bucket" "rl" "key" "{who}" "max" 2 "window" 60}
       "respond" {"status" 200 "text" "ok"}}))

(deftest limit-windows-follow-the-clock
  (let [state (h/fresh-state)
        opts  {:clock-start 1700000000}
        call  #(h/run limited {:headers {"x-who" %}} opts state)
        rl    (fn [r] (mapv #(get-in r [:headers %]) ["X-Rate-Limit-Limit" "X-Rate-Limit-Remaining" "X-Rate-Limit-Reset"]))]
    (is (= ["2" "1" "1700000040"] (rl (call "a"))))
    (is (= 200 (:status (call "a"))))
    (let [over (call "a")]
      (is (= 429 (:status over)))
      (is (= "E0000047" (get-in over [:json "errorCode"])))
      (is (= ["2" "0" "1700000040"] (rl over))))
    (is (= 200 (:status (call "b"))) "each key has its own budget")
    (h/clock! state 1700000000 {"advance" 60})
    (is (= ["2" "1" "1700000100"] (rl (call "a"))) "the next window starts a fresh budget")))

(def ^:private remember
  (op {"bind" {"k" {"query" "k"}} "store" {"bucket" "b" "key" "{k}" "value" "v-{k}" "ttl" 10} "respond" {"status" 201}}))

(defn- reader [verb] (op {"bind" {"k" {"query" "k"}} verb {"bucket" "b" "key" "{k}" "as" "v" "miss" {"status" 410}}
                          "respond" {"text" "{v}"}}))

(deftest ttl-entries-expire-on-the-clock
  (let [state (h/fresh-state)
        opts  {:clock-start 1000}
        run   #(h/run %1 {:query {"k" %2}} opts state)]
    (run remember "a")
    (run remember "b")
    (is (= "v-a" (:body (run (reader "lookup") "a"))))
    (is (= "v-a" (:body (run (reader "consume") "a"))))
    (is (= 410 (:status (run (reader "lookup") "a"))) "consume took it")
    (h/clock! state 1000 {"advance" 10})
    (is (= "v-b" (:body (run (reader "lookup") "b"))) "an entry lives through its last second")
    (h/clock! state 1000 {"advance" 1})
    (is (= 410 (:status (run (reader "lookup") "b"))) "and is gone after it")))

(deftest the-clock-control-reads-sets-and-advances
  (let [state (h/fresh-state)]
    (is (= {"now" 50 "iso" "1970-01-01T00:00:50.000Z"} (h/clock! state 50 {})))
    (is (= 80 (get (h/clock! state 50 {"advance" 30}) "now")))
    (is (= 5000 (get (h/clock! state 50 {"set" 5000}) "now")))
    (is (= 5000 (get (h/clock! state 50 {"advance" "x"}) "now")) "a non-number is ignored")))

(def ^:private seed {"users" [{"login" "ann" "id" 1} {"login" "bo" "id" 2}]})

(deftest resolve-reads-the-seed
  (let [e (op {"bind" {"l" {"query" "l"}}
               "resolve" {"seed" "users" "by" "login" "eq" "{l}" "as" "u" "miss" {"status" 401 "json" {"m" "no"}}}
               "respond" {"jsonVar" "u"}})]
    (is (= {"login" "bo" "id" 2} (:json (h/run e {:query {"l" "bo"}} {:seed seed}))))
    (is (= 401 (:status (h/run e {:query {"l" "cy"}} {:seed seed}))))))

(def ^:private colls
  {"users" {"idField" "id"
            "seed" [{"id" "u1" "status" "ACTIVE" "profile" {"login" "a@x" "first" "A"}}
                    {"id" "u2" "status" "STAGED" "profile" {"login" "b@x" "first" "B"}}
                    {"id" "u3" "status" "ACTIVE" "profile" {"login" "c@x" "first" "C"}}]}
   "groups" {"idField" "id" "seed" [{"id" "g1"} {"id" "g2"}]}
   "m"      {"idField" "id" "seed" [{"id" "g1:u1" "gid" "g1" "uid" "u1"}
                                    {"id" "g1:u3" "gid" "g1" "uid" "u3"}
                                    {"id" "g1:u9" "gid" "g1" "uid" "u9"}]}})

(defn- by-id [verb spec]
  (op {"bind" {"id" {"query" "id"} "body" {"bodyJson" true}} verb (merge {"collection" "users" "key" "{id}"} spec)}))

(deftest update-merges-one-level-and-remove-deletes
  (let [state (h/fresh-state)
        run   #(h/run %1 (merge {:query {"id" %2}} %3) {:collections colls} state)
        patch (by-id "update" {"patch" {"status" "SUSPENDED" "profile" {"first" "{id}!"}}})
        merge (by-id "update" {"mergeVar" "body"})]
    (is (= {"id" "u1" "status" "SUSPENDED" "profile" {"login" "a@x" "first" "u1!"}}
           (:json (run patch "u1" {}))))
    (is (= {"id" "u2" "status" "STAGED" "profile" ["replaced"] "extra" 1}
           (:json (run merge "u2" {:body {"profile" ["replaced"] "extra" 1}}))) "an array replaces")
    (is (= 404 (:status (run patch "nope" {}))))
    (is (= [204 ""] ((juxt :status :body) (run (by-id "remove" {}) "u2" {}))))
    (is (= 404 (:status (run (by-id "remove" {"miss" {"status" 404 "json" {"e" "gone"}}}) "u2" {}))))
    (is (= 404 (:status (run (by-id "get" {}) "u2" {}))) "the removed row is gone")))

(deftest list-and-list-join-page-with-a-link
  (let [state (h/fresh-state)
        page  (op {"bind" {"after" {"query" "after"} "lim" {"query" "limit"} "q" {"query" "filter"}}
                   "list" {"collection" "users" "filter" "{q}" "after" "{after}" "limit" "{lim}"
                           "link" "</users?after={next}>; rel=\"next\""}})
        join  (op {"bind" {"after" {"query" "after"} "lim" {"query" "limit"}}
                   "listJoin" {"via" "m" "byField" "gid" "byValue" "g1" "hydrate" "users" "hydrateVia" "uid"
                               "after" "{after}" "limit" "{lim}" "link" "<?after={next}>"}})
        run   #(h/run %1 {:query %2} {:collections colls} state)
        ids   #(mapv (fn [u] (get u "id")) (:json %))]
    (let [p1 (run page {"limit" "2"})]
      (is (= ["u1" "u2"] (ids p1)))
      (is (= "</users?after=u2>; rel=\"next\"" (get-in p1 [:headers "Link"]))))
    (let [p2 (run page {"limit" "2" "after" "u2"})]
      (is (= ["u3"] (ids p2)))
      (is (nil? (get-in p2 [:headers "Link"])) "no Link on the last page"))
    (is (= ["u1" "u3"] (ids (run page {"filter" "status eq \"ACTIVE\""}))))
    (is (= "Invalid filter: expected a value" (get-in (run page {"filter" "status eq"}) [:json "detail"])))
    (is (= "Invalid filter: unexpected )" (get-in (run page {"filter" "status pr )"}) [:json "detail"])))
    (let [j1 (run join {"limit" "1"})]
      (is (= ["u1"] (ids j1)) "a membership whose user is gone drops out")
      (is (= "<?after=u1>" (get-in j1 [:headers "Link"]))))
    (is (= ["u3"] (ids (run join {"after" "u1"}))))))

(deftest find-by-field-picks-and-filters
  (let [run  #(h/run (op %1) {:query %2} {:collections colls})
        pick {"bind" {"l" {"query" "l"} "q" {"query" "q"}}
              "find" {"collection" "users" "field" "profile.login" "eq" "{l}" "pick" "profile.first" "as" "f"
                      "filter" "{q}" "miss" {"status" 404} "invalid" {"status" 422 "text" "bad: {filterError}"}}
              "respond" {"text" "{f}"}}]
    (is (= "B" (:body (run pick {"l" "b@x" "q" "id pr"}))))
    (is (= 404 (:status (run pick {"l" "b@x" "q" "status eq \"ACTIVE\""}))))
    (is (= [422 "bad: Invalid filter: unknown operator zz"] ((juxt :status :body) (run pick {"l" "a@x" "q" "id zz 1"}))))
    (is (= 404 (:status (run pick {"l" "zz@x" "q" "id zz 1"}))) "a filter is only parsed for a row that was found")))

(deftest render-pages-rows-and-refuses-a-bad-filter
  (let [r (op {"bind" {"q" {"query" "q"} "who" {"const" "me"}}
               "render" {"collection" "users" "filter" "{q}" "row" "<li>{id}:{profile.first}:{who}</li>"
                         "wrap" "<ul>{rows}</ul>"}})
        run #(h/run r {:query {"q" %}} {:collections colls})]
    (is (= "<ul><li>u1:A:me</li><li>u3:C:me</li></ul>" (:body (run "status eq \"ACTIVE\""))))
    (is (= "text/html; charset=utf-8" (get-in (run "") [:headers "Content-Type"])))
    (is (= [400 "invalidFilter"] ((juxt :status #(get-in % [:json "scimType"])) (run "(id pr"))))))

(deftest minted-ids-follow-the-random-seed
  (let [m  (op {"mint" {"a" {"prefix" "x_"} "b" {}} "respond" {"text" "{a} {b}"}})
        one (:body (h/run m {} {:random-seed 42}))]
    (is (= one (:body (h/run m {} {:random-seed 42}))))
    (is (not= one (:body (h/run m {}))))
    (is (re-matches #"x_[0-9a-f]{24} [0-9a-f]{24}" one))))

(deftest respond-edges
  (testing "jsonVar of a falsy value is null"
    (is (= "null" (:body (h/run (op {"bind" {"z" {"const" 0}} "respond" {"jsonVar" "z"}}) {})))))
  (testing "a Content-Type the spec names wins; headers interpolate"
    (let [r (h/run (op {"bind" {"v" {"const" "7"}} "respond" {"status" 201 "headers" {"Content-Type" "text/x" "X-V" "{v}"}
                                                              "json" {"v" "{v}"}}}) {})]
      (is (= [201 "text/x" "7" "{\"v\":\"7\"}"] [(:status r) (get-in r [:headers "Content-Type"]) (get-in r [:headers "X-V"]) (:body r)]))))
  (testing "an effect that only stores answers 200"
    (is (= [200 ""] ((juxt :status :body) (h/run (op {"store" {"bucket" "b" "key" "k" "value" "v"} "respond" {}}) {}))))))

(deftest ctx-respond-edges
  (let [gate (op {"call" {"verb" "v"} "respond" {"status" 202}})
        run  (fn [spec] (h/run gate {} {:verbs #js {:v (fn [^js ctx] (.respond ctx spec))}}))]
    (testing "a content-type header in any case suppresses the JSON one"
      (let [r (run #js {:json #js {:a 1} :headers #js {"content-type" "application/vnd+json"}})]
        (is (= ["application/vnd+json" nil] [(get-in r [:headers "content-type"]) (get-in r [:headers "Content-Type"])]))))
    (testing "json null is an empty body; the status defaults to 200"
      (is (= [200 ""] ((juxt :status :body) (run #js {:json nil})))))
    (testing "a non-string body is sent as JSON with no content type"
      (let [r (run #js {:status 409 :body #js [1 2]})]
        (is (= [409 "[1,2]" nil] [(:status r) (:body r) (get-in r [:headers "Content-Type"])]))))
    (testing "a value that JSON cannot carry is named"
      (is (thrown-with-msg? js/Error #"verb 'v': ctx.respond body does not serialize: it is a function"
                            (run #js {:body (fn [])}))))
    (testing "a thrown non-Error is reported by value"
      (is (thrown-with-msg? js/Error #"^verb 'v' threw: 0$"
                            (h/run gate {} {:verbs #js {:v (fn [_] (throw 0))}}))))))
