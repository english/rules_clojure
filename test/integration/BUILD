# End-to-end coverage that is not exercised by //test/rules_clojure unit tests.
#
# Git deps: example-simple's deps.edn pins a real tools.deps git library. Building
# and running this test from the main workspace forces @deps materialize-git-deps
# (clone → pack jar → java_import / per-ns AOT) and loads a namespace from that jar.
# Without this suite, main-repo `bazel test //...` never enters examples/simple's
# workspace graph for git deps (only unit tests with fabricated bases run).

test_suite(
    name = "git_deps_e2e",
    tests = [
        "@example-simple//test/example:gitdep_test",
    ],
)
