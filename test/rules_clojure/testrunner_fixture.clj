(ns rules-clojure.testrunner-fixture
  "Fixture test vars exercised by rules-clojure.testrunner-test. These are not
  run directly by a clojure_test target; testrunner-test invokes them through
  rules-clojure.testrunner/run-ns-tests with various filters. gamma-fail fails
  on purpose so the test can assert that filtering excludes it."
  (:require [clojure.test :refer [deftest is]]))

(deftest alpha-pass
  (is (= 1 1)))

(deftest beta-pass
  (is (= 2 2)))

(deftest gamma-fail
  (is (= 1 2)))
