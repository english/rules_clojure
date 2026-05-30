(ns rules-clojure.testrunner-test
  (:require [clojure.test :as t :refer [deftest is]]
            [clojure.xml :as xml]
            [rules-clojure.testrunner :as tr])
  (:import [java.io ByteArrayInputStream]))

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

(deftest well-formed-and-one-suite-per-namespace
  (let [doc (parse (tr/results->junit-xml sample))
        suites (elements doc)]
    (is (= :testsuites (:tag doc)))
    (is (= 2 (count suites)) "one <testsuite> per namespace")
    (is (= ["my.ns" "other.ns"] (map (comp :name :attrs) suites))
        "namespaces appear in first-seen order")))

(deftest counts-aggregated-per-suite
  (let [doc (parse (tr/results->junit-xml sample))
        my-ns (first (elements doc))
        other-ns (second (elements doc))]
    (is (= "2" (get-in my-ns [:attrs :tests])))
    (is (= "1" (get-in my-ns [:attrs :failures])))
    (is (= "0" (get-in my-ns [:attrs :errors])))
    (is (= "0" (get-in other-ns [:attrs :failures])))
    (is (= "1" (get-in other-ns [:attrs :errors])))))

(deftest passing-test-is-self-closing
  (let [doc (parse (tr/results->junit-xml sample))
        cases (elements (first (elements doc)))
        passing (first cases)]
    (is (= "passing-test" (get-in passing [:attrs :name])))
    (is (empty? (elements passing)) "a passing test has no <failure>/<error> children")))

(deftest special-characters-round-trip
  (let [xml (tr/results->junit-xml sample)]
    (is (not (re-find #"boom <&>" xml))
        "raw special characters must be escaped in the serialized string")
    (let [doc (parse xml)
          text (->> (elements doc)
                    (mapcat elements)     ; testcases
                    (mapcat elements)     ; <failure>/<error>
                    (mapcat :content)     ; text nodes
                    (filter string?)
                    (apply str))]
      (is (re-find #"boom <&> \"quote\"" text)
          "escaped text decodes back to the original"))))

(deftest empty-results-still-valid
  (let [doc (parse (tr/results->junit-xml {:cases []}))]
    (is (= :testsuites (:tag doc)))
    (is (empty? (elements doc)))))

;; ---------------------------------------------------------------------------
;; Report-hook / accumulator coverage
;;
;; These drive tr/pretty-report the way clojure.test would, capturing into a
;; freshly-bound *results* atom, so we cover the live hot path (not just the
;; pure serializer). Console output is silenced via *test-out*.
;; ---------------------------------------------------------------------------

(defn capture
  "Run f with a fresh *results* atom and pretty-report bound, returning the
   accumulated cases. Silences the runner's console output and isolates
   *report-counters* so simulated :pass/:fail/:error events don't leak into the
   enclosing run's summary."
  [f]
  (binding [tr/*results* (atom {:cases []})
            t/*report-counters* (ref {})
            t/*test-out* (java.io.PrintWriter. (java.io.StringWriter.))]
    (f)
    (:cases @tr/*results*)))

(defn- ev
  "Synthesize a clojure.test report event for var named `nm`."
  [type nm & {:as extra}]
  (merge {:type type
          :var (with-meta (fn []) {:name (symbol nm) :ns (the-ns 'rules-clojure.testrunner-test)})}
         extra))

(deftest begin-end-var-records-one-timed-case
  (let [cases (capture (fn []
                         (tr/pretty-report (ev :begin-test-var "my-test"))
                         (tr/pretty-report {:type :pass})
                         (tr/pretty-report (ev :end-test-var "my-test"))))
        c (first cases)]
    (is (= 1 (count cases)))
    (is (= "my-test" (:name c)))
    (is (= "rules-clojure.testrunner-test" (:classname c)))
    (is (and (number? (:time c)) (<= 0 (:time c))) "duration is a non-negative number")
    (is (not (contains? c :start)) ":start must not leak into the recorded case")))

(deftest fail-and-error-attach-to-current-case
  (let [cases (capture (fn []
                         (tr/pretty-report (ev :begin-test-var "t"))
                         (tr/pretty-report (ev :fail "t" :expected 1 :actual 2 :message "m"))
                         (tr/pretty-report (ev :error "t" :expected 1
                                               :actual (ex-info "boom" {})))
                         (tr/pretty-report (ev :end-test-var "t"))))
        c (first cases)]
    (is (= 1 (count (:failures c))))
    (is (= 1 (count (:errors c))))
    (is (re-find #"boom" (get-in c [:errors 0 :body])) "throwable actual is rendered")))

(deftest end-var-without-begin-leaves-zero-time
  ;; Regression: a missing :start must not produce a ~10^9s duration.
  (let [cases (capture (fn [] (tr/pretty-report {:type :end-test-var})))
        c (first cases)]
    (is (= 0 (:time c)) "no start recorded -> time stays at its 0 default")
    (is (not (contains? c :start)))))

(deftest multiline-message-stays-in-attribute
  ;; A failure message with a newline must survive as an attribute value: the
  ;; raw string must not contain a literal newline inside the message="...",
  ;; and after parsing the attribute must read back with the newline intact.
  (let [results {:cases [{:name "t" :classname "my.ns" :time 0.0
                          :failures [{:message "line1\nline2" :body "b"}]
                          :errors []}]}
        xml (tr/results->junit-xml results)
        doc (parse xml)
        failure (->> (elements doc) (mapcat elements) (mapcat elements) first)]
    (is (not (re-find #"message=\"line1\nline2" xml))
        "newline must be encoded, not left literal in the attribute")
    (is (= "line1\nline2" (get-in failure [:attrs :message]))
        "parser decodes the encoded newline back to the original")))
