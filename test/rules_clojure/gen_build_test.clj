(ns rules-clojure.gen-build-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rules-clojure.fs :as fs]
            [rules-clojure.gen-build :as gb]
            [rules-clojure.jar :as jar]
            [rules-clojure.test-utils :as test-utils])
  (:import (java.util.jar JarFile)))

(defn- make-temp-dir
  "Create a temp directory with a src/ subdirectory (matching basis :paths)."
  []
  (let [dir (.toFile (fs/new-temp-dir "gen-build-test"))]
    (.mkdirs (io/file dir "src" "example"))
    dir))

(defn- write-file [dir name content]
  (let [f (io/file dir "src" "example" name)]
    (spit f content)
    (fs/->path (.getAbsolutePath f))))

(defn- minimal-args
  "Build a minimal args map for ns-rules / gen-dir with the given dep-ns->label map."
  [dir dep-ns->label]
  (let [dir-path (fs/->path (.getAbsolutePath dir))]
    {:src-ns->label {}
     :dep-ns->label dep-ns->label
     :jar->lib {}
     :class->jar {}
     :deps-repo-tag "@deps"
     :deps-bazel {}
     :deps-edn-dir dir-path
     :basis {:paths ["src"]
             :classpath {}}}))

(defn- extract-deps
  "Given ns-rules output, extract the deps list from the first clojure_library rule."
  [rules-output]
  (:deps (:attrs (first rules-output))))

(deftest ns-rules-no-cross-platform-deps
  (testing "a .clj file's requires should not be resolved via the CLJS dep map"
    (let [dir (make-temp-dir)
          ;; Create .clj and .cljs files sharing a basename
          clj-path (write-file dir "api_test.clj"
                               "(ns example.api-test\n  (:require [reitit.core :as r]\n            [clojure.test :refer [deftest]]))")
          cljs-path (write-file dir "api_test.cljs"
                                "(ns example.api-test\n  (:require [reagent.core :as reagent]))")
          ;; dep-ns->label with different labels per platform
          dep-ns->label {:clj  {'reitit.core      "ns_metosin_reitit_core_reitit_core"
                                'clojure.test     "org_clojure_clojure"
                                'reagent.core     "ns_reagent_reagent_reagent_core"}
                         :cljs {'reitit.core      "metosin_reitit_core"
                                'reagent.core     "reagent_reagent"}}
          args (minimal-args dir dep-ns->label)
          result (gb/ns-rules args [clj-path cljs-path])
          deps (extract-deps result)]
      (try
        ;; Should include CLJ resolution of reitit.core (AOT label)
        (is (some #(= "@deps//:ns_metosin_reitit_core_reitit_core" %) deps)
            "CLJ require should resolve via CLJ dep map to AOT label")
        ;; Should include CLJS resolution of reagent.core (plain label)
        (is (some #(= "@deps//:reagent_reagent" %) deps)
            "CLJS require should resolve via CLJS dep map to plain label")
        ;; Should NOT include CLJS resolution of reitit.core (plain label)
        ;; This was the bug: .clj file crossed with :cljs platform
        (is (not (some #(= "@deps//:metosin_reitit_core" %) deps))
            "CLJ require must NOT be resolved via CLJS dep map")
        ;; Should NOT include CLJ resolution of reagent.core (AOT label)
        (is (not (some #(= "@deps//:ns_reagent_reagent_reagent_core" %) deps))
            "CLJS require must NOT be resolved via CLJ dep map")
        (finally
          (fs/rm-rf (.toPath dir)))))))

(deftest ns-rules-cljc-resolves-both-platforms
  (testing "a .cljc file's requires should be resolved via both CLJ and CLJS dep maps"
    (let [dir (make-temp-dir)
          cljc-path (write-file dir "shared.cljc"
                                "(ns example.shared\n  (:require [clojure.string :as str]))")
          dep-ns->label {:clj  {'clojure.string "ns_org_clojure_clojure_clojure_string"}
                         :cljs {'clojure.string "org_clojure_clojurescript"}}
          args (minimal-args dir dep-ns->label)
          result (gb/ns-rules args [cljc-path])
          deps (extract-deps result)]
      (try
        ;; .cljc should get deps from BOTH platforms
        (is (some #(= "@deps//:ns_org_clojure_clojure_clojure_string" %) deps)
            "cljc require should resolve via CLJ dep map")
        (is (some #(= "@deps//:org_clojure_clojurescript" %) deps)
            "cljc require should resolve via CLJS dep map")
        (finally
          (fs/rm-rf (.toPath dir)))))))

(deftest ns-rules-cljc-reader-conditional-branches-differ
  (testing "platform-split require in a .cljc file picks the right branch per platform.
            Regression: clojure.core/read silently returns the :clj branch even when
            :features #{:cljs} is set, so the cljs-only require was dropped."
    (let [dir (make-temp-dir)
          cljc-path (write-file dir "split.cljc"
                                "(ns example.split (:require #?(:clj  [clojure.spec.alpha :as s]
                                                                :cljs [cljs.spec.alpha :as s])))")
          dep-ns->label {:clj  {'clojure.spec.alpha "ns_org_clojure_spec_alpha_clojure_spec_alpha"}
                         :cljs {'cljs.spec.alpha    "org_clojure_clojurescript"}}
          args (minimal-args dir dep-ns->label)
          result (gb/ns-rules args [cljc-path])
          deps (extract-deps result)]
      (try
        (is (some #(= "@deps//:ns_org_clojure_spec_alpha_clojure_spec_alpha" %) deps)
            ":clj branch require should resolve")
        (is (some #(= "@deps//:org_clojure_clojurescript" %) deps)
            ":cljs branch require should resolve")
        (finally
          (fs/rm-rf (.toPath dir)))))))

(deftest ns-rules-cljs-clojure-prefix-auto-aliases-to-cljs
  (testing "clojure.X with no CLJS source falls back to cljs.X (mirror of cljs.analyzer/aliasable-clj-ns?)"
    (let [dir (make-temp-dir)
          cljs-path (write-file dir "demo.cljs"
                                "(ns example.demo (:require [clojure.spec.alpha :as s]))")
          dep-ns->label {:clj  {}
                         :cljs {'cljs.spec.alpha "org_clojure_clojurescript"}}
          args (minimal-args dir dep-ns->label)
          result (gb/ns-rules args [cljs-path])
          deps (extract-deps result)]
      (try
        (is (some #(= "@deps//:org_clojure_clojurescript" %) deps)
            "clojure.spec.alpha should auto-alias to cljs.spec.alpha")
        (finally
          (fs/rm-rf (.toPath dir)))))))

(deftest ns-rules-cljs-clojure-prefix-no-alias-when-original-present
  (testing "clojure.X present on cljs side resolves directly, no rewrite"
    (let [dir (make-temp-dir)
          cljs-path (write-file dir "demo.cljs"
                                "(ns example.demo (:require [clojure.set :as set]))")
          dep-ns->label {:clj  {}
                         :cljs {'clojure.set "ns_org_clojure_clojurescript_clojure_set"
                                'cljs.set    "should_not_resolve_to_this"}}
          args (minimal-args dir dep-ns->label)
          result (gb/ns-rules args [cljs-path])
          deps (extract-deps result)]
      (try
        (is (some #(= "@deps//:ns_org_clojure_clojurescript_clojure_set" %) deps)
            "clojure.set is on cljs classpath, resolves directly")
        (is (not (some #(= "@deps//:should_not_resolve_to_this" %) deps))
            "must NOT fall through to cljs.set when clojure.set resolves")
        (finally
          (fs/rm-rf (.toPath dir)))))))

(deftest ns-rules-clj-clojure-prefix-no-cljs-fallback
  (testing "auto-alias is CLJS-only; :clj platform never falls through to cljs.X"
    (let [dir (make-temp-dir)
          clj-path (write-file dir "demo.clj"
                               "(ns example.demo (:require [clojure.spec.alpha :as s]))")
          dep-ns->label {:clj  {}
                         :cljs {'cljs.spec.alpha "org_clojure_clojurescript"}}
          args (minimal-args dir dep-ns->label)
          result (gb/ns-rules args [clj-path])
          deps (extract-deps result)]
      (try
        (is (not (some #(= "@deps//:org_clojure_clojurescript" %) deps))
            ":clj platform must not pick up the cljs fallback")
        (finally
          (fs/rm-rf (.toPath dir)))))))

(defn- find-rule
  "Find a rule in ns-rules output by type keyword (e.g. :clojure_binary). Returns attrs map."
  [rules-output type-kw]
  (:attrs (some #(when (= type-kw (:type %)) %) rules-output)))

(deftest ns-rules-java-binary-from-metadata
  (testing "ns with :bazel/clojure_binary metadata emits a clojure_binary target"
    (let [dir (make-temp-dir)
          clj-path (write-file dir "benchmark.clj"
                               (str "(ns example.benchmark\n"
                                    "  {:bazel/clojure_binary {:jvm_flags [\"-Djdk.attach.allowAttachSelf\"]}}\n"
                                    "  (:gen-class))"))
          args (-> (minimal-args dir {:clj {} :cljs {}})
                   (assoc :deps-bazel {:clojure_library {:jvm_flags ["-Dclojure.main.report=stderr"]}}))
          result (gb/ns-rules args [clj-path])
          attrs (find-rule result :clojure_binary)]
      (try
        (is (some? attrs) "should emit a clojure_binary target")
        (is (= "benchmark.bin" (:name attrs)) "target name should use .bin suffix")
        (is (= "clojure.main" (:main_class attrs)) "should default to clojure.main")
        (is (= ["-m" "example.benchmark"] (:args attrs)) "should pass -m with ns name")
        (is (= ["-Dclojure.main.report=stderr" "-Djdk.attach.allowAttachSelf"] (:jvm_flags attrs))
            "should prepend base jvm flags and append ns-specific")
        (is (some #{":benchmark"} (:runtime_deps attrs)) "should depend on the library target")
        (finally
          (fs/rm-rf (.toPath dir))))))

  (testing "ns without :bazel/clojure_binary metadata does not emit a binary target"
    (let [dir (make-temp-dir)
          clj-path (write-file dir "core.clj"
                               "(ns example.core\n  (:gen-class))")
          args (minimal-args dir {:clj {} :cljs {}})
          result (gb/ns-rules args [clj-path])
          attrs (find-rule result :clojure_binary)]
      (try
        (is (nil? attrs) "should not emit a clojure_binary without metadata")
        (finally
          (fs/rm-rf (.toPath dir))))))

  (testing "metadata can override main_class"
    (let [dir (make-temp-dir)
          clj-path (write-file dir "server.clj"
                               (str "(ns example.server\n"
                                    "  {:bazel/clojure_binary {:main_class \"example.server\"}}\n"
                                    "  (:gen-class))"))
          args (minimal-args dir {:clj {} :cljs {}})
          result (gb/ns-rules args [clj-path])
          attrs (find-rule result :clojure_binary)]
      (try
        (is (= "example.server" (:main_class attrs)) "should use overridden main_class")
        (finally
          (fs/rm-rf (.toPath dir)))))))

(deftest ns-rules-partial-test-meta-no-nil-keys
  (testing "test metadata with only :timeout should not produce nil :size or :tags"
    (let [dir (make-temp-dir)
          clj-path (write-file dir "post_test.clj"
                               "(ns example.post-test\n  {:bazel/clojure_test {:timeout :long}}\n  (:require [clojure.test :refer [deftest]]))")
          args (minimal-args dir {:clj {'clojure.test "org_clojure_clojure"} :cljs {}})
          result (gb/ns-rules args [clj-path])
          attrs (find-rule result :clojure_test)]
      (try
        (is (some? attrs) "should emit a clojure_test target")
        (is (= "long" (:timeout attrs)) "timeout should be converted to string")
        (is (not (contains? attrs :size)) "absent :size should not appear in attrs")
        (is (not (contains? attrs :tags)) "absent :tags should not appear in attrs")
        (finally
          (fs/rm-rf (.toPath dir))))))

  (testing "test metadata with only :size should not produce nil :timeout or :tags"
    (let [dir (make-temp-dir)
          clj-path (write-file dir "large_test.clj"
                               "(ns example.large-test\n  {:bazel/clojure_test {:size :large}}\n  (:require [clojure.test :refer [deftest]]))")
          args (minimal-args dir {:clj {'clojure.test "org_clojure_clojure"} :cljs {}})
          result (gb/ns-rules args [clj-path])
          attrs (find-rule result :clojure_test)]
      (try
        (is (= "large" (:size attrs)) "size should be converted to string")
        (is (not (contains? attrs :timeout)) "absent :timeout should not appear in attrs")
        (is (not (contains? attrs :tags)) "absent :tags should not appear in attrs")
        (finally
          (fs/rm-rf (.toPath dir)))))))

;; ---- formatting tests (emit-bazel) ----

(deftest test-emit-bazel-vector-inline
  (testing "empty vector"
    (is (= "[]" (gb/emit-bazel []))))
  (testing "single element"
    (is (= "[\"a\"]" (gb/emit-bazel ["a"]))))
  (testing "single element stays inline regardless of length"
    (is (= "[\"long-element-name\"]" (gb/emit-bazel ["long-element-name"]))))
  (testing "multi-element standalone vector renders inline with comma+space separator"
    (is (= "[\"a\", \"b\"]" (gb/emit-bazel ["a" "b"])))))

(deftest test-emit-bazel-function-call-inline
  (testing "positional args only"
    (is (= "load(\"@rules_clojure//:rules.bzl\", \"clojure_library\")"
           (gb/emit-bazel (list 'load "@rules_clojure//:rules.bzl" "clojure_library")))))
  (testing "single kwarg stays inline"
    (is (= "package(default_visibility = [\"//visibility:public\"])"
           (gb/emit-bazel (list 'package (gb/kwargs {:default_visibility ["//visibility:public"]})))))))

(deftest test-emit-bazel-function-call-multiline
  (testing "multiple kwargs go multiline with sorted attrs"
    (let [result (gb/emit-bazel
                  (list 'clojure_library
                        (gb/kwargs {:name "build"
                                    :deps ["//resources:data_readers"
                                           "@deps//:org_clojure_clojure"]
                                    :srcs ["build.clj"]
                                    :aot ["build"]})))]
      (is (= (str "clojure_library(\n"
                  "    name = \"build\",\n"
                  "    srcs = [\"build.clj\"],\n"
                  "    aot = [\"build\"],\n"
                  "    deps = [\n"
                  "        \"//resources:data_readers\",\n"
                  "        \"@deps//:org_clojure_clojure\",\n"
                  "    ],\n"
                  ")")
             result)))))

(deftest test-empty-collection-attrs-dropped
  (testing "empty list/map values are omitted from the rendered call"
    (let [result (gb/emit-bazel
                  (list 'filegroup
                        (gb/kwargs {:name "x"
                                    :srcs ["a.clj"]
                                    :data []})))]
      (is (= "filegroup(\n    name = \"x\",\n    srcs = [\"a.clj\"],\n)"
             result)
          "data = [] is noise — bazel default is [] anyway — and must not render")))
  (testing "non-empty values survive"
    (let [result (gb/emit-bazel
                  (list 'filegroup
                        (gb/kwargs {:name "x"
                                    :srcs ["a.clj"]
                                    :data ["//b:c"]})))]
      (is (str/includes? result "data = [\"//b:c\"]"))))
  (testing "non-collection falsy/zero values are preserved"
    (is (str/includes?
         (gb/emit-bazel (list 'rule (gb/kwargs {:name "x" :testonly false})))
         "testonly = False")
        "False must render, not be confused with an empty collection")))

(deftest test-attr-sorting
  (testing "name first, then by priority, then alphabetical"
    (let [result (gb/emit-bazel
                  (list 'clojure_library
                        (gb/kwargs {:deps ["a"] :name "x" :aot ["b"] :srcs ["c"] :runtime_deps ["d"]})))]
      (is (str/starts-with? result "clojure_library(\n    name = \"x\",\n    srcs = [\"c\"],"))
      (is (str/includes? result "    aot = [\"b\"],"))
      (is (str/includes? result "    runtime_deps = [\"d\"],\n    deps = [\"a\"],")))))

(deftest test-list-sorting
  (testing "deps are sorted by buildifier phase order (: < // < @)"
    (let [result (gb/emit-bazel
                  (list 'clojure_library
                        (gb/kwargs {:name "x"
                                    :deps ["@deps//:zzz" ":aaa" "//pkg:lib"]})))]
      (is (str/includes? result "        \":aaa\",\n        \"//pkg:lib\",\n        \"@deps//:zzz\","))))
  (testing "aot is NOT sorted (not in sortable set)"
    (let [result (gb/emit-bazel
                  (list 'clojure_library
                        (gb/kwargs {:name "x"
                                    :aot ["z-ns" "a-ns"]})))]
      (is (str/includes? result "        \"z-ns\",\n        \"a-ns\",")))))

(deftest test-buildifier-round-trip
  (testing "generated output is buildifier-stable"
    (let [buildifier-path (first (test-utils/runfiles-env "BUILDIFIER"))]
      (is (some? buildifier-path) "BUILDIFIER env var must be set (see :env on the gen-build-test target)")
      (when buildifier-path
        (let [content (str "\"\"\"\nTest file.\n\"\"\"\n\n"
                           (gb/emit-bazel (list 'load "@rules_clojure//:rules.bzl" "clojure_library"))
                           "\n\n"
                           (gb/emit-bazel (list 'package (gb/kwargs {:default_visibility ["//visibility:public"]})))
                           "\n\n"
                           (gb/emit-bazel
                            (list 'clojure_library
                                  (gb/kwargs {:name "test"
                                              :deps ["@deps//:zzz" ":aaa"]
                                              :srcs ["z.clj" "a.clj"]
                                              :aot ["z-ns" "a-ns"]
                                              :runtime_deps ["@deps//:bbb"]})))
                           "\n")
              result (shell/sh buildifier-path "--type=build" :in content)]
          (is (zero? (:exit result)) (str "buildifier failed: " (:err result)))
          (is (= content (:out result))
              (str "buildifier produced diff. Expected:\n" content "\nGot:\n" (:out result))))))))

;; ---- gen-dir load-symbol pruning ----

(defn- load-symbols
  "Extract the symbol args of the rules_clojure load() from generated BUILD content.
   Returns a set of symbol strings, or nil if no rules_clojure load() is present.
   The first captured string is the bzl path; drop it and keep the symbol names."
  [content]
  (when-let [load-call (re-find #"(?s)load\(\"@rules_clojure//:rules.bzl\"[^)]*\)" content)]
    (set (->> (re-seq #"\"([^\"]+)\"" load-call)
              (map second)
              rest))))

(defn- run-gen-dir!
  "Set up a temp dir with the given relative-path → content files, call gen-dir,
   and return the BUILD.bazel content (nil if no BUILD was written).
   Cleans the temp dir via teardown. dep-ns->label defaults to empty per platform."
  [files & {:keys [dep-ns->label]
            :or {dep-ns->label {:clj {} :cljs {}}}}]
  (let [dir (make-temp-dir)
        src-dir (fs/->path (.getAbsolutePath dir) "src" "example")]
    (try
      (doseq [[rel-path content] files]
        (let [target (io/file (.toFile src-dir) rel-path)]
          (.mkdirs (.getParentFile target))
          (spit target content)))
      (gb/gen-dir (minimal-args dir dep-ns->label) src-dir)
      (let [build-file (io/file (.toFile src-dir) "BUILD.bazel")]
        (when (.exists build-file) (slurp build-file)))
      (finally
        (fs/rm-rf (.toPath dir))))))

(deftest gen-dir-load-symbols
  (testing "library only — load includes only clojure_library"
    (let [content (run-gen-dir! {"core.clj" "(ns example.core)"})]
      (is (= #{"clojure_library"} (load-symbols content)))))

  (testing "library + .clj test — load includes clojure_test"
    (let [content (run-gen-dir!
                   {"core.clj"      "(ns example.core)"
                    "core_test.clj" "(ns example.core-test (:require [clojure.test :refer [deftest]]))"}
                   :dep-ns->label {:clj {'clojure.test "org_clojure_clojure"} :cljs {}})]
      (is (= #{"clojure_library" "clojure_test"} (load-symbols content))
          "should load clojure_test when .clj test files exist")))

  (testing "library + .cljc test — load includes clojure_test"
    (let [content (run-gen-dir!
                   {"core.cljc"      "(ns example.core)"
                    "core_test.cljc" "(ns example.core-test (:require [clojure.test :refer [deftest]]))"}
                   :dep-ns->label {:clj {'clojure.test "org_clojure_clojure"} :cljs {}})]
      (is (= #{"clojure_library" "clojure_test"} (load-symbols content))
          "should load clojure_test when .cljc test files exist")))

  (testing ".cljs-only test files — load does NOT include clojure_test (ns-rules emits clojure_test only for clj/cljc)"
    (let [content (run-gen-dir!
                   {"core.cljs"      "(ns example.core)"
                    "core_test.cljs" "(ns example.core-test (:require [cljs.test :refer-macros [deftest]]))"}
                   :dep-ns->label {:clj {} :cljs {'cljs.test "org_clojure_clojurescript"}})]
      (is (= #{"clojure_library"} (load-symbols content))
          "should NOT load clojure_test for .cljs-only tests"))))

(deftest gen-dir-includes-clojure-binary-when-ns-has-bazel-clojure-binary-meta
  (testing "ns with :bazel/clojure_binary metadata adds clojure_binary to the load"
    (let [content (run-gen-dir!
                   {"main.clj" (str "(ns example.main\n"
                                    "  {:bazel/clojure_binary {}}\n"
                                    "  (:gen-class))")})]
      (is (contains? (load-symbols content) "clojure_binary")
          (str "expected clojure_binary in load, got: " (load-symbols content))))))

(deftest gen-dir-skips-load-for-empty-dirs
  (testing "directory with no clj files: load(), __clj_lib, __clj_files all absent; package() still emitted"
    (let [content (run-gen-dir! {})]
      (is (not (re-find #"(?m)^load\(" content))
          "should not emit load() for empty directories")
      (is (not (re-find #"name = \"__clj_lib\"" content))
          "should not emit __clj_lib rule for empty directories")
      (is (not (re-find #"name = \"__clj_files\"" content))
          "should not emit __clj_files filegroup for empty directories")
      (is (re-find #"(?m)^package\(" content)
          "should still emit package() (matches Gazelle behaviour)"))))

(deftest gen-dir-subdirs-only
  (testing "dir with no own files but a clj-only subdir still emits load + __clj_lib referencing the subdir"
    (let [content (run-gen-dir! {"child/core.clj" "(ns example.child.core)"})]
      (is (= #{"clojure_library"} (load-symbols content)))
      (is (re-find #"name = \"__clj_lib\"" content)
          "should emit __clj_lib aggregating subdirs")
      (is (re-find #"\"//src/example/child:__clj_lib\"" content)
          "__clj_lib deps should reference the clj subdir"))))

;; ---- git dependencies ----

(def ^:private git-sha "d34ba6d489455b30828a7286538934891130de6d")

(defn- write-tree!
  "Write a map of relative-path -> content under dir."
  [^java.nio.file.Path dir files]
  (doseq [[rel content] files]
    (let [f (io/file (fs/path->file dir) rel)]
      (.mkdirs (.getParentFile f))
      (spit f content))))

(defn- jar-entry-names [jar-path]
  (with-open [jf (JarFile. (fs/path->file jar-path))]
    (->> jf .entries enumeration-seq (mapv #(.getName ^java.util.jar.JarEntry %)))))

(defn- jar-entry-content [jar-path entry-name]
  (with-open [jf (JarFile. (fs/path->file jar-path))]
    (slurp (.getInputStream jf (.getEntry jf entry-name)))))

(deftest pack-dirs-deterministic
  (let [tmp (fs/new-temp-dir "pack-dirs-test")
        src (fs/->path tmp "src")
        resources (fs/->path tmp "resources")]
    (try
      (write-tree! tmp {"src/foo/bar.clj" "(ns foo.bar)"
                        "src/foo/baz.clj" "(ns foo.baz)"
                        "src/.git/config" "git metadata"
                        "src/dup.txt" "from src"
                        "resources/data.edn" "{:a 1}"
                        "resources/dup.txt" "from resources"})
      (let [jar-a (fs/->path tmp "a.jar")
            jar-b (fs/->path tmp "b.jar")]
        (jar/pack-dirs! jar-a [src resources])
        (jar/pack-dirs! jar-b [src resources])
        (testing "byte-identical output for identical input trees"
          (is (= (fs/shasum jar-a) (fs/shasum jar-b))))
        (testing "contains files from all dirs, excludes .git"
          (let [entries (set (jar-entry-names jar-a))]
            (is (contains? entries "foo/bar.clj"))
            (is (contains? entries "foo/baz.clj"))
            (is (contains? entries "data.edn"))
            (is (not-any? #(str/includes? % ".git") entries))))
        (testing "duplicate entries: first dir wins, like JVM classpath shadowing"
          (is (= 1 (count (filter #(= "dup.txt" %) (jar-entry-names jar-a)))))
          (is (= "from src" (jar-entry-content jar-a "dup.txt")))))
      (finally
        (fs/rm-rf tmp)))))

(defn- git-dep-basis
  "Fabricate a resolved basis for a single git dep with classpath dirs."
  [lib sha dirs]
  {:classpath (into {} (map (fn [d] [d {:lib-name lib}]) dirs))
   :classpath-roots (vec dirs)
   :libs {lib {:git/url "https://github.com/example/lib.git"
               :git/sha sha
               :paths (mapv str dirs)}}})

(deftest materialize-git-deps-rewrites-basis
  (let [tmp (fs/new-temp-dir "materialize-test")
        checkout (fs/->path tmp "gitlibs" "libs" "io.github.foo" "bar" git-sha)
        src (fs/->path checkout "src")
        resources (fs/->path checkout "resources")
        build-dir (fs/->path tmp "deps-build")
        lib 'io.github.foo/bar]
    (try
      (write-tree! tmp {(str "gitlibs/libs/io.github.foo/bar/" git-sha "/src/foo/bar.clj") "(ns foo.bar)"
                        (str "gitlibs/libs/io.github.foo/bar/" git-sha "/resources/data.edn") "{}"})
      (fs/ensure-directory build-dir)
      (let [basis (git-dep-basis lib git-sha [src resources])
            basis' (gb/materialize-git-deps basis build-dir)
            expected-jar (fs/->path build-dir "gitjars" (str "io_github_foo_bar-" git-sha ".jar"))]
        (testing "classpath dirs are replaced by a single jar per lib"
          (is (fs/exists? expected-jar))
          (is (= {expected-jar {:lib-name lib}} (:classpath basis')))
          (is (= [expected-jar] (:classpath-roots basis'))))
        (testing "the jar contains the union of the lib's paths"
          (is (= #{"META-INF/MANIFEST.MF" "foo/bar.clj" "data.edn"}
                 (set (jar-entry-names expected-jar)))))
        (testing "existing jars are reused (content-addressed by sha)"
          (let [mtime (.lastModified (fs/path->file expected-jar))
                basis'' (gb/materialize-git-deps basis build-dir)]
            (is (= (:classpath basis') (:classpath basis'')))
            (is (= mtime (.lastModified (fs/path->file expected-jar))))))
        (testing "maven jars pass through untouched"
          (let [jar-path (fs/->path tmp "some.jar")
                mvn-basis {:classpath {jar-path {:lib-name 'org.clojure/clojure}}
                           :classpath-roots [jar-path]
                           :libs {'org.clojure/clojure {:mvn/version "1.12.1"}}}]
            (is (= mvn-basis (gb/materialize-git-deps mvn-basis build-dir))))))
      (finally
        (fs/rm-rf tmp)))))

(deftest materialize-git-deps-rejects-non-git-dirs
  (let [tmp (fs/new-temp-dir "materialize-local-test")
        local-dir (fs/->path tmp "local-lib" "src")
        build-dir (fs/->path tmp "deps-build")
        lib 'my/local-lib]
    (try
      (write-tree! tmp {"local-lib/src/local/core.clj" "(ns local.core)"})
      (fs/ensure-directory build-dir)
      (let [basis {:classpath {local-dir {:lib-name lib}}
                   :classpath-roots [local-dir]
                   :libs {lib {:local/root (str (fs/->path tmp "local-lib"))}}}]
        (is (thrown-with-msg? Exception #"unsupported dependency type"
                              (gb/materialize-git-deps basis build-dir))))
      (finally
        (fs/rm-rf tmp)))))

(deftest gen-deps-build-emits-git-dep-targets
  (let [tmp (fs/new-temp-dir "gen-deps-git-test")
        checkout (fs/->path tmp "gitlibs" "libs" "io.github.foo" "bar" git-sha)
        src (fs/->path checkout "src")
        build-dir (fs/->path tmp "deps-build")
        lib 'io.github.foo/bar]
    (try
      (write-tree! tmp {(str "gitlibs/libs/io.github.foo/bar/" git-sha "/src/foo/bar.clj") "(ns foo.bar)"})
      (fs/ensure-directory build-dir)
      (let [basis (-> (git-dep-basis lib git-sha [src])
                      (gb/materialize-git-deps build-dir))
            jar->lib (gb/->jar->lib basis)
            dep-ns->label (gb/->dep-ns->label {:basis basis :deps-bazel {}})]
        (gb/gen-deps-build {:repository-dir (fs/->path tmp "repository")
                            :deps-build-dir build-dir
                            :dep-ns->label dep-ns->label
                            :jar->lib jar->lib
                            :lib->jar (set/map-invert jar->lib)
                            :lib->deps {}
                            :deps-bazel {}})
        (let [content (slurp (fs/path->file (fs/->path build-dir "BUILD.bazel")))]
          (testing "emits a java_import over the materialized jar"
            (is (re-find #"name = \"io_github_foo_bar\"" content))
            (is (re-find (re-pattern (str "jars = \\[\"gitjars/io_github_foo_bar-" git-sha ".jar\"\\]")) content)))
          (testing "emits per-namespace AOT targets like maven deps get"
            (is (re-find #"name = \"ns_io_github_foo_bar_foo_bar\"" content))
            (is (re-find #"aot = \[\"foo.bar\"\]" content)))))
      (finally
        (fs/rm-rf tmp)))))

(deftest class->jar-handles-directory-classpath-entries
  (testing "->class->jar must not crash on git-dep directory entries (gen_srcs-time basis)"
    (let [tmp (fs/new-temp-dir "class-jar-dir-test")
          dir (fs/->path tmp "src")]
      (try
        (write-tree! tmp {"src/com/example/Foo.class" "fake class bytes"
                          "src/foo/bar.clj" "(ns foo.bar)"})
        (let [basis {:classpath {dir {:lib-name 'io.github.foo/bar}}
                     :classpath-roots [dir]}]
          (is (= {'com.example.Foo dir} (gb/->class->jar basis))))
        (finally
          (fs/rm-rf tmp))))))

(deftest path->absolute-handles-dirs
  (let [deps-edn (fs/->path "/workspace/deps.edn")]
    (testing "absolute paths pass through, regardless of extension"
      (is (= (fs/->path "/home/u/.gitlibs/libs/foo/bar/abc/src")
             (gb/path->absolute "/home/u/.gitlibs/libs/foo/bar/abc/src" deps-edn)))
      (is (= (fs/->path "/home/u/.m2/repository/foo/foo.jar")
             (gb/path->absolute "/home/u/.m2/repository/foo/foo.jar" deps-edn))))
    (testing "relative paths resolve against the deps.edn dir"
      (is (= (fs/->path "/workspace/src")
             (gb/path->absolute "src" deps-edn))))))
