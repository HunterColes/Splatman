# Splatman Forgejo runner

A Forgejo Actions runner that can build Splatman: forgejo-runner 13.2.1, Temurin JDK 21.0.13, node 22.23.4, the
Android command-line tools (13114758), `platforms;android-34` and `build-tools;34.0.0`, licences accepted at image
build time. Every download is checked against a pinned sha256 (see the top of the `Dockerfile`). Image size: about
1.7 GB on disk.

Workflows pick it with `runs-on: splatman`. Jobs run inside the runner's own container (host mode), so it needs no
docker socket. The container runs one job and stops; Docker starts a fresh one for the next (see "Jobs and trust").

| Limit | Value | Why |
|---|---|---|
| CPUs | 4 | leaves the rest of the host to the web app's runners |
| Memory | 5 GB, no extra swap | the gate needs about 3 GB (Gradle `-Xmx2g` + Kotlin daemon `-Xmx1g`) plus headroom |
| Processes | 2048 | |
| Jobs at once | 1 | one job per container start (`forgejo-runner one-job`, `entrypoint.sh`) |
| Root filesystem | read-only | writable: `/data` and `/cache/gradle` (named volumes), `/tmp` and `$HOME` (tmpfs, fresh per job) |
| User | job steps as `runner` (uid 1001), no capabilities | the entrypoint starts as root with `SETUID` and `SETGID` only, to read the credentials and drop to uid 1001 |
| Network | default bridge, outbound only | no host network, no published ports, no actions cache server |
| Volumes | `splatman-runner_runner-data`, `splatman-runner_gradle-cache`, `splatman-runner_runner-secret` | its own only; never the docker socket or another project's volumes |

All commands below run on the server, from a checkout of this repo. `<forge-url>` is the forge's address
(`https://...`); it never goes into a tracked file.

## 1. Build the image

```bash
cd deploy/forgejo-runner
docker compose build
docker image ls splatman-runner:13.2.1
```

## 2. Get a registration token

Either in the forge's web UI: `<forge-url>/hunter/Splatman/settings/actions/runners` -> **Create new runner** ->
copy the registration token.

Or on the forge's own host, with the forge's container name in place of `<forgejo-container>`:

```bash
docker exec -u git <forgejo-container> forgejo forgejo-cli actions generate-runner-token --scope hunter/Splatman
```

The token registers a runner for the Splatman repository only. Do not save it in a file in this repo.

## 3. Register the runner

```bash
cd deploy/forgejo-runner
docker compose run --rm runner forgejo-runner register --no-interactive \
  --config /etc/forgejo-runner/config.yml \
  --instance <forge-url> \
  --token <token-from-step-2> \
  --name splatman \
  --labels splatman:host
```

This runs as root and writes `/secret/.runner` (the runner's credentials) into the `splatman-runner_runner-secret`
volume, root's alone (mode 0600 in a 0700 directory): no job step can read it. Clear the token from the shell history
afterwards (`history -d <n>`).

Registered before #74? The old registration sat in `/data/.runner`, readable by every job; the new entrypoint deletes
it and will not start without `/secret/.runner`. Delete the old runner in the forge's runner settings, then steps 2 to
4 (rebuild the image first: step 1).

## 4. Start it

```bash
docker compose up -d
docker compose logs --tail 20 runner      # expect "declared successfully", then "single task poller launched"
```

The runner then shows as idle under `<forge-url>/hunter/Splatman/settings/actions/runners` with the label
`splatman`.

## Jobs and trust

Who may trigger a job on `splatman`: anyone who can push a branch to the Splatman repository on the forge or open a
pull request against it there (`.forgejo/workflows/ci.yml` runs on every pull request, every push to `master` and a
manual dispatch). Today that is Hunter and the Company's agents. A pull request runs its own branch's copy of the
workflow and its own Gradle build, so whoever can open one can run any code in this runner as uid 1001, before review.

What one job can and cannot leave for a later one (#74):

| Path | Shared with later jobs? | Guard |
|---|---|---|
| Processes | no | one job per container: when it ends the container stops, which kills everything the job started |
| `/tmp`, `$HOME` | no | tmpfs, discarded when the container stops (`.gitconfig`, Kotlin daemon files and the like go with it) |
| `/data/work` | no | the entrypoint empties it before every job |
| `/secret/.runner` (credentials) | not readable | root's, 0700/0600; the token reaches the runner through a one-shot FIFO, read once at start; job steps run as uid 1001 with no capabilities |
| Gradle hooks in `/cache/gradle` (`init.d/`, `init.gradle`, `init.gradle.kts`, `gradle.properties`) | no | the entrypoint deletes them before every job; the workflow's `gradle hooks` step again |
| The rest of `/cache/gradle` (downloaded jars, the Gradle distribution under `wrapper/dists`, transforms) | **yes** | none: a job can still replace a cached jar that a later build loads. Trusted while only trusted people can push; the moment branches from someone else run here, drop the cache before a master build (`docker compose down`, `docker volume rm splatman-runner_gradle-cache`, `docker compose up -d`) or give their jobs a separate runner |

The token stays in the runner's memory. A job step is a child of the runner and cannot read its parent's memory while
the host keeps `kernel.yama.ptrace_scope` at 1 or more (Ubuntu's default); check with `sysctl kernel.yama.ptrace_scope`.

Proof, on any machine with docker: `bash tools/test/runner-entrypoint.sh` (builds the image, runs it with these
flags against a fake forge on the container's loopback, plants what a hostile job would leave) -> `all pass`.

## Update or remove

- New versions: change the `ARG` and its sha256 together in the `Dockerfile`, then `docker compose build && docker compose up -d`.
- Re-register: `docker compose down -v` (drops the registration, the job volume and the Gradle cache), then steps 2 to 4.
- Remove: `docker compose down -v`, then delete the runner in the forge's runner settings.
