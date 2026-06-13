(ns rules-clojure.testrunner
  (:require [clojure.pprint :refer [pprint]]
            [clojure.stacktrace :as stack]
            [clojure.string :as str]
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

(defn var->test-name
  "Fully-qualified name of a test var, e.g. \"foo.bar-test/my-test\"."
  [v]
  (let [m (meta v)]
    (str (ns-name (:ns m)) "/" (:name m))))

(defn test-filter->pred
  "Given the raw value of bazel's `--test_filter` flag (passed to the test
  runner via the `TESTBRIDGE_TEST_ONLY` environment variable), return a
  predicate matching test vars.

  A blank/nil filter matches every test. Otherwise the filter is treated as a
  regular expression and matched (via `re-find`) against each test's
  fully-qualified name, `ns/test-name`. A plain string therefore acts as a
  substring match, e.g. `--test_filter=my-test` runs every test whose name
  contains `my-test`."
  [test-filter]
  (if (str/blank? test-filter)
    (constantly true)
    (let [pattern (re-pattern test-filter)]
      (fn [v]
        (boolean (re-find pattern (var->test-name v)))))))

(defn run-ns-tests
  "Run the `clojure.test` test vars in `ns-sym` whose var matches `pred`.

  Mirrors `clojure.test/run-tests` for a single namespace, reporting
  `:begin-test-ns`/`:end-test-ns`/`:summary` and returning the summary map, but
  only the vars selected by `pred` are run. This lets bazel's `--test_filter`
  select a subset of tests within a namespace."
  [ns-sym pred]
  (let [ns-obj (the-ns ns-sym)
        vars (->> (ns-interns ns-obj)
                  vals
                  (filter (comp :test meta))
                  (filter pred))]
    (binding [c.test/*report-counters* (ref c.test/*initial-report-counters*)]
      (c.test/do-report {:type :begin-test-ns :ns ns-obj})
      (c.test/test-vars vars)
      (c.test/do-report {:type :end-test-ns :ns ns-obj})
      (let [summary (assoc @c.test/*report-counters* :type :summary)]
        (c.test/do-report summary)
        summary))))

(defn -main [& args]
  (assert (string? (first args)) (print-str "first argument must be a string, got" args))
  (let [the-ns (-> args first symbol)
        pred (test-filter->pred (System/getenv "TESTBRIDGE_TEST_ONLY"))]
    (try
      (require the-ns)
      (binding [c.test/report pretty-report]
        (let [test-report (run-ns-tests the-ns pred)]
          (println test-report)
          (if (and (zero? (:fail test-report))
                   (zero? (:error test-report)))
            (System/exit 0)
            (System/exit 1))))
      (catch Throwable t
        (println t)
        (System/exit 1)))))
