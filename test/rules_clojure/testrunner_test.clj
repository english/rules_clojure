(ns rules-clojure.testrunner-test
  (:require [clojure.test :as c.test :refer [deftest is testing]]
            [rules-clojure.testrunner :as tr]
            ;; required so the fixture's test vars exist for run-ns-tests
            [rules-clojure.testrunner-fixture]))

(def fixture-ns 'rules-clojure.testrunner-fixture)

(defn- silent-report
  "A clojure.test report fn that only tallies counters, so running the failing
  fixture var doesn't print noise or touch the surrounding test's counters."
  [m]
  (when (#{:pass :fail :error} (:type m))
    (c.test/inc-report-counter (:type m))))

(defn- run
  "Run the fixture namespace's tests through the testrunner with the given raw
  --test_filter value, returning the summary map."
  [test-filter]
  (binding [c.test/report silent-report]
    (tr/run-ns-tests fixture-ns (tr/test-filter->pred test-filter))))

(deftest test-filter->pred-blank-matches-everything
  (testing "nil and blank filters select every var"
    (doseq [f [nil "" "   "]]
      (let [pred (tr/test-filter->pred f)]
        (is (true? (boolean (pred #'rules-clojure.testrunner-fixture/alpha-pass))))
        (is (true? (boolean (pred #'rules-clojure.testrunner-fixture/gamma-fail))))))))

(deftest test-filter->pred-substring-and-regex
  (let [alpha #'rules-clojure.testrunner-fixture/alpha-pass
        gamma #'rules-clojure.testrunner-fixture/gamma-fail]
    (testing "plain string is a substring match against ns/test-name"
      (is (true? ((tr/test-filter->pred "alpha-pass") alpha)))
      (is (false? ((tr/test-filter->pred "alpha-pass") gamma))))
    (testing "filter matches the fully-qualified name"
      (is (true? ((tr/test-filter->pred "testrunner-fixture/alpha") alpha))))
    (testing "filter is interpreted as a regex"
      (is (true? ((tr/test-filter->pred "alpha|gamma") alpha)))
      (is (true? ((tr/test-filter->pred "alpha|gamma") gamma)))
      (is (false? ((tr/test-filter->pred "alpha|gamma") #'rules-clojure.testrunner-fixture/beta-pass))))))

(deftest var->test-name-is-fully-qualified
  (is (= "rules-clojure.testrunner-fixture/alpha-pass"
         (tr/var->test-name #'rules-clojure.testrunner-fixture/alpha-pass))))

(deftest run-ns-tests-no-filter-runs-all
  (let [summary (run nil)]
    (is (= 3 (:test summary)))
    (is (= 2 (:pass summary)))
    (is (= 1 (:fail summary)))))

(deftest run-ns-tests-substring-filter
  (testing "selecting a single passing test"
    (let [summary (run "alpha-pass")]
      (is (= 1 (:test summary)))
      (is (= 1 (:pass summary)))
      (is (= 0 (:fail summary)))))
  (testing "selecting all passing tests by shared substring"
    (let [summary (run "pass")]
      (is (= 2 (:test summary)))
      (is (= 2 (:pass summary)))
      (is (= 0 (:fail summary)))))
  (testing "selecting only the failing test"
    (let [summary (run "gamma")]
      (is (= 1 (:test summary)))
      (is (= 1 (:fail summary))))))

(deftest run-ns-tests-regex-filter
  (let [summary (run "alpha|beta")]
    (is (= 2 (:test summary)))
    (is (= 2 (:pass summary)))
    (is (= 0 (:fail summary)))))

(deftest run-ns-tests-no-match-runs-nothing
  (let [summary (run "does-not-exist")]
    (is (= 0 (:test summary)))
    (is (= 0 (:pass summary)))
    (is (= 0 (:fail summary)))))
