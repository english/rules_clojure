(ns rules-clojure.bootstrap-gen-build
  (:require [clojure.string :as str]
            [rules-clojure.jar :as jar]
            [rules-clojure.fs :as fs]))

(def nses-to-compile
  '[clojure.tools.deps.extensions,
    clojure.tools.deps.util.session,
    clojure.tools.deps.util.io,
    clojure.tools.deps.util.dir,
    clojure.tools.deps.util.concurrent,
    clojure.tools.deps,
    rules-clojure.tools.reader.default-data-readers,

    rules-clojure.tools.reader.impl.inspect,
    rules-clojure.tools.reader.impl.utils,

    rules-clojure.tools.reader.reader-types,
    rules-clojure.tools.reader.impl.errors,
    rules-clojure.tools.reader.impl.commons,
    rules-clojure.tools.reader,

    rules-clojure.java.classpath,
    rules-clojure.namespace.file,
    rules-clojure.namespace.find,
    rules-clojure.util,
    rules-clojure.jar,
    rules-clojure.gen-build])

(def classes-dir "gen-build-classes")

(def externally-provided-nses
  "rules-clojure namespaces whose classes are supplied by another Bazel target
  (see `libgen_build` runtime_deps in //src/rules_clojure:BUILD), so they are
  intentionally not AOT'd into this jar."
  '#{rules-clojure.fs})

(defn- aot-class-file
  "Path, relative to `classes-dir`, of the __init.class emitted when AOT'ing ns."
  [ns]
  (let [root-resource (#'clojure.core/root-resource ns)]
    (str (subs root-resource 1) "__init.class")))

(defn- missing-class-nses
  "Every rules-clojure namespace that was loaded (transitively) while compiling
  but did not produce a class file here and is not provided elsewhere. Loading a
  namespace before `compile` reaches it makes `compile` skip emitting its
  classes, so such a namespace would be silently absent from the jar and only
  fail at runtime when `gen_srcs` requires it."
  []
  (->> (loaded-libs)
       (filter #(str/starts-with? (str %) "rules-clojure."))
       (remove #(str/starts-with? (str %) "rules-clojure.bootstrap"))
       (remove externally-provided-nses)
       (remove (fn [ns] (fs/exists? (fs/->path classes-dir (aot-class-file ns)))))
       sort))

(defn -main [jar-path]
  (fs/clean-directory (fs/->path classes-dir))

  (binding [*compile-path* classes-dir]
    (doseq [n nses-to-compile]
      (compile n)))

  (let [missing (missing-class-nses)]
    (when (seq missing)
      (throw (ex-info (str "bootstrap-gen-build: these rules-clojure namespaces were loaded but not "
                           "compiled into the jar; add them to `nses-to-compile` (in dependency order) "
                           "or to `externally-provided-nses`: " (vec missing))
                      {:missing missing}))))

  (jar/create-jar {:aot-nses '[rules-clojure.gen-build]
                   :classes-dir (fs/->path classes-dir)
                   :output-jar (fs/->path jar-path)}))
