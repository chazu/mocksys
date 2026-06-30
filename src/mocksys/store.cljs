(ns mocksys.store
  "On-disk scenario layout under .mocks/ in the caller's cwd.

  Our own metadata is YAML (agent- and human-readable); the Mountebank imposter
  is kept as JSON, its native format, so it round-trips with zero translation."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["js-yaml" :as yaml]))

;; The store is a single shared library, not per-project: $MOCKSYS_HOME, else
;; ~/.mocksys. Set MOCKSYS_HOME=./.mocks in a project to scope mocks to it.
(def root
  (let [home (.-MOCKSYS_HOME js/process.env)]
    (if (and home (not= home ""))
      home
      (path/join (os/homedir) ".mocksys"))))

(def services-dir (path/join root "services"))

;; A scenario id may be nested ("github/create-issue") — node's path join turns
;; that into a real subdirectory, giving the idea.md layout for free.
(defn scenario-dir [name] (path/join root name))

(defn- ensure-dir! [dir]
  (fs/mkdirSync dir #js {:recursive true}))

;; --- YAML helpers ---------------------------------------------------------

(defn- write-yaml! [file m]
  (fs/writeFileSync file (yaml/dump (clj->js m))))

(defn- read-yaml [file]
  (js->clj (yaml/load (fs/readFileSync file "utf8")) :keywordize-keys true))

;; The contract is read string-keyed (no keywordize) so embedded JSON Schemas and
;; example bodies round-trip faithfully — arbitrary property names stay strings.
(defn- read-yaml-raw [file]
  (js->clj (yaml/load (fs/readFileSync file "utf8"))))

;; --- recording state (record -> freeze handoff) ---------------------------

(defn write-recording! [name m]
  (let [dir (scenario-dir name)]
    (ensure-dir! dir)
    (write-yaml! (path/join dir "recording.yaml") m)))

(defn read-recording [name]
  (let [file (path/join (scenario-dir name) "recording.yaml")]
    (when (fs/existsSync file)
      (read-yaml file))))

;; --- frozen scenario ------------------------------------------------------

(defn write-imposter! [name imposter]
  (let [dir (scenario-dir name)]
    (ensure-dir! dir)
    (fs/writeFileSync (path/join dir "imposter.json")
                      (js/JSON.stringify (clj->js imposter) nil 2))))

(defn read-imposter [name]
  (let [file (path/join (scenario-dir name) "imposter.json")]
    (when-not (fs/existsSync file)
      (throw (js/Error. (str "No frozen scenario '" name "'. Run `freeze " name "` first."))))
    (js->clj (js/JSON.parse (fs/readFileSync file "utf8")))))

(defn write-mock! [name m]
  (write-yaml! (path/join (scenario-dir name) "mock.yaml") m))

;; --- contract (canonical source) ------------------------------------------

(defn- contract-file [name] (path/join (scenario-dir name) "contract.yaml"))
(defn- imposter-file [name] (path/join (scenario-dir name) "imposter.json"))

(defn write-contract! [name contract]
  (let [dir (scenario-dir name)]
    (ensure-dir! dir)
    (fs/writeFileSync (contract-file name) (yaml/dump (clj->js contract)))))

(defn read-contract [name]
  (let [file (contract-file name)]
    (when (fs/existsSync file)
      (try (read-yaml-raw file)
           (catch :default e
             (throw (js/Error. (str "contract.yaml for '" name "' is not valid YAML — "
                                    (.-message e)
                                    "  (tip: quote paths with {param}, e.g. path: \"/users/{id}\")"))))))))

(defn watch-contract!
  "Invoke `on-change` (debounced by the caller) whenever the scenario's
   contract.yaml changes on disk. Returns the fs watcher."
  [name on-change]
  (fs/watch (contract-file name) (fn [_event _filename] (on-change))))

;; A *draft* contract (contract.draft.yaml): recording can land here for review
;; before `promote` makes it the canonical contract — recording as an input, not
;; the source of truth.
(defn- draft-file [name] (path/join (scenario-dir name) "contract.draft.yaml"))

(defn write-draft! [name contract]
  (ensure-dir! (scenario-dir name))
  (fs/writeFileSync (draft-file name) (yaml/dump (clj->js contract))))

(defn has-draft? [name] (fs/existsSync (draft-file name)))

(defn promote-draft!
  "Make a scenario's draft its canonical contract.yaml. Returns true on success,
   false if there was no draft."
  [name]
  (let [d (draft-file name)]
    (when (fs/existsSync d)
      (fs/renameSync d (contract-file name))
      true)))

(defn has-contract? [name]
  (fs/existsSync (contract-file name)))

(defn contract-stale?
  "True when the contract should be (re)compiled: a contract exists and the
   imposter artifact is missing or older than it. No contract -> never stale
   (legacy imposter-only scenarios are left untouched)."
  [name]
  (let [cf (contract-file name) imf (imposter-file name)]
    (and (fs/existsSync cf)
         (or (not (fs/existsSync imf))
             (> (.. (fs/statSync cf) -mtimeMs)
                (.. (fs/statSync imf) -mtimeMs))))))

(defn read-mock [name]
  (let [file (path/join (scenario-dir name) "mock.yaml")]
    (when (fs/existsSync file) (read-yaml file))))

(defn exists? [name]
  (fs/existsSync (path/join (scenario-dir name) "imposter.json")))

(defn slurp [file]
  (when-not (fs/existsSync file) (throw (js/Error. (str "no such file: " file))))
  (fs/readFileSync file "utf8"))

;; --- stacks (named sets of scenarios brought up together) -----------------

(def stacks-dir (path/join root "stacks"))
(defn- stack-file [name] (path/join stacks-dir (str name ".yaml")))

(defn write-stack! [name m]
  (ensure-dir! stacks-dir)
  (write-yaml! (stack-file name) m))

(defn read-stack [name]
  (let [f (stack-file name)]
    (when (fs/existsSync f) (read-yaml f))))

(defn list-stacks []
  (if-not (fs/existsSync stacks-dir)
    []
    (->> (fs/readdirSync stacks-dir)
         (map str)
         (filter #(str/ends-with? % ".yaml"))
         (map #(str/replace % #"\.yaml$" ""))
         sort)))

;; --- service templates ----------------------------------------------------

(defn- service-file [service] (path/join services-dir (str service ".yaml")))

(defn write-service! [service m]
  (ensure-dir! services-dir)
  (write-yaml! (service-file service) m))

(defn read-service [service]
  (let [file (service-file service)]
    (when (and service (fs/existsSync file)) (read-yaml file))))

;; --- catalog --------------------------------------------------------------

(defn list-scenarios
  "Every frozen scenario, by walking .mocks/ for imposter.json. Scenario id is
   the directory relative to root (so nested 'github/create-issue' survives)."
  []
  (if-not (fs/existsSync root)
    []
    (->> (fs/readdirSync root #js {:recursive true})
         (map str)
         (filter #(str/ends-with? % "imposter.json"))
         (map #(path/dirname %))
         (remove #(str/starts-with? % "services"))
         sort)))
