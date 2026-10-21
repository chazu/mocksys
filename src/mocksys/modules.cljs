(ns mocksys.modules
  "Custom verbs: the CommonJS files a contract lists under `modules:`. A module sets
  `module.exports = { verbs: { name(ctx) { ... } } }` and an effect runs a verb with
  `call`. `lower` resolves the paths and checks the verbs; the daemon loads the files
  again on each post and hands the verbs to the generated injections."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:vm" :as vm]
            ["node:module" :as node-module]))

(defn- fail [msg] (throw (js/Error. (str "modules: " msg))))

(defn- inside? [dir file] (str/starts-with? file (str dir path/sep)))

(defn resolve-paths
  "The absolute path of each entry of `modules`, resolved against the scenario
   directory `dir`. A module must be a file inside `dir`, symlinks followed: the
   scenario directory is what pack, publish and push carry, so a module outside it
   would not travel with the scenario."
  [dir modules]
  (when-not (or (nil? modules) (and (sequential? modules) (every? string? modules)))
    (fail "expected a list of file paths, relative to the scenario directory"))
  (when (and (seq modules) (nil? dir))
    (fail (str (first modules) " needs the scenario directory to resolve against")))
  (mapv (fn [m]
          (let [file (path/resolve dir m)]
            (when-not (inside? (path/resolve dir) file)
              (fail (str m " is outside the scenario directory " dir)))
            (when-not (fs/existsSync file)
              (fail (str m ": no such file")))
            (let [real (fs/realpathSync file)]
              (when-not (inside? (fs/realpathSync dir) real)
                (fail (str m " is outside the scenario directory " dir " (it links to " real ")"))))
            file))
        modules))

(defn- exports-of
  "Run one module afresh (never from the require cache) and return its exports.
   `require` inside it resolves relative to the module's own file."
  [label file]
  (try
    (let [module #js {:exports #js {}}
          run    (vm/compileFunction (fs/readFileSync file "utf8")
                                     #js ["exports" "require" "module" "__filename" "__dirname"]
                                     #js {:filename file})]
      (.call run (.-exports module) (.-exports module) (node-module/createRequire file) module file (path/dirname file))
      (.-exports module))
    (catch :default e
      (fail (str label " does not load: " (ex-message e)
                 " (a module is CommonJS: module.exports = { verbs: { name(ctx) { ... } } })")))))

(defn- verbs-of [label file]
  (let [^js vs (some-> ^js (exports-of label file) .-verbs)]
    (when-not (and vs (= "object" (goog/typeOf vs)))
      (fail (str label " exports no `verbs` object")))
    (doseq [k (js-keys vs) :when (not (fn? (aget vs k)))]
      (fail (str label ": verb '" k "' is not a function")))
    vs))

(defn load-verbs
  "Every verb the modules export, as one JS object of name -> function. `modules` is
   [[label file] ...]; an error names the module by its label."
  [modules]
  (let [verbs (js/Object.create nil)]
    (reduce (fn [owners [label file]]
              (let [vs (verbs-of label file)]
                (reduce (fn [owners k]
                          (when-let [prior (get owners k)]
                            (fail (str "verb '" k "' is exported by both " prior " and " label)))
                          (aset verbs k (aget vs k))
                          (assoc owners k label))
                        owners
                        (js-keys vs))))
            {}
            modules)
    verbs))
