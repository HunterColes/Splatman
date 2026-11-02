# Splatman: notes for Claude

## What it is meant to be

A free, offline Android app that scans a place into a 3D Gaussian splat: point the phone camera around,
ARCore tracks the pose of every frame, the app builds a 3D Gaussian splat from them on the device, shows it in a
viewer you can orbit, and exports it as a file (.ply and .splat). Importing a splat file to view is secondary.
No ads, no accounts, no internet permission. See STATUS.md for how far the code really is (not far: it is being
rebuilt, so do not polish what is there).

## Layout

| Module | What lives there |
|---|---|
| `app` | `MainActivity`, `MainApplication`, Room database wiring, release build config |
| `core` | Design system (`core/design`: `Splat*` tokens, theme), navigation, preferences, coroutines/Hilt modules. Still holds leftovers from a poker app this was forked from (blinds, chips, odds, bank); delete them as you meet them |
| `capture-feature` | Capture flow: camera, ARCore session, frame extraction (the poker "tournament" leftovers in it are dead) |
| `library-feature` | The list of saved scans (the "Bank" classes are poker leftovers) |
| `viewer-feature` | OpenGL ES renderer, camera controller, viewer screen, export |

Jetpack Compose (Material 3), Hilt, MVI-style (`*Intent`, `*UiState`, `*ViewModel`). Bytecode targets 17; the
toolchain on this machine is JDK 21.

## The gate

```bash
tools/check.sh        # ./gradlew assembleDebug testDebugUnitTest lintDebug, one Gradle at a time
```

A card must pass it before `tools/pr.sh` pushes it, and `tools/pr-merge.sh` reruns it on the PR head before merging.
Detekt is not in the gate (it has 69 open findings). The check takes a lock (`/tmp/splatman-gradle.lock`), runs a
single-use `--no-daemon` Gradle with `-Xmx2g` and the Kotlin daemon at `-Xmx1g`, so builds never overlap.
Never run `./gradlew --stop`, never `pkill -f` with a pattern that can match your own shell, never regenerate a
lint baseline to get green. Do not run other Gradle builds beside the gate.

## Company conventions

- One card, one branch (`card/...`), one PR into `master`; open it with `tools/pr.sh "<title>" --body-file <file>`
  (merges `forge/master` into the branch, runs the gate, pushes to `forge`, opens the PR).
- Keep a branch current with `git merge forge/master`. **Never rebase, never force-push.**
- `tools/pr-merge.sh <n>` squash-merges a reviewed PR straight into `master` after the gate passes on its head.
- Files that steer the gate (`tools/`, `*.gradle.kts`, `CLAUDE.md`, dotfiles, `metadata/*.yml`, ...) are listed by
  `tools/gate-files.sh`; a PR that changes one needs its reviewer to name it.
- Update `STATUS.md` (the Now line) at the end of a session.
- Keep working features unless the card says to drop them; list what you removed.

## Hard rule: this repo is public

`master` is mirrored to PUBLIC GitHub, and the mirror refuses to publish anything that names the private forge.
No tracked file, commit message, author or committer may contain the private forge's hostname (any host under
its domain), a private-LAN address of the forge, or the name of the machine it runs on (the mirror's check is a case-insensitive
grep for those; this file deliberately does not spell them out). `tools/forge.sh` reads the forge's address from the
`forge` git remote and the untracked `git config company.forge`; never copy it into the repo. Use the author name and email already
configured in git; never put a machine or host name in a commit.

## Secrets

Release signing comes from environment variables or an untracked `*.keystore`; never read, print or commit them.
