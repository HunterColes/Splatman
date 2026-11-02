#!/usr/bin/env bash
# tools/pr-merge.sh keeps both token header paths out of the PR-head gate while the merge still gets them (#73).
# No network and no real token: a throwaway repo with a local bare "forge", a stub FORGE_SH (pr, pr-body, git, merge)
# that logs which headers the merge saw, and a stub GATE_CMD that logs `${FORGE_MERGE_HEADER-unset}` and
# `${FORGE_HEADER-unset}` as the PR's code would see them. A second case (#86) has a hostile gate stub that plants a
# reference-transaction hook (in its own git dir and in the merge checkout's) and rewrites the checkout's forge.sh
# (the default FORGE_SH); neither may run with the merge header, and the merge must still send it. The #112 cases have a
# gate stub that rewrites tools/pr-merge.sh in place (code after the gate line that logs $merge_hdr) and/or the
# checkout's remote.forge.url: the injected code never runs, and a changed forge remote refuses the merge.
#   bash tools/test/pr-merge.sh     prints one line a case, ends `all pass` (exit 0) or `FAILED: n` (exit 1)
set -euo pipefail
TOOLS="$(cd "$(dirname "$0")/.." && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
export SEEN="$T/seen" MERGES="$T/merges" HOOKED="$T/hooked" CHECKOUT="$T/repo"
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
SHA="$(g rev-parse HEAD)"; BASE_SHA="$(g rev-parse master)"
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
cat >"$T/evil-gate.sh" <<'EOF'
#!/usr/bin/env bash
# Hostile PR code: a hook that logs the merge header wherever git runs next, in the git dir it was given and in the
# merge checkout's (found by path, as same-uid code can), and a rewritten checkout forge.sh that does the same.
set -euo pipefail
hook='#!/bin/sh
echo "hook FORGE_MERGE_HEADER=${FORGE_MERGE_HEADER-unset}" >>"$HOOKED"'
for d in "$(git rev-parse --path-format=absolute --git-common-dir)/hooks" "$CHECKOUT/.git/hooks"; do
  mkdir -p "$d"; printf '%s\n' "$hook" >"$d/reference-transaction"; chmod +x "$d/reference-transaction"
done
printf '#!/bin/sh\necho "forge.sh rewritten $1 FORGE_MERGE_HEADER=${FORGE_MERGE_HEADER-unset}" >>"$HOOKED"\n' \
  >"$CHECKOUT/tools/forge.sh"
echo "gate: green"
EOF
cat >"$T/rewrite-gate.sh" <<'EOF'
#!/usr/bin/env bash
# Hostile PR code (#112): rewrite the checkout's tools/pr-merge.sh in place, byte-identical through the gate line and
# then code that logs $merge_hdr, which a bash still reading that file would run next; with EVIL_URL set, also point
# the checkout's forge remote at another repo; with URL_ONLY set, only that.
set -euo pipefail
f="$CHECKOUT/tools/pr-merge.sh"
new="$(awk '{ print } /"\$GATE" \) >"\$log" 2>&1/ { exit }' "$f"
  printf '%s\n' 'echo "injected merge_hdr=${merge_hdr-unset}" >>"$HOOKED"' 'exit 0')"
[[ -n ${URL_ONLY:-} ]] || printf '%s\n' "$new" >"$f"
[[ -z ${EVIL_URL:-} ]] || git -C "$CHECKOUT" config remote.forge.url "$EVIL_URL"
echo "gate: green"
EOF
chmod +x "$T/forge-stub.sh" "$T/gate-stub.sh" "$T/evil-gate.sh" "$T/rewrite-gate.sh"

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

# #86: the forge stub is the checkout's own (untracked) tools/forge.sh, the default FORGE_SH the gate rewrites.
git -C "$T/forge.git" update-ref refs/heads/master "$BASE_SHA"
cp "$T/forge-stub.sh" "$T/repo/tools/forge.sh"
: >"$MERGES"; : >"$HOOKED"; rc=0
(cd "$T/repo" && unset FORGE_BASE FORGE_SH &&
  GATE_CMD="$T/evil-gate.sh" FORGE_MERGE_HEADER="$HUNTER_HDR" FORGE_HEADER="$AGENT_HDR" \
  bash tools/pr-merge.sh 7) >"$T/out" 2>&1 || rc=$?
check "hostile gate: merge exits 0" "$rc" 0
check "hostile gate: last line is MERGED" "$(tail -1 "$T/out")" "MERGED #7 -> master $(g rev-parse --short "$SHA")"
check "hostile gate: planted hook and rewritten forge.sh never run" "$(cat "$HOOKED")" ""
check "hostile gate: merge still gets both headers" "$(cat "$MERGES")" \
  "merge 7 FORGE_MERGE_HEADER=$HUNTER_HDR FORGE_HEADER=$AGENT_HDR"
check "hostile gate: forge master is the PR head" "$(git -C "$T/forge.git" rev-parse master)" "$SHA"
((rc == 0)) || sed 's/^/  out: /' "$T/out" | tail -5

# #112 (a): the gate rewrites tools/pr-merge.sh in place; the bash running the merge reads a private copy, so the
# injected code never runs and the merge goes through as before.
git -C "$T/forge.git" update-ref refs/heads/master "$BASE_SHA"
g checkout -q -- tools/pr-merge.sh
: >"$MERGES"; : >"$HOOKED"; rc=0
(cd "$T/repo" && unset FORGE_BASE EVIL_URL &&
  FORGE_SH="$T/forge-stub.sh" GATE_CMD="$T/rewrite-gate.sh" FORGE_MERGE_HEADER="$HUNTER_HDR" FORGE_HEADER="$AGENT_HDR" \
  bash tools/pr-merge.sh 7) >"$T/out" 2>&1 || rc=$?
check "rewritten pr-merge.sh: the gate did rewrite it" "$(grep -c '^echo "injected' "$T/repo/tools/pr-merge.sh")" 1
check "rewritten pr-merge.sh: merge exits 0" "$rc" 0
check "rewritten pr-merge.sh: last line is MERGED" "$(tail -1 "$T/out")" "MERGED #7 -> master $(g rev-parse --short "$SHA")"
check "rewritten pr-merge.sh: injected code never runs" "$(cat "$HOOKED")" ""
check "rewritten pr-merge.sh: merge still gets both headers" "$(cat "$MERGES")" \
  "merge 7 FORGE_MERGE_HEADER=$HUNTER_HDR FORGE_HEADER=$AGENT_HDR"
((rc == 0)) || sed 's/^/  out: /' "$T/out" | tail -5

# #112 (b): the gate also points remote.forge.url at another repo; the merge is refused before any post-gate forge
# call, so the header goes nowhere and neither forge moves.
git -C "$T/forge.git" update-ref refs/heads/master "$BASE_SHA"
g checkout -q -- tools/pr-merge.sh
git clone -q --bare "$T/forge.git" "$T/evil.git"
: >"$MERGES"; : >"$HOOKED"; rc=0
(cd "$T/repo" && unset FORGE_BASE &&
  FORGE_SH="$T/forge-stub.sh" GATE_CMD="$T/rewrite-gate.sh" EVIL_URL="$T/evil.git" \
  FORGE_MERGE_HEADER="$HUNTER_HDR" FORGE_HEADER="$AGENT_HDR" bash tools/pr-merge.sh 7) >"$T/out" 2>&1 || rc=$?
check "rewritten forge remote: the gate did rewrite it" "$(g remote get-url forge)" "$T/evil.git"
check "rewritten forge remote: merge exits 1" "$rc" 1
check "rewritten forge remote: last line refuses" "$(tail -1 "$T/out")" \
  "refusing: PR #7: the forge config changed while the gate ran (remote forge, url.*, http.* or company.forge)"
check "rewritten forge remote: injected code never runs" "$(cat "$HOOKED")" ""
check "rewritten forge remote: no merge call" "$(cat "$MERGES")" ""
check "rewritten forge remote: forge master unmoved" "$(git -C "$T/forge.git" rev-parse master)" "$BASE_SHA"
check "rewritten forge remote: other repo unmoved" "$(git -C "$T/evil.git" rev-parse master)" "$BASE_SHA"

# #112 (c): the forge remote alone, so the refusal is proven without the re-exec's help.
g config remote.forge.url "$T/forge.git"
g checkout -q -- tools/pr-merge.sh
: >"$MERGES"; rc=0
(cd "$T/repo" && unset FORGE_BASE &&
  FORGE_SH="$T/forge-stub.sh" GATE_CMD="$T/rewrite-gate.sh" EVIL_URL="$T/evil.git" URL_ONLY=1 \
  FORGE_MERGE_HEADER="$HUNTER_HDR" FORGE_HEADER="$AGENT_HDR" bash tools/pr-merge.sh 7) >"$T/out" 2>&1 || rc=$?
check "forge remote only: merge exits 1" "$rc" 1
check "forge remote only: last line refuses" "$(tail -1 "$T/out")" \
  "refusing: PR #7: the forge config changed while the gate ran (remote forge, url.*, http.* or company.forge)"
check "forge remote only: no merge call" "$(cat "$MERGES")" ""
check "forge remote only: other repo unmoved" "$(git -C "$T/evil.git" rev-parse master)" "$BASE_SHA"

if ((fails)); then echo "FAILED: $fails"; exit 1; fi
echo "all pass"
