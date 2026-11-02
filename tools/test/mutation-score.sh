#!/usr/bin/env bash
# tools/mutation-score.py (#66): stubbed pitest mutations.xml reports in a throwaway dir, no Gradle.
#   bash tools/test/mutation-score.sh     prints one line a case, ends `all pass` (exit 0) or `FAILED: n` (exit 1)
set -euo pipefail
SCORE="$(cd "$(dirname "$0")/.." && pwd)/mutation-score.py"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fails=0

# report <file> <status>...: a pitest mutations.xml with one mutation per status.
report() {
  local f="$1" s; shift
  { echo '<?xml version="1.0" encoding="UTF-8"?>'; echo '<mutations>'
    for s in "$@"; do echo "<mutation detected=\"false\" status=\"$s\"><mutatedClass>A</mutatedClass></mutation>"; done
    echo '</mutations>'; } >"$f"
}
# expect <name> <pass|fail> <line the output must hold> <file>
expect() {
  local out rc=0
  out="$(python3 -I "$SCORE" "$4" 2>&1)" || rc=$?
  if { [[ $2 == pass ]] && ((rc == 0)); } || { [[ $2 == fail ]] && ((rc != 0)); }; then
    if grep -qF -- "$3" <<<"$out"; then echo "ok   $1"; return; fi
  fi
  echo "FAIL $1 (rc=$rc): $out"; fails=$((fails + 1))
}

report "$T/a.xml" KILLED KILLED TIMED_OUT SURVIVED NO_COVERAGE MEMORY_ERROR
expect "timeouts and memory errors count as killed" pass "mutation score: 4/6 (67%), test strength 80%" "$T/a.xml"
report "$T/b.xml" SURVIVED NO_COVERAGE
expect "nothing killed" pass "mutation score: 0/2 (0%), test strength 0%" "$T/b.xml"
report "$T/c.xml"
expect "no mutants" pass "mutation score: 0/0 (0%), test strength 0%" "$T/c.xml"
expect "missing report" fail "unreadable report" "$T/none.xml"
echo '<testsuite tests="1"/>' >"$T/d.xml"
expect "not a pitest report" fail "not a pitest mutations report" "$T/d.xml"
echo '<mutations><mutation' >"$T/e.xml"
expect "malformed xml" fail "unreadable report" "$T/e.xml"

if ((fails)); then echo "FAILED: $fails"; exit 1; fi
echo "all pass"
