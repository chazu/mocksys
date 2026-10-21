(ns mocksys.source-text-test
  "Source files must stay plain text: one literal NUL byte makes git treat a file as
   binary, which hides every diff to it."
  (:require [clojure.test :refer [deftest is]]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(defn- cljs-files [dir]
  (mapcat (fn [ent]
            (let [p (path/join dir (.-name ent))]
              (cond (.isDirectory ent)                (cljs-files p)
                    (re-find #"\.clj[sc]?$" (.-name ent)) [p])))
          (fs/readdirSync dir #js {:withFileTypes true})))

(deftest no-nul-bytes-in-source
  (let [files (mapcat cljs-files ["src" "test"])]
    (is (seq files))
    (doseq [f files]
      (is (not (.includes (fs/readFileSync f) 0)) (str f " contains a NUL byte")))))
