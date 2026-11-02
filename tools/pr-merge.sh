#!/usr/bin/env bash
# Land a forge PR into the Company's day train: refuse without "Reviewer: pass" in its body, rerun the gate
# (tools/check.sh) on exactly its head in a temporary worktree, squash-merge that head only. From PersonalSite's agent
# template; the Company runs it as its merge_cmd (company.toml, project splatman) in its merge worktree.
#   tools/pr-merge.sh <n> [--no-review] [--gate-files a,b]
# FORGE_BASE names the base the PR must go into: a day train day/YYYY-MM-DD[-N] (the Company always sets it), or master
# (by hand only); anything else exits 2. The head must hold the base's tip (merged in, never rebased), checked before
# the gate and again right before the merge, with the wording the Company keys on: `refusing: PR #<n> is behind <base>`.
# When FORGE_BASE is set a change to a gate file (tools/gate-files.sh, judged with this checkout's copy) that
# --gate-files does not name is refused; without it the gate files are only listed.
# Every failure's line starts `refusing:`, `RED:` (the gate), `REFUSED:` (gate files) or `NOT MERGED:` (the forge
# refused the merge: `HTTP 405/409 ...`, which the Company sends to rework). The gate's own output is printed as its
# last 40 lines, each indented two spaces, so nothing a PR's tests print can pass for one of those lines (or for
# `MERGED`). Success's last line is exactly `MERGED #<n> -> <base> <short sha>`.
# Tests stub the forge and the gate: FORGE_SH (default tools/forge.sh) and GATE_CMD (default tools/check.sh, run inside
# a throwaway clone of the PR head). The PR's code runs as this uid, so it is kept away from the merge token (#73, #86):
# FORGE_MERGE_HEADER is read into a local and unset first, and only the final `forge.sh merge` gets it back; the gate
# runs without FORGE_HEADER too, in a clone that shares no .git with this checkout (no hooks, config or forge.sh of
# ours to rewrite); every git here runs with core.hooksPath=/dev/null, so a hook a gate planted anyway never fires; and
# $F is copied to a temp file before the gate, so a rewritten checkout copy is never run. This script itself runs from
# a private copy (re-exec at the top, the copy unlinked at once), so a gate that rewrites tools/pr-merge.sh in place
# never has its code read by the bash running the merge (#112). The forge config forge.sh reads (the forge remote's
# URLs, remote.forge.*, url.*, http.*, company.forge) is snapshotted before the gate; if it differs after, or right
# before the merge, the merge is refused (`refusing: PR #<n>: the forge config changed while the gate ran`), and the
# post-gate forge.sh calls get FORGE_URL pinned to the forge picked before the gate. tools/test/pr-merge.sh proves it.
# Same-uid code can still find this checkout and the token file by itself: the root fix (the gate as another uid, or
# the token file unreadable while PR code runs) is Bombadil's.
set -euo pipefail
if [[ ${PR_MERGE_COPY-} != "${BASH_SOURCE[0]}" ]]; then
  self="$(mktemp)"; cp "${BASH_SOURCE[0]}" "$self"
  PR_MERGE_COPY="$self" exec "$BASH" "$self" "$@"
fi
rm -f "$PR_MERGE_COPY"; unset PR_MERGE_COPY
merge_hdr_set="${FORGE_MERGE_HEADER+1}"; merge_hdr="${FORGE_MERGE_HEADER-}"; unset FORGE_MERGE_HEADER
# Read by every git below, forge.sh's included (it sets its own GIT_CONFIG_COUNT, which this does not touch).
export GIT_CONFIG_PARAMETERS="${GIT_CONFIG_PARAMETERS:+$GIT_CONFIG_PARAMETERS }'core.hooksPath'='/dev/null'"
cd "$(git rev-parse --show-toplevel)"
n="${1:-}"; shift || true
[[ $n =~ ^[1-9][0-9]{0,6}$ ]] || { echo "refusing: not a PR number: ${n:-none}"; exit 2; }
review=1; named=""
while (($#)); do case "$1" in
  --no-review) review=0; shift ;;
  --gate-files) named="${2-}"; shift 2 || { echo "refusing: --gate-files needs a list"; exit 2; } ;;
  *) echo "refusing: unknown argument $1"; exit 2 ;;
esac; done
enforce=0; [[ -z ${FORGE_BASE:-} ]] || enforce=1
BASE="${FORGE_BASE:-master}"
[[ $BASE == master || $BASE =~ ^day/[0-9]{4}-[0-9]{2}-[0-9]{2}(-[0-9]{1,2})?$ ]] ||
  { echo "refusing: bad FORGE_BASE '$BASE' (master or day/YYYY-MM-DD[-N])"; exit 2; }
F="${FORGE_SH:-$PWD/tools/forge.sh}"; [[ $F == /* ]] || F="$PWD/$F"; GATE="${GATE_CMD:-tools/check.sh}"
trap 'echo "refusing: PR #$n: unexpected failure at tools/pr-merge.sh line $LINENO"' ERR

info="$("$F" pr "$n")" || { echo "refusing: PR #$n: forge.sh pr failed"; exit 1; }
read -r state merged sha ref base <<<"$info"
[[ $state == open && $merged == unmerged ]] || { echo "refusing: PR #$n is $state/$merged"; exit 1; }
[[ $base == "$BASE" ]] || { echo "refusing: PR #$n goes into $base, not $BASE"; exit 1; }
[[ ! $ref =~ ^day/ && $ref != master ]] || { echo "refusing: PR #$n is from $ref, not a card branch"; exit 1; }
if ((review)); then
  "$F" pr-body "$n" | grep -qx 'Reviewer: pass' || { echo "refusing: PR #$n has no 'Reviewer: pass' line"; exit 1; }
fi
"$F" git fetch -q --no-tags forge "+refs/heads/$BASE:refs/remotes/forge/$BASE" "+refs/pull/$n/head:refs/forge-pr/$n"
[[ "$(git rev-parse "refs/forge-pr/$n")" == "$sha" ]] ||
  { echo "refusing: the forge's refs/pull/$n/head is not the PR's head ($sha)"; exit 1; }
behind() {
  git merge-base --is-ancestor "forge/$BASE" "$sha" ||
    { echo "refusing: PR #$n is behind $BASE (its head lacks $(git rev-parse --short "forge/$BASE")): merge forge/$BASE into $ref, never rebase"; exit 1; }
}
behind

touched="$(git diff --raw --no-renames --no-abbrev -z "$(git merge-base "forge/$BASE" "$sha")" "$sha" | tools/gate-files.sh --raw)"
if [[ -n $touched ]]; then
  missing=""
  while IFS= read -r f; do [[ ",$named," == *",$f,"* ]] || missing+=" $f"; done <<<"$touched"
  if [[ -n $missing && $enforce == 1 ]]; then
    echo "REFUSED: PR #$n changes gate files not named in --gate-files:$missing"; exit 1
  fi
  echo "gate files changed: $(tr '\n' ' ' <<<"$touched")"
fi

wt="$(mktemp -d)"; log="$(mktemp)"; fsnap="$(mktemp)"
trap 'rm -rf "$wt" "$log" "$fsnap"' EXIT
# The forge.sh the post-gate calls run: this copy, taken before any PR code runs.
cp "$F" "$fsnap"; chmod 700 "$fsnap"; F="$fsnap"
# A throwaway clone of exactly the head (its own .git, nothing shared with this checkout's), with the local config
# check.sh reads (company.private*, the private-host check) and a link to the build cache a worktree would get.
git init -q "$wt"
git -C "$wt" fetch -q --no-tags --depth 1 "file://$PWD" "refs/forge-pr/$n"
git -C "$wt" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$wt" rev-parse HEAD)" == "$sha" ]] || { echo "refusing: PR #$n: the gate clone is not at $sha"; exit 1; }
for k in company.private company.private-allow; do
  if v="$(git config --get "$k")"; then git -C "$wt" config "$k" "$v"; fi
done
cache="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")/build-cache"
[[ ! -d $cache ]] || ln -s "$cache" "$wt/build-cache"
# What forge.sh reads to pick where the merge header goes; any change while PR code ran refuses the merge.
forge_cfg() {
  git remote get-url --all forge; git remote get-url --push --all forge
  git config --get-regexp '^(remote\.forge\.|url\.|http\.|company\.forge$)' || true
}
cfg="$(forge_cfg)"; forge_pin="${FORGE_URL:-$(git config --get company.forge || true)}"
same_forge() {
  [[ "$(forge_cfg 2>/dev/null || true)" == "$cfg" && "${FORGE_URL:-$(git config --get company.forge || true)}" == "$forge_pin" ]] ||
    { echo "refusing: PR #$n: the forge config changed while the gate ran (remote forge, url.*, http.* or company.forge)"; exit 1; }
}
# In a `||` list a red gate trips neither errexit nor the ERR trap.
rc=0
# The PR's own code runs here as this uid: it gets neither token header path (#73); only the final merge sees them.
( cd "$wt" && exec env -u FORGE_MERGE_HEADER -u FORGE_HEADER "$GATE" ) >"$log" 2>&1 || rc=$?
echo "gate on ${sha:0:10}, last 40 lines:"
tail -n 40 "$log" | sed 's/^/  /'
((rc == 0)) || { echo "RED: PR #$n: gate red on ${sha:0:10}: $(grep -v '^[[:space:]]*$' "$log" | tail -1 | cut -c1-300)"; exit 1; }

same_forge
[[ -z $forge_pin ]] || export FORGE_URL="$forge_pin"
"$F" git fetch -q --no-tags forge "+refs/heads/$BASE:refs/remotes/forge/$BASE"
behind
same_forge
if [[ -n $merge_hdr_set ]]; then mout="$(FORGE_MERGE_HEADER="$merge_hdr" "$F" merge "$n" "$sha" 2>&1)"
else mout="$("$F" merge "$n" "$sha" 2>&1)"; fi || { echo "NOT MERGED: PR #$n: $(tr '\n' ' ' <<<"$mout" | cut -c1-400)"; exit 1; }
echo "$mout"
"$F" git fetch -q --no-tags forge "+refs/heads/$BASE:refs/remotes/forge/$BASE"
echo "MERGED #$n -> $BASE $(git rev-parse --short "forge/$BASE")"
