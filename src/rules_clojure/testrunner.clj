(ns rules-clojure.testrunner
  (:require [clojure.pprint :refer [pprint]]
            [clojure.stacktrace :as stack]
            [clojure.test :as c.test])
  (:gen-class))

(defn pp-str [x]
  (with-out-str (pprint x)))

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
    (print "actual:\n" (pp-str (:actual m)))))

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
        (prn actual)))))

(defmethod pretty-report :summary [m]
  (c.test/with-test-out
    (println "\nRan" (:test m) "tests containing"
             (+ (:pass m) (:fail m) (:error m)) "assertions.")
    (println (:fail m) "failures," (:error m) "errors.")))

(defmethod pretty-report :begin-test-ns [m]
  (c.test/with-test-out
    (println "\nTesting" (ns-name (:ns m)))))

;; Ignore these message types:
(defmethod pretty-report :end-test-ns [_])
(defmethod pretty-report :begin-test-var [_])
(defmethod pretty-report :end-test-var [_])

(defn test-var-matches?
  "True if the test var `v` matches regex `re`, either by its (unqualified) var
   name or by its fully-qualified `namespace/name`. Uses `re-find`, so the
   filter matches if it occurs anywhere in the name (substring/partial match)."
  [re v]
  (let [m (meta v)
        var-name (str (:name m))
        full-name (str (ns-name (:ns m)) "/" var-name)]
    (boolean (or (re-find re var-name)
                 (re-find re full-name)))))

(defn ns-test-vars
  "Test vars (vars carrying `:test` metadata) interned in `ns-sym`. When `re` is
   non-nil, only vars matching it (see `test-var-matches?`) are returned. Result
   is sorted by var name for deterministic ordering."
  [ns-sym re]
  (cond->> (vals (ns-interns ns-sym))
    true (filter (comp :test meta))
    re (filter #(test-var-matches? re %))
    true (sort-by (comp :name meta))))

(defn run-ns-tests
  "Like `clojure.test/run-tests` for a single namespace, but only runs the test
   vars matching `re` (a regex derived from Bazel's `--test_filter`). When `re`
   is nil, every test var in the namespace is run. Fixtures are honoured via
   `clojure.test/test-vars`. Returns the summary report map."
  [ns-sym re]
  (let [ns-obj (the-ns ns-sym)]
    (binding [c.test/*report-counters* (ref c.test/*initial-report-counters*)]
      (c.test/do-report {:type :begin-test-ns :ns ns-obj})
      (c.test/test-vars (ns-test-vars ns-sym re))
      (c.test/do-report {:type :end-test-ns :ns ns-obj})
      (let [summary (assoc @c.test/*report-counters* :type :summary)]
        (c.test/do-report summary)
        summary))))

(defn -main [& args]
  (assert (string? (first args)) (print-str "first argument must be a string, got" args))
  (let [the-ns (-> args first symbol)
        ;; Bazel exposes the value of `--test_filter=<filter>` to the test
        ;; runner via the TESTBRIGE_TEST_ONLY environment variable.
        ;; https://bazel.build/reference/command-line-reference#flag--test_filter
        test-filter (System/getenv "TESTBRIGE_TEST_ONLY")
        re (when (seq test-filter) (re-pattern test-filter))]
    (try
      (require the-ns)
      (binding [c.test/report pretty-report]
        (let [test-report (run-ns-tests the-ns re)]
          (println test-report)
          (if (and (zero? (:fail test-report))
                   (zero? (:error test-report)))
            (System/exit 0)
            (System/exit 1))))
      (catch Throwable t
        (println t)
        (System/exit 1)))))
