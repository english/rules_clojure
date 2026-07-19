(ns rules-clojure.testrunner-fixtures.filter
  "Fixture vars for --test_filter unit tests. Not a clojure_test target;
   testrunner-test invokes them through run-ns with various filters.
   gamma-fail fails on purpose so filtering it out yields a green summary."
  (:require [clojure.test :refer [deftest is]]))

(deftest alpha-pass
  (is (= 1 1)))

(deftest beta-pass
  (is (= 2 2)))

(deftest gamma-fail
  (is (= 1 2)))
