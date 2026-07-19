(ns example.gitdep-test
  "Integration: loads a namespace from a tools.deps git dep that was
   materialized into a jar under @deps and AOT'd (see example.gitdep + deps.edn)."
  (:require [clojure.test :refer :all]
            [example.gitdep :as gitdep]
            [clojure.tools.gitlibs :as gitlibs]))

(deftest git-dep-loads
  (is (gitdep/gitlibs-loaded?))
  ;; Stronger than var identity: call a real public API from the git dep.
  (is (ifn? gitlibs/procure)))
