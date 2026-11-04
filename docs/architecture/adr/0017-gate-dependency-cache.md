# ADR 0017: gate dependencies from a read-only, checksum-verified cache

Status: accepted (2026-11-04, epic #430). Keeps every rule of #74 (one job per container, credentials out of the jobs'
reach) and #133 (a fresh Gradle user home for every job); adds a cache no job can write.

## Context

Since #133 the forge runner empties `/cache/gradle` (`GRADLE_USER_HOME`) before every job, so no job loads a
distribution, jar or init script an earlier one left: the runner cannot tell a master job from a pull request's before
it runs. Since #74 each job runs alone in its container start, as uid 1001 with no capabilities, and the credentials sit
in a root-only volume. The price: every build downloads the Gradle distribution and every dependency again, and Maven
Central answers a busy gate with HTTP 429, so gates fail for reasons unrelated to the change.

## Decision

1. **Read-only cache in the image.** The runner image carries a Gradle dependency cache at `/opt/gradle-ro-cache`,
   filled once at image build time: a build stage resolves the gate's tasks on a pinned Splatman commit, then keeps
   only `caches/modules-2` minus its `*.lock` and `gc.properties` files (Gradle's "shared read-only dependency cache").
   The image sets `GRADLE_RO_DEP_CACHE=/opt/gradle-ro-cache`. Root owns it and the root filesystem is read-only, so no
   job can write it. Hunter builds the image; no job does.
2. **#133 stays.** `/cache/gradle` is still emptied before every job. Only artifacts missing from the read-only cache
   (a dependency bumped since the image was built) are downloaded, into the job's own empty home.
3. **Dependency verification on.** `gradle/verification-metadata.xml` holds a sha256 for every artifact and plugin, so
   an artifact from the read-only cache, from Central or from Google's Maven fails the build unless its checksum is in
   the repo. A dependency bump regenerates that file in the same PR with
   `./gradlew --write-verification-metadata sha256 assembleDebug testDebugUnitTest lintDebug`, and the reviewer reads
   the diff of new checksums.
4. **Image marker.** The image writes `/etc/splatman-runner-image`, one line: the image date and the pinned Splatman
   commit its cache came from. The gate's diagnostics step prints it, so a log shows which image ran.
5. **Rejected:**
   - a second public Maven mirror: more upstreams to trust, and the 429 just moves;
   - keeping the Gradle home between jobs: #133's reason (a pull request's job would leave code a master build runs);
   - a writable shared cache volume: the same hole as keeping the home;
   - retrying until green as the only fix: Gradle's own retry/backoff is tuned (a card of #430), but only as a cushion.
6. **Cost.** The image grows by the cache (the measured size goes in the image card's README, not here). A dependency
   bump downloads only the bumped artifacts until Hunter rebuilds the image.

## Consequences

- All of #74's rules hold (one job per container start, jobs as uid 1001 without capabilities, credentials root-only),
  and all of #133's (the whole Gradle user home emptied before every job; no job loads what an earlier one left).
- A gate normally downloads nothing but the Gradle distribution (checked against `distributionSha256Sum`), so 429s stop
  failing gates.
- A tampered or swapped artifact, in the cache or upstream, fails verification instead of running.
- Every dependency bump touches `gradle/verification-metadata.xml`, a file its reviewer must read.
