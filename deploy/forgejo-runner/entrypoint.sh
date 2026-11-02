#!/bin/bash
# The Splatman runner's entrypoint (#74): one job per container start, and the runner's credentials out of the jobs'
# reach. See README.md ("Jobs and trust").
#
# Stage 1, as root (the container starts as root with only CAP_SETUID and CAP_SETGID, see compose.yaml):
#   reads /secret/.runner (root, 0600, its own volume), hands the token to the runner through a one-shot FIFO in the
#   fresh /tmp, then drops to the runner user for good. The runner reads the token once, at start; after that the
#   FIFO has no writer and the file stays unreadable to every job step, which all run as the runner user.
# Stage 2 (`--job`), as the runner user: removes what the last job left where the next one would run it (old job
#   workdirs, Gradle's init scripts and user gradle.properties), then runs exactly one job. When it exits the
#   container stops, which ends every process the job left behind and drops /tmp and $HOME (tmpfs), and Docker starts
#   a fresh one (restart: unless-stopped).
# Any other arguments run as given, as root without capabilities: `forgejo-runner register ...` (README step 3).
set -euo pipefail

SECRET=/secret/.runner
TOKEN_FIFO=/tmp/runner-token
RUNNER_UID=1001
LABEL=splatman:host
CONFIG=/etc/forgejo-runner/job.yml   # config.yml with no registration file: the token comes from the FIFO

job() {
  local url="$1" uuid="$2"
  [[ "$(id -u)" == "$RUNNER_UID" ]] || { echo "entrypoint: --job must run as uid $RUNNER_UID"; exit 1; }
  # rm and mkdir, not a find inside it: a job can swap /data/work for a symlink, and find does not follow one.
  rm -rf -- /data/work && mkdir -- /data/work
  # Registered before #74, the credentials sat in the jobs' own volume; they must not stay there.
  if [[ -e /data/.runner ]]; then rm -f -- /data/.runner; echo "entrypoint: removed the old /data/.runner"; fi
  # Files Gradle runs or reads from GRADLE_USER_HOME on every build: a job must not leave one for the next.
  rm -rf -- "${GRADLE_USER_HOME:?}/init.d" "$GRADLE_USER_HOME/init.gradle" "$GRADLE_USER_HOME/init.gradle.kts" \
    "$GRADLE_USER_HOME/gradle.properties"
  exec forgejo-runner one-job --wait --config "$CONFIG" --url "$url" --uuid "$uuid" \
    --token-url "file:$TOKEN_FIFO" --label "$LABEL"
}

if [[ "${1:-}" == --job ]]; then
  shift
  job "$@"
fi
if (( $# > 0 )); then exec "$@"; fi

[[ "$(id -u)" == 0 ]] || { echo "entrypoint: start as root (compose.yaml), so the credentials stay out of jobs"; exit 1; }
[[ -f "$SECRET" ]] || { echo "entrypoint: not registered: no $SECRET (README.md, step 3)"; exit 1; }
chmod 0600 "$SECRET"
read -r url uuid token < <(python3 -I -c '
import json, re, sys
d = json.load(open(sys.argv[1]))
url, uuid, token = d.get("address", ""), d.get("uuid", ""), d.get("token", "")
ok = re.fullmatch(r"https?://[^\s]+", url) and re.fullmatch(r"[0-9a-fA-F-]{36}", uuid) and re.fullmatch(r"[0-9a-zA-Z]+", token)
print(url, uuid, token) if ok else print("- - -")
' "$SECRET")
[[ "$token" != - ]] || { echo "entrypoint: $SECRET lacks a valid address, uuid or token; register again"; exit 1; }

rm -f -- "$TOKEN_FIFO"
mkfifo -m 0644 "$TOKEN_FIFO"
# Blocks until the runner opens the FIFO, writes the token once, exits. Stays root, so no job can read its memory.
( printf '%s' "$token" > "$TOKEN_FIFO" ) &
unset token
exec setpriv --reuid="$RUNNER_UID" --regid="$RUNNER_UID" --clear-groups --inh-caps=-all \
  env HOME=/home/runner "$0" --job "$url" "$uuid"
