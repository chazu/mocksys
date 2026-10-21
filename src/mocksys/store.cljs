(ns mocksys.store
  "On-disk scenario layout under the store `root` ($MOCKSYS_HOME, else ~/.mocksys).

  Our own metadata is YAML (agent- and human-readable); the compiled imposter
  is kept as JSON, its native format, so it round-trips with zero translation."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["js-yaml" :as yaml])
  (:refer-clojure :exclude [exists?]))

;; The store is a single shared library, not per-project: $MOCKSYS_HOME, else
;; ~/.mocksys. Set MOCKSYS_HOME=./.mocks in a project to scope mocks to it.
(def default-root (path/join (os/homedir) ".mocksys"))

(defn root-for
  "The store a MOCKSYS_HOME value names, as an absolute path, so a command run from
   a subdirectory, and the daemon it starts, agree on the store and its token file."
  [home]
  (if (and home (not= home ""))
    (path/resolve home)
    default-root))

(def root (root-for (.-MOCKSYS_HOME js/process.env)))

(def services-dir (path/join root "services"))

;; A scenario id may be nested ("github/create-issue") — node's path join turns
;; that into a real subdirectory, giving the docs/design/idea.md layout for free.
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
  "Every frozen scenario, by walking `root` for imposter.json. Scenario id is
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

;; --- user kits ------------------------------------------------------------

(def kits-dir (path/join root "kits"))

(defn- read-kit-file
  "{:file :text}, or {:file :error} when `file` is not a readable regular file."
  [file]
  (try
    (if (.isFile (fs/statSync file))
      {:file file :text (fs/readFileSync file "utf8")}
      {:file file :error "not a file"})
    (catch :default e {:file file :error (.-message e)})))

(defn- directory? [file]
  (try (.isDirectory (fs/statSync file)) (catch :default _ false)))

(defn- read-kit-dir
  "A directory kit: its kit.yaml as read-kit-file reads it, with :dir, the directory
   the files it ships resolve against."
  [dir]
  (let [file (path/join dir "kit.yaml")]
    (if (fs/existsSync file)
      (assoc (read-kit-file file) :dir dir)
      {:file dir :error "no kit.yaml (a directory kit keeps its kit in kit.yaml)"})))

(defn kit-files
  "One entry per kit in `dir`, sorted by name: {:file :text} for a *.yaml or *.yml
   file, the same with :dir for a subdirectory holding kit.yaml, and {:file :error}
   for an entry that cannot be read. A subdirectory whose name starts with a dot
   (.git) is not a kit. Empty when `dir` does not exist."
  [dir]
  (if-not (fs/existsSync dir)
    []
    (->> (fs/readdirSync dir)
         (map str)
         sort
         (keep (fn [entry]
                 (let [file (path/join dir entry)]
                   (cond
                     (directory? file)           (when-not (str/starts-with? entry ".")
                                                   (read-kit-dir file))
                     (re-find #"\.ya?ml$" entry) (read-kit-file file)))))
         vec)))
