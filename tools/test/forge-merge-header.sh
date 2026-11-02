#!/usr/bin/env bash
# tools/forge.sh with FORGE_MERGE_HEADER unset, set and refused (#58). No network and no real token: curl and git's
# remote calls are PATH shims that log `<METHOD> <api path> <agent|hunter>` per call and answer with canned JSON; the
# repo is a throwaway with a fake forge remote. Every call's argv and every stderr line are checked for the tokens.
#   bash tools/test/forge-merge-header.sh     prints one line a case, ends `all pass` (exit 0) or `FAILED: n` (exit 1)
set -euo pipefail
FORGE_SH="$(cd "$(dirname "$0")/.." && pwd)/forge.sh"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
AGENT_TOKEN="$(printf 'a%.0s' {1..40})"; HUNTER_TOKEN="$(printf 'b%.0s' {1..40})"
SHA="$(printf 'c%.0s' {1..40})"
export AGENT_HDR="$T/agent.header" HUNTER_HDR="$T/hunter.header" CALLS="$T/calls" AGENT_TOKEN HUNTER_TOKEN SHA
export REAL_GIT; REAL_GIT="$(command -v git)"
(umask 077; echo "Authorization: token $AGENT_TOKEN" >"$AGENT_HDR"; echo "Authorization: token $HUNTER_TOKEN" >"$HUNTER_HDR")

mkdir -p "$T/bin" "$T/repo"
cat >"$T/bin/curl" <<'EOF'
#!/usr/bin/env bash
# curl shim: log method, API path and whose header file; answer the few calls forge.sh makes.
m=GET hdr=none url="" out="" code=""
while (($#)); do
  case "$1" in
    *"$AGENT_TOKEN"* | *"$HUNTER_TOKEN"*) echo "LEAK argv" >>"$CALLS" ;;
  esac
  case "$1" in
    -X) m="$2"; shift ;;
    -H) case "$2" in @"$AGENT_HDR") hdr=agent ;; @"$HUNTER_HDR") hdr=hunter ;; @*) hdr="other:${2#@}" ;; esac; shift ;;
    -o) out="$2"; shift ;;
    -w) code=1; shift ;;
    --data-binary | --proto) shift ;;
    https://*) url="$1" ;;
  esac
  shift
done
[[ $m == GET ]] || cat >/dev/null
p="${url#https://forge.example/api/v1/repos/hunter/Splatman}"
echo "$m $p $hdr" >>"$CALLS"
pr='{"number":7,"title":"t","body":"b","state":"open","merged":false,"head":{"ref":"card/x","sha":"'"$SHA"'"},"base":{"ref":"master"}}'
case "$m $p" in
  "GET /pulls?"*) reply="[$pr]" ;;
  "GET /pulls/7") reply="$pr" ;;
  "GET /pulls/7/reviews") reply='[]' ;;
  "POST /pulls") reply="$pr" ;;
  *) reply='{}' ;;
esac
if [[ -n $out ]]; then printf '%s' "$reply" >"$out"; else printf '%s' "$reply"; fi
[[ -z $code ]] || printf 200
EOF
cat >"$T/bin/git" <<'EOF'
#!/usr/bin/env bash
# git shim: remote calls are logged with whose header they carry; everything else is the real git.
case "${1:-}" in
  fetch | push | ls-remote)
    case "${GIT_CONFIG_VALUE_0:-}" in
      "Authorization: token $AGENT_TOKEN") who=agent ;; "Authorization: token $HUNTER_TOKEN") who=hunter ;; *) who=none ;;
    esac
    echo "GIT $1 $who" >>"$CALLS" ;;
  *) exec "$REAL_GIT" "$@" ;;
esac
EOF
chmod +x "$T/bin/curl" "$T/bin/git"
"$REAL_GIT" -C "$T/repo" init -q
"$REAL_GIT" -C "$T/repo" remote add forge https://forge.example/hunter/Splatman.git
"$REAL_GIT" -C "$T/repo" config company.forge https://forge.example
echo "a review" >"$T/review.md"

fails=0
# run <merge-header or -> <subcommand args...>: forge.sh in the throwaway repo; sets rc, $T/err; resets $CALLS.
run() {
  local mh="$1"; shift
  : >"$CALLS"
  rc=0
  if [[ $mh == - ]]; then
    (cd "$T/repo" && unset FORGE_MERGE_HEADER && PATH="$T/bin:$PATH" FORGE_HEADER="$AGENT_HDR" bash "$FORGE_SH" "$@") \
      >"$T/out" 2>"$T/err" || rc=$?
  else
    (cd "$T/repo" && PATH="$T/bin:$PATH" FORGE_HEADER="$AGENT_HDR" FORGE_MERGE_HEADER="$mh" bash "$FORGE_SH" "$@") \
      >"$T/out" 2>"$T/err" || rc=$?
  fi
  if grep -qF -e "$AGENT_TOKEN" -e "$HUNTER_TOKEN" "$T/out" "$T/err" "$CALLS"; then rc=leak; fi
}
# expect <name> <rc> <calls, one per line>
expect() {
  local name="$1" want_rc="$2" want="$3" got
  got="$(cat "$CALLS")"
  if [[ $rc == "$want_rc" && $got == "$want" ]]; then echo "pass: $name"
  else
    fails=$((fails + 1))
    echo "FAIL: $name: rc $rc (want $want_rc)"; echo "  calls: ${got//$'\n'/ | }"; echo "  want:  ${want//$'\n'/ | }"
    sed 's/^/  stderr: /' "$T/err" | head -3
  fi
}

# Every subcommand but merge, under each setting of FORGE_MERGE_HEADER: all the agent's.
others() { # <merge-header or -> <label>
  run "$1" pr-list; expect "$2: pr-list" 0 "GET /pulls?state=open&limit=50 agent"
  run "$1" pr 7; expect "$2: pr" 0 "GET /pulls/7 agent"
  run "$1" pr-body 7; expect "$2: pr-body" 0 "GET /pulls/7 agent"
  run "$1" pr-title 7 new; expect "$2: pr-title" 0 "PATCH /pulls/7 agent"
  run "$1" pr-set-body 7 "$T/review.md"; expect "$2: pr-set-body" 0 "PATCH /pulls/7 agent"
  run "$1" comment 7 "$T/review.md"; expect "$2: comment" 0 "POST /pulls/7/reviews agent"
  run "$1" comments 7; expect "$2: comments" 0 "GET /pulls/7/reviews agent"
  run "$1" pr-open card/x title "$T/review.md"; expect "$2: pr-open" 0 "POST /pulls agent"
  run "$1" git ls-remote forge; expect "$2: git" 0 "GIT ls-remote agent"
}

# 1. Unset, and set but empty: exactly as before, the agent's header everywhere.
run - merge 7 "$SHA"; expect "unset: merge" 0 $'GET /pulls/7 agent\nPOST /pulls/7/merge agent'
others - unset
run "" merge 7 "$SHA"; expect "empty: merge" 0 $'GET /pulls/7 agent\nPOST /pulls/7/merge agent'
others "" empty

# 2. Set: only the merge POST carries Hunter's header.
run "$HUNTER_HDR" merge 7 "$SHA"; expect "set: merge" 0 $'GET /pulls/7 agent\nPOST /pulls/7/merge hunter'
others "$HUNTER_HDR" set

# 3. Refused before any call: missing, world- or group-readable, two lines, not a header line, a directory.
bad() { # <name> <file>
  run "$2" merge 7 "$SHA"; expect "refused: $1" 2 ""
}
bad missing "$T/nope.header"
cp "$HUNTER_HDR" "$T/world.header"; chmod 644 "$T/world.header"; bad world-readable "$T/world.header"
cp "$HUNTER_HDR" "$T/group.header"; chmod 640 "$T/group.header"; bad group-readable "$T/group.header"
(umask 077; { cat "$HUNTER_HDR"; echo "X-Other: 1"; } >"$T/two.header"); bad two-lines "$T/two.header"
(umask 077; { cat "$HUNTER_HDR"; echo; } >"$T/blank.header"); bad trailing-blank-line "$T/blank.header"
(umask 077; echo "Authorization: token short" >"$T/short.header"); bad malformed "$T/short.header"
(umask 077; : >"$T/empty.header"); bad empty-file "$T/empty.header"
mkdir -m 700 "$T/dir.header"; bad directory "$T/dir.header"
(umask 077; echo "Authorization: token $HUNTER_TOKEN" >"$T/unread.header"); chmod 200 "$T/unread.header"
[[ $(id -u) == 0 ]] || bad unreadable "$T/unread.header"
# A bad merge header never stops the agent's own calls.
run "$T/world.header" pr-list; expect "refused merge header: pr-list still agent" 0 "GET /pulls?state=open&limit=50 agent"

if ((fails)); then echo "FAILED: $fails"; exit 1; fi
echo "all pass"
