(ns mocksys.inject
  "Compile a declarative `effect` spec on a contract operation into a Mountebank
  *response injection* — a JS function that runs at serve time over a per-imposter
  `state` object. This is how mocksys expresses *stateful* mocks WITHOUT the agent
  ever writing JavaScript. The agent authors effects; mocksys generates the (vetted,
  dependency-free) injection.

  Two families of effect verbs:

  HANDSHAKE verbs (flat string->string buckets — an OAuth code/token flow):
    bind:    name -> {query|header|body|bodyJson|pathRegex|const ...}  pull request values
    mint:    name -> {prefix}                          random hex id (e.g. a token)
    store:   {bucket key value|valueVar}               write a flat state-bucket entry
    consume: {bucket key as miss}                      read+DELETE an entry (single-use)
    lookup:  {bucket key as miss}                      read an entry (no delete)
    resolve: {seed by eq as miss}                      find a seed-array item by field
    respond: {status headers json|jsonVar|text}        the success response

  COLLECTION verbs (named, seedable sets of *objects* with CRUD+list — a REST
  resource like Okta users/groups). Each is *terminal*: it forms and returns its
  own response, so a CRUD endpoint is one verb.
    create: {collection merge body|bodyVar as status}  insert a row (id from body)
    get:    {collection key as miss status}            read one by id
    update: {collection key mergeVar|patch as miss}    merge-patch a row
    remove: {collection key miss status}               delete a row
    list:   {collection filter after limit link}       filtered + cursor-paginated

  Collections are declared at the contract's `state.collections` (idField + a seed
  array name) and seeded once into shared state. The filter grammar is SCIM-ish:
  `field op \"value\"` clauses joined by ` and `/` or `, ops `eq sw co pr`, dotted
  field paths (`profile.email`). Templates inside specs reference vars with `{name}`."
  (:require [clojure.string :as str]))

(defn stateful?
  "True if an operation drives a generated injection rather than a static response."
  [op]
  (some? (get op "effect")))

(defn contract-stateful?
  "True if any operation in the contract is stateful (so the imposter needs the
   Mountebank daemon launched with --allowInjection)."
  [contract]
  (boolean (some stateful? (get contract "operations"))))

(defn imposter-stateful?
  "True if a compiled imposter contains any inject response (used to decide whether
   the running daemon must allow injection)."
  [imposter]
  (boolean
   (some (fn [stub] (some #(contains? % "inject") (get stub "responses")))
         (get imposter "stubs"))))

;; --- JS emission helpers --------------------------------------------------

(defn- js [x]
  "A JS literal for a Clojure value (objects/arrays/strings/numbers)."
  (js/JSON.stringify (clj->js x)))

(defn- buckets-of
  "Every flat state bucket named by the operation's handshake effects (so we can
   pre-init them)."
  [effect]
  (->> [(get-in effect ["store" "bucket"])
        (get-in effect ["consume" "bucket"])
        (get-in effect ["lookup" "bucket"])]
       (filter some?) distinct vec))

(def ^:private collection-verbs ["create" "get" "update" "remove" "list"])

(defn- collection-verb
  "The single terminal collection verb present on an effect, or nil."
  [effect]
  (some (fn [v] (when (get effect v) v)) collection-verbs))

(defn- bind-js
  "JS that pulls one named var out of the request."
  [name spec]
  (let [v (str "V[" (js name) "]")]
    (cond
      (contains? spec "const")     (str v "=" (js (get spec "const")) ";")
      (contains? spec "query")     (str v "=qv(" (js (get spec "query")) ");")
      (contains? spec "body")      (str v "=bv(" (js (get spec "body")) ");")
      (contains? spec "bodyJson")  (str v "=bodyJson();")
      (contains? spec "pathRegex") (str v "=(String(request.path).match(new RegExp("
                                       (js (get spec "pathRegex")) "))||[])[1]||'';")
      (contains? spec "header")    (let [h (js (str/lower-case (get spec "header")))]
                                     (if (get spec "bearer")
                                       (str v "=bearer(hv(" h "));")
                                       (str v "=hv(" h ");")))
      :else (str v "='';"))))

(defn- resp-js
  "JS expression building a Mountebank response object from a respond/miss spec."
  [spec]
  (let [status  (or (get spec "status") 200)
        json?   (or (contains? spec "json") (contains? spec "jsonVar"))
        headers (cond-> (or (get spec "headers") {})
                  (and json? (not (get-in spec ["headers" "Content-Type"])))
                  (assoc "Content-Type" "application/json"))
        body    (cond
                  (contains? spec "jsonVar") (str "JSON.stringify(V[" (js (get spec "jsonVar")) "]||null)")
                  (contains? spec "json")    (str "JSON.stringify(deepInterp(" (js (get spec "json")) ",V))")
                  (contains? spec "text")    (str "interp(" (js (get spec "text")) ",V)")
                  :else "''")]
    (str "{statusCode:" status ",headers:deepInterp(" (js headers) ",V),body:" body "}")))

;; --- collection verb emission (each is terminal: forms + returns a response) ---

(defn- create-js [c]
  (let [coll   (js (get c "collection"))
        merges (for [v (get c "merge" [])] (str "V[" (js v) "]||{}"))
        body   (str "deepInterp(" (js (get c "body" {})) ",V)")
        args   (str/join "," (concat ["{}"] merges [body]))
        as     (get c "as")
        status (or (get c "status") 201)]
    (str "{var _o=Object.assign(" args ");coll(state," coll ").put(_o);"
         (when as (str "V[" (js as) "]=_o;"))
         "return collResp(" status ",_o);}")))

(defn- get-js [g]
  (let [coll   (js (get g "collection"))
        key*   (str "interp(" (js (get g "key")) ",V)")
        as     (get g "as")
        miss   (resp-js (or (get g "miss") {"status" 404}))
        status (or (get g "status") 200)]
    (str "{var _r=coll(state," coll ").get(" key* ");"
         "if(_r===undefined){return " miss ";}"
         (when as (str "V[" (js as) "]=_r;"))
         "return collResp(" status ",_r);}")))

(defn- update-js [u]
  (let [coll   (js (get u "collection"))
        key*   (str "interp(" (js (get u "key")) ",V)")
        patch  (cond
                 (get u "mergeVar") (str "(V[" (js (get u "mergeVar")) "]||{})")
                 (get u "patch")    (str "deepInterp(" (js (get u "patch")) ",V)")
                 :else "{}")
        as     (get u "as")
        miss   (resp-js (or (get u "miss") {"status" 404}))
        status (or (get u "status") 200)]
    (str "{var _c=coll(state," coll "),_r=_c.get(" key* ");"
         "if(_r===undefined){return " miss ";}"
         "var _n=merge1(_r," patch ");_c.put(_n);"
         (when as (str "V[" (js as) "]=_n;"))
         "return collResp(" status ",_n);}")))

(defn- remove-js [d]
  (let [coll   (js (get d "collection"))
        key*   (str "interp(" (js (get d "key")) ",V)")
        miss   (resp-js (or (get d "miss") {"status" 404}))
        status (or (get d "status") 204)]
    (str "{var _c=coll(state," coll "),_r=_c.get(" key* ");"
         "if(_r===undefined){return " miss ";}"
         "_c.del(" key* ");return collResp(" status ",null);}")))

(defn- list-js [l]
  (let [coll   (js (get l "collection"))
        filt   (if (get l "filter") (str "interp(" (js (get l "filter")) ",V)") "''")
        after  (if (get l "after")  (str "interp(" (js (get l "after")) ",V)") "''")
        limit  (if (get l "limit")  (str "parseInt(interp(" (js (get l "limit")) ",V),10)") "0")
        link   (get l "link")
        status (or (get l "status") 200)]
    (str "{var _p=coll(state," coll ").list({filter:" filt ",after:" after ",limit:" limit "});"
         "var _h={};"
         (when link (str "if(_p.next){_h[\"Link\"]=interp(" (js link) ",{next:_p.next});}"))
         "return collResp(" status ",_p.items,_h);}")))

(defn- collection-terminal-js [verb effect]
  (case verb
    "create" (create-js (get effect "create"))
    "get"    (get-js    (get effect "get"))
    "update" (update-js (get effect "update"))
    "remove" (remove-js (get effect "remove"))
    "list"   (list-js   (get effect "list"))))

;; --- handshake (flat-bucket) verb emission --------------------------------

(defn- effect-body-js
  "The ordered statement list (a single JS string) for one operation's effects.
   Read-then-write order: bind inputs, consume/lookup/resolve to pull values out of
   state+seed (early-returning on a miss), THEN mint fresh ids. An op ends EITHER in
   a terminal collection verb (CRUD) OR the store+respond path (a handshake)."
  [effect]
  (let [coll-verb (collection-verb effect)]
    (str/join
     (concat
      ;; bind: pull request values into vars
      (for [[n spec] (get effect "bind")] (bind-js n spec))
      ;; consume / lookup (read a bucket; consume also deletes; miss -> early return)
      (for [[op del?] [["consume" true] ["lookup" false]]
            :let [l (get effect op)]
            :when l]
        (let [bucket (str "state[" (js (get l "bucket")) "]")
              key*   (str "interp(" (js (get l "key")) ",V)")
              miss   (resp-js (or (get l "miss") {"status" 404}))]
          (str "{var _k=" key* ",_h=" bucket "[_k];"
               "if(_h===undefined){return " miss ";}"
               (when del? (str "delete " bucket "[_k];"))
               "V[" (js (get l "as")) "]=_h;}")))
      ;; resolve (find a seed-array item by field == a var; miss -> early return)
      (when-let [r (get effect "resolve")]
        (let [arr  (str "(seed[" (js (get r "seed")) "]||[])")
              by   (js (get r "by"))
              eq   (str "interp(" (js (get r "eq")) ",V)")
              miss (resp-js (or (get r "miss") {"status" 404}))]
          [(str "{var _m=" arr ".filter(function(x){return String(x[" by "])===" eq ";})[0];"
                "if(!_m){return " miss ";}"
                "V[" (js (get r "as")) "]=_m;}")]))
      ;; mint: random hex ids (after reads, so a minted id can't be clobbered)
      (for [[n spec] (get effect "mint")]
        (str "V[" (js n) "]=rnd(" (js (or (get spec "prefix") "")) ");"))
      (if coll-verb
        ;; terminal collection action (CRUD/list) — forms + returns its own response
        [(collection-terminal-js coll-verb effect)]
        (concat
         ;; store: write a flat state-bucket entry (using freshly bound/minted vars)
         (when-let [s (get effect "store")]
           (let [bucket (str "state[" (js (get s "bucket")) "]")
                 key*   (str "interp(" (js (get s "key")) ",V)")
                 val*   (if (contains? s "valueVar")
                          (str "V[" (js (get s "valueVar")) "]")
                          (str "interp(" (js (or (get s "value") "")) ",V)"))]
             [(str bucket "[" key* "]=" val* ";")]))
         ;; respond
         [(str "return " (resp-js (or (get effect "respond") {"status" 200})) ";")]))))))

(def ^:private prelude
  "Dependency-free runtime helpers shared by every generated injection."
  (str
   "function rnd(p){var s=p||'',h='0123456789abcdef';for(var i=0;i<24;i++){s+=h[Math.floor(Math.random()*16)];}return s;}"
   "function qv(k){return (request.query&&request.query[k])||'';}"
   "function hv(k){var H=request.headers||{};for(var n in H){if(n.toLowerCase()===k)return H[n];}return '';}"
   "function bearer(h){h=String(h||'');if(/^bearer /i.test(h))return h.slice(7);if(/^token /i.test(h))return h.slice(6);return h;}"
   "function bv(k){var b=request.body;if(b==null)return '';if(typeof b==='object')return b[k]!=null?b[k]:'';"
   "try{var j=JSON.parse(b);if(j&&j[k]!=null)return j[k];}catch(e){}"
   "var ps=String(b).split('&');for(var i=0;i<ps.length;i++){var kv=ps[i].split('=');"
   "if(decodeURIComponent(kv[0])===k)return decodeURIComponent((kv[1]||'').replace(/\\+/g,' '));}return '';}"
   "function bodyJson(){var b=request.body;if(b==null)return {};if(typeof b==='object')return b;try{return JSON.parse(b)||{};}catch(e){return {};}}"
   "function interp(t,v){return String(t).replace(/\\{(\\w+)\\}/g,function(_,k){return v[k]!=null?v[k]:'';});}"
   "function deepInterp(x,v){if(typeof x==='string')return interp(x,v);"
   "if(Array.isArray(x))return x.map(function(e){return deepInterp(e,v);});"
   "if(x&&typeof x==='object'){var o={};for(var k in x){o[k]=deepInterp(x[k],v);}return o;}return x;}"
   "function merge1(a,b){var o={},k;for(k in a)o[k]=a[k];for(k in b){"
   "if(b[k]&&typeof b[k]==='object'&&!Array.isArray(b[k])&&a[k]&&typeof a[k]==='object'){"
   "var s={},j;for(j in a[k])s[j]=a[k][j];for(j in b[k])s[j]=b[k][j];o[k]=s;}else{o[k]=b[k];}}return o;}"
   ;; --- collections ---
   "function dget(o,p){var ps=String(p).split('.'),i;for(i=0;i<ps.length&&o!=null;i++){o=o[ps[i]];}return o;}"
   "function clause(c){c=String(c).trim();var m=c.match(/^(\\S+)\\s+(eq|sw|co|pr)(?:\\s+\"([^\"]*)\")?$/i);"
   "if(!m){return function(){return false;};}var f=m[1],op=m[2].toLowerCase(),val=m[3];"
   "return function(o){var v=dget(o,f);if(op==='pr')return v!=null&&v!=='';if(val===undefined||v==null)return false;v=String(v);"
   "if(op==='eq')return v===val;if(op==='sw')return v.indexOf(val)===0;if(op==='co')return v.indexOf(val)>=0;return false;};}"
   "function scimPred(expr){var ors=String(expr).split(/\\s+or\\s+/i).map(function(o){"
   "var ands=o.split(/\\s+and\\s+/i).map(clause);return function(x){return ands.every(function(f){return f(x);});};});"
   "return function(x){return ors.some(function(f){return f(x);});};}"
   "function Coll(m,f){this.m=m;this.f=f;}"
   "Coll.prototype.get=function(id){return this.m[String(id)];};"
   "Coll.prototype.put=function(o){this.m[String(o[this.f])]=o;return o;};"
   "Coll.prototype.del=function(id){var k=String(id),o=this.m[k];delete this.m[k];return o;};"
   "Coll.prototype.list=function(opt){opt=opt||{};var f=this.f,arr=[],k;for(k in this.m)arr.push(this.m[k]);"
   "if(opt.filter)arr=arr.filter(scimPred(opt.filter));"
   "arr.sort(function(a,b){var x=String(a[f]),y=String(b[f]);return x<y?-1:x>y?1:0;});"
   "var lim=opt.limit>0?opt.limit:200,start=0,i;"
   "if(opt.after){for(i=0;i<arr.length;i++){if(String(arr[i][f])===String(opt.after)){start=i+1;break;}}}"
   "var page=arr.slice(start,start+lim),next=(start+lim<arr.length&&page.length)?String(page[page.length-1][f]):'';"
   "return {items:page,next:next};};"
   "function initColl(state,name,idField,seed){state.__c=state.__c||{};state.__cf=state.__cf||{};"
   "if(!state.__c[name]){var m={},i,r,s=seed||[];for(i=0;i<s.length;i++){r=s[i];m[String(r[idField])]=r;}"
   "state.__c[name]=m;state.__cf[name]=idField;}}"
   "function coll(state,name){return new Coll(state.__c[name]||{},state.__cf[name]||'id');}"
   "function collResp(status,obj,headers){var h=headers||{};h['Content-Type']=h['Content-Type']||'application/json';"
   "return {statusCode:status,headers:h,body:obj==null?'':JSON.stringify(obj)};}"))

(defn gen
  "The Mountebank inject function (as a JS source string) for one stateful op.
   `seed` (handshake seed arrays) and `collections` ({name {idField seed}}) are
   embedded as literals so the function is self-contained. Every declared collection
   is seeded once into shared `state` (idempotent), so all ops see the same data."
  ([op seed] (gen op seed nil))
  ([op seed collections]
   (let [effect    (get op "effect")
         buck-init (->> (buckets-of effect)
                        (map (fn [b] (str "state[" (js b) "]=state[" (js b) "]||{};")))
                        str/join)
         coll-init (->> collections
                        (map (fn [[name spec]]
                               (str "initColl(state," (js name) ","
                                    (js (or (get spec "idField") "id")) ","
                                    (js (or (get spec "seed") [])) ");")))
                        str/join)]
     (str "function (request, state, logger) {"
          prelude
          "var seed=" (js (or seed {})) ";"
          "var V={};"
          buck-init
          coll-init
          (effect-body-js effect)
          "}"))))
