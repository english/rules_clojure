(ns example.gitdep-test
  (:require [clojure.test :refer :all]
            [example.gitdep :as gitdep]))

(deftest git-dep-loads
  (is (gitdep/gitlibs-loaded?)))
