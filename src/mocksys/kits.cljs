(ns mocksys.kits
  "Kits & genes: the agent-facing way to stand up a sophisticated (often *stateful*)
  mock — a digital twin of a real service — without authoring it operation by
  operation.

  A *gene* is a small, parameterized contract fragment ({:operations :collections
  :seed}) — a proven behavioral pattern: an OIDC authorize/token/userinfo handshake,
  a REST resource with CRUD+filter+pagination, a provider error envelope. A *kit*
  composes genes into a complete, runnable contract. Most kits are YAML templates
  (built in, or in $MOCKSYS_HOME/kits); a kit that must compute at build time is a
  builder function.

  This is `gene transfusion` made concrete: a new twin is *composed from existing
  genes* rather than written from scratch. `oauth2` reproduces snooker's mockoauth;
  `okta` composes a users + groups CRUD twin with the Okta error envelope and an
  OIDC token flow — a digital twin of Okta in one command."
  (:require [clojure.string :as str]
            [shadow.resource :as rc]
            ["crypto" :as crypto]
            ["js-yaml" :as yaml]))

;; --- composition ----------------------------------------------------------

(defn compose
  "Merge gene fragments into one contract. Each fragment may carry :operations
   (concatenated, in order), :collections and :seed. Earlier wins throughout, as
   Mountebank serves the first matching stub: `base` keeps its own collections and
   seed arrays over a fragment's of the same name."
  [base & fragments]
  (reduce (fn [c {:keys [operations collections seed]}]
            (cond-> c
              (seq operations)   (update "operations" (fnil into []) operations)
              (seq collections)  (update-in ["state" "collections"] #(merge collections %))
              (seq seed)         (update "seed" #(merge seed %))))
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
   `opts`: :coll :base :id-field :seed-name :id-prefix :create-defaults :not-found
   :invalid-filter :rate-limit {\"max\" N \"window\" S}."
  [{:keys [coll base id-field seed-name id-prefix create-defaults not-found invalid-filter rate-limit]}]
  (let [id-field (or id-field "id")
        id-regex (str base "/([^/]+)$")
        item     (str base "/{id}")
        nf       (or not-found {"status" 404 "json" (okta-error "E0000007" "Not found: Resource not found")})
        bad-flt  (or invalid-filter {"status" 400 "json" (okta-error "E0000031" "Invalid search criteria.")})
        ;; rate-limit gene: when enabled, every op binds a client key (the bearer
        ;; token, else "anon") and guards with a clock-windowed `limit`.
        rl-bind  (when rate-limit {"__cli" {"header" "authorization" "bearer" true}})
        rl       (when rate-limit
                   {"bucket" "rl" "key" "{__cli}"
                    "max"    (get rate-limit "max") "window" (or (get rate-limit "window") 60)})
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
                               "link"       (str "<" base "?after={next}>; rel=\"next\"")
                               "invalid"    bad-flt}})}
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
   `find`+dotted `respond`. `active-only` gates issuance and the picker to
   `status eq \"ACTIVE\"` (Q7). `claims` is the (dotted) userinfo claim template."
  [{:keys [authz token userinfo token-ttl user-source jwt]}]
  (let [{:strs [collection login-field active-only claims]} user-source
        login-field (or login-field "profile.login")
        active-flt  (when active-only "status eq \"ACTIVE\"")
        claims      (or claims {"sub" "{user.id}"})
        ;; the access-token op: opaque bucket token, or a signed RS256 JWT pair when
        ;; the kit supplies a `:jwt` config (private PEM + kid + claim templates).
        access-op
        (if jwt
          (let [{:keys [private-pem kid access-claims id-claims ttl]} jwt
                hdr {"alg" "RS256" "kid" kid "typ" "JWT"}]
            {"id"      "access-token"
             "summary" "code -> signed access_token + id_token JWTs (RS256)"
             "request" {"method" "POST" "path" token}
             "effect"  {"bind"    {"code" {"body" "code"} "scope" {"body" "scope"}
                                   "client_id" {"body" "client_id"}}
                        "consume" {"bucket" "codes" "key" "{code}" "as" "uid"
                                   "miss"  {"status" 400 "json" {"error" "bad_verification_code"}}}
                        ;; the JWT sub/email/name come off the live row → find it
                        "find"    {"collection" collection "key" "{uid}" "as" "user"
                                   "miss" {"status" 400 "json" {"error" "invalid_grant"}}}
                        "mint"    {"jti" {"prefix" "AT."} "idjti" {"prefix" "ID."}}
                        "sign"    [{"as" "access_token" "key" private-pem "header" hdr "claims" access-claims}
                                   {"as" "id_token" "key" private-pem "header" hdr "claims" id-claims}]
                        ;; bucket stays the internal source of truth for userinfo, keyed
                        ;; on the JWT and TTL'd so bearer lookups expire on the clock
                        "store"   {"bucket" "tokens" "key" "{access_token}" "value" "{uid}" "ttl" ttl}
                        "respond" {"status" 200
                                   "json"  {"access_token" "{access_token}" "id_token" "{id_token}"
                                            "token_type" "Bearer" "expires_in" ttl "scope" "{scope}"}}}})
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
                                           token-ttl (assoc "expires_in" token-ttl))}}})]
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
     access-op
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
  "OIDC genes over `authz`/`token`/`userinfo` paths. Users come from the seed array
   `:seed-name` (oauth2, default; `:users` feeds the static picker), or from a live
   collection when `:user-source` is
     {\"collection\" c \"login-field\" p \"active-only\" bool \"claims\" tpl}  (okta)
   Returns authorize(+picker), token, userinfo. With `:token-ttl` (seconds), issued
   tokens expire on the virtual clock. `:jwt` (live source only) signs RS256 tokens;
   it carries a private key made at kit time, so only code can supply it."
  [{:keys [user-source] :as opts}]
  {:operations
   (if (get user-source "collection")
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

;; --- gene: oauth2 provider (snooker's mockoauth) ---------------------------

(defn oauth2-provider
  "A GitHub- or Codeberg-shaped OAuth2 provider: the seed-array OIDC handshake at
   /{provider}/login/oauth/..., the provider's userinfo path, its default accounts
   (or `:users`) as the `users` seed, and an account index at /. With `:token-ttl`
   (seconds), issued tokens expire on the virtual clock."
  [{:keys [provider users token-ttl]}]
  (let [provider (or provider "github")
        users    (if (seq users) users (get default-users provider (get default-users "github")))]
    {:seed {"users" users}
     :operations
     (conj (:operations
            (oidc-handshake {:authz     (str "/" provider "/login/oauth/authorize")
                             :token     (str "/" provider "/login/oauth/access_token")
                             :userinfo  (user-path provider)
                             :seed-name "users" :users users :token-ttl token-ttl}))
           {"id"      "index"
            "summary" "account index"
            "request" {"method" "GET" "path" "/"}
            "responses"
            [{"status"  200
              "headers" {"Content-Type" "text/plain; charset=utf-8"}
              "examples" {"index" {"select" true "body" (index-text users)}}}]})}))

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

;; --- JWT: keypair at build time, JWKS + discovery, claim templates ---------

(defn- b64url [buf]
  (-> (.toString buf "base64") (str/replace "+" "-") (str/replace "/" "_") (str/replace #"=+$" "")))

(defn- jwt-config
  "Generate an RSA-2048 keypair once at build time (or load `signing-key` PEM for a
   pinned, cross-invocation-stable key), returning the private PEM for `sign`, a
   stable thumbprint `kid`, and the published JWK (n/e). Deterministic within one
   built contract — the same JWKS verifies every run of that contract."
  [signing-key]
  (let [priv (if signing-key
               (crypto/createPrivateKey signing-key)
               (.-privateKey (crypto/generateKeyPairSync "rsa" #js {:modulusLength 2048})))
        pub  (crypto/createPublicKey priv)
        jwk  (js->clj (.export pub #js {:format "jwk"}))            ; {kty n e}
        der  (.export pub #js {:type "spki" :format "der"})
        kid  (b64url (-> (.createHash crypto "sha256") (.update der) (.digest)))]
    {:private-pem (.export priv #js {:type "pkcs8" :format "pem"})
     :kid  kid
     :jwk  (assoc jwk "alg" "RS256" "use" "sig" "kid" kid)}))

(defn- okta-access-claims
  "Org-server access-token claims. sub=login (NOT the id), aud=org URL (NOT client_id),
   cid=client_id, uid=user id, scp=scope array."
  [ttl]
  {"ver" 1 "jti" "{jti}"
   "iss" {"baseUrl" true} "aud" {"baseUrl" true}
   "sub" "{user.profile.login}" "cid" "{client_id}" "uid" "{uid}"
   "scp" {"split" "{scope}" "on" " "}
   "iat" {"now" true} "exp" {"now" {"add" ttl}}})

(defn- okta-id-claims
  "ID-token claims — the mirror of the access token: aud=client_id, sub=uid, plus the
   profile/email scope claims and amr/auth_time."
  [ttl]
  {"ver" 1 "jti" "{idjti}"
   "iss" {"baseUrl" true} "sub" "{uid}" "aud" "{client_id}"
   "iat" {"now" true} "exp" {"now" {"add" ttl}} "auth_time" {"now" true} "amr" ["pwd"]
   "email" "{user.profile.email}" "email_verified" true
   "name" "{user.profile.firstName} {user.profile.lastName}"
   "preferred_username" "{user.profile.login}"})

(defn- jwks-op
  "GET /oauth2/v1/keys — the published JWKS (a static literal from the built key)."
  [jwk]
  {"id" "jwks"
   "summary" "JWKS — the twin's RS256 public signing key"
   "request" {"method" "GET" "path" "/oauth2/v1/keys"}
   "responses" [{"status" 200 "headers" {"Content-Type" "application/json"}
                 "examples" {"jwks" {"select" true "body" {"keys" [jwk]}}}}]})

(defn- discovery-body [path-issuer]
  ;; host-templated so the doc reflects the requesting origin (mb copy behavior on
  ;; {{request.headers.host}}); matches the JWT `iss` (baseUrl = http://{Host}).
  (let [base (str path-issuer "{{request.headers.host}}")]
    {"issuer"                                 base
     "authorization_endpoint"                 (str base "/oauth2/v1/authorize")
     "token_endpoint"                         (str base "/oauth2/v1/token")
     "userinfo_endpoint"                      (str base "/oauth2/v1/userinfo")
     "jwks_uri"                               (str base "/oauth2/v1/keys")
     "response_types_supported"               ["code" "id_token" "code id_token" "token" "token id_token"]
     "grant_types_supported"                  ["authorization_code" "implicit" "refresh_token"]
     "subject_types_supported"                ["public"]
     "id_token_signing_alg_values_supported"  ["RS256"]
     "scopes_supported"                       ["openid" "profile" "email"]
     "token_endpoint_auth_methods_supported"  ["client_secret_basic" "none"]
     "claims_supported"                       ["ver" "jti" "iss" "aud" "sub" "iat" "exp" "cid" "uid" "scp"
                                               "email" "email_verified" "name" "preferred_username"]}))

(defn- discovery-op [id path body]
  {"id" id
   "summary" (str "OIDC discovery — " path)
   "request" {"method" "GET" "path" path}
   "responses" [{"status" 200 "headers" {"Content-Type" "application/json"}
                 "examples" {"cfg" {"select" true "body" body}}}]})

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
     :rate-limit {\"max\" N \"window\" S}  rate-limit the CRUD ops (429 + X-Rate-Limit-*)
     :token-ttl  S                   OIDC tokens expire after S seconds (virtual clock)
   Stays in code rather than a YAML kit: each build generates an RSA keypair (or
   reads --signing-key) and publishes the derived JWK and kid in the JWKS op and the
   signed-token op. A YAML kit only substitutes flag values into a fixed template,
   so it has no step that can run code at kit time."
  [scenario users {:keys [rate-limit token-ttl issuer signing-key]}]
  (let [users  (or (seq users) okta-users)
        groups okta-groups
        ttl    (or token-ttl 3600)
        cfg    (jwt-config signing-key)
        jwt    {:private-pem (:private-pem cfg) :kid (:kid cfg) :ttl ttl
                :access-claims (okta-access-claims ttl) :id-claims (okta-id-claims ttl)}]
    (cond->
     (compose
      (cond-> {"service"  (or (first (str/split scenario #"/")) "okta")
               "scenario" scenario
               "origin"   "kit"
               "summary"  "Okta (mock) — users & groups CRUD + OIDC (signed JWTs, JWKS, discovery) + group membership"
               ;; OIDC signs JWTs (iat/exp on the clock), so the twin always carries one
               "state"    {"clock" {"start" 1700000000}}
               "seed"     {"users" users "groups" groups "memberships" []}}
        issuer (assoc "issuer" issuer))
      (crud-collection {:coll "users" :base "/api/v1/users" :seed-name "users"
                        :id-prefix "00u" :create-defaults {"status" "STAGED"} :rate-limit rate-limit})
      (crud-collection {:coll "groups" :base "/api/v1/groups" :seed-name "groups"
                        :id-prefix "00g" :create-defaults {"type" "OKTA_GROUP"} :rate-limit rate-limit})
      (membership {:coll "memberships" :users-coll "users" :groups-coll "groups"
                   :users-base "/api/v1/users" :groups-base "/api/v1/groups"})
      ;; OIDC over the LIVE users collection, minting signed RS256 JWTs. The picker,
      ;; login→uid, and userinfo all read the same mutable collection the CRUD ops
      ;; write, so SCIM-created users can log in. Issuance + picker gate to ACTIVE (Q7).
      (oidc-handshake {:authz "/oauth2/v1/authorize" :token "/oauth2/v1/token"
                       :userinfo "/oauth2/v1/userinfo" :token-ttl token-ttl :jwt jwt
                       :user-source {"collection" "users" "login-field" "profile.login"
                                     "active-only" true "claims" okta-userinfo-claims}})
      ;; JWKS + discovery so third-party validators can verify the twin's tokens
      {:operations [(jwks-op (:jwk cfg))
                    (discovery-op "openid-config" "/.well-known/openid-configuration"
                                  (discovery-body "http://"))
                    (discovery-op "oauth-authz-server" "/.well-known/oauth-authorization-server"
                                  (discovery-body "http://"))]}))))

;; --- gene registry ------------------------------------------------------------

(def param-shapes
  "shape -> predicate: what a gene param's value must be when it is set."
  {:string string?
   :number number?
   :map    map?
   :list   sequential?})

(def genes
  "name -> {:doc :params :required :build}: the genes a contract may list under
   `genes:`. `:params` is [[option shape doc] ...] in display order, each shape a
   key of `param-shapes`. `:build` takes the options keyword-keyed, values as
   written in YAML, and returns a fragment."
  {"crud-collection"
   {:doc "REST resource: create/list/get/update/delete, SCIM filter + cursor pagination"
    :params [[:coll :string "collection name; also names the ops (list-<coll>, ...)"]
             [:base :string "resource path, e.g. /api/v1/users"]
             [:seed-name :string "seed array the collection starts from"]
             [:id-field :string "row id field (default id)"]
             [:id-prefix :string "prefix for minted ids (default none)"]
             [:create-defaults :map "fields a created row gets unless the body sets them"]
             [:not-found :map "miss response {status, json} (default Okta E0000007)"]
             [:invalid-filter :map "bad-filter response {status, json} (default Okta E0000031)"]
             [:rate-limit :map "{max, window}: per-client budget per window seconds (default 60), then 429 + X-Rate-Limit-*"]]
    :required #{:coll :base}
    :build crud-collection}
   "oidc-handshake"
   {:doc "OAuth2/OIDC: authorize (picker + single-use code), token exchange, bearer userinfo"
    :params [[:authz :string "authorize path"]
             [:token :string "token path"]
             [:userinfo :string "userinfo path"]
             [:seed-name :string "seed array userinfo resolves logins in (seed source)"]
             [:users :list "accounts the static picker lists (seed source)"]
             [:token-ttl :number "seconds until an issued token expires on the virtual clock"]
             [:user-source :map "{collection, login-field, active-only, claims}: read users from a live collection instead"]]
    :required #{:authz :token :userinfo}
    :build oidc-handshake}
   "membership"
   {:doc "Many-to-many membership: assign/unassign (idempotent, FK-guarded) + list either side, hydrated + paginated"
    :params [[:coll :string "relation collection (default memberships)"]
             [:users-coll :string "member collection (default users)"]
             [:groups-coll :string "group collection (default groups)"]
             [:users-base :string "member resource path (default /api/v1/users)"]
             [:groups-base :string "group resource path (default /api/v1/groups)"]
             [:not-found :map "miss response {status, json} (default Okta E0000007)"]]
    :required #{}
    :build membership}
   "oauth2-provider"
   {:doc "GitHub/Codeberg-shaped OAuth2 provider: oidc-handshake over seed accounts + an account index at /"
    :params [[:provider :string "github | codeberg; another name gets /<name>/... paths (default github)"]
             [:users :list "accounts to seed and list (default: the provider's)"]
             [:token-ttl :number "seconds until an issued token expires on the virtual clock"]]
    :required #{}
    :build oauth2-provider}})

(def capabilities
  "Behavior that is not a standalone gene, and where it lives instead."
  [["rate-limit"     "crud-collection's rate-limit param, or the `limit` effect verb"]
   ["error-envelope" "the default not-found / invalid-filter of crud-collection and membership (Okta-shaped)"]
   ["virtual-clock"  "contract `state.clock`, or implied by store.ttl, limit, sign or a `now` bind; `mocksys clock` moves it"]
   ["jwt-signing"    "the `sign` effect verb; `kit okta` wires it with a key generated at kit time"]])

;; --- kits: YAML templates and code -------------------------------------------

(defn- substitute
  "Replace every string that is exactly \"{flag}\" with that flag's value, keeping
   the value's type. Any other string is left alone, including effect templates
   such as \"{token}\" that name no flag."
  [x vals]
  (cond
    (string? x) (if-let [[_ flag] (re-matches #"\{([^{}]+)\}" x)]
                  (let [k (keyword flag)] (if (contains? vals k) (get vals k) x))
                  x)
    (map? x)    (update-vals x #(substitute % vals))
    (vector? x) (mapv #(substitute % vals) x)
    :else       x))

(defn parse-kit
  "A kit from YAML `text`: `name` (else `default-name`), `doc`, `flags` (flag ->
   default) and a `contract` template. `source` records where it came from.
   Throws when the shape is wrong."
  [text source default-name]
  (let [m        (js->clj (yaml/load text))
        flags    (get m "flags")
        template (get m "contract")]
    (when-not (map? m) (throw (js/Error. "a kit is a YAML map with name, doc, flags and contract")))
    (when-not (or (nil? flags) (map? flags)) (throw (js/Error. "kit `flags` must map each flag to its default")))
    (when-not (map? template) (throw (js/Error. "a kit needs a `contract` map (the contract template)")))
    (let [defaults (update-keys (or flags {}) keyword)
          doc      (str (get m "doc" ""))]
      {:name     (str (get m "name" default-name))
       :doc      doc
       :source   source
       :flags    (set (keys defaults))
       :defaults defaults
       :build    (fn [scenario opts]
                   (let [given (into {} (filter (comp some? val)) (select-keys opts (keys defaults)))]
                     (-> (substitute template (merge defaults given))
                         (assoc "service" (first (str/split scenario #"/"))
                                "scenario" scenario
                                "origin" "kit")
                         (update "summary" #(or % doc)))))})))

(def ^:private builtin-yaml
  "Built-in YAML kits, inlined at build time: the bun binary cannot read repo files."
  {"oauth2" (rc/inline "./kits/oauth2.yaml")})

(def builtin-kits
  "name -> kit. A kit is {:name :doc :source :flags :build}, plus :defaults for a
   YAML kit. `:flags` are the `kit` options it honors; `:build` takes [scenario
   opts] and returns the contract to write."
  (merge
   {"okta" {:name   "okta"
            :doc    "Okta twin: users & groups CRUD + OIDC (signed JWTs, JWKS, discovery) + membership"
            :source "built-in"
            :flags  #{:users :rate-limit :rate-window :token-ttl :issuer :signing-key}
            :build  (fn [scenario {:keys [users rate-limit rate-window token-ttl issuer signing-key]}]
                      (okta scenario users {:rate-limit (when rate-limit
                                                          (cond-> {"max" rate-limit}
                                                            rate-window (assoc "window" rate-window)))
                                            :token-ttl token-ttl
                                            :issuer issuer :signing-key signing-key}))}}
   (update-vals builtin-yaml #(parse-kit % "built-in" nil))))

(defn with-user-kits
  "The built-in kits plus the user kits in `files` ([[path text] ...], or [path nil
   reason] for a file that could not be read), as {:kits name->kit :warnings [...]}.
   A user kit named like a built-in replaces it; of two user kits with one name,
   the later file wins. Both warn. A file that cannot be read or parsed is skipped,
   with a warning."
  [files]
  (reduce (fn [acc [file text unreadable]]
            (let [skip #(update acc :warnings conj (str "skipped kit " file ": " %))]
              (if unreadable
                (skip unreadable)
                (try
                  (let [kit   (parse-kit text file (str/replace file #"^.*/|\.ya?ml$" ""))
                        n     (:name kit)
                        prior (get-in acc [:kits n :source])]
                    (cond-> (assoc-in acc [:kits n] kit)
                      (contains? builtin-kits n)
                      (update :warnings conj (str "user kit '" n "' (" file ") shadows the built-in kit"))
                      (and prior (not= prior "built-in"))
                      (update :warnings conj (str "user kit '" n "' is declared by " prior " and " file
                                                  "; using " file))))
                  (catch :default e
                    (skip (first (str/split-lines (.-message e)))))))))
          {:kits builtin-kits :warnings []}
          files))

(def ^:private numeric-flags #{:token-ttl :rate-limit :rate-window})

(defn- flag-value
  "Parse kit flag `k` from its command-line value `v`. --users and --signing-key
   read a file. Otherwise the value takes the type of the flag's default."
  [k v default slurp]
  (let [file (fn [what parse]
               (when-not (string? v) (throw (js/Error. (str "--" (name k) " needs a file"))))
               (try (parse (slurp v))
                    (catch :default _ (throw (js/Error. (str "could not read --" (name k) " as " what ": " v))))))]
    (cond
      (= k :users)         (file "a JSON array" #(js->clj (js/JSON.parse %)))
      (= k :signing-key)   (file "a PEM file" identity)
      (or (numeric-flags k) (number? default))
      (let [n (js/Number v)]
        (if (or (boolean? v) (js/isNaN n)) (throw (js/Error. (str "--" (name k) " needs a number, got " v))) n))
      (boolean? default)   (if (string? v) (= v "true") v)
      :else                v)))

(defn parse-flags
  "The kit flags in command-line `opts`, parsed. `slurp` reads a file's text.
   Throws with a message naming the flag when a value does not parse."
  [kit opts slurp]
  (into {} (for [[k v] opts :when ((:flags kit) k)]
             [k (flag-value k v (get-in kit [:defaults k]) slurp)])))

(defn unsupported-flags
  "The options in `opts` that `kit` does not declare, in sorted order. `scenario`
   and `port` belong to the `kit` command itself."
  [kit opts]
  (->> (keys opts)
       (remove (:flags kit))
       (remove #{:scenario :port})
       sort
       vec))
