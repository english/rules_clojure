load("@rules_clojure//:rules.bzl", "clojure_library", "clojure_test")

java_library(name="libcore_test",
                resources=["core_test.clj"],
                resource_strip_prefix="test",
                runtime_deps=["//src/example:core"])

clojure_test(name="core_test",
             test_ns="example.core-test",
             # Measure line coverage of example.core under `bazel coverage`.
             # Cloverage instruments .clj *source*, so the source of the
             # instrumented namespaces must be on the test runtime classpath.
             # //src/example:src is a java_library shipping the raw .clj (the
             # AOT jar //src/example:core contains only .class files). It is a
             # runtime-only dep of the test, so it does not affect compilation.
             instrument_ns=["example.core"],
             runtime_deps=[":libcore_test", "//src/example:src"])
