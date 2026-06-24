(ns mocksys.bundle
  "One-file scenario bundles (.mock.tgz) for moving mocks between repos/sessions.

  A bundle carries the scenario directory AND its service profile, so the
  receiver inherits the same redact/volatile/env rules — a mock stays portable
  and self-describing. Implemented over `tar` (present on macOS/Linux)."
  (:require [clojure.string :as str]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            [mocksys.store :as store]
            [mocksys.service :as service]))

(defn- member-paths
  "Paths (relative to .mocks) to include for `scenario`: its dir + service yaml."
  [scenario]
  (let [svc      (service/service-of scenario)
        svc-path (str "services/" svc ".yaml")]
    (cond-> [scenario]
      (and svc (fs/existsSync (str store/root "/" svc-path))) (conj svc-path))))

(defn pack!
  "Write a gzip tar of `scenario` (+ its service) to `out`. `out` of \"-\" streams
   the archive to stdout. Returns nil; throws if tar fails."
  [scenario out]
  (when-not (store/exists? scenario)
    (throw (js/Error. (str "no frozen scenario '" scenario "' to pack"))))
  (let [args (concat ["-czf" out "-C" store/root] (member-paths scenario))
        ;; stream to our stdout only in "-" mode; otherwise let tar write the file
        res  (cp/spawnSync "tar" (clj->js args)
                           #js {:stdio (if (= out "-")
                                         #js ["ignore" "inherit" "inherit"]
                                         "inherit")})]
    (when-not (zero? (.-status res))
      (throw (js/Error. (str "tar failed packing " scenario))))))

(defn unpack!
  "Extract a bundle into .mocks/. Returns the scenario id(s) it contained."
  [file]
  (when-not (fs/existsSync file) (throw (js/Error. (str "no such bundle: " file))))
  (fs/mkdirSync store/root #js {:recursive true})
  (let [listing (cp/spawnSync "tar" #js ["-tzf" file] #js {:encoding "utf8"})
        _       (cp/spawnSync "tar" #js ["-xzf" file "-C" store/root] #js {:stdio "inherit"})
        entries (->> (str/split-lines (or (.-stdout listing) ""))
                     (remove str/blank?))]
    ;; top-level scenario dirs = entries that hold an imposter.json, minus services/
    (->> entries
         (filter #(str/ends-with? % "imposter.json"))
         (map #(str/replace % #"/imposter.json$" ""))
         (remove #(str/starts-with? % "services"))
         distinct
         vec)))
