(ns mocksys.cli.share
  "The git-backed store and portable bundles: home, status, log, publish, rm,
  restore, remote, push, pull, pack, unpack."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [mocksys.mb :as mb]
            [mocksys.store :as store]
            [mocksys.bundle :as bundle]
            [mocksys.git :as git]
            [mocksys.lifecycle :as lifecycle]
            [mocksys.cli.util :refer [die]]))

(defn cmd-pack [{:keys [pos opts]}]
  (let [scenario (first pos)]
    (when-not scenario (die "pack needs a scenario: pack <name> [--out FILE] | --stdout > file"))
    (lifecycle/ensure-compiled! scenario)
    ;; Default to a file (predictable for agents, who always run non-TTY); stream
    ;; the raw archive to stdout only when --stdout is explicitly requested.
    (if (:stdout opts)
      (bundle/pack! scenario "-")
      (let [out (or (:out opts) (str (str/replace scenario "/" "-") ".mock.tgz"))]
        (bundle/pack! scenario out)
        (println (str "▦ packed '" scenario "' → " out))))))

(defn cmd-unpack [{:keys [pos]}]
  (let [file (first pos)]
    (when-not file (die "unpack needs a bundle: unpack <file.mock.tgz>"))
    (let [names (bundle/unpack! file)]
      (println (str "▣ unpacked " (str/join ", " names) " into " store/root))
      (doseq [n names] (println (str "  run it with:  mocksys run " n))))))

(defn cmd-home [_]
  (let [{:keys [root repo? branch remote dirty?]} (git/info)]
    (println (str "store: " root))
    (if repo?
      (do (println (str "git:   " branch (when dirty? " (uncommitted changes)")))
          (println (str "remote: " (or remote "none — `mocksys remote <url>` to set"))))
      (println "git:   not initialized — `mocksys publish` will init it"))
    (let [n (count (store/list-scenarios))]
      (println (str "scenarios: " n)))))

(defn cmd-publish [{:keys [pos]}]
  (let [id (first pos)]
    (case (git/publish! id)
      :clean     (println "nothing to publish — store is clean")
      :committed (println (str "✔ published " (or id "all changes") "  (" store/root ")")))))

(defn cmd-remote [{:keys [pos]}]
  (let [url (first pos)]
    (when-not url (die "remote needs a URL: remote <git-url>"))
    (git/set-remote! url)
    (println (str "✔ remote set: " url))))

(defn cmd-push [_]
  (git/push!)
  (println "✔ pushed to remote"))

(defn cmd-pull [_]
  (git/pull!)
  (println (str "✔ pulled from remote  (" (count (store/list-scenarios)) " scenario(s) now in store)")))

(defn cmd-rm
  "Remove a scenario or a whole service from the store (stops it if live; commits
   the removal in a git store):  rm <scenario|service>"
  [{:keys [pos]}]
  (let [id (first pos)]
    (when-not id (die "rm needs a scenario or service: rm <name>  (e.g. github/create-issue or github)"))
    (p/let [live (mb/list-imposters)]
      (doseq [imp (filter #(let [n (str (:name %))]
                             (or (= n id) (str/starts-with? n (str id "/")))) live)]
        (mb/delete-imposter! (:port imp)))
      (let [res (git/remove! id)]
        (println (str "🗑 removed '" id "' from the store"
                      (when (= res :committed) " (committed)")))))))

(defn- status-word [state]
  (case state "??" "untracked" "A" "added" "M" "modified" "D" "deleted" "R" "renamed" state))

(defn cmd-status
  "Show uncommitted changes in the store (which scenarios are new/edited/removed)."
  [_]
  (let [files (git/changed-files)]
    (cond
      (nil? files)   (println "store is not a git repo yet — `mocksys publish` will init it")
      (empty? files) (println (str "store is clean — nothing to publish  (" store/root ")"))
      :else
      (do (println (str (count files) " uncommitted change(s) in " store/root ":"))
          (doseq [{:keys [state path]} files]
            (println (str "  " (status-word state) ": " path)))
          (println)
          (println "  record them:  mocksys publish [<name>]")))))

(defn cmd-log
  "Recent store history:  log [--n N]"
  [{:keys [opts]}]
  (let [n (if (string? (:n opts)) (js/parseInt (:n opts)) 20)]
    (if-let [l (git/log n)]
      (do (println (str "# last " n " change(s) — " store/root))
          (println l))
      (println "store has no history yet — `mocksys publish` will init it"))))

(defn cmd-restore
  "Discard uncommitted local changes (revert edits, drop new files):
     restore <name>   (or restore --all for the whole store)"
  [{:keys [pos opts]}]
  (let [id (first pos)]
    (when-not (or id (:all opts))
      (die "restore needs a scenario/service (or --all): restore <name> | restore --all"))
    (git/restore! id)
    (println (str "↩ restored " (or id "the whole store") " to the last committed state"))))

