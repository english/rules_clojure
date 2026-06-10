(ns example.gitdep
  "Exercises a tools.deps git dependency (see :deps in deps.edn)."
  (:require [clojure.tools.gitlibs :as gitlibs]))

(defn gitlibs-loaded? []
  (some? gitlibs/procure))
