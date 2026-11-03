#!/usr/bin/env bash
# tools/forge.sh merge ends every squash message with Hunter's co-author trailer, once (#324). No network and no real
# token: curl is a PATH shim that answers GET /pulls/7 with a PR whose body is $PR_BODY and saves the merge POST's
# JSON; the repo is a throwaway with a fake forge remote. Each case checks the MergeMessageField it sent.
#   bash tools/test/forge-merge-trailer.sh     prints one line a case, ends `all pass` (exit 0) or `FAILED: n` (exit 1)
set -euo pipefail
FORGE_SH="$(cd "$(dirname "$0")/.." && pwd)/forge.sh"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
SHA="$(printf 'c%.0s' {1..40})"
export AGENT_HDR="$T/agent.header" POSTED="$T/posted" SHA PR_BODY=""
(umask 077; echo "Authorization: token $(printf 'a%.0s' {1..40})" >"$AGENT_HDR")
CO="Co-authored-by: Hunter Coles <hunter.colesw@gmail.com>"

mkdir -p "$T/bin" "$T/repo"
cat >"$T/bin/curl" <<'SHIM'
#!/usr/bin/env bash
# curl shim: GET /pulls/7 returns a PR with body $PR_BODY; the merge POST's JSON is saved to $POSTED.
m=GET out="" code=""
while (($#)); do
  case "$1" in
    -X) m="$2"; shift ;;
    -o) out="$2"; shift ;;
    -w) code=1; shift ;;
    -H | --data-binary | --proto) shift ;;
  esac
  shift
done
if [[ $m == GET ]]; then
  python3 -c 'import json,os,sys; print(json.dumps({"number":7,"title":"t","body":os.environ["PR_BODY"],"state":"open",
"merged":False,"head":{"ref":"card/x","sha":sys.argv[1]},"base":{"ref":"master"}}))' "$SHA"
else
  cat >"$POSTED"
  [[ -z $out ]] || printf '{}' >"$out"
  [[ -z $code ]] || printf 200
fi
SHIM
chmod +x "$T/bin/curl"
git -C "$T/repo" init -q
git -C "$T/repo" remote add forge https://forge.example/hunter/Splatman.git
git -C "$T/repo" config company.forge https://forge.example

fails=0
# check <name> <pr body> <wanted MergeMessageField>
check() {
  local name="$1" got rc=0
  : >"$POSTED"
  (cd "$T/repo" && PR_BODY="$2" PATH="$T/bin:$PATH" FORGE_HEADER="$AGENT_HDR" FORGE_MERGE_HEADER="" \
    bash "$FORGE_SH" merge 7 "$SHA") >"$T/out" 2>"$T/err" || rc=$?
  got="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["MergeMessageField"], end="")' "$POSTED" \
    2>/dev/null || true)"
  if [[ $rc == 0 && $got == "$3" ]]; then echo "pass: $name"
  else
    fails=$((fails + 1))
    echo "FAIL: $name: rc $rc"; echo "  got:  ${got//$'\n'/\\n}"; echo "  want: ${3//$'\n'/\\n}"
    sed 's/^/  stderr: /' "$T/err" | head -3
  fi
}

# 1. A body with trailers: a blank line, then the co-author trailer last.
check "trailer after Reviewer: pass" $'Goal: x\n\nReviewer: pass' $'Goal: x\n\nReviewer: pass\n\n'"$CO"
# 2. The exact line already in the body (anywhere): once, last.
check "no duplicate" $'Goal: x\n\n'"$CO"$'\nReviewer: pass' $'Goal: x\n\nReviewer: pass\n\n'"$CO"
check "no duplicate when it is last" $'Goal: x\n\n'"$CO" $'Goal: x\n\n'"$CO"
# 3. An empty body: just the trailer.
check "empty body" "" "$CO"
check "blank body" $'\n  \n' "$CO"
# Hardening: a line naming the forge is still dropped; other co-author lines (another case, another person) stay.
check "forge line dropped" $'Goal: x\nSee https://forge.example/hunter/Splatman/pulls/7\nReviewer: pass' \
  $'Goal: x\nReviewer: pass\n\n'"$CO"
check "body only a forge line" "https://forge.example/x" "$CO"
check "other trailers kept" \
  $'Goal: x\n\nco-authored-by: Hunter Coles <hunter.colesw@gmail.com>\nCo-authored-by: Someone <s@example.com>' \
  $'Goal: x\n\nco-authored-by: Hunter Coles <hunter.colesw@gmail.com>\nCo-authored-by: Someone <s@example.com>\n\n'"$CO"

if ((fails)); then echo "FAILED: $fails"; exit 1; fi
echo "all pass"
