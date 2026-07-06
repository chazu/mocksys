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

(defn- live-picker-row
  "A picker link over a live collection row: preserves the inbound
   client_id/redirect_uri/state (bound vars) and carries the row's login + id, so
   the subsequent authorize needs no lookup."
  [authz login-field]
  (str "<a href=\"" authz "?client_id={cid}&redirect_uri={ruri}&state={st}"
       "&login={" login-field "}&uid={id}\">"
       "<b>{" login-field "}</b> <small>({profile.email})</small></a>"))

(def ^:private picker-wrap
  "The styled shell the rendered rows are framed in ({rows} = the joined links)."
  (str "<!doctype html><meta charset=utf-8><title>mock login</title>"
       "<style>body{font-family:system-ui;max-width:32rem;margin:4rem auto;padding:0 1rem}"
       "a{display:block;padding:.75rem 1rem;margin:.5rem 0;border:1px solid #ccc;"
       "border-radius:.5rem;text-decoration:none;color:#111}a:hover{background:#f3f3f3}"
       "small{color:#666}</style>"
       "<h2>mock login</h2><p><small>Authorize as:</small></p>{rows}"))

(defn- oidc-seed-ops
  "The seed-array handshake (oauth2): static picker, code/token buckets carry the
   login, userinfo resolves the seed user. Unchanged from the original gene."
  [{:keys [authz token userinfo seed-name users token-ttl]}]
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
               "respond" {"status" 200 "jsonVar" "user"}}}])

(defn- oidc-live-ops
  "The live-collection handshake (okta): a `render` picker over the mutable
   collection, a uid carried through the code→token buckets, and userinfo shaped by
   `find`+dotted `respond`. `active-only?` gates issuance and the picker to
   `status eq \"ACTIVE\"` (Q7). `claims` is the (dotted) userinfo claim template."
  [{:keys [authz token userinfo token-ttl user-source]}]
  (let [{:keys [collection login-field active-only? claims]} user-source
        login-field (or login-field "profile.login")
        active-flt  (when active-only? "status eq \"ACTIVE\"")
        claims      (or claims {"sub" "{user.id}"})]
    [{"id"      "authorize"
      "summary" "authorize (?login given) -> code; issuance gated to the live user"
      "request" {"method" "GET" "path" authz "query" {"login" {"present" true}}}
      "effect"  {"bind"    {"login" {"query" "login"} "uid" {"query" "uid"}
                            "state" {"query" "state"} "ruri" {"query" "redirect_uri"}}
                 ;; presence + (optional) ACTIVE gate on the live row before minting
                 "find"    (cond-> {"collection" collection "key" "{uid}" "as" "_u"
                                    "miss" {"status" 403 "json" (okta-error "E0000038" "User is not active or does not exist")}}
                             active-flt (assoc "filter" active-flt))
                 "mint"    {"code" {"prefix" "code_"}}
                 "store"   {"bucket" "codes" "key" "{code}" "value" "{uid}"}
                 "respond" {"status" 302 "headers" {"Location" "{ruri}?code={code}&state={state}"}}}}
     {"id"      "authorize-picker"
      "summary" "authorize (no ?login) -> live account picker (render)"
      "request" {"method" "GET" "path" authz}
      "effect"  {"bind"   {"cid" {"query" "client_id"} "ruri" {"query" "redirect_uri"} "st" {"query" "state"}}
                 "render" (cond-> {"collection" collection
                                   "row"  (live-picker-row authz login-field)
                                   "wrap" picker-wrap}
                            active-flt (assoc "filter" active-flt))}}
     {"id"      "access-token"
      "summary" "code -> access_token (code is single-use); carries the uid"
      "request" {"method" "POST" "path" token}
      "effect"  {"bind"    {"code" {"body" "code"} "scope" {"body" "scope"}}
                 "consume" {"bucket" "codes" "key" "{code}" "as" "uid"
                            "miss"  {"status" 400 "json" {"error" "bad_verification_code"}}}
                 "mint"    {"token" {"prefix" "mockoauth_"}}
                 "store"   (cond-> {"bucket" "tokens" "key" "{token}" "value" "{uid}"}
                             token-ttl (assoc "ttl" token-ttl))
                 "respond" {"status" 200
                            "json"  (cond-> {"access_token" "{token}" "token_type" "bearer" "scope" "{scope}"}
                                      token-ttl (assoc "expires_in" token-ttl))}}}
     {"id"      "userinfo"
      "summary" "bearer access_token -> OIDC claims from the live user row"
      "request" {"method" "GET" "path" userinfo}
      "effect"  {"bind"    {"auth" {"header" "authorization" "bearer" true}}
                 "lookup"  {"bucket" "tokens" "key" "{auth}" "as" "uid"
                            "miss"  {"status" 401 "json" (okta-error "E0000011" "Invalid token provided")}}
                 ;; find by the carried uid — a deleted user misses → 401
                 "find"    {"collection" collection "key" "{uid}" "as" "user"
                            "miss"  {"status" 401 "json" (okta-error "E0000011" "Invalid token provided")}}
                 "respond" {"status" 200 "json" claims}}}]))

(defn oidc-handshake
  "OIDC genes over `authz`/`token`/`userinfo` paths. `:user-source` forks the source:
     {:seed <name>}                            → seed-array resolve (oauth2, default)
     {:collection <c> :login-field <p> :active-only? bool :claims <tpl>}
                                               → live mutable collection (okta)
   Returns authorize(+picker), token, userinfo. With `:token-ttl` (seconds), issued
   tokens expire on the virtual clock."
  [{:keys [user-source] :as opts}]
  {:operations
   (if (:collection user-source)
     (oidc-live-ops opts)
     (oidc-seed-ops opts))})

;; --- gene: group membership (many-to-many relation) -----------------------
;; A membership is its own collection whose rows carry a composite id `{gid}:{uid}`.
;; assign/unassign guard that both sides exist (else 404); the two list ops hydrate
;; the far side of the relation and cursor-paginate. Reusable across any twin with
;; two collections to relate — parameterize the collections, base paths, and 404.

(defn membership
  "Four operations + a relation collection joining `users-coll` and `groups-coll`:
     PUT    {groups-base}/{gid}/users/{uid}   assign   (idempotent, 204)
     DELETE {groups-base}/{gid}/users/{uid}   unassign (miss-tolerant, 204)
     GET    {groups-base}/{gid}/users         list a group's users  -> [User]
     GET    {users-base}/{uid}/groups         list a user's groups  -> [Group]
   assign/unassign FK-guard both ids (404 on a bad group/user); the list ops guard
   the parent id. `opts`: :coll :users-coll :groups-coll :users-base :groups-base
   :not-found."
  [{:keys [coll users-coll groups-coll users-base groups-base not-found]}]
  (let [coll   (or coll "memberships")
        ucoll  (or users-coll "users")
        gcoll  (or groups-coll "groups")
        ubase  (or users-base "/api/v1/users")
        gbase  (or groups-base "/api/v1/groups")
        nf     (or not-found {"status" 404 "json" (okta-error "E0000007" "Not found: Resource not found")})
        pair   (str gbase "/{gid}/users/{uid}")
        gid-re (str gbase "/([^/]+)/users/[^/]+$")   ; gid on the assign/unassign path
        uid-re "/users/([^/]+)$"                       ; uid = last segment
        guard  [{"collection" gcoll "key" "{gid}" "as" "_g" "miss" nf}
                {"collection" ucoll "key" "{uid}" "as" "_u" "miss" nf}]]
    {:collections {coll {"idField" "id" "seed" coll}}
     :operations
     [{"id"      "assign-membership"
       "summary" "assign a user to a group (idempotent)"
       "request" {"method" "PUT" "path" pair}
       "effect"  {"bind" {"gid" {"pathRegex" gid-re} "uid" {"pathRegex" uid-re}}
                  "find" guard
                  "link" {"collection" coll "id" "{gid}:{uid}"
                          "row" {"gid" "{gid}" "uid" "{uid}"} "status" 204}}}
      {"id"      "unassign-membership"
       "summary" "remove a user from a group (204 even if not a member)"
       "request" {"method" "DELETE" "path" pair}
       "effect"  {"bind"   {"gid" {"pathRegex" gid-re} "uid" {"pathRegex" uid-re}}
                  "find"   guard
                  "unlink" {"collection" coll "id" "{gid}:{uid}" "status" 204}}}
      {"id"      "list-group-users"
       "summary" "list the users in a group"
       "request" {"method" "GET" "path" (str gbase "/{gid}/users")}
       "effect"  {"bind" {"gid" {"pathRegex" (str gbase "/([^/]+)/users$")}
                          "after" {"query" "after"} "lim" {"query" "limit"}}
                  "find" {"collection" gcoll "key" "{gid}" "as" "_g" "miss" nf}
                  "listJoin" {"via" coll "byField" "gid" "byValue" "{gid}"
                              "hydrate" ucoll "hydrateVia" "uid"
                              "after" "{after}" "limit" "{lim}"
                              "link" (str "<" gbase "/{gid}/users?after={next}>; rel=\"next\"")}}}
      {"id"      "list-user-groups"
       "summary" "list the groups a user belongs to"
       "request" {"method" "GET" "path" (str ubase "/{uid}/groups")}
       "effect"  {"bind" {"uid" {"pathRegex" (str ubase "/([^/]+)/groups$")}
                          "after" {"query" "after"} "lim" {"query" "limit"}}
                  "find" {"collection" ucoll "key" "{uid}" "as" "_u" "miss" nf}
                  "listJoin" {"via" coll "byField" "uid" "byValue" "{uid}"
                              "hydrate" gcoll "hydrateVia" "gid"
                              "after" "{after}" "limit" "{lim}"
                              "link" (str "<" ubase "/{uid}/groups?after={next}>; rel=\"next\"")}}}]}))

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

;; OIDC userinfo claims (dotted templates read the found live `users` row). Access
;; token `sub` is the Okta user id; the profile/email scope claims mirror the row.
(def ^:private okta-userinfo-claims
  {"sub"                "{user.id}"
   "name"               "{user.profile.firstName} {user.profile.lastName}"
   "given_name"         "{user.profile.firstName}"
   "family_name"        "{user.profile.lastName}"
   "preferred_username" "{user.profile.login}"
   "email"              "{user.profile.email}"
   "email_verified"     true})

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
       "summary"  "Okta (mock) — users & groups CRUD + OIDC + group membership; SCIM-ish filter, pagination, error envelope"
       "seed"     {"users" users "groups" groups "memberships" []}}
      (crud-collection {:coll "users" :base "/api/v1/users" :seed-name "users"
                        :id-prefix "00u" :create-defaults {"status" "STAGED"} :rate-limit rate-limit})
      (crud-collection {:coll "groups" :base "/api/v1/groups" :seed-name "groups"
                        :id-prefix "00g" :create-defaults {"type" "OKTA_GROUP"} :rate-limit rate-limit})
      (membership {:coll "memberships" :users-coll "users" :groups-coll "groups"
                   :users-base "/api/v1/users" :groups-base "/api/v1/groups"})
      ;; OIDC over the LIVE users collection: the picker, login→uid, and userinfo all
      ;; read the same mutable collection the CRUD ops write, so SCIM-created users can
      ;; log in. Issuance + picker gate to status==ACTIVE (Q7).
      (oidc-handshake {:authz "/oauth2/v1/authorize" :token "/oauth2/v1/token"
                       :userinfo "/oauth2/v1/userinfo" :token-ttl token-ttl
                       :user-source {:collection "users" :login-field "profile.login"
                                     :active-only? true :claims okta-userinfo-claims}}))
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
   {:name "membership"      :doc "Group membership: assign/unassign (idempotent, FK-guarded) + list either side of the relation, hydrated + paginated"}
   {:name "error-envelope"  :doc "Provider-shaped error bodies for misses (e.g. Okta errorCode/errorSummary)"}
   {:name "rate-limit"      :doc "Per-client request budget over a virtual-clock window → 429 + X-Rate-Limit-* headers"}
   {:name "virtual-clock"   :doc "Deterministic, advanceable time (store.ttl expiry, now bind); driven by `mocksys clock`"}])
