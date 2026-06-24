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

(defn read-mock [name]
  (let [file (path/join (scenario-dir name) "mock.yaml")]
    (when (fs/existsSync file) (read-yaml file))))

(defn exists? [name]
  (fs/existsSync (path/join (scenario-dir name) "imposter.json")))

(defn slurp [file]
  (when-not (fs/existsSync file) (throw (js/Error. (str "no such file: " file))))
  (fs/readFileSync file "utf8"))

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
