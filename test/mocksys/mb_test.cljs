(ns mocksys.mb-test
  "The daemon client's lifecycle against real listeners on a private admin port:
   what ensure-up! does about a foreign listener, a bad MOCKSYS_ADMIN_PORT, a daemon
   from another mocksys version and a daemon that dies on startup, and how the
   daemon is re-run from each kind of process."
  (:require [clojure.test :refer [deftest testing is async]]
            [clojure.string :as str]
            [promesa.core :as p]
            ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:net" :as net]
            ["node:os" :as os]
            ["node:path" :as path]
            [mocksys.mb :as mb]
            [mocksys.runtime :as runtime]))

(deftest daemon-command-per-process-kind
  (testing "the bun binary is its own runtime; its script path is virtual"
    (is (= ["/usr/local/bin/mocksys" ["daemon"]]
           (mb/daemon-command {:exec-path "/usr/local/bin/mocksys" :script "/$bunfs/root/mocksys" :bun? true}))))
  (testing "bun running a script re-runs the script"
    (is (= ["/opt/bun/bin/bun" ["/src/mocksys/out/mocksys.js" "daemon"]]
           (mb/daemon-command {:exec-path "/opt/bun/bin/bun" :script "/src/mocksys/out/mocksys.js" :bun? true}))))
  (testing "node re-runs the script"
    (is (= ["/usr/bin/node" ["/src/mocksys/bin/mocksys" "daemon"]]
           (mb/daemon-command {:exec-path "/usr/bin/node" :script "/src/mocksys/bin/mocksys" :bun? false})))))
