load("//:rules.bzl", "clojure_library", "clojure_test")

package(default_visibility = ["//visibility:public"])

# Two libraries both named core.clj — exercises package-disambiguated LCOV paths
# (CoverageAnalyzer keys by package/SourceFile; paths file ends with that suffix).
clojure_library(
    name = "foo_core",
    srcs = ["foo/core.clj"],
    aot = ["coverage.foo.core"],
    resource_strip_prefix = "test",
    deps = ["@maven_deps//:org_clojure_clojure"],
)

clojure_library(
    name = "bar_core",
    srcs = ["bar/core.clj"],
    aot = ["coverage.bar.core"],
    resource_strip_prefix = "test",
    deps = ["@maven_deps//:org_clojure_clojure"],
)

clojure_library(
    name = "coverage_test_lib",
    # resources (not srcs): no AOT of the test ns itself; runner loads it from the classpath.
    resources = ["coverage_test.clj"],
    resource_strip_prefix = "test",
    deps = [
        ":foo_core",
        ":bar_core",
        "@maven_deps//:org_clojure_clojure",
    ],
)

clojure_test(
    name = "coverage_test",
    test_ns = "coverage.coverage-test",
    deps = [
        ":foo_core",
        ":bar_core",
        ":coverage_test_lib",
    ],
)
