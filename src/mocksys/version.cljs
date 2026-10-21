(ns mocksys.version
  "The mocksys version, read from package.json. bun --compile bundles the JSON into
   the binary; under node it resolves next to out/.")

(def version (.-version (js/require "../package.json")))
