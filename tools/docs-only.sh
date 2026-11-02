#!/usr/bin/env bash
# Is a change docs-only? The forge CI's first decision (.forgejo/workflows/ci.yml, #61): a PR whose diff touches only
# documentation ends its `gate` job green without building. Run in the repository, on two commits:
#   tools/docs-only.sh <base> <head>
# It diffs <head> against its merge-base with <base> (git diff base...head), with renames off, so a .kt renamed to .md
# counts as a deleted .kt (code). Prints one line and exits:
#   0  `docs-only: yes (N paths)`        every changed path is documentation
#   1  `docs-only: no: <path>`           the first path that is not, or `docs-only: no: empty diff`
#   2  `docs-only: error: <why>`         bad arguments or a commit git cannot read (the CI then runs the full gate)
# A path is documentation only when every rule holds:
#   - both sides of the record are a regular non-executable file or absent (no symlink, executable or submodule);
#   - no part of the path starts with a dot (.forgejo/, .github/, .editorconfig, ...) and its name is not CLAUDE*;
#   - it is not under tools/, gradle/ or any src/ (a .md under src/main/assets ships in the APK);
#   - its name is not a build or code file (*.kt, *.kts, *.gradle*, *.java, *.sh, gradlew*, *.properties, *.toml,
#     *.pro, *.jar, *.xml, *.yml, *.yaml, *.json);
#   - and it is a *.md anywhere, any file under docs/, or a *.txt under metadata/ (F-Droid's store text).
# The CI runs the base commit's copy of this file, so a PR that edits it is still judged by master's.
set -euo pipefail

say() { echo "docs-only: $*"; }
err() { say "error: $*"; exit 2; }

# docs_path <path> <old mode> <new mode>: 0 when the path is documentation.
docs_path() {
  local p="$1" m
  for m in "$2" "$3"; do [[ $m == 100644 || $m == 000000 ]] || return 1; done
  case "/$p/" in */.*) return 1 ;; esac
  case "/$p" in */src/* | /tools/* | /gradle/*) return 1 ;; esac
  case "${p##*/}" in
    CLAUDE* | gradlew* | *.kt | *.kts | *.gradle | *.gradle.* | *.java | *.sh | *.properties | *.toml | *.pro | \
    *.jar | *.xml | *.yml | *.yaml | *.json) return 1 ;;
  esac
  case "$p" in
    docs/* | metadata/*.txt | *.md) return 0 ;;
  esac
  return 1
}

(($# == 2)) || err "usage: tools/docs-only.sh <base> <head>"
base="$(git rev-parse -q --verify "$1^{commit}" 2>/dev/null)" || err "no commit $1"
head="$(git rev-parse -q --verify "$2^{commit}" 2>/dev/null)" || err "no commit $2"
git merge-base "$base" "$head" >/dev/null 2>&1 || err "no merge-base of $1 and $2"

raw="$(mktemp)"; trap 'rm -f "$raw"' EXIT
git -c core.quotePath=false diff --raw --no-renames --no-abbrev --no-ext-diff --no-textconv -z "$base...$head" >"$raw" ||
  err "git diff $1...$2 failed"
n=0
while IFS= read -r -d '' meta; do
  [[ $meta =~ ^:([0-7]{6})\ ([0-7]{6})\ [0-9a-f]{40,64}\ [0-9a-f]{40,64}\ [A-Z][0-9]{0,3}$ ]] || err "not a raw diff record"
  IFS= read -r -d '' path || err "a raw diff record without a path"
  docs_path "$path" "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" || { say "no: $path"; exit 1; }
  n=$((n + 1))
done <"$raw"
((n)) || { say "no: empty diff"; exit 1; }
say "yes ($n paths)"
