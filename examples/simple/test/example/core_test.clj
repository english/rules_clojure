(ns example.core-test
  (:require [clojure.test :refer :all]
            [example.core :as core]))

(deftest hello
  (is true))

(deftest classify-test
  ;; only exercises the positive branch of example.core/classify, leaving the
  ;; :non-positive branch and example.core/unused uncovered.
  (is (= :positive (core/classify 5))))
