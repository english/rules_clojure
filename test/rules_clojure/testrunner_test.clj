(ns rules-clojure.testrunner-test
  (:require [clojure.test :refer [deftest is testing]]
            [rules-clojure.testrunner :as tr]
            [rules-clojure.testrunner-fixture]))

(deftest test-var-matches?
  (let [v #'rules-clojure.testrunner-fixture/alpha-test]
    (testing "matches by unqualified var name"
      (is (tr/test-var-matches? #"alpha-test" v))
      (is (tr/test-var-matches? #"alpha" v)))
    (testing "matches by fully-qualified namespace/name"
      (is (tr/test-var-matches? #"rules-clojure.testrunner-fixture/alpha-test" v)))
    (testing "non-matching filter does not match"
      (is (not (tr/test-var-matches? #"beta-test" v)))
      (is (not (tr/test-var-matches? #"zzz" v))))))

(deftest ns-test-vars-filtering
  (let [fixture 'rules-clojure.testrunner-fixture]
    (testing "nil filter returns every test var"
      (is (= 3 (count (tr/ns-test-vars fixture nil)))))
    (testing "filter narrows to matching vars"
      (is (= [#'rules-clojure.testrunner-fixture/alpha-test]
             (tr/ns-test-vars fixture #"alpha"))))
    (testing "regex (not just literal) filters work"
      (is (= #{#'rules-clojure.testrunner-fixture/alpha-test
               #'rules-clojure.testrunner-fixture/beta-test}
             (set (tr/ns-test-vars fixture #"alpha|beta")))))
    (testing "filter matching nothing returns empty"
      (is (empty? (tr/ns-test-vars fixture #"no-such-test"))))))

(deftest run-ns-tests-respects-filter
  ;; run-ns-tests rebinds *report-counters* to its own ref, so running the
  ;; fixture's tests here does not pollute this test's own counters. Suppress
  ;; the nested test output to keep logs readable.
  (binding [clojure.test/*test-out* (java.io.StringWriter.)]
    (testing "no filter runs all tests in the namespace"
      (is (= 3 (:test (tr/run-ns-tests 'rules-clojure.testrunner-fixture nil)))))
    (testing "filter runs only the matching test"
      (let [summary (tr/run-ns-tests 'rules-clojure.testrunner-fixture #"alpha")]
        (is (= 1 (:test summary)))
        (is (= 1 (:pass summary)))
        (is (zero? (:fail summary)))
        (is (zero? (:error summary)))))
    (testing "filter matching nothing runs no tests"
      (is (= 0 (:test (tr/run-ns-tests 'rules-clojure.testrunner-fixture #"no-such-test")))))))
