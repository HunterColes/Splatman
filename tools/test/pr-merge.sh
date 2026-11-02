#!/usr/bin/env bash
# tools/pr-merge.sh keeps both token header paths out of the PR-head gate while the merge still gets them (#73).
# No network and no real token: a throwaway repo with a local bare "forge", a stub FORGE_SH (pr, pr-body, git, merge)
# that logs which headers the merge saw, and a stub GATE_CMD that logs `${FORGE_MERGE_HEADER-unset}` and
# `${FORGE_HEADER-unset}` as the PR's code would see them.
#   bash tools/test/pr-merge.sh     prints one line a case, ends `all pass` (exit 0) or `FAILED: n` (exit 1)
set -euo pipefail
TOOLS="$(cd "$(dirname "$0")/.." && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
export SEEN="$T/seen" MERGES="$T/merges"
HUNTER_HDR="$T/hunter.header"; AGENT_HDR="$T/agent.header"
(umask 077; echo "Authorization: token $(printf 'b%.0s' {1..40})" >"$HUNTER_HDR"
  echo "Authorization: token $(printf 'a%.0s' {1..40})" >"$AGENT_HDR")

g() { git -C "$T/repo" -c user.name=t -c user.email=t@example.invalid -c commit.gpgsign=false "$@"; }
git init -q --bare "$T/forge.git"
git init -q -b master "$T/repo"
mkdir -p "$T/repo/tools"
cp "$TOOLS/pr-merge.sh" "$TOOLS/gate-files.sh" "$T/repo/tools/"
echo base >"$T/repo/README.md"
g add -A; g commit -qm base
g remote add forge "$T/forge.git"
g push -q forge master
g checkout -q -b card/x
echo card >>"$T/repo/README.md"; g commit -qam card
SHA="$(g rev-parse HEAD)"
g push -q forge "card/x:refs/heads/card/x" "card/x:refs/pull/7/head"
g checkout -q master
export SHA

cat >"$T/forge-stub.sh" <<'EOF'
#!/usr/bin/env bash
# forge.sh stub: PR 7 is open from card/x into master with a pass review; merge logs the headers it saw and lands the
# head on the bare forge's master (a fast-forward: the card holds master).
set -euo pipefail
case "$1" in
  pr) echo "open unmerged $SHA card/x master" ;;
  pr-body) printf 'Goal: x\nReviewer: pass\n' ;;
  git) shift; exec git "$@" ;;
  merge)
    echo "merge $2 FORGE_MERGE_HEADER=${FORGE_MERGE_HEADER-unset} FORGE_HEADER=${FORGE_HEADER-unset}" >>"$MERGES"
    git push -q forge "$3:refs/heads/master"
    echo "merged" ;;
  *) echo "stub: unexpected $*" >&2; exit 2 ;;
esac
EOF
cat >"$T/gate-stub.sh" <<'EOF'
#!/usr/bin/env bash
# The PR's code: record what it can see of the token header paths.
echo "FORGE_MERGE_HEADER=${FORGE_MERGE_HEADER-unset} FORGE_HEADER=${FORGE_HEADER-unset}" | tee -a "$SEEN"
env | grep -c '^FORGE_\(MERGE_\)\?HEADER=' >>"$SEEN" || true
EOF
chmod +x "$T/forge-stub.sh" "$T/gate-stub.sh"

fails=0
check() { # <name> <got> <want>
  if [[ $2 == "$3" ]]; then echo "pass: $1"
  else fails=$((fails + 1)); echo "FAIL: $1"; echo "  got:  ${2//$'\n'/ | }"; echo "  want: ${3//$'\n'/ | }"; fi
}

: >"$SEEN"; : >"$MERGES"; rc=0
(cd "$T/repo" && unset FORGE_BASE &&
  FORGE_SH="$T/forge-stub.sh" GATE_CMD="$T/gate-stub.sh" FORGE_MERGE_HEADER="$HUNTER_HDR" FORGE_HEADER="$AGENT_HDR" \
  bash tools/pr-merge.sh 7) >"$T/out" 2>&1 || rc=$?
check "merge exits 0" "$rc" 0
check "last line is MERGED" "$(tail -1 "$T/out")" "MERGED #7 -> master $(g rev-parse --short "$SHA")"
check "gate sees neither header" "$(cat "$SEEN")" $'FORGE_MERGE_HEADER=unset FORGE_HEADER=unset\n0'
check "merge still gets both headers" "$(cat "$MERGES")" "merge 7 FORGE_MERGE_HEADER=$HUNTER_HDR FORGE_HEADER=$AGENT_HDR"
check "forge master is the PR head" "$(git -C "$T/forge.git" rev-parse master)" "$SHA"
((rc == 0)) || sed 's/^/  out: /' "$T/out" | tail -5

if ((fails)); then echo "FAILED: $fails"; exit 1; fi
echo "all pass"
