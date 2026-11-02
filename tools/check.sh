#!/usr/bin/env bash
# The gate a card must pass, on this checkout (a worktree is fine):
#   ./gradlew assembleDebug testDebugUnitTest lintDebug   (detekt is left out: 69 open findings, the app is being rewritten)
# The device tour, monkey and mutation runs are not part of it (CLAUDE.md).
#   tools/check.sh            the whole gate
#   tools/check.sh <tasks>    other Gradle tasks under the same lock and caps (e.g. assembleDebug)
# One Gradle build at a time on this machine (CLAUDE.md): every gate takes
# /tmp/splatman-gradle.lock first (waits up to GATE_LOCK_WAIT seconds, default 3600), with the memory caps CLAUDE.md
# gives for builds that may overlap. A worktree has no local.properties: ANDROID_HOME defaults to ~/Android/Sdk, and a
# worktree's build-cache links to the main checkout's (Gradle locks the cache itself).
# Every gate runs in its own single-use Gradle JVM (--no-daemon): Android Lint keeps a JVM-wide cache of library lint
# registries (lint-api JarFileIssueRegistry.cache) whose class loaders read the migrated jars in whichever checkout
# linted first, so in a long-lived daemon one bad load poisons every later lint, in every checkout (#38:
# NoClassDefFoundError ComposedModifierDetector$visitMethodCall$1, red 04:47-05:15 in one daemon, green in fresh ones).
# Cost: a cold JVM per gate (configuration and the first tasks run unwarmed); the Kotlin compile daemon and the build
# cache still carry over, and no idle 2 GB daemon is left behind. tools/lint-daemon-repro.sh shows the leak.
# When testDebugUnitTest runs, the gate then sums the tests in every module's JUnit XML results (JUnit 4 and Jupiter
# both write them), prints `unit tests: N`, and goes red when N is 0 or below the floor in tools/test-count.min (#59).
# Each module's old results are deleted before Gradle starts, so only this run's are counted (an up-to-date task is
# rerun or restored from the build cache, which writes them again). Raise the floor on purpose, in the card that adds
# the tests. Sourcing this file only defines the functions (tools/test/check-test-count.sh).
# A red gate ends with one line `gate: RED: <the last failure line>`; green ends `gate: green`.
set -euo pipefail

# test_result_dirs <root>: every module's unit-test results under root (not the build cache): an Android module's
# build/test-results/testDebugUnitTest, and a pure-JVM module's build/test-results/test (splat-core, splat-io,
# splat-train: their testDebugUnitTest only depends on `test`, ADR 0001) (#92).
test_result_dirs() {
  find "$1" \( -name build-cache -o -name .gradle -o -name .git \) -prune -o \
    -type d \( -path '*/build/test-results/testDebugUnitTest' -o -path '*/build/test-results/test' \) -print -prune
}

# test_results_clear <root>: delete every module's unit-test results, so the count sees only the next run's.
test_results_clear() {
  local d
  while IFS= read -r d; do rm -rf -- "$d"; done < <(test_result_dirs "$1")
}

# test_count <root>: prints the number of tests in every results XML under root. A file that is not a JUnit report
# (malformed XML, no <testsuite>, no integer tests=) exits 2 with its path: a result that cannot be read is not 0.
test_count() {
  local d f files=()
  while IFS= read -r d; do
    while IFS= read -r -d '' f; do files+=("$f"); done < <(find "$d" -maxdepth 1 -type f -name '*.xml' -print0)
  done < <(test_result_dirs "$1")
  python3 -I -c '
import sys, xml.etree.ElementTree as ET
n = 0
for p in sys.argv[1:]:
    try:
        root = ET.parse(p).getroot()
        if root.tag == "testsuite":
            suites = [root]
        elif root.tag == "testsuites":
            suites = root.findall("testsuite")
        else:
            suites = []
        if not suites:
            raise ValueError("no <testsuite>")
        n += sum(int(s.get("tests")) for s in suites)
    except (ET.ParseError, ValueError, TypeError) as e:
        sys.exit(f"unreadable test result {p}: {e}")
print(n)
' "${files[@]}" || return 2
}

# test_count_gate <root> <floor file>: prints `unit tests: N`; 1 (with the reason last) when N is 0 or under the floor.
test_count_gate() {
  local n floor
  n="$(test_count "$1")" || { echo "unit tests: unreadable results"; return 1; }
  echo "unit tests: $n"
  floor="$(tr -d '[:space:]' <"$2" 2>/dev/null)" || true
  [[ $floor =~ ^[0-9]+$ ]] || { echo "unit tests: no floor in $2"; return 1; }
  ((n > 0)) || { echo "unit tests: none ran"; return 1; }
  ((n >= floor)) || { echo "unit tests: $n is under the floor of $floor ($2)"; return 1; }
}

[[ ${BASH_SOURCE[0]} == "$0" ]] || return 0
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
main_root="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
if [[ "$main_root" != "$PWD" && ! -e build-cache && -d "$main_root/build-cache" ]]; then
  ln -s "$main_root/build-cache" build-cache
fi
tasks=("$@"); ((${#tasks[@]})) || tasks=(assembleDebug testDebugUnitTest lintDebug)
# This repo is public (GitHub mirrors master): no private host name in a tracked file. The pattern is local git config
# (company.private, company.private-allow), set in each checkout and never committed.
if ((! $#)) && pat="$(git config --get company.private)"; then
  allow="$(git config --get company.private-allow || true)"
  hits="$(git grep -nIiE "$pat" -- . | { [[ -n $allow ]] && grep -viE "$allow" || cat; } | head -5)" || true
  [[ -z $hits ]] || { printf '%s\n' "$hits"; echo "gate: RED: a tracked file names a private host (git config company.private)"; exit 1; }
fi
counted=0; for t in "${tasks[@]}"; do if [[ $t == testDebugUnitTest ]]; then counted=1; fi; done
((! counted)) || test_results_clear .
log="$(mktemp)"; trap 'rm -f "$log"' EXIT
rc=0
flock -w "${GATE_LOCK_WAIT:-3600}" /tmp/splatman-gradle.lock \
  ./gradlew --console=plain --no-daemon -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.daemon.jvmargs=-Xmx1g "${tasks[@]}" \
  > >(tee "$log") 2>&1 || rc=$?
wait
if ((rc)); then
  why="$(grep -E '^(> |e: |FAILED|.*tests completed, [0-9]+ failed|Lint found)' "$log" | tail -3 | paste -sd';' -)" || true
  printf '\ngate: RED: %s\n' "${why:-$(grep -v '^[[:space:]]*$' "$log" | tail -1)}"
  exit "$rc"
fi
if ((counted)); then
  rc=0; why="$(test_count_gate . tools/test-count.min)" || rc=$?
  printf '%s\n' "$why"
  ((! rc)) || { printf '\ngate: RED: %s\n' "${why##*$'\n'}"; exit 1; }
fi
echo "gate: green"
