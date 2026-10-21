(ns mocksys.modules
  "Custom verbs: the CommonJS files a contract lists under `modules:`. A module sets
  `module.exports = { verbs: { name(ctx) { ... } } }` and an effect runs a verb with
  `call`. The CLI resolves the paths, and `kit` copies a kit's modules into the
  scenario, but it never runs a module. The daemon loads the files on each post,
  checks the verbs the imposter calls, and hands them to the generated injections."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:vm" :as vm]
            ["node:module" :as node-module]))

(defn- fail [msg] (throw (js/Error. (str "modules: " msg))))

(defn- inside? [dir file] (str/starts-with? file (str dir path/sep)))

(defn scenario-paths
  "Each entry of `modules` as a normalized path relative to the scenario directory
   `dir`, which is how imposter.json records them. A module must be a file inside
   `dir`, symlinks followed: the scenario directory is what pack, publish and push
   carry, so a module outside it would not travel with the scenario. Two entries
   naming one file (./v.js and v.js) are one module. `what` names `dir` in errors;
   a kit's modules resolve against the kit directory."
  ([dir modules] (scenario-paths dir modules "scenario directory"))
  ([dir modules what]
   (when-not (or (nil? modules) (and (sequential? modules) (every? string? modules)))
     (fail (str "expected a list of file paths, relative to the " what)))
   (when (and (seq modules) (nil? dir))
     (fail (str (first modules) " needs the " what " to resolve against")))
   (->> modules
        (map (fn [m]
               (let [file (path/resolve dir m)]
                 (when-not (inside? (path/resolve dir) file)
                   (fail (str m " is outside the " what " " dir)))
                 (when-not (fs/existsSync file)
                   (fail (str m ": no such file")))
                 (let [real (fs/realpathSync file)]
                   (when-not (inside? (fs/realpathSync dir) real)
                     (fail (str m " is outside the " what " " dir " (it links to " real ")"))))
                 (path/relative (path/resolve dir) file))))
        distinct
        vec)))

(defn- landing
  "Where a write to `file` lands: the real path of its nearest existing ancestor,
   joined with the rest. lstat counts a dangling link as existing, so realpath
   throws on it rather than let the write create the file it points to."
  [file]
  (loop [p file more ()]
    (if (try (fs/lstatSync p) (catch :default _ nil))
      (apply path/join (fs/realpathSync p) more)
      (recur (path/dirname p) (conj more (path/basename p))))))

(defn copy-plan
  "How copying `paths` (scenario-paths over `from`) into the directory `to` would
   go, without writing: [[path state] ...], state :new, :same, or :changed when the
   file already at `to` differs. Throws when a copy would land outside `to`, through
   a link inside it, or onto something that is not a file."
  [from to paths]
  (let [root (landing (path/resolve to))]
    (mapv (fn [p]
            (let [real (try (landing (path/resolve to p))
                            (catch :default _ (fail (str p " in " to " is a link to nothing"))))]
              (when-not (inside? root real)
                (fail (str "copying " p " into " to " would write " real ", outside it")))
              [p (cond
                   (not (fs/existsSync real)) :new
                   (not (.isFile (fs/statSync real))) (fail (str p " in " to " is not a file"))
                   (.equals (fs/readFileSync real) (fs/readFileSync (path/resolve from p))) :same
                   :else :changed)]))
          paths)))

(defn copy!
  "Carry out a copy-plan: write each :new or :changed path from `from` into `to`."
  [from to plan]
  (doseq [[p state] plan :when (not= state :same)]
    (let [dest (path/resolve to p)]
      (fs/mkdirSync (path/dirname dest) #js {:recursive true})
      (fs/copyFileSync (path/resolve from p) dest))))

(defn absolute
  "`paths` from scenario-paths, resolved against the scenario directory `dir`: the
   form the daemon accepts."
  [dir paths]
  (mapv #(path/resolve dir %) paths))

(defn- load-error
  "What a module threw while loading: the message and the stack's first frame
   (file:line[:col]), or the thrown value when it is not an Error."
  [e]
  (if (instance? js/Error e)
    (let [frame (second (re-find #"((?:/|[A-Za-z]:\\)[^\s()]*?:\d+(?::\d+)?)" (str (.-stack e))))]
      (str (.-message e) (when frame (str " (at " frame ")"))))
    (str "it threw " (or (try (js/JSON.stringify e) (catch :default _ nil)) (str e)) ", not an Error")))

(defn- compile-module
  "The module's CommonJS function. The wrapper shares the module's first line so
   stack lines match the file (bun's vm.compileFunction reports every line one too
   high). A syntax error that runs into the wrapper's closing line is re-reported
   from the bare source, whose own end of input is where the file ends."
  [file]
  (let [code (fs/readFileSync file "utf8")]
    (try
      (.runInThisContext
       (vm/Script. (str "(function (exports, require, module, __filename, __dirname) {" code "\n})")
                   #js {:filename file}))
      (catch js/SyntaxError e
        (vm/Script. code #js {:filename file})
        (throw e)))))

(defn- exports-of
  "Run one module afresh (never from the require cache) and return its exports.
   `require` inside it resolves relative to the module's own file."
  [label file]
  (try
    (let [module #js {:exports #js {}}
          run    (compile-module file)]
      (.call run (.-exports module) (.-exports module) (node-module/createRequire file) module file (path/dirname file))
      (.-exports module))
    (catch :default e
      (fail (str label " does not load: " (load-error e)
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
   [[label file] ...]; an error names the module by its label. Throws unless each verb
   named in `calls` is exported."
  [modules calls]
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
    (when-let [verb (first (remove #(fn? (aget verbs %)) calls))]
      (throw (js/Error. (str "call: verb '" verb "' "
                             (if (seq modules)
                               (str "is not exported by the modules (they export "
                                    (str/join ", " (sort (js-keys verbs))) ")")
                               "needs a module that exports it; list one under `modules:`")))))
    verbs))
