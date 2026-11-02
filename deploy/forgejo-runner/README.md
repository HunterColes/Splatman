# Splatman Forgejo runner

A Forgejo Actions runner that can build Splatman: forgejo-runner 13.2.1, Temurin JDK 21.0.13, node 22.23.4, the
Android command-line tools (13114758), `platforms;android-34` and `build-tools;34.0.0`, licences accepted at image
build time. Every download is checked against a pinned sha256 (see the top of the `Dockerfile`). Image size: about
1.7 GB on disk.

Workflows pick it with `runs-on: splatman`. Jobs run inside the runner's own container (host mode), so it needs no
docker socket.

| Limit | Value | Why |
|---|---|---|
| CPUs | 4 | leaves the rest of the host to the web app's runners |
| Memory | 5 GB, no extra swap | the gate needs about 3 GB (Gradle `-Xmx2g` + Kotlin daemon `-Xmx1g`) plus headroom |
| Processes | 2048 | |
| Jobs at once | 1 | `capacity: 1` in the image's config |
| Root filesystem | read-only | writable: `/data` and `/cache/gradle` (named volumes), `/tmp` and `$HOME` (tmpfs) |
| Network | default bridge, outbound only | no host network, no published ports, no actions cache server |
| Volumes | `splatman-runner_runner-data`, `splatman-runner_gradle-cache` | its own only; never the docker socket or another project's volumes |

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

This writes `/data/.runner` (the runner's credentials) into the `splatman-runner_runner-data` volume. Clear the token from
the shell history afterwards (`history -d <n>`).

## 4. Start it

```bash
docker compose up -d
docker compose logs --tail 20 runner      # expect the runner to declare itself with its labels, no error
```

The runner then shows as idle under `<forge-url>/hunter/Splatman/settings/actions/runners` with the label
`splatman`.

## Update or remove

- New versions: change the `ARG` and its sha256 together in the `Dockerfile`, then `docker compose build && docker compose up -d`.
- Re-register: `docker compose down -v` (drops the registration and the Gradle cache), then steps 2 to 4.
- Remove: `docker compose down -v`, then delete the runner in the forge's runner settings.
