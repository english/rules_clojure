(ns example.core
  (:gen-class))

(defn classify
  "Classify a number. Used to demonstrate partial line coverage: the tests
  only exercise the positive branch."
  [n]
  (if (pos? n)
    :positive
    :non-positive))

(defn unused
  "Never exercised by the tests, so it should show up as uncovered."
  [x]
  (* x x))

(defn -main []
  (println "hello world!"))
