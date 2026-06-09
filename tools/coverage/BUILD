load("@rules_java//java:java_binary.bzl", "java_binary")

# Depends on the java toolchain's own jacoco jar so the instrumenter's jacoco version
# always matches the offline runtime java_test puts on the test classpath.
java_binary(
    name = "jacoco_instrumenter",
    srcs = ["JacocoInstrumenter.java"],
    main_class = "rules_clojure.coverage.JacocoInstrumenter",
    visibility = ["//visibility:public"],
    deps = ["@bazel_tools//tools/jdk:JacocoCoverage"],
)
