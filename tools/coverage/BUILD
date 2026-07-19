load("@rules_java//java:java_binary.bzl", "java_binary")
load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_java//java:java_test.bzl", "java_test")

# Offline-instruments clojure_library jars under `bazel coverage`.
# Jacoco comes from the Java toolchain runner so the instrumenter version matches
# the offline runtime java_test puts on the test classpath.
java_library(
    name = "jacoco_instrumenter_lib",
    srcs = ["JacocoInstrumenter.java"],
    visibility = ["//visibility:private"],
    deps = [
        "@bazel_tools//tools/jdk:JacocoCoverage",
    ],
)

java_binary(
    name = "jacoco_instrumenter",
    main_class = "rules_clojure.coverage.JacocoInstrumenter",
    visibility = ["//visibility:public"],
    runtime_deps = [":jacoco_instrumenter_lib"],
)

java_test(
    name = "jacoco_instrumenter_test",
    size = "small",
    srcs = ["JacocoInstrumenterTest.java"],
    test_class = "rules_clojure.coverage.JacocoInstrumenterTest",
    deps = [
        ":jacoco_instrumenter_lib",
        # ASM only for synthesizing fixture class files in the test.
        "@maven_deps//:junit_junit",
        "@maven_deps//:org_ow2_asm_asm",
    ],
)
