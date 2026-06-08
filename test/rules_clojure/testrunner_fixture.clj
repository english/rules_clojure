(ns rules-clojure.testrunner-fixture
  "Fixture namespace with a few passing test vars, used by
   rules-clojure.testrunner-test to exercise --test_filter handling."
  (:require [clojure.test :refer [deftest is]]))

(deftest alpha-test
  (is true))

(deftest beta-test
  (is true))

(deftest gamma-test
  (is true))
