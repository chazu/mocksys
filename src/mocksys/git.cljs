(ns mocksys.git
  "The store (~/.mocksys) is a git repo; this wraps the operations agents need so
  they never shell git directly. freeze/add just write files — `publish` commits,
  `push`/`pull` sync a remote. Everything on disk is already scrubbed, so the repo
  is safe to share."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [mocksys.store :as store]))

(defn- git
  "Run git inside the store. Returns the spawnSync result (check .status)."
  ([args] (git args {}))
  ([args opts]
   (cp/spawnSync "git" (clj->js (concat ["-C" store/root] args))
                 (clj->js (merge {:encoding "utf8"} opts)))))

(defn- out [res] (str/trim (or (.-stdout res) "")))
(defn- ok? [res] (zero? (.-status res)))

(defn repo? []
  (fs/existsSync (path/join store/root ".git")))

(defn- identity-args
  "Fallback commit identity only when the user hasn't configured one globally,
   so we never override real attribution."
  []
  (if (str/blank? (out (git ["config" "user.email"])))
    ["-c" "user.name=mocksys" "-c" "user.email=mocksys@localhost"]
    []))

(defn ensure-repo!
  "Initialize the store as a git repo on first use (with a .gitignore that drops
   transient recording state)."
  []
  (fs/mkdirSync store/root #js {:recursive true})
  (when-not (repo?)
    (git ["init" "-q"])
    (fs/writeFileSync (path/join store/root ".gitignore")
                      "# transient record→freeze handoff state\n**/recording.yaml\n")
    (git ["add" ".gitignore"])
    (git (concat (identity-args) ["commit" "-q" "-m" "mocksys: initialize store"]))))

(defn current-branch [] (out (git ["rev-parse" "--abbrev-ref" "HEAD"])))

(defn remote-url []
  (when (repo?)
    (let [r (git ["remote" "get-url" "origin"])]
      (when (ok? r) (out r)))))

(defn dirty? []
  (and (repo?) (not (str/blank? (out (git ["status" "--porcelain"]))))))

(defn info []
  (if-not (repo?)
    {:root store/root :repo? false}
    {:root   store/root
     :repo?  true
     :branch (current-branch)
     :remote (remote-url)
     :dirty? (dirty?)}))

(defn publish!
  "Stage + commit changes for `id` (a scenario or service path), or everything
   when `id` is nil. Returns :clean (nothing staged) or :committed."
  [id]
  (ensure-repo!)
  (when (and id (not (fs/existsSync (path/join store/root id))))
    (throw (js/Error. (str "no such scenario/service '" id "' in the store"))))
  (git ["add" "-A" "--" (or id ".")])
  (if (ok? (git ["diff" "--cached" "--quiet"]))
    :clean
    (do (git (concat (identity-args) ["commit" "-q" "-m" (str "mocksys: publish " (or id "all"))]))
        :committed)))

(defn set-remote! [url]
  (ensure-repo!)
  (if (remote-url)
    (git ["remote" "set-url" "origin" url])
    (git ["remote" "add" "origin" url])))

(defn push! []
  (ensure-repo!)
  (when-not (remote-url) (throw (js/Error. "no remote set — `mocksys remote <url>` first")))
  (when-not (ok? (git ["push" "-u" "origin" (current-branch)] {:stdio "inherit"}))
    (throw (js/Error. "git push failed (see output above)"))))

(defn pull! []
  (ensure-repo!)
  (when-not (remote-url) (throw (js/Error. "no remote set — `mocksys remote <url>` first")))
  (when-not (ok? (git ["pull" "--ff-only" "origin" (current-branch)] {:stdio "inherit"}))
    (throw (js/Error. "git pull failed (see output above)"))))

;; --- managing store contents ----------------------------------------------

(defn log
  "Recent store history as oneline text (nil when the store isn't a repo yet)."
  [n]
  (when (repo?)
    (let [r (git ["log" (str "-" n) "--pretty=format:%h %ad %s" "--date=short"])]
      (when (ok? r) (out r)))))

(defn changed-files
  "Uncommitted changes as [{:state \"M|A|D|??\" :path store-relative}] (nil when the
   store isn't a repo)."
  []
  (when (repo?)
    ;; Parse raw stdout (not `out`, which trims the leading status-space off line 1).
    (->> (str/split-lines (or (.-stdout (git ["status" "--porcelain"])) ""))
         (remove str/blank?)
         (keep (fn [line]
                 (when-let [[_ st p] (re-matches #"^(.{2})\s(.*)$" line)]
                   {:state (str/trim st) :path p}))))))

(defn remove!
  "Delete a scenario/service path from the store. Commits the removal in a git
   store; just deletes files in a plain (non-git) store. Returns :committed or
   :deleted. Throws if the path isn't there."
  [id]
  (let [dir (path/join store/root id)]
    (when-not (fs/existsSync dir)
      (throw (js/Error. (str "no such scenario/service '" id "' in the store"))))
    (fs/rmSync dir #js {:recursive true :force true})
    (if (repo?)
      (do (git ["add" "-A" "--" id])
          (git (concat (identity-args) ["commit" "-q" "-m" (str "mocksys: remove " id)]))
          :committed)
      :deleted)))

(defn restore!
  "Discard uncommitted changes under `id` (whole store when nil): revert tracked
   edits and drop untracked files. Returns true."
  [id]
  (ensure-repo!)
  (let [target (or id ".")]
    (git ["checkout" "--" target])   ; revert tracked modifications (no-op if untracked)
    (git ["clean" "-fdq" "--" target]) ; drop untracked/new files
    true))
