(ns mocksys.kits
  "Kits & genes: the agent-facing way to stand up a sophisticated (often *stateful*)
  mock — a digital twin of a real service — without authoring it operation by
  operation.

  A *gene* is a small, parameterized contract fragment ({:operations :collections
  :seed}) — a proven behavioral pattern: an OIDC authorize/token/userinfo handshake,
  a REST resource with CRUD+filter+pagination, a provider error envelope. A *kit*
  composes genes into a complete, runnable contract.

  This is `gene transfusion` made concrete: a new twin is *composed from existing
  genes* rather than written from scratch. `oauth2` reproduces snooker's mockoauth;
  `okta` composes a users + groups CRUD twin with the Okta error envelope and an
  OIDC token flow — a digital twin of Okta in one command."
  (:require [clojure.string :as str]))

;; --- composition ----------------------------------------------------------

(defn- compose
  "Merge gene fragments into one contract. Each fragment may carry :operations
   (concatenated, in order — Mountebank serves the first matching stub),
   :collections (merged), and :seed (merged)."
  [base & fragments]
  (reduce (fn [c {:keys [operations collections seed]}]
            (cond-> c
              (seq operations)   (update "operations" (fnil into []) operations)
              (seq collections)  (update-in ["state" "collections"] merge collections)
              (seq seed)         (update "seed" merge seed)))
          (merge {"operations" [] "state" {"collections" {}} "seed" {}} base)
          fragments))

;; --- gene: error envelope -------------------------------------------------

(defn okta-error
  "An Okta-shaped error body (used as a `miss`/4xx response)."
  [code summary]
  {"errorCode" code "errorSummary" summary "errorLink" code "errorCauses" []})

;; --- gene: REST resource (CRUD + filter + pagination) ---------------------

(defn crud-collection
  "Five operations + a collection declaration for a REST resource served from a
   seeded, mutable collection. Generalizes the users/groups surface:
     POST   {base}            create (id minted, request body merged in)
     GET    {base}?filter=…   list, SCIM-ish filter, cursor pagination
     GET    {base}/{id}       read one
     POST   {base}/{id}       merge-patch
     DELETE {base}/{id}       delete
   `opts`: :coll :base :id-field :seed-name :id-prefix :create-defaults :not-found."
  [{:keys [coll base id-field seed-name id-prefix create-defaults not-found rate-limit]}]
  (let [id-field (or id-field "id")
        id-regex (str base "/([^/]+)$")
        item     (str base "/{id}")
        nf       (or not-found {"status" 404 "json" (okta-error "E0000007" "Not found: Resource not found")})
        ;; rate-limit gene: when enabled, every op binds a client key (the bearer
        ;; token, else "anon") and guards with a clock-windowed `limit`.
        rl-bind  (when rate-limit {"__cli" {"header" "authorization" "bearer" true}})
        rl       (when rate-limit
                   {"bucket" "rl" "key" "{__cli}"
                    "max"    (:max rate-limit) "window" (or (:window rate-limit) 60)})
        eff      (fn [m] (cond-> m
                           rl-bind (update "bind" merge rl-bind)
                           rl      (assoc "limit" rl)))]
    {:collections {coll {"idField" id-field "seed" seed-name}}
     :operations
     [{"id"      (str "create-" coll)
       "summary" (str "create a " coll " entry")
       "request" {"method" "POST" "path" base}
       "effect"  (eff {"bind"   {"incoming" {"bodyJson" true}}
                       "mint"   {"newid" {"prefix" (or id-prefix "")}}
                       "create" {"collection" coll
                                 "merge"      ["incoming"]
                                 "body"       (merge {id-field "{newid}"} create-defaults)
                                 "status"     201}})}
      {"id"      (str "list-" coll)
       "summary" (str "list / filter / paginate " coll)
       "request" {"method" "GET" "path" base}
       "effect"  (eff {"bind" {"q" {"query" "filter"} "after" {"query" "after"} "lim" {"query" "limit"}}
                       "list" {"collection" coll
                               "filter"     "{q}"
                               "after"      "{after}"
                               "limit"      "{lim}"
                               "link"       (str "<" base "?after={next}>; rel=\"next\"")}})}
      {"id"      (str "get-" coll)
       "summary" (str "get a " coll " entry by id")
       "request" {"method" "GET" "path" item}
       "effect"  (eff {"bind" {"id" {"pathRegex" id-regex}}
                       "get"  {"collection" coll "key" "{id}" "miss" nf}})}
      {"id"      (str "update-" coll)
       "summary" (str "merge-patch a " coll " entry")
       "request" {"method" "POST" "path" item}
       "effect"  (eff {"bind"   {"id" {"pathRegex" id-regex} "incoming" {"bodyJson" true}}
                       "update" {"collection" coll "key" "{id}" "mergeVar" "incoming" "miss" nf}})}
      {"id"      (str "delete-" coll)
       "summary" (str "delete a " coll " entry")
       "request" {"method" "DELETE" "path" item}
       "effect"  (eff {"bind"   {"id" {"pathRegex" id-regex}}
                       "remove" {"collection" coll "key" "{id}" "miss" nf}})}]}))

;; --- gene: OIDC authorize / token / userinfo handshake --------------------
;; Single-use authorization codes minted at `authorize`, exchanged for a bearer
;; token at `token`, which `userinfo` resolves back to the bound seed user. Stateful.

(defn- picker-html
  "A tiny account picker: each seed user links back to authorize with &login=<u>,
   preserving the inbound redirect_uri/state/client_id via response templating."
  [authz users]
  (let [link (fn [u]
               (let [login (get u "login")]
                 (str "<a href=\"" authz
                      "?client_id={{request.query.client_id}}"
                      "&redirect_uri={{request.query.redirect_uri}}"
                      "&state={{request.query.state}}"
                      "&login=" login "\">"
                      "<b>" login "</b> <small>(" (get u "email") ")</small></a>")))]
    (str "<!doctype html><meta charset=utf-8><title>mock login</title>"
         "<style>body{font-family:system-ui;max-width:32rem;margin:4rem auto;padding:0 1rem}"
         "a{display:block;padding:.75rem 1rem;margin:.5rem 0;border:1px solid #ccc;"
         "border-radius:.5rem;text-decoration:none;color:#111}a:hover{background:#f3f3f3}"
         "small{color:#666}</style>"
         "<h2>mock login</h2><p><small>Authorize as:</small></p>"
         (str/join (map link users)))))

(defn oidc-handshake
  "OIDC genes over `authz`/`token`/`userinfo` paths, resolving tokens to the
   `seed-name` user array (by `login`). Returns authorize(+picker), token, userinfo.
   With `:token-ttl` (seconds), issued tokens expire on the virtual clock."
  [{:keys [authz token userinfo seed-name users token-ttl]}]
  {:operations
   [{"id"      "authorize"
     "summary" "authorize (?login given) -> redirect with a single-use code"
     "request" {"method" "GET" "path" authz "query" {"login" {"present" true}}}
     "effect"  {"bind"    {"login" {"query" "login"} "state" {"query" "state"} "ruri" {"query" "redirect_uri"}}
                "mint"    {"code" {"prefix" "code_"}}
                "store"   {"bucket" "codes" "key" "{code}" "value" "{login}"}
                "respond" {"status" 302 "headers" {"Location" "{ruri}?code={code}&state={state}"}}}}
    {"id"      "authorize-picker"
     "summary" "authorize (no ?login) -> account picker"
     "request" {"method" "GET" "path" authz}
     "responses"
     [{"status"  200
       "headers" {"Content-Type" "text/html; charset=utf-8"}
       "examples" {"picker" {"select" true "body" (picker-html authz users)}}}]}
    {"id"      "access-token"
     "summary" "code -> access_token (code is single-use)"
     "request" {"method" "POST" "path" token}
     "effect"  {"bind"    {"code" {"body" "code"} "scope" {"body" "scope"}}
                "consume" {"bucket" "codes" "key" "{code}" "as" "login"
                           "miss"  {"status" 400 "json" {"error" "bad_verification_code"}}}
                "mint"    {"token" {"prefix" "mockoauth_"}}
                "store"   (cond-> {"bucket" "tokens" "key" "{token}" "value" "{login}"}
                            token-ttl (assoc "ttl" token-ttl))
                "respond" {"status" 200
                           "json"  (cond-> {"access_token" "{token}" "token_type" "bearer" "scope" "{scope}"}
                                     token-ttl (assoc "expires_in" token-ttl))}}}
    {"id"      "userinfo"
     "summary" "bearer access_token -> the bound user"
     "request" {"method" "GET" "path" userinfo}
     "effect"  {"bind"    {"auth" {"header" "authorization" "bearer" true}}
                "lookup"  {"bucket" "tokens" "key" "{auth}" "as" "login"
                           "miss"  {"status" 401 "json" {"message" "Bad credentials"}}}
                "resolve" {"seed" seed-name "by" "login" "eq" "{login}" "as" "user"
                           "miss"  {"status" 401 "json" {"message" "Bad credentials"}}}
                "respond" {"status" 200 "jsonVar" "user"}}}]})

;; --- default seed accounts (match snooker's mockoauth) --------------------

(def ^:private default-users
  {"github"   [{"id" 1001 "login" "octocat" "email" "octocat@github.local"}
               {"id" 1002 "login" "hubot"   "email" "hubot@github.local"}]
   "codeberg" [{"id" 2001 "login" "forgejo"  "email" "forgejo@codeberg.local"}
               {"id" 2002 "login" "berguser" "email" "berguser@codeberg.local"}]})

(defn- user-path
  "The provider's userinfo endpoint (GitHub- vs Codeberg/Gitea-shaped)."
  [provider]
  (case provider
    "github"   "/github/user"
    "codeberg" "/codeberg/api/v1/user"
    (str "/" provider "/user")))

(defn- index-text [users]
  (str "mock OAuth2 — accounts:\n"
       (str/join (for [u users] (str "  - " (get u "login") " (" (get u "email") ")\n")))))

;; --- kit: oauth2 (snooker's mockoauth) ------------------------------------

(defn oauth2
  "An OAuth2 provider twin: account picker, single-use codes, token table, userinfo.
   Composed from the OIDC handshake gene."
  [scenario provider users]
  (let [users (or (seq users) (get default-users provider) (get default-users "github"))
        authz (str "/" provider "/login/oauth/authorize")
        token (str "/" provider "/login/oauth/access_token")]
    (compose
     {"service"  (or (first (str/split scenario #"/")) "oauth")
      "scenario" scenario
      "origin"   "kit"
      "summary"  (str provider " OAuth2 provider (mock) — picker, single-use codes, userinfo")
      "seed"     {"users" users}}
     (oidc-handshake {:authz authz :token token :userinfo (user-path provider)
                      :seed-name "users" :users users})
     {:operations
      [{"id"      "index"
        "summary" "account index"
        "request" {"method" "GET" "path" "/"}
        "responses"
        [{"status"  200
          "headers" {"Content-Type" "text/plain; charset=utf-8"}
          "examples" {"index" {"select" true "body" (index-text users)}}}]}]})))

;; --- kit: okta (users + groups CRUD twin + OIDC) --------------------------

(def ^:private okta-users
  [{"id" "00u1" "status" "ACTIVE"
    "profile" {"login" "alice@example.com" "email" "alice@example.com" "firstName" "Alice" "lastName" "Ng"}}
   {"id" "00u2" "status" "ACTIVE"
    "profile" {"login" "bob@example.com" "email" "bob@example.com" "firstName" "Bob" "lastName" "Lee"}}
   {"id" "00u3" "status" "STAGED"
    "profile" {"login" "carol@example.com" "email" "carol@example.com" "firstName" "Carol" "lastName" "Diaz"}}])

(def ^:private okta-groups
  [{"id" "00g1" "type" "OKTA_GROUP" "profile" {"name" "Everyone" "description" "All users"}}
   {"id" "00g2" "type" "OKTA_GROUP" "profile" {"name" "Admins" "description" "Administrators"}}])

;; OIDC users carry a bare `login` (oidc resolve looks it up there), derived from
;; the same accounts so the login flow and the SCIM users agree at seed time.
(defn- okta-oidc-users [users]
  (mapv (fn [u] {"id" (get u "id")
                 "login" (get-in u ["profile" "login"])
                 "email" (get-in u ["profile" "email"])
                 "name" (str/trim (str (get-in u ["profile" "firstName"]) " "
                                       (get-in u ["profile" "lastName"])))})
        users))

(defn okta
  "A digital twin of Okta: users + groups CRUD (SCIM-ish filter + pagination), the
   Okta error envelope on misses, and an OIDC authorize/token/userinfo flow — all on
   one stateful imposter. Composed entirely from genes.
     :rate-limit {:max N :window S}  rate-limit the CRUD ops (429 + X-Rate-Limit-*)
     :token-ttl  S                   OIDC tokens expire after S seconds (virtual clock)"
  [scenario users {:keys [rate-limit token-ttl]}]
  (let [users  (or (seq users) okta-users)
        groups okta-groups
        clock? (or rate-limit token-ttl)]
    (cond->
     (compose
      {"service"  (or (first (str/split scenario #"/")) "okta")
       "scenario" scenario
       "origin"   "kit"
       "summary"  "Okta (mock) — users & groups CRUD + OIDC; SCIM-ish filter, pagination, error envelope"
       "seed"     {"users" users "groups" groups "oidc_users" (okta-oidc-users users)}}
      (crud-collection {:coll "users" :base "/api/v1/users" :seed-name "users"
                        :id-prefix "00u" :create-defaults {"status" "STAGED"} :rate-limit rate-limit})
      (crud-collection {:coll "groups" :base "/api/v1/groups" :seed-name "groups"
                        :id-prefix "00g" :create-defaults {"type" "OKTA_GROUP"} :rate-limit rate-limit})
      (oidc-handshake {:authz "/oauth2/v1/authorize" :token "/oauth2/v1/token"
                       :userinfo "/oauth2/v1/userinfo" :seed-name "oidc_users"
                       :users (okta-oidc-users users) :token-ttl token-ttl}))
      ;; enabling the clock makes `mocksys clock` available and ttl/limit windows move
      clock? (assoc-in ["state" "clock"] {"start" 1700000000}))))

;; --- registry -------------------------------------------------------------

(def kits
  "name -> {:doc :build}. Each builder takes [scenario opts] and returns a contract."
  {"oauth2" {:doc "OAuth2 provider (picker, single-use codes, userinfo)"
             :build (fn [scenario {:keys [provider users]}]
                      (oauth2 scenario (or provider "github") users))}
   "okta"   {:doc "Okta twin: users & groups CRUD + OIDC, SCIM filter & pagination [--rate-limit N] [--token-ttl S]"
             :build (fn [scenario {:keys [users rate-limit token-ttl]}]
                      (okta scenario users {:rate-limit rate-limit :token-ttl token-ttl}))}})

(def genes
  "The reusable behavioral genes a kit composes — shown by `mocksys genes`."
  [{:name "crud-collection" :doc "REST resource: create/list/get/update/delete, SCIM-ish filter + cursor pagination"}
   {:name "oidc-handshake"  :doc "OAuth2/OIDC: authorize (picker + single-use code), token exchange, bearer userinfo"}
   {:name "error-envelope"  :doc "Provider-shaped error bodies for misses (e.g. Okta errorCode/errorSummary)"}
   {:name "rate-limit"      :doc "Per-client request budget over a virtual-clock window → 429 + X-Rate-Limit-* headers"}
   {:name "virtual-clock"   :doc "Deterministic, advanceable time (store.ttl expiry, now bind); driven by `mocksys clock`"}])
