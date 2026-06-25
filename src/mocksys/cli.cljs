(ns mocksys.cli
  "nbb entry point. nbb sets *command-line-args*; shadow-cljs / the compiled binary
   read process.argv via mocksys.core/main instead. Keeping the auto-run out of
   mocksys.core lets shadow require that namespace without executing it."
  (:require [mocksys.core :as core]))

(apply core/-main *command-line-args*)
