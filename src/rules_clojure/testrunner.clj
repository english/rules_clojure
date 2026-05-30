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
;; results instead of synthesizing a bare pass/fail from the exit code.
;; ----------------------------------------------------------------------------

(def ^:dynamic *results*
  "Atom holding {:cases [testcase ...]}. Each testcase is
   {:name :classname :time :failures [] :errors []}. The live path also carries
   a transient :start (nanoTime) between :begin-test-var and :end-test-var."
  (atom {:cases []}))

(defn- make-testcase
  "Canonical empty testcase. Centralizes the shape so the normal path and the
   error paths can't drift apart. :time defaults to 0 for cases that never see
   an :end-test-var event (e.g. a load failure)."
  [name classname]
  {:name name :classname classname
   :time 0 :failures [] :errors []})

(defn- update-current!
  "Apply f to the testcase currently under test. If a report event arrives with
   no active test var (clojure.test does not pair every event with a var — e.g.
   a stray :error reported outside test-var), synthesize a placeholder case so
   the event is still reported rather than lost. Note that fixture exceptions do
   not reach here: run-tests lets them propagate, and -main attributes them."
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
  "Build a {:message :body} report detail, deferring how `actual` is rendered."
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
;; Pretty console reporting (kept from the original runner) + result recording
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
     ;; :start is only present when a matching :begin-test-var ran; without it
     ;; we can't compute a duration, so leave :time at its 0 default rather than
     ;; measuring from nanoTime 0 (which would yield a ~10^9s bogus value).
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
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")
      (str/replace #"[\x00-\x08\x0B\x0C\x0E-\x1F]" "")))

(defn- xml-escape-attr
  "Like xml-escape, but also encodes the whitespace characters that an XML
   parser would otherwise normalize away inside an attribute value (tab,
   newline, carriage return) as numeric character references."
  [s]
  (-> (xml-escape s)
      (str/replace "\t" "&#9;")
      (str/replace "\n" "&#10;")
      (str/replace "\r" "&#13;")))

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
        failures (reduce + (map (comp count :failures) cases))
        errors (reduce + (map (comp count :errors) cases))
        time (reduce + (map (comp double #(or % 0) :time) cases))]
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
        suites (for [n (distinct (map :classname cases))]
                 [n (grouped n)])]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<testsuites>\n"
         (str/join "\n" (map (fn [[n cs]] (testsuite->xml n cs)) suites))
         (when (seq suites) "\n")
         "</testsuites>\n")))

(defn- write-xml!
  "Serialize results to $XML_OUTPUT_FILE when Bazel has set it. Best-effort: a
   write failure must never mask the test outcome or a load error, so it is
   logged rather than thrown."
  [results]
  (when-let [out (System/getenv "XML_OUTPUT_FILE")]
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

(defn -main [& args]
  (assert (string? (first args)) (print-str "first argument must be a string, got" args))
  (let [the-ns (-> args first symbol)]
    (binding [*results* (atom {:cases []})]
      (let [test-report
            (try
              ;; The load phase: a failure here (won't compile/require) happens
              ;; before any test runs, so there are no results to preserve.
              (require the-ns)
              (try
                ;; The execution phase: run-tests reports per-test results into
                ;; *results* as it goes. clojure.test lets fixture exceptions
                ;; (:once / :each) propagate out of run-tests rather than
                ;; catching them, so a throw here is a fixture/runtime error
                ;; that may follow already-recorded results — attribute it as a
                ;; distinct errored case rather than a "load" failure.
                (binding [c.test/report pretty-report]
                  (clojure.test/run-tests the-ns))
                (catch Throwable t
                  (record-error! "fixture-error" (str the-ns)
                                 (str "Error running tests in " the-ns) t)
                  (println t)
                  {:fail 0 :error 1}))
              (catch Throwable t
                (record-error! "load" (str the-ns)
                               (str "Failed to load " the-ns) t)
                (println t)
                {:fail 0 :error 1}))]
        (write-xml! @*results*)
        (println test-report)
        (if (and (zero? (:fail test-report))
                 (zero? (:error test-report)))
          (System/exit 0)
          (System/exit 1))))))
