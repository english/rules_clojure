(ns rules-clojure.testrunner-test
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.xml :as xml]
            [rules-clojure.testrunner :as tr])
  (:import [java.io ByteArrayInputStream Closeable File PrintWriter StringWriter]))

(defn parse
  "Parse an XML string into clojure.xml's element tree. Doubles as a
   well-formedness check — malformed XML throws."
  [^String s]
  (xml/parse (ByteArrayInputStream. (.getBytes s "UTF-8"))))

(defn elements
  "Child element nodes of `node`, skipping the whitespace text nodes that the
   SAX parser emits between our pretty-printed elements."
  [node]
  (filter map? (:content node)))

(defn by-name
  "Index testcase element nodes by their name attribute. Test execution order
   isn't guaranteed, so we look cases up by name rather than position."
  [cases]
  (into {} (map (juxt #(get-in % [:attrs :name]) identity)) cases))

;; Serializer golden tests: hand-built inputs cover shaping a single-namespace
;; live run can't reach (multiple suites, escaping). The expected XML is embedded
;; adjacent to each test so a format change surfaces as a readable diff here.

(def sample
  {:cases [{:name "passing-test" :classname "my.ns" :time 0.01
            :failures [] :errors []}
           {:name "failing-test" :classname "my.ns" :time 0.02
            :failures [{:message "my.ns/failing-test (core.clj:5)"
                        :body "expected: 1  actual: 2"}]
            :errors []}
           {:name "erroring-test" :classname "other.ns" :time 0.0
            :failures []
            :errors [{:message "other.ns/erroring-test (core.clj:9)"
                      :body "boom <&> \"quote\""}]}]})

(def sample-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<testsuites>
  <testsuite name=\"my.ns\" tests=\"2\" failures=\"1\" errors=\"0\" time=\"0.030\">
    <testcase name=\"passing-test\" classname=\"my.ns\" time=\"0.010\"/>
    <testcase name=\"failing-test\" classname=\"my.ns\" time=\"0.020\">
    <failure message=\"my.ns/failing-test (core.clj:5)\">expected: 1  actual: 2</failure>
    </testcase>
  </testsuite>
  <testsuite name=\"other.ns\" tests=\"1\" failures=\"0\" errors=\"1\" time=\"0.000\">
    <testcase name=\"erroring-test\" classname=\"other.ns\" time=\"0.000\">
    <error message=\"other.ns/erroring-test (core.clj:9)\">boom &lt;&amp;&gt; &quot;quote&quot;</error>
    </testcase>
  </testsuite>
</testsuites>
")

(deftest sample-serializes-to-golden
  (is (= sample-xml (tr/results->junit-xml sample))))

(def empty-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<testsuites>
</testsuites>
")

(deftest empty-results-serialize-to-golden
  (is (= empty-xml (tr/results->junit-xml {:cases []}))))

(def multiline-results
  {:cases [{:name "t" :classname "my.ns" :time 0.0
            :failures [{:message "line1\nline2" :body "b"}]
            :errors []}]})

(def multiline-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<testsuites>
  <testsuite name=\"my.ns\" tests=\"1\" failures=\"1\" errors=\"0\" time=\"0.000\">
    <testcase name=\"t\" classname=\"my.ns\" time=\"0.000\">
    <failure message=\"line1&#10;line2\">b</failure>
    </testcase>
  </testsuite>
</testsuites>
")

(deftest multiline-message-serializes-to-golden
  ;; A newline in a failure message must be encoded as a numeric reference so it
  ;; survives as an attribute value rather than being normalized to a space.
  (is (= multiline-xml (tr/results->junit-xml multiline-results))))

(deftest end-var-without-begin-leaves-zero-time
  (binding [tr/*results* (atom {:cases []})]
    (tr/pretty-report {:type :end-test-var})
    (let [c (first (:cases @tr/*results*))]
      (is (= 0 (:time c)) "no start recorded -> time stays at its 0 default")
      (is (not (contains? c :start)) ":start must not leak into the recorded case"))))

(defn run-fixture
  "Run the-ns through the real runner, writing XML to a temp file, and return
   {:summary <clojure.test summary> :doc <parsed XML root>}. Silences the
   runner's console + test output.

   Optional `test-filter` is the raw `--test_filter` / TESTBRIDGE_TEST_ONLY
   string (same as -main passes through after reading the env)."
  ([the-ns] (run-fixture the-ns nil))
  ([the-ns test-filter]
   (let [out (File/createTempFile "junit" ".xml")]
     ;; The JDK ships no Closeable that deletes a file, so reify one and let
     ;; with-open delete the temp file on scope exit.
     (with-open [_ (reify Closeable (close [_] (.delete out)))]
       (let [sink (PrintWriter. (StringWriter.))
             summary (binding [*out* sink, t/*test-out* sink]
                       (tr/run-ns the-ns (.getAbsolutePath out) test-filter))]
         {:summary summary :doc (parse (slurp out))})))))

(deftest passing-namespace-runs-clean
  (let [{:keys [summary doc]} (run-fixture 'rules-clojure.testrunner-fixtures.passing)
        suites (elements doc)
        cases (elements (first suites))]
    (is (zero? (:fail summary)))
    (is (zero? (:error summary)))
    (is (= 1 (count suites)) "one suite for the one namespace run")
    (is (= "rules-clojure.testrunner-fixtures.passing"
           (get-in (first suites) [:attrs :name])))
    (is (= 2 (count cases)))
    (is (every? #(empty? (elements %)) cases) "passing tests have no children")
    (is (every? #(<= 0 (Double/parseDouble (get-in % [:attrs :time]))) cases)
        "each testcase carries a non-negative time")))

(deftest mixed-namespace-classifies-and-counts
  (let [{:keys [summary doc]} (run-fixture 'rules-clojure.testrunner-fixtures.mixed)
        suite (first (elements doc))
        cases (by-name (elements suite))]
    (is (= 1 (:fail summary)))
    (is (= 1 (:error summary)))
    (is (= "3" (get-in suite [:attrs :tests])))
    (is (= "1" (get-in suite [:attrs :failures])))
    (is (= "1" (get-in suite [:attrs :errors])))
    (is (empty? (elements (cases "a-pass"))) "passing test is self-closing")
    (is (= [:failure] (map :tag (elements (cases "a-fail")))) "assertion failure -> <failure>")
    (is (= [:error] (map :tag (elements (cases "an-error")))) "thrown exception -> <error>")))

(deftest fixture-throw-becomes-errored-case
  (let [{:keys [summary doc]} (run-fixture 'rules-clojure.testrunner-fixtures.bad-fixture)
        cases (by-name (mapcat elements (elements doc)))]
    (is (= 1 (:error summary)) "a throwing :once fixture is one error")
    (is (contains? cases "fixture-error"))
    (is (= [:error] (map :tag (elements (cases "fixture-error")))))))

(deftest missing-namespace-becomes-load-error
  (let [{:keys [summary doc]} (run-fixture 'rules-clojure.testrunner-fixtures.does-not-exist)
        cases (by-name (mapcat elements (elements doc)))]
    (is (= 1 (:error summary)) "a namespace that won't load is one error")
    (is (contains? cases "load"))
    (is (= [:error] (map :tag (elements (cases "load")))))))

;; ----------------------------------------------------------------------------
;; --test_filter / TESTBRIDGE_TEST_ONLY
;; ----------------------------------------------------------------------------

(def filter-ns 'rules-clojure.testrunner-fixtures.filter)

(defn- filter-var
  "Resolve a var from the filter fixture after requiring it (avoid compile-time
   #'ns/name which fails when the fixture ns is only on the resource classpath)."
  [sym]
  (require filter-ns)
  (ns-resolve filter-ns sym))

(deftest var->test-name-is-fully-qualified
  (is (= "rules-clojure.testrunner-fixtures.filter/alpha-pass"
         (tr/var->test-name (filter-var 'alpha-pass)))))

(deftest test-filter->pred-blank-matches-everything
  (doseq [f [nil "" "   "]]
    (let [pred (tr/test-filter->pred f)]
      (is (true? (boolean (pred (filter-var 'alpha-pass)))))
      (is (true? (boolean (pred (filter-var 'gamma-fail))))))))

(deftest test-filter->pred-substring-and-regex
  (let [alpha (filter-var 'alpha-pass)
        gamma (filter-var 'gamma-fail)
        beta (filter-var 'beta-pass)]
    (is (true? ((tr/test-filter->pred "alpha-pass") alpha)))
    (is (false? ((tr/test-filter->pred "alpha-pass") gamma)))
    (is (true? ((tr/test-filter->pred "testrunner-fixtures.filter/alpha") alpha)))
    (is (true? ((tr/test-filter->pred "alpha|gamma") alpha)))
    (is (true? ((tr/test-filter->pred "alpha|gamma") gamma)))
    (is (false? ((tr/test-filter->pred "alpha|gamma") beta)))))

(deftest test-filter->pred-invalid-regex-throws-clear-error
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Invalid --test_filter regex"
                        (tr/test-filter->pred "*"))))

(deftest run-ns-no-filter-runs-all-filter-fixture
  (let [{:keys [summary]} (run-fixture filter-ns)]
    (is (= 3 (:test summary)))
    (is (= 2 (:pass summary)))
    (is (= 1 (:fail summary)))
    (is (= 1 (tr/exit-code nil summary)) "failures still fail the process")))

(deftest run-ns-substring-filter-selects-tests
  (testing "single passing test"
    (let [{:keys [summary doc]} (run-fixture filter-ns "alpha-pass")
          cases (by-name (mapcat elements (elements doc)))]
      (is (= 1 (:test summary)))
      (is (= 1 (:pass summary)))
      (is (= 0 (:fail summary)))
      (is (= 0 (tr/exit-code "alpha-pass" summary)))
      (is (= #{"alpha-pass"} (set (keys cases))) "JUnit XML lists only selected vars")))
  (testing "shared substring selects both passers"
    (let [{:keys [summary doc]} (run-fixture filter-ns "pass")
          cases (by-name (mapcat elements (elements doc)))]
      (is (= 2 (:test summary)))
      (is (= 2 (:pass summary)))
      (is (= 0 (:fail summary)))
      (is (= #{"alpha-pass" "beta-pass"} (set (keys cases))))))
  (testing "selecting only the failing test"
    (let [{:keys [summary]} (run-fixture filter-ns "gamma")]
      (is (= 1 (:test summary)))
      (is (= 1 (:fail summary)))
      (is (= 1 (tr/exit-code "gamma" summary))))))

(deftest run-ns-regex-filter
  (let [{:keys [summary]} (run-fixture filter-ns "alpha|beta")]
    (is (= 2 (:test summary)))
    (is (= 2 (:pass summary)))
    (is (= 0 (:fail summary)))))

(deftest run-ns-filter-no-match-is-process-failure
  "A typo in --test_filter must not look like a green empty suite."
  (let [{:keys [summary doc]} (run-fixture filter-ns "does-not-exist")
        cases (mapcat elements (elements doc))]
    (is (= 0 (:test summary)))
    (is (= 0 (:fail summary)))
    (is (= 0 (:error summary)))
    (is (tr/filter-miss? "does-not-exist" summary))
    (is (= 1 (tr/exit-code "does-not-exist" summary)))
    (is (empty? cases) "no testcases in XML when nothing matched")))

(deftest run-ns-invalid-filter-regex-is-error
  (let [{:keys [summary doc]} (run-fixture filter-ns "*")
        cases (by-name (mapcat elements (elements doc)))]
    (is (= 1 (:error summary)))
    (is (= 1 (tr/exit-code "*" summary)))
    (is (contains? cases "test-filter"))))

(deftest run-ns-filter-matches-mixed-fixture-xml
  "End-to-end through run-ns (the same entry -main uses after reading
   TESTBRIDGE_TEST_ONLY): filter + JUnit XML on the mixed fixture."
  (let [{:keys [summary doc]} (run-fixture 'rules-clojure.testrunner-fixtures.mixed "a-pass")
        suite (first (elements doc))
        cases (by-name (elements suite))]
    (is (= 1 (:test summary)))
    (is (zero? (:fail summary)))
    (is (zero? (:error summary)))
    (is (= "1" (get-in suite [:attrs :tests])))
    (is (= #{"a-pass"} (set (keys cases))))
    (is (empty? (elements (cases "a-pass"))))))
