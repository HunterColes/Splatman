#!/usr/bin/env bash
# deploy/forgejo-runner/entrypoint.sh (#74): builds the runner image and runs it with compose.yaml's hardening, with no
# network: a fake forge on the container's loopback stands in for the real one. Needs docker; the image's base layers
# come from the Docker cache, or are downloaded on the first build (the Dockerfile's pinned sha256s).
#   bash tools/test/runner-entrypoint.sh     prints one line a case, ends `all pass` (exit 0) or `FAILED: n` (exit 1)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IMAGE=splatman-runner:test-entrypoint
T="$(mktemp -d)"; vols=()
cleanup() { ((${#vols[@]})) && docker volume rm -f "${vols[@]}" >/dev/null; rm -rf "$T"; }
trap cleanup EXIT
fails=0
docker build -q -t "$IMAGE" "$ROOT/deploy/forgejo-runner" >/dev/null

# A forge that answers every RunnerService call with an empty message (no task) and logs the token it was sent.
cat >"$T/fake.py" <<'EOF'
import http.server, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get('content-length') or 0))
        sys.stderr.write("%s token=%s\n" % (self.path.rsplit('/', 1)[-1], self.headers.get('x-runner-token')))
        self.send_response(200); self.send_header('content-type', self.headers.get('content-type') or 'application/proto')
        self.send_header('content-length', '0'); self.end_headers()
    def log_message(self, *a): pass
http.server.HTTPServer(('127.0.0.1', 8099), H).serve_forever()
EOF
# Runs as root in the container: registers (a fake registration file), plants what a hostile job would leave, starts
# the entrypoint, then looks around as the job user. Prints key=value lines.
cat >"$T/probe.sh" <<'EOF'
python3 -I /t/fake.py 2>/tmp/forge.log &
for _ in $(seq 50); do (echo >/dev/tcp/127.0.0.1/8099) 2>/dev/null && break; sleep 0.1; done
if [[ "$1" == registered ]]; then
  printf '{"uuid":"11111111-2222-3333-4444-555555555555","token":"s3cr3tTok","address":"http://127.0.0.1:8099"}' >/secret/.runner
  chmod 0644 /secret/.runner
fi
setpriv --reuid=1001 --regid=1001 --clear-groups bash -c 'mkdir -p /cache/gradle/init.d /data/work/oldjob
  for f in init.d/evil.gradle init.gradle init.gradle.kts gradle.properties; do echo x >/cache/gradle/$f; done
  echo old-credentials >/data/.runner'
if [[ "${2:-}" == symlinked ]]; then
  setpriv --reuid=1001 --regid=1001 --clear-groups bash -c 'mkdir -p /data/stash/oldjob && rm -rf /data/work && ln -s /data/stash /data/work'
fi
/usr/local/bin/splatman-runner-entrypoint >/tmp/ep.log 2>&1 & ep=$!
for _ in $(seq 50); do grep -q FetchTask /tmp/forge.log 2>/dev/null && break; [[ -d /proc/$ep ]] || break; sleep 0.2; done
[[ -d /proc/$ep ]] && echo "running=yes" || echo "running=no"
echo "log=$(tr '\n' ' ' </tmp/ep.log)"
echo "forge=$(sort -u /tmp/forge.log | tr '\n' ' ')"
echo "secret_mode=$(stat -c '%a %U' /secret/.runner 2>/dev/null)"
setpriv --reuid=1001 --regid=1001 --clear-groups bash -c '
  cat /secret/.runner >/dev/null 2>&1 && echo "job_reads_secret=yes" || echo "job_reads_secret=no"
  echo "job_reads_fifo=[$(timeout 2 cat /tmp/runner-token 2>/dev/null)]"
  p=$(pgrep -x forgejo-runner | head -1)
  if [[ -n "$p" ]]; then
    echo "runner_uid=$(stat -c %u /proc/$p) runner_$(grep CapEff /proc/$p/status | tr -d "\t ")"
    echo "runner_env_cmdline_token=$(cat /proc/$p/environ /proc/$p/cmdline | tr "\0" " " | grep -c s3cr3t)"
  fi
  echo "work=$([[ -L /data/work ]] && echo link || echo dir):$(ls -A /data/work | tr "\n" " ")"
  echo "left=$(cd /cache/gradle && ls -A | tr "\n" " ")|$(ls -A /data/work | tr "\n" " ")|$(ls -A /data | tr "\n" " ")"'
EOF

# run <registered|unregistered>: one container with compose.yaml's flags; prints the probe's lines.
run() {
  local id; id="$(basename "$T")-$RANDOM"; vols+=("$id-s" "$id-d" "$id-c")
  docker run --rm --network none --read-only --cap-drop ALL --cap-add SETUID --cap-add SETGID \
    --security-opt no-new-privileges:true --tmpfs /tmp:size=64m,uid=1001,gid=1001,mode=1777 \
    --tmpfs /home/runner:size=16m,uid=1001,gid=1001,mode=0700 -v "$id-s:/secret" -v "$id-d:/data" \
    -v "$id-c:/cache/gradle" -v "$T:/t:ro" --entrypoint bash "$IMAGE" /t/probe.sh "$@" 2>&1
}
# expect <name> <output> <line the output must hold>
expect() {
  if grep -qF -- "$3" <<<"$2"; then echo "ok   $1"; else echo "FAIL $1: no '$3' in:"; sed 's/^/     /' <<<"$2"; fails=$((fails + 1)); fi
}

out="$(run registered)"
expect "the runner starts and keeps polling" "$out" "running=yes"
expect "the forge gets the token" "$out" "forge=Declare token=s3cr3tTok FetchTask token=s3cr3tTok"
expect "the credentials end up root's, 0600" "$out" "secret_mode=600 root"
expect "a job step cannot read the credentials" "$out" "job_reads_secret=no"
expect "a job step gets nothing from the token FIFO" "$out" "job_reads_fifo=[]"
expect "the runner runs as uid 1001 with no capabilities" "$out" "runner_uid=1001 runner_CapEff:0000000000000000"
expect "the token is in neither the runner's environment nor its argv" "$out" "runner_env_cmdline_token=0"
expect "the last job's Gradle hooks, workdirs and old credentials are gone" "$out" "left=||work "
expect "the old /data/.runner removal is logged" "$out" "entrypoint: removed the old /data/.runner"

out="$(run registered symlinked)"
expect "a job that swapped /data/work for a symlink -> a fresh, empty directory" "$out" "work=dir:"
expect "symlinked /data/work -> the runner still starts" "$out" "running=yes"

out="$(run unregistered)"
expect "no registration -> refuses to start" "$out" "running=no"
expect "no registration -> points at the README" "$out" "entrypoint: not registered: no /secret/.runner"

out="$(docker run --rm --network none --user 1001 "$IMAGE" 2>&1 || true)"
expect "started as the runner user -> refuses" "$out" "entrypoint: start as root"

if ((fails)); then echo "FAILED: $fails"; exit 1; fi
echo "all pass"
