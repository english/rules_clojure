#!/usr/bin/env bash
# End-to-end smoke: bazel coverage on the same-basename core.clj fixtures, then
# assert LCOV mentions both workspace paths with at least one hit line each.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

FILTER='//test/coverage'
TARGET='//test/coverage:coverage_test'

echo "Running: bazel coverage --combined_report=lcov --instrumentation_filter=${FILTER} ${TARGET}"
bazel coverage \
  --combined_report=lcov \
  --instrumentation_filter="${FILTER}" \
  "${TARGET}"

# Per-test report (more specific than the combined report for grepping).
REPORT=$(find -L bazel-testlogs/test/coverage/coverage_test -name 'coverage.dat' 2>/dev/null | head -1 || true)
if [[ -z "${REPORT}" ]]; then
  # Fallback: combined report
  REPORT="bazel-out/_coverage/_coverage_report.dat"
fi

if [[ ! -f "${REPORT}" ]]; then
  echo "ERROR: no coverage.dat found under bazel-testlogs or bazel-out/_coverage" >&2
  exit 1
fi

echo "Checking LCOV report: ${REPORT}"
echo "--- report ---"
cat "${REPORT}"
echo "---"

# Prefer the per-test report if non-empty; else combined (baseline-only is not enough).
if [[ ! -s "${REPORT}" ]] || ! grep -q '^DA:' "${REPORT}"; then
  COMBINED="bazel-out/_coverage/_coverage_report.dat"
  if [[ -s "${COMBINED}" ]] && grep -q '^DA:' "${COMBINED}"; then
    REPORT="${COMBINED}"
    echo "Using combined report: ${REPORT}"
  fi
fi

# Distinct SF: lines for each same-basename core.clj (package-disambiguated).
if ! grep -qE 'SF:.*test/coverage/foo/core\.clj' "${REPORT}"; then
  echo "ERROR: missing SF: for test/coverage/foo/core.clj" >&2
  exit 1
fi
if ! grep -qE 'SF:.*test/coverage/bar/core\.clj' "${REPORT}"; then
  echo "ERROR: missing SF: for test/coverage/bar/core.clj" >&2
  exit 1
fi

# At least one executed line in each file (DA:n,1 with n hit count >= 1)
foo_hits=$(awk '/SF:.*test\/coverage\/foo\/core\.clj/,/end_of_record/' "${REPORT}" | grep -cE '^DA:[0-9]+,[1-9]' || true)
bar_hits=$(awk '/SF:.*test\/coverage\/bar\/core\.clj/,/end_of_record/' "${REPORT}" | grep -cE '^DA:[0-9]+,[1-9]' || true)

if [[ "${foo_hits}" -lt 1 ]]; then
  echo "ERROR: no hit lines for foo/core.clj (got ${foo_hits})" >&2
  exit 1
fi
if [[ "${bar_hits}" -lt 1 ]]; then
  echo "ERROR: no hit lines for bar/core.clj (got ${bar_hits})" >&2
  exit 1
fi

echo "OK: coverage e2e smoke passed (foo hits=${foo_hits}, bar hits=${bar_hits})"
