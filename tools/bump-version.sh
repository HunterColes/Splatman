#!/usr/bin/env bash
# Steps the app's version by one patch: versionName 0.0.N -> 0.0.N+1 and versionCode by one, in app/build.gradle.kts
# (#209). Bump once for every build that goes to Hunter for testing (CLAUDE.md). The version stays in 0.0.x: the
# script refuses a versionName outside 0.0.x and never writes 0.1.0 or above; leaving 0.0.x is Hunter's call, by hand.
#   tools/bump-version.sh [--dry-run] [--file <build.gradle.kts>]
# Prints `<new versionName> / <new versionCode>`; --dry-run prints it and changes nothing.
set -euo pipefail
file="$(cd "$(dirname "$0")/.." && pwd)/app/build.gradle.kts"
dry=0
while (($#)); do
  case "$1" in
    --dry-run) dry=1 ;;
    --file) file="${2:?--file needs a path}"; shift ;;
    *) echo "usage: tools/bump-version.sh [--dry-run] [--file <build.gradle.kts>]" >&2; exit 2 ;;
  esac
  shift
done
[[ -f "$file" ]] || { echo "bump-version: no $file" >&2; exit 1; }

name_lines="$(grep -cE '^[[:space:]]*versionName = "[^"]*"' "$file" || true)"
code_lines="$(grep -cE '^[[:space:]]*versionCode = [0-9]+[[:space:]]*$' "$file" || true)"
if [[ "$name_lines" != 1 || "$code_lines" != 1 ]]; then
  echo "bump-version: expected one versionName and one versionCode line in $file" >&2
  exit 1
fi
name="$(sed -nE 's/^[[:space:]]*versionName = "([^"]*)".*/\1/p' "$file")"
code="$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+)[[:space:]]*$/\1/p' "$file")"
if [[ ! "$name" =~ ^0\.0\.([0-9]+)$ ]]; then
  echo "bump-version: versionName $name is not 0.0.x; leaving 0.0.x is Hunter's call (CLAUDE.md)" >&2
  exit 1
fi
new_name="0.0.$((10#${BASH_REMATCH[1]} + 1))"
new_code=$((10#$code + 1))
if [[ ! "$new_name" =~ ^0\.0\.[0-9]+$ ]]; then
  echo "bump-version: refusing $new_name (0.1.0 or above)" >&2
  exit 1
fi

echo "$new_name / $new_code"
if ((dry)); then exit 0; fi
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
sed -E -e "s/^([[:space:]]*versionName = )\"[^\"]*\"/\1\"$new_name\"/" \
  -e "s/^([[:space:]]*versionCode = )[0-9]+([[:space:]]*)$/\1$new_code\2/" "$file" >"$tmp"
cat "$tmp" >"$file"
