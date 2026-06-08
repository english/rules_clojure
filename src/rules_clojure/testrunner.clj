(ns rules-clojure.testrunner
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
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

(defn run-tests
  "Default (non-coverage) path: require and run the test namespace with the
  pretty reporter. Returns an integer exit code."
  [test-ns]
  (try
    (require test-ns)
    (binding [c.test/report pretty-report]
      (let [test-report (clojure.test/run-tests test-ns)]
        (println test-report)
        (if (and (zero? (:fail test-report))
                 (zero? (:error test-report)))
          0
          1)))
    (catch Throwable t
      (println t)
      1)))

;; ---------------------------------------------------------------------------
;; Code coverage (bazel coverage)
;;
;; When run under `bazel coverage`, Bazel sets the COVERAGE_DIR environment
;; variable and expects the test process to drop one or more LCOV files into
;; it. Bazel's lcov_merger (inherited from java_test) then merges them and
;; `--combined_report=lcov` produces the final report.
;;
;; We instrument Clojure *source forms* with Cloverage, which emits true
;; Clojure line coverage (unlike JaCoCo bytecode coverage, which maps poorly
;; to .clj source and would require AOT). Cloverage is resolved lazily so it is
;; only needed on the classpath under coverage.
;; ---------------------------------------------------------------------------

(defn- relativize-sf
  "Cloverage emits classpath-relative SF: paths (e.g. `example/core.clj`).
  Bazel's lcov_merger keys coverage by the source file's workspace-relative
  path listed in COVERAGE_MANIFEST. Rewrite each SF: line to the manifest entry
  whose path ends with the classpath-relative path, so the merged report
  resolves to real source files."
  [lcov manifest-paths]
  (str/join
   "\n"
   (for [line (str/split-lines lcov)]
     (if (str/starts-with? line "SF:")
       (let [cp-rel (subs line 3)
             match  (some (fn [p]
                            (when (or (= p cp-rel)
                                      (str/ends-with? p (str "/" cp-rel)))
                              p))
                          manifest-paths)]
         (str "SF:" (or match cp-rel)))
       line))))

(defn- read-manifest
  "Read COVERAGE_MANIFEST (one workspace-relative path per line), if present."
  []
  (when-let [m (System/getenv "COVERAGE_MANIFEST")]
    (let [f (io/file m)]
      (when (.exists f)
        (->> (str/split-lines (slurp f))
             (remove str/blank?))))))

(defn run-coverage
  "Run `test-ns` under Cloverage instrumentation of `instrument-nses`, writing
  an LCOV report into COVERAGE_DIR. Returns the test exit code."
  [coverage-dir test-ns instrument-nses]
  (when (empty? instrument-nses)
    (println "WARNING: rules_clojure coverage: no namespaces to instrument."
             "Pass `instrument_ns` to clojure_test to measure coverage."))
  (let [parse-args (requiring-resolve 'cloverage.args/parse-args)
        run-main   (requiring-resolve 'cloverage.coverage/run-main)
        exit-var   (requiring-resolve 'cloverage.coverage/*exit-after-test*)
        sfp-var    (requiring-resolve 'cloverage.source/source-file-path)
        out-dir    (str coverage-dir "/cloverage")
        argv       (concat ["--lcov" "--no-html" "--no-text"
                            "--fail-threshold" "0"
                            "-o" out-dir
                            "-x" (str test-ns)]
                           (map str instrument-nses))]
    ;; Cloverage's lcov reporter recomputes the SF: path via the classloader and
    ;; throws on jar-resident resources (jar: URIs are opaque). Under Bazel all
    ;; sources are jar resources, so replace it with identity on the
    ;; classpath-relative path and fix the paths up ourselves afterwards.
    (alter-var-root sfp-var (constantly (fn [resource] resource)))
    ;; Don't let Cloverage call System/exit; we own the process exit code.
    (alter-var-root exit-var (constantly false))
    (let [code (if (seq instrument-nses)
                 (try
                   (run-main (parse-args argv {}) {})
                   (catch Throwable t
                     (println "ERROR: rules_clojure coverage failed while instrumenting"
                              (pr-str instrument-nses) ":" (.getMessage t))
                     (println "Cloverage instruments .clj SOURCE, so the source of each"
                              "instrumented namespace must be on the test runtime classpath"
                              "(e.g. a java_library shipping the raw .clj as resources, added"
                              "to the clojure_test runtime_deps). The AOT jar contains only"
                              ".class files.")
                     (throw t)))
                 ;; nothing to instrument: just run the tests
                 (run-tests test-ns))
          lcov-file (io/file out-dir "lcov.info")]
      (when (.exists lcov-file)
        (let [fixed (relativize-sf (slurp lcov-file) (read-manifest))
              dest  (io/file coverage-dir (str (str/replace (str test-ns) #"[^A-Za-z0-9_.-]" "_") ".dat"))]
          (spit dest fixed)
          (println "Wrote coverage report to" (.getAbsolutePath dest))))
      code)))

(defn -main [& args]
  (assert (string? (first args)) (print-str "first argument must be a string, got" args))
  (let [test-ns        (-> args first symbol)
        instrument-nses (map symbol (rest args))
        coverage-dir   (System/getenv "COVERAGE_DIR")
        code (if (and coverage-dir (not (str/blank? coverage-dir)))
               (run-coverage coverage-dir test-ns instrument-nses)
               (run-tests test-ns))]
    (System/exit (int code))))
