# Forgejo Actions runner that builds Splatman: JDK 21 + Android SDK. Jobs run on the runner's own filesystem
# (label `splatman:host`), so it needs no docker socket. See README.md in this folder.
#
# Pinned versions (bump one by changing its ARG and its sha256 together):
#   base             eclipse-temurin 21.0.13+9 JDK on Ubuntu 24.04 (noble), pinned by digest
#   forgejo-runner   13.2.1   (code.forgejo.org/forgejo/runner release binary)
#   node             22.23.4  (actions such as actions/checkout are JavaScript and run on the runner's node)
#   cmdline-tools    13114758 (Android command-line tools: sdkmanager)
#   platforms        android-34   (compileSdk = 34 in every module)
#   build-tools      34.0.0       (the default for Android Gradle Plugin 8.7.2, gradle/libs.versions.toml)
#   ndk              27.0.12077973 (AGP 8.7.2 default; native splat-sfm module, #225)
#   cmake            3.22.1       (AGP 8.7.2 default)
# Gradle itself (8.11.1) is fetched by ./gradlew, which checks distributionSha256Sum.
FROM eclipse-temurin:21.0.13_9-jdk-noble@sha256:114cabb73033ca4ce7ceec3deb62396bc1b716282bae051ed3c1d82e38fcab2d

ARG RUNNER_VERSION=13.2.1
ARG RUNNER_SHA256=bb369fe1ecb50fdc983c0b1d993f19b0920df6e5864511892b3cdbf16d858b34
ARG NODE_VERSION=22.23.4
ARG NODE_SHA256=1805bcd35e6815559bd2eb97db438d3fdb0eb3a5e6a977b7c1badca76337e9ce
ARG CMDLINE_TOOLS_BUILD=13114758
ARG CMDLINE_TOOLS_SHA256=7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee
ARG ANDROID_PLATFORM=android-34
ARG ANDROID_BUILD_TOOLS=34.0.0
ARG ANDROID_NDK=27.0.12077973
ARG ANDROID_CMAKE=3.22.1

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/cache/gradle

# Ubuntu packages come from the signed Ubuntu archive (apt verifies them): git for checkout, unzip/xz for SDK and node.
RUN apt-get update \
 && apt-get install -y --no-install-recommends git unzip xz-utils curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL -o /tmp/node.tar.xz "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" \
 && echo "${NODE_SHA256}  /tmp/node.tar.xz" | sha256sum -c - \
 && tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 --no-same-owner \
      --exclude='*/include' --exclude='*/share' --exclude='*/CHANGELOG.md' --exclude='*/README.md' --exclude='*/LICENSE' \
 && rm /tmp/node.tar.xz \
 && node --version

RUN curl -fsSL -o /usr/local/bin/forgejo-runner \
      "https://code.forgejo.org/forgejo/runner/releases/download/v${RUNNER_VERSION}/forgejo-runner-${RUNNER_VERSION}-linux-amd64" \
 && echo "${RUNNER_SHA256}  /usr/local/bin/forgejo-runner" | sha256sum -c - \
 && chmod 0755 /usr/local/bin/forgejo-runner \
 && forgejo-runner --version

# Licences are accepted here, at build time. The SDK is read-only for jobs: every package the build asks for is
# already installed, so the Android Gradle Plugin never tries to download one.
RUN curl -fsSL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip" \
 && echo "${CMDLINE_TOOLS_SHA256}  /tmp/cmdline-tools.zip" | sha256sum -c - \
 && mkdir -p "${ANDROID_HOME}/cmdline-tools" \
 && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
 && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
 && rm /tmp/cmdline-tools.zip \
 && (yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null) \
 && "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --install \
      "platforms;${ANDROID_PLATFORM}" "build-tools;${ANDROID_BUILD_TOOLS}" \
      "ndk;${ANDROID_NDK}" "cmake;${ANDROID_CMAKE}" > /dev/null \
 && "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --list_installed \
 && rm -rf /root/.android /tmp/*

# python3 (Ubuntu's, from the signed archive) for tools/check.sh's unit-test count (#59), which the CI gate runs (#61).
# Its own layer, so the SDK layers above stay cached.
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 \
 && rm -rf /var/lib/apt/lists/* \
 && python3 -I -c 'import xml.etree.ElementTree'

# One unprivileged user runs the runner and every job step. /data (job workdirs), /cache/gradle and /secret (the
# registration file) are named volumes in compose.yaml; creating them here gives fresh volumes the same ownership:
# /data and /cache/gradle the runner user's, /secret root's alone (0700), so no job step can read the credentials.
RUN useradd --uid 1001 --user-group --home-dir /home/runner --no-create-home --shell /bin/bash runner \
 && mkdir -p /home/runner /data/work /cache/gradle /etc/forgejo-runner /secret \
 && chown -R runner:runner /home/runner /data /cache \
 && chmod 0700 /secret

# The runner's config: one job at a time, label splatman in host mode (steps run inside this container), no docker,
# no actions cache server (it would listen on a port). `register` writes the credentials to /secret (as root).
# job.yml is the same config for `one-job` (entrypoint.sh), which gets the token from the entrypoint instead: it
# points at a registration file that never exists (read-only root filesystem), so the runner never opens /secret.
RUN printf '%s\n' \
      'log:' \
      '  level: info' \
      'runner:' \
      '  file: /secret/.runner' \
      '  capacity: 1' \
      '  timeout: 2h' \
      '  labels:' \
      '    - "splatman:host"' \
      'cache:' \
      '  enabled: false' \
      'container:' \
      '  docker_host: "-"' \
      'host:' \
      '  workdir_parent: /data/work' \
      > /etc/forgejo-runner/config.yml \
 && sed 's#^  file: /secret/.runner$#  file: /etc/forgejo-runner/no-registration-file#' /etc/forgejo-runner/config.yml \
      > /etc/forgejo-runner/job.yml \
 && grep -q 'no-registration-file' /etc/forgejo-runner/job.yml

# One job per container start (#74): the entrypoint starts as root, hands the runner its token without letting job
# steps read it, drops to the runner user and runs `forgejo-runner one-job`. See entrypoint.sh and README.md.
COPY --chmod=0755 entrypoint.sh /usr/local/bin/splatman-runner-entrypoint

ENV HOME=/home/runner \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:${PATH}
WORKDIR /data
ENTRYPOINT ["/usr/local/bin/splatman-runner-entrypoint"]
CMD []
