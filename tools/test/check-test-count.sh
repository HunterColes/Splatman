#!/usr/bin/env bash
# tools/check.sh's unit-test count (#59): stubbed JUnit XML results in a throwaway tree, no Gradle.
# Sources check.sh for its functions (test_results_clear, test_count, test_count_gate) and checks each case.
#   bash tools/test/check-test-count.sh     prints one line a case, ends `all pass` (exit 0) or `FAILED: n` (exit 1)
set -euo pipefail
TOOLS="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../check.sh
source "$TOOLS/check.sh"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fails=0

# result <module> <file> <tests> [task]: a JUnit 4 style report (Gradle writes the same shape for Jupiter) in
# build/test-results/<task>, testDebugUnitTest (an Android module) unless task is given (`test`: a pure-JVM module).
result() {
  local d="$T/root/$1/build/test-results/${4:-testDebugUnitTest}"
  mkdir -p "$d"
  printf '<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="%s" tests="%s" skipped="0" failures="0" errors="0">\n</testsuite>\n' \
    "$2" "$3" >"$d/TEST-$2.xml"
}
reset() { rm -rf "$T/root"; mkdir -p "$T/root"; echo "${2:-10}" >"$T/floor"; : "$1"; }
# expect <name> <pass|fail> <line the output must hold>
expect() {
  local out rc=0
  out="$(test_count_gate "$T/root" "$T/floor" 2>&1)" || rc=$?
  if { [[ $2 == pass ]] && ((rc == 0)); } || { [[ $2 == fail ]] && ((rc != 0)); }; then
    if grep -qF -- "$3" <<<"$out"; then echo "ok   $1"; return; fi
  fi
  echo "FAIL $1 (rc=$rc): $out"; fails=$((fails + 1))
}

reset "zero tests"
result core A 0
expect "zero tests -> fail" fail "unit tests: none ran"

reset "no results anywhere"
mkdir -p "$T/root/app/build"
expect "no results at all -> fail" fail "unit tests: 0"

reset "under the floor"
result core A 4; result app B 5
expect "under the floor -> fail" fail "9 is under the floor of 10"

reset "at the floor"
result core A 4; result app B 6
expect "at the floor -> pass" pass "unit tests: 10"

reset "over the floor, JUnit 4 and 5 modules"
result core A 7
mkdir -p "$T/root/viewer-feature/build/test-results/testDebugUnitTest"
cat >"$T/root/viewer-feature/build/test-results/testDebugUnitTest/TEST-J5.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<testsuites><testsuite name="J5a" tests="3"/><testsuite name="J5b" tests="2"/></testsuites>
EOF
expect "over the floor, <testsuites> summed -> pass" pass "unit tests: 12"

reset "a module with no test-results directory"
result core A 10
mkdir -p "$T/root/library-feature/build/intermediates"
expect "module without test-results -> pass on the others" pass "unit tests: 10"

reset "malformed XML"
result core A 20
printf '<testsuite name="x" tests="3">' >"$T/root/core/build/test-results/testDebugUnitTest/TEST-broken.xml"
expect "malformed XML -> fail" fail "unreadable results"

reset "not a JUnit report"
result core A 20
echo '<testsuite name="x" tests="many"/>' >"$T/root/core/build/test-results/testDebugUnitTest/TEST-odd.xml"
expect "non-integer tests= -> fail" fail "unreadable results"

reset "stale results from an earlier build"
result core Old 50; result app Old 50
test_results_clear "$T/root"
result core New 3
expect "stale results cleared, only this run counted -> fail" fail "3 is under the floor of 10"

reset "a pure-JVM module's results (#92)"
result app A 4; result splat-core B 6 test
expect "JVM module build/test-results/test counted -> pass" pass "unit tests: 10"

reset "a pure-JVM module's stale results are cleared (#92)"
result app A 4; result splat-io Old 50 test
test_results_clear "$T/root"
result app A 4
expect "JVM module results cleared before the run -> fail" fail "4 is under the floor of 10"

reset "other Gradle test-results are not counted"
result app A 10
result app R 5 testReleaseUnitTest
expect "only testDebugUnitTest and test dirs counted" pass "unit tests: 10"

reset "the build cache is not counted"
result core A 10
mkdir -p "$T/root/build-cache/x/build/test-results/testDebugUnitTest"
cp "$T/root/core/build/test-results/testDebugUnitTest/TEST-A.xml" "$T/root/build-cache/x/build/test-results/testDebugUnitTest/"
expect "build-cache copies ignored" pass "unit tests: 10"

reset "no floor file"
result core A 10
rm -f "$T/floor"
expect "missing floor -> fail" fail "no floor in"

reset "committed floor"
result core A 1
cp "$TOOLS/test-count.min" "$T/floor"
floor="$(tr -d '[:space:]' <"$T/floor")"
if [[ $floor =~ ^[1-9][0-9]*$ ]]; then echo "ok   committed floor is a positive integer ($floor)"
else echo "FAIL committed floor '$floor'"; fails=$((fails + 1)); fi

if ((fails)); then echo "FAILED: $fails"; exit 1; fi
echo "all pass"
