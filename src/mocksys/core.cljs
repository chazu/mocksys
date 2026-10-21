(ns mocksys.core
  "mocksys: agent-native CLI for local mocks and digital twins. This namespace is only the entry
  point and the command table; each command lives in a `mocksys.cli.*` namespace
  and the scenario lifecycle in `mocksys.lifecycle`."
  (:require [promesa.core :as p]
            [mocksys.cli.util :refer [parse-args die]]
            [mocksys.cli.record :as record]
            [mocksys.cli.serve :as serve]
            [mocksys.cli.twins :as twins]
            [mocksys.cli.author :as author]
            [mocksys.cli.share :as share]
            [mocksys.cli.help :as help]))

(def commands
  "Command name -> handler. A handler takes {:pos [...] :opts {...}} and returns a
   value or a promise."
  {"init"         record/cmd-init
   "record"       record/cmd-record
   "freeze"       record/cmd-freeze
   "promote"      record/cmd-promote
   "run"          serve/cmd-run
   "env"          serve/cmd-env
   "inspect"      serve/cmd-inspect
   "doctor"       serve/cmd-doctor
   "requests"     serve/cmd-requests
   "assert"       serve/cmd-assert
   "ls"           serve/cmd-ls
   "stop"         serve/cmd-stop
   "stack"        serve/cmd-stack
   "up"           serve/cmd-up
   "down"         serve/cmd-down
   "kit"          twins/cmd-kit
   "kits"         twins/cmd-kits
   "genes"        twins/cmd-genes
   "conform"      twins/cmd-conform
   "clock"        twins/cmd-clock
   "import"       author/cmd-import
   "compile"      author/cmd-compile
   "new"          author/cmd-new
   "validate"     author/cmd-validate
   "examples"     author/cmd-examples
   "use"          author/cmd-use
   "add"          author/cmd-add
   "fault"        author/cmd-fault
   "parameterize" author/cmd-parameterize
   "pack"         share/cmd-pack
   "unpack"       share/cmd-unpack
   "home"         share/cmd-home
   "status"       share/cmd-status
   "log"          share/cmd-log
   "restore"      share/cmd-restore
   "publish"      share/cmd-publish
   "remote"       share/cmd-remote
   "push"         share/cmd-push
   "pull"         share/cmd-pull
   "rm"           share/cmd-rm
   "delete"       share/cmd-rm
   "prime"        help/cmd-prime
   "daemon"       serve/cmd-daemon
   "tls"          serve/cmd-tls})

(defn -main [& args]
  (let [[cmd & more] args
        handler (cond
                  (contains? #{nil "help" "--help" "-h"} cmd) (fn [_] (help/usage))
                  (contains? commands cmd)                    (get commands cmd)
                  :else (fn [_] (println "unknown command:" cmd) (help/usage)))]
    ;; A handler runs eagerly to produce its promise, so a *synchronous* throw (e.g.
    ;; a malformed contract.yaml) would escape the chain as a raw stack trace. Funnel
    ;; sync and async failures alike through `die` for a clean one-line error.
    (-> (try (p/promise (handler (parse-args more)))
             (catch :default e (p/rejected e)))
        (p/catch (fn [err] (die (.-message err)))))))

(defn- cli-args
  "User args from process.argv. The node bin shim (`node bin/mocksys a b`) and a
   bun-compiled binary (`mocksys a b`) both expose argv as [runtime script-or-vpath
   ...args] (bun keeps a virtual script path, /$bunfs/root/mocksys, at argv[1]), so
   drop the first two."
  []
  (let [argv (vec (.-argv js/process))]
    (if (>= (count argv) 2) (subvec argv 2) [])))

(defn ^:export main
  "Entry point: shadow-cljs sets this as :main, so it runs on load of out/mocksys.js
   (both the node bin shim and the bun binary go through here)."
  [& _]
  (apply -main (cli-args)))
