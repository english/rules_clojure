(ns rules-clojure.pin-deps-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rules-clojure.fs :as fs]
            [rules-clojure.pin-deps :as pin])
  (:import [java.util.jar JarEntry JarOutputStream Manifest]
           [java.io FileOutputStream]))

(defn- write-fake-jar!
  "Minimal jar with one .clj source entry for scanning."
  [jar-path class-rel content]
  (fs/ensure-directory (fs/dirname jar-path))
  (with-open [jos (JarOutputStream. (FileOutputStream. (fs/path->file jar-path))
                                    (Manifest.))]
    (let [e (JarEntry. class-rel)]
      (.putNextEntry jos e)
      (.write jos (.getBytes ^String content "UTF-8"))
      (.closeEntry jos))))

(deftest maven-only-rejects-git
  (is (thrown-with-msg? Exception #"only Maven"
                        (pin/basis->rje-lock
                         {:libs {'io.github.foo/bar {:git/sha "abc" :git/url "https://x"}}
                          :classpath {}
                          :classpath-roots []
                          :mvn/repos {}}))))

(deftest basis->rje-lock-builds-artifacts
  (let [tmp (fs/new-temp-dir "pin-deps-test")
        jar (fs/->path tmp "org/clojure/clojure/1.12.1/clojure-1.12.1.jar")]
    (try
      (write-fake-jar! jar "clojure/core.clj" "(ns clojure.core)")
      (let [lib 'org.clojure/clojure
            basis {:libs {lib {:mvn/version "1.12.1"}}
                   :classpath {(str jar) {:lib-name lib}}
                   :classpath-roots [(str jar)]
                   :mvn/repos {"central" {:url "https://repo1.maven.org/maven2/"}}}
            ;; ->lib->deps needs trace meta; empty is ok for this unit test
            basis (with-meta basis {})
            {:keys [lock artifacts-list aot-manifest]} (pin/basis->rje-lock basis)]
        (is (= "2" (:version lock)))
        (is (get-in lock [:artifacts "org.clojure:clojure" :shasums :jar]))
        (is (= "1.12.1" (get-in lock [:artifacts "org.clojure:clojure" :version])))
        (is (some #{"org.clojure:clojure:1.12.1"} artifacts-list))
        (is (contains? aot-manifest "org.clojure/clojure")))
      (finally
        (fs/rm-rf tmp)))))
