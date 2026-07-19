(ns rules-clojure.testrunner
  (:require [clojure.pprint :refer [pprint]]
            [clojure.stacktrace :as stack]
            [clojure.string :as str]
            [clojure.test :as c.test])
  (:gen-class))

(defn pp-str [x]
  (with-out-str (pprint x)))

;; ----------------------------------------------------------------------------
;; Result accumulation
;;
;; clojure.test reports its progress through the `report` multimethod. We piggy
;; back on the var-level lifecycle events (:begin-test-var / :end-test-var) to
;; build one JUnit `<testcase>` per `deftest`, recording any failures/errors and
;; per-test timing along the way. The collected data is later serialized to the
;; file named by $XML_OUTPUT_FILE so Bazel can surface structured, per-test
;; results.
;; ----------------------------------------------------------------------------

(def ^:dynamic *results*
  "Atom holding {:cases [testcase ...]}. Each testcase is
   {:name :classname :time :failures [] :errors []}. The live path also carries
   a transient :start (nanoTime) between :begin-test-var and :end-test-var."
  (atom {:cases []}))

(defn- make-testcase
  [name classname]
  {:name name :classname classname
   :time 0 :failures [] :errors []})

(defn- update-current!
  "If a report event arrives with
   no active test var (clojure.test does not pair every event with a var — e.g.
   a stray :error reported outside test-var), synthesize a placeholder case so
   the event is still reported rather than lost."
  [f]
  (swap! *results* update :cases
         (fn [cases]
           (if (seq cases)
             (conj (pop cases) (f (peek cases)))
             [(f (make-testcase "uncaught" "unknown"))]))))

(defn- contexts-str []
  (when (seq c.test/*testing-contexts*)
    (str (c.test/testing-contexts-str) "\n")))

(defn- detail
  [m render-actual]
  {:message (c.test/testing-vars-str m)
   :body (str (when-let [msg (:message m)] (str msg "\n"))
              (contexts-str)
              "expected: " (pp-str (:expected m)) "\n"
              "actual: " (render-actual (:actual m)))})

(defn- fail->detail [m] (detail m pp-str))

(defn- error->detail [m]
  (detail m (fn [actual]
              (if (instance? Throwable actual)
                (with-out-str (stack/print-cause-trace actual c.test/*stack-trace-depth*))
                (pp-str actual)))))

;; ----------------------------------------------------------------------------
;; Pretty console reporting + result recording
;; ----------------------------------------------------------------------------

(defmulti
  ^{:doc "Prettier report printing method.
    Code is taken from clojure.test, with some added pretty-printing."}
  pretty-report :type)

(defmethod pretty-report :default [m]
  (c.test/with-test-out (prn m)))

(defmethod pretty-report :pass [_]
  (c.test/with-test-out (c.test/inc-report-counter :pass)))

(defmethod pretty-report :fail [m]
  (c.test/with-test-out
    (c.test/inc-report-counter :fail)
    (println "\nFAIL in" (c.test/testing-vars-str m))
    (when (seq c.test/*testing-contexts*) (println (c.test/testing-contexts-str)))
    (when-let [message (:message m)] (println message))
    (print "expected:\n" (pp-str (:expected m)))
    (print "actual:\n" (pp-str (:actual m))))
  (update-current! (fn [c] (update c :failures conj (fail->detail m)))))

(defmethod pretty-report :error [m]
  (c.test/with-test-out
    (c.test/inc-report-counter :error)
    (println "\nERROR in" (c.test/testing-vars-str m))
    (when (seq c.test/*testing-contexts*) (println (c.test/testing-contexts-str)))
    (when-let [message (:message m)] (println message))
    (print "expected:\n" (pp-str (:expected m)))
    (print "actual: ")
    (let [actual (:actual m)]
      (if (instance? Throwable actual)
        (stack/print-cause-trace actual c.test/*stack-trace-depth*)
        (prn actual))))
  (update-current! (fn [c] (update c :errors conj (error->detail m)))))

(defmethod pretty-report :summary [m]
  (c.test/with-test-out
    (println "\nRan" (:test m) "tests containing"
             (+ (:pass m) (:fail m) (:error m)) "assertions.")
    (println (:fail m) "failures," (:error m) "errors.")))

(defmethod pretty-report :begin-test-ns [m]
  (c.test/with-test-out
    (println "\nTesting" (ns-name (:ns m)))))

(defmethod pretty-report :begin-test-var [m]
  (let [vm (meta (:var m))]
    (swap! *results* update :cases conj
           (assoc (make-testcase (str (:name vm)) (str (ns-name (:ns vm))))
                  :start (System/nanoTime)))))

(defmethod pretty-report :end-test-var [_]
  (update-current!
   (fn [c]
     (-> (if-let [start (:start c)]
           (assoc c :time (/ (double (- (System/nanoTime) start)) 1e9))
           c)
         (dissoc :start)))))

;; Ignore this message type:
(defmethod pretty-report :end-test-ns [_])

;; ----------------------------------------------------------------------------
;; JUnit XML serialization
;; ----------------------------------------------------------------------------

(defn- xml-escape
  "Escape markup characters for use in element text content, and strip
   characters that are illegal in XML 1.0."
  [s]
  (-> (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&apos;"})
      (str/replace #"[\x00-\x08\x0B\x0C\x0E-\x1F]" "")))

(defn- xml-escape-attr
  "Like xml-escape, but also encodes the whitespace characters that an XML
   parser would otherwise normalize away inside an attribute value (tab,
   newline, carriage return) as numeric character references."
  [s]
  (str/escape (xml-escape s) {\tab "&#9;" \newline "&#10;" \return "&#13;"}))

(defn- fmt-time
  "Format seconds with a fixed locale so the decimal separator is always '.'."
  [t]
  (String/format java.util.Locale/US "%.3f" (object-array [(double (or t 0))])))

(defn- detail->xml [tag {:keys [message body]}]
  (str "    <" tag " message=\"" (xml-escape-attr message) "\">"
       (xml-escape body)
       "</" tag ">"))

(defn- testcase->xml [{:keys [name classname time failures errors]}]
  (let [children (concat (map #(detail->xml "failure" %) failures)
                         (map #(detail->xml "error" %) errors))
        open (str "    <testcase name=\"" (xml-escape-attr name)
                  "\" classname=\"" (xml-escape-attr classname)
                  "\" time=\"" (fmt-time time) "\"")]
    (if (seq children)
      (str open ">\n" (str/join "\n" children) "\n    </testcase>")
      (str open "/>"))))

(defn- testsuite->xml [suite-name cases]
  (let [tests (count cases)
        failures (transduce (map (comp count :failures)) + cases)
        errors (transduce (map (comp count :errors)) + cases)
        time (transduce (map (comp double #(or % 0) :time)) + cases)]
    (str "  <testsuite name=\"" (xml-escape-attr suite-name)
         "\" tests=\"" tests
         "\" failures=\"" failures
         "\" errors=\"" errors
         "\" time=\"" (fmt-time time) "\">\n"
         (str/join "\n" (map testcase->xml cases))
         "\n  </testsuite>")))

(defn results->junit-xml
  "Serialize accumulated results to a JUnit XML string. Testcases are grouped
   into one `<testsuite>` per namespace, in first-seen order."
  [{:keys [cases]}]
  (let [grouped (group-by :classname cases)
        classnames (into [] (comp (map :classname) (distinct)) cases)]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<testsuites>\n"
         (str/join "\n" (map #(testsuite->xml % (grouped %)) classnames))
         (when (seq classnames) "\n")
         "</testsuites>\n")))

(defn- write-xml!
  "Serialize results to `out` (a file path) when it is non-nil. Best-effort: a
   write failure must never mask the test outcome or a load error, so it is
   logged rather than thrown."
  [out results]
  (when out
    (try
      (spit out (results->junit-xml results))
      (catch Throwable t
        (println "Failed to write" out ":" t)))))

(defn- record-error!
  "Append a synthetic errored testcase to *results* describing a Throwable that
   escaped the normal per-test reporting (a load failure or a fixture throw).
   Any cases recorded before the throw are preserved."
  [name classname message t]
  (swap! *results* update :cases conj
         (-> (make-testcase name classname)
             (update :errors conj
                     {:message message
                      :body (with-out-str (stack/print-cause-trace t))}))))

;; ----------------------------------------------------------------------------
;; --test_filter (TESTBRIDGE_TEST_ONLY)
;; ----------------------------------------------------------------------------

(defn var->test-name
  "Fully-qualified name of a test var, e.g. \"foo.bar-test/my-test\"."
  [v]
  (let [m (meta v)]
    (str (ns-name (:ns m)) "/" (:name m))))

(defn test-filter->pred
  "Build a predicate on test vars from bazel's `--test_filter` value
  (`TESTBRIDGE_TEST_ONLY`).

  Blank/nil matches every test. Otherwise the filter is a regular expression
  matched with `re-find` against each var's fully-qualified name
  (`ns/test-name`), so a plain string acts as a substring match.

  Throws `ex-info` with a clear message if the pattern is not a valid regex."
  [test-filter]
  (if (str/blank? test-filter)
    (constantly true)
    (let [pattern
          (try
            (re-pattern test-filter)
            (catch java.util.regex.PatternSyntaxException e
              (throw (ex-info
                      (str "Invalid --test_filter regex: "
                           (pr-str test-filter)
                           " (" (.getMessage e) ")")
                      {:test-filter test-filter}
                      e))))]
      (fn [v]
        (boolean (re-find pattern (var->test-name v)))))))

(defn- test-vars-for-ns
  "Test vars in `ns-obj` matching `pred`, in a stable order by var name."
  [ns-obj pred]
  (->> (ns-interns ns-obj)
       vals
       (filter (comp :test meta))
       (filter pred)
       (sort-by (comp str :name meta))
       vec))

(defn- run-ns-tests*
  "Run selected test vars in an already-loaded namespace. Mirrors a single-ns
  `clojure.test/run-tests` (begin/end-ns + summary) but only runs `vars`.
  Suite `:once` fixtures still run via `test-vars` even when filtering."
  [ns-obj vars]
  (binding [c.test/*report-counters* (ref c.test/*initial-report-counters*)]
    (c.test/do-report {:type :begin-test-ns :ns ns-obj})
    (c.test/test-vars vars)
    (c.test/do-report {:type :end-test-ns :ns ns-obj})
    (let [summary (assoc @c.test/*report-counters* :type :summary)]
      (c.test/do-report summary)
      summary)))

(defn filter-miss?
  "True when a non-blank `--test_filter` selected zero tests and nothing failed
  or errored (including load/fixture errors)."
  [test-filter summary]
  (and (not (str/blank? test-filter))
       (zero? (or (:test summary) 0))
       (zero? (or (:fail summary) 0))
       (zero? (or (:error summary) 0))))

(defn exit-code
  "Process exit code for a completed run. Non-zero on assertion failures,
  errors, or a non-blank filter that matched no tests (typo guard)."
  [test-filter summary]
  (if (or (filter-miss? test-filter summary)
          (pos? (or (:fail summary) 0))
          (pos? (or (:error summary) 0)))
    1
    0))

(defn run-ns
  "Run tests in `ns-sym`, write a JUnit XML report to `out-path` when it is
   non-nil, and return the clojure.test summary.

   `test-filter` is the raw `--test_filter` / `TESTBRIDGE_TEST_ONLY` string
   (nil/blank = run all tests). Performs no System/exit and does not read the
   environment, so tests can drive it directly.

   Only selected vars are reported in JUnit XML. A non-blank filter that
   matches nothing prints a warning; callers should treat that as failure via
   `exit-code`."
  ([ns-sym out-path]
   (run-ns ns-sym out-path nil))
  ([ns-sym out-path test-filter]
   (binding [*results* (atom {:cases []})]
     (let [summary
           (try
             (require ns-sym)
             (try
               ;; Per-test results go into *results* via pretty-report.
               ;; clojure.test lets fixture exceptions (:once / :each) propagate
               ;; out of test-vars rather than catching them, so a throw here is
               ;; a fixture/runtime error that may follow already-recorded
               ;; results — attribute it as a distinct errored case rather than
               ;; a "load" failure.
               (binding [c.test/report pretty-report]
                 (let [ns-obj (find-ns ns-sym)
                       pred (test-filter->pred test-filter)
                       vars (test-vars-for-ns ns-obj pred)]
                   (when (and (not (str/blank? test-filter)) (empty? vars))
                     (println
                      (str "WARNING: --test_filter matched 0 tests in " ns-sym
                           " (filter=" (pr-str test-filter) "). "
                           "Patterns match each test's fully-qualified name "
                           "(ns/test-name), not JUnit Class#method syntax.")))
                   (run-ns-tests* ns-obj vars)))
               (catch clojure.lang.ExceptionInfo e
                 (if (:test-filter (ex-data e))
                   ;; Invalid filter regex from test-filter->pred
                   (do
                     (println (.getMessage e))
                     (record-error! "test-filter" (str ns-sym)
                                    (.getMessage e) e)
                     {:fail 0 :error 1 :test 0})
                   ;; Fixture/code may throw ExceptionInfo too
                   (do
                     (record-error! "fixture-error" (str ns-sym)
                                    (str "Error running tests in " ns-sym) e)
                     (println e)
                     {:fail 0 :error 1})))
               (catch Throwable t
                 (record-error! "fixture-error" (str ns-sym)
                                (str "Error running tests in " ns-sym) t)
                 (println t)
                 {:fail 0 :error 1}))
             (catch Throwable t
               (record-error! "load" (str ns-sym)
                              (str "Failed to load " ns-sym) t)
               (println t)
               {:fail 0 :error 1}))]
       (write-xml! out-path @*results*)
       summary))))

(defn -main [& args]
  (assert (string? (first args)) (print-str "first argument must be a string, got" args))
  ;; Bazel passes --test_filter to the test action as TESTBRIDGE_TEST_ONLY.
  (let [test-filter (System/getenv "TESTBRIDGE_TEST_ONLY")
        summary (run-ns (symbol (first args))
                        (System/getenv "XML_OUTPUT_FILE")
                        test-filter)
        code (exit-code test-filter summary)]
    (println summary)
    (when (filter-miss? test-filter summary)
      (println "ERROR: exiting non-zero because --test_filter matched 0 tests."))
    (System/exit code)))
