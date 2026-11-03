#!/usr/bin/env bash
# tools/bump-version.sh (#209): each case runs it on a throwaway build file; no Gradle.
#   bash tools/test/bump-version.sh     prints one line a case, ends `all pass` (exit 0) or `FAILED: n` (exit 1)
set -euo pipefail
SUT="$(cd "$(dirname "$0")/.." && pwd)/bump-version.sh"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
fails=0
put() { printf '    defaultConfig {\n        versionCode = %s\n        versionName = "%s"\n    }\n' "$1" "$2" >"$T/b.kts"; }
# expect <name> <exit code> <line the output must hold> [<args>...]
expect() {
  local name="$1" want="$2" line="$3" out rc=0
  shift 3
  out="$(bash "$SUT" --file "$T/b.kts" "$@" 2>&1)" || rc=$?
  if ((rc == want)) && grep -qF -- "$line" <<<"$out"; then echo "ok   $name"; return; fi
  echo "FAIL $name (rc=$rc): $out"
  fails=$((fails + 1))
}
# holds <name> <text the build file must hold>
holds() {
  if grep -qF -- "$2" "$T/b.kts"; then echo "ok   $1"; return; fi
  echo "FAIL $1: $(cat "$T/b.kts")"
  fails=$((fails + 1))
}

put 2 0.0.2
expect "dry run prints the next version" 0 "0.0.3 / 3" --dry-run
holds "dry run leaves versionName" 'versionName = "0.0.2"'
holds "dry run leaves versionCode" "versionCode = 2"
expect "bump prints the next version" 0 "0.0.3 / 3"
holds "bump writes versionName" 'versionName = "0.0.3"'
holds "bump writes versionCode" "versionCode = 3"
put 9 0.0.9
expect "0.0.9 steps to 0.0.10, not 0.1.0" 0 "0.0.10 / 10"
holds "0.0.10 written" 'versionName = "0.0.10"'
put 10 0.1.0
expect "refuses 0.1.0" 1 "not 0.0.x"
holds "0.1.0 untouched" 'versionName = "0.1.0"'
put 10 1.0.0
expect "refuses 1.0.0" 1 "not 0.0.x"
put 3 0.0.3-rc
expect "refuses a suffix" 1 "not 0.0.x"
printf '        versionName = "0.0.1"\n' >"$T/b.kts"
expect "refuses a file without versionCode" 1 "expected one versionName"
put 2 0.0.2
expect "refuses an unknown flag" 2 "usage" --bogus

if ((fails)); then
  echo "FAILED: $fails"
  exit 1
fi
echo "all pass"
