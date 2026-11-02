#!/usr/bin/env bash
# tools/docs-only.sh (#61): each case is a throwaway git repo with a base commit and a head commit; no Gradle.
#   bash tools/test/docs-only.sh     prints one line a case, ends `all pass` (exit 0) or `FAILED: n` (exit 1)
set -euo pipefail
SUT="$(cd "$(dirname "$0")/.." && pwd)/docs-only.sh"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fails=0
export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t.invalid \
  GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t.invalid

g() { git -C "$T/r" "$@"; }
put() { mkdir -p "$(dirname "$T/r/$1")"; printf '%s\n' "${2:-x}" >"$T/r/$1"; }
# reset: a fresh repo whose base commit holds a little of everything; `base` names it.
reset() {
  rm -rf "$T/r"; git init -q -b master "$T/r"
  put README.md; put docs/guide.md; put app/build.gradle.kts; put build.gradle.kts
  put app/src/main/java/A.kt; put tools/check.sh; put metadata/en-US/full_description.txt; put metadata/app.yml
  g add -A; g commit -qm base; g tag base
}
commit() { g add -A; g commit -qm head --allow-empty; }
# expect <name> <exit code> <line the output must hold> [<base> <head>]
expect() {
  local out rc=0
  out="$(cd "$T/r" && bash "$SUT" "${4:-base}" "${5:-HEAD}" 2>&1)" || rc=$?
  if ((rc == $2)) && grep -qF -- "$3" <<<"$out"; then echo "ok   $1"; return; fi
  echo "FAIL $1 (rc=$rc): $out"; fails=$((fails + 1))
}

reset; put README.md y; commit
expect "README.md edit -> docs" 0 "docs-only: yes (1 paths)"

reset; put docs/guide.md y; put docs/img/shot.png png; put "docs/a b.md" spaced; commit
expect "docs/ files, a png and a spaced name -> docs" 0 "docs-only: yes (3 paths)"

reset; put metadata/en-US/full_description.txt y; put deploy/forgejo-runner/README.md y; commit
expect "metadata text and a module README -> docs" 0 "docs-only: yes (2 paths)"

reset; g rm -q docs/guide.md; commit
expect "deleting a doc -> docs" 0 "docs-only: yes (1 paths)"

reset; put metadata/app.yml y; commit
expect "metadata/*.yml (F-Droid's recipe) -> code" 1 "docs-only: no: metadata/app.yml"

reset; g mv app/src/main/java/A.kt app/A.md; commit
expect "a .kt renamed to .md -> code" 1 "docs-only: no: app/src/main/java/A.kt"

reset; g mv app/build.gradle.kts NOTES.md; commit
expect "a .gradle.kts renamed to .md -> code" 1 "docs-only: no: app/build.gradle.kts"

reset; put tools/NOTES.md; commit
expect "a .md under tools/ -> code" 1 "docs-only: no: tools/NOTES.md"

reset; put tools/check.sh y; put README.md y; commit
expect "tools/ beside a doc -> code" 1 "docs-only: no: tools/check.sh"

reset; put build.gradle.kts y; commit
expect "root build.gradle.kts -> code" 1 "docs-only: no: build.gradle.kts"

reset; put docs/build.gradle.kts y; commit
expect "a build file under docs/ -> code" 1 "docs-only: no: docs/build.gradle.kts"

reset; put app/src/main/assets/help.md y; commit
expect "a .md under src/ (ships in the APK) -> code" 1 "docs-only: no: app/src/main/assets/help.md"

reset; put CLAUDE.md y; commit
expect "CLAUDE.md -> code" 1 "docs-only: no: CLAUDE.md"

reset; put .forgejo/workflows/ci.yml y; commit
expect "the workflow -> code" 1 "docs-only: no: .forgejo/workflows/ci.yml"

reset; put .forgejo/NOTES.md y; commit
expect "a .md under a dot directory -> code" 1 "docs-only: no: .forgejo/NOTES.md"

reset; chmod +x "$T/r/README.md"; commit
expect "README.md made executable -> code" 1 "docs-only: no: README.md"

reset; g rm -q README.md; ln -s app/src/main/java/A.kt "$T/r/README.md"; commit
expect "README.md turned into a symlink -> code" 1 "docs-only: no: README.md"

reset; put README.md y; put app/src/main/java/A.kt y; commit
expect "a doc and a .kt -> code" 1 "docs-only: no: app/src/main/java/A.kt"

reset; commit
expect "empty diff -> code" 1 "docs-only: no: empty diff"

reset; g checkout -q -b pr; put README.md y; commit; g checkout -q master; put app/src/main/java/A.kt y; commit
expect "base moved on with code, PR only docs -> docs (merge-base)" 0 "docs-only: yes (1 paths)" master pr

reset
expect "an unknown base -> error" 2 "docs-only: error: no commit nosuch" nosuch HEAD
out="$(cd "$T/r" && bash "$SUT" base 2>&1)" && rc=0 || rc=$?
if ((rc == 2)) && grep -qF "docs-only: error: usage" <<<"$out"; then echo "ok   one argument -> error"
else echo "FAIL one argument -> error (rc=$rc): $out"; fails=$((fails + 1)); fi

if ((fails)); then echo "FAILED: $fails"; exit 1; fi
echo "all pass"
