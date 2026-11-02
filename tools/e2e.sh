#!/usr/bin/env bash
# Device smoke test: boots a headless emulator, installs the debug APK and runs the Compose smoke test
# (app/src/androidTest, `:app:connectedFullDebugAndroidTest`: the `full` flavour, #48), then pulls its screenshots to build/e2e/.
#   tools/e2e.sh
# - Its own AVD, `splatman_e2e` (android-34 google_apis_playstore x86_64, 1080x2400), created here if missing; it never
#   touches another AVD. Started with -no-window -no-audio -no-snapshot-save -wipe-data (a clean device every run); the
#   script checks that every port the emulator listens on is bound to loopback and stops if one is not.
# - Gradle runs under /tmp/splatman-gradle.lock with the same caps as tools/check.sh, so it never runs beside the gate,
#   and only against this emulator (ANDROID_SERIAL).
# - On exit, pass, fail or Ctrl-C, it kills the emulator it started: `adb emu kill` on the serial it recorded, then its
#   own process group if that is still alive. Nothing else is killed.
# - No ARCore on the emulator: the capture test is skipped (a line says so); that is not a failure.
# Ends with `OK (n tests)` and the screenshots under build/e2e/, or `e2e: RED: <why>`.
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
adb="$ANDROID_HOME/platform-tools/adb"
emulator="$ANDROID_HOME/emulator/emulator"
avd=splatman_e2e
image=system-images/android-34/google_apis_playstore/x86_64
avd_home="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
out="$PWD/build/e2e"
device_dir=/sdcard/Download/splatman-e2e
results=app/build/outputs/androidTest-results/connected  # flavoured: debug/flavors/full/ below it (#48)

red() { echo "e2e: RED: $*"; exit 1; }
[[ -x $adb && -x $emulator ]] || red "no adb or emulator under $ANDROID_HOME"
[[ -d $ANDROID_HOME/$image ]] || red "missing system image $image"
[[ -w /dev/kvm ]] || red "/dev/kvm is not writable"

main_root="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
if [[ "$main_root" != "$PWD" && ! -e build-cache && -d "$main_root/build-cache" ]]; then
  ln -s "$main_root/build-cache" build-cache
fi

# The AVD: written by hand (this SDK has no cmdline-tools/avdmanager).
if [[ ! -f $avd_home/$avd.ini ]]; then
  mkdir -p "$avd_home/$avd.avd"
  cat > "$avd_home/$avd.ini" <<EOF
avd.ini.encoding=UTF-8
path=$avd_home/$avd.avd
path.rel=avd/$avd.avd
target=android-34
EOF
  cat > "$avd_home/$avd.avd/config.ini" <<EOF
AvdId = $avd
avd.ini.displayname = Splatman e2e (API 34)
avd.ini.encoding = UTF-8
PlayStore.enabled = true
abi.type = x86_64
image.sysdir.1 = $image/
tag.id = google_apis_playstore
tag.display = Google Play
target = android-34
disk.dataPartition.size = 6442450944
hw.cpu.arch = x86_64
hw.cpu.ncore = 4
hw.ramSize = 3072
vm.heapSize = 256
hw.lcd.width = 1080
hw.lcd.height = 2400
hw.lcd.density = 420
hw.initialOrientation = portrait
hw.mainKeys = no
hw.keyboard = yes
hw.gpu.enabled = yes
hw.gpu.mode = swiftshader_indirect
hw.audioInput = no
hw.audioOutput = no
hw.camera.back = none
hw.camera.front = none
hw.gps = no
hw.sdCard = no
showDeviceFrame = no
EOF
  echo "e2e: created AVD $avd"
fi

"$adb" start-server >/dev/null
# A console port pair nobody uses (adb's view and the kernel's), so the serial is ours alone.
port=""
for p in $(seq 5600 2 5680); do
  "$adb" devices | grep -q "^emulator-$p[[:space:]]" && continue
  ss -Htln "( sport = :$p or sport = :$((p + 1)) )" | grep -q . && continue
  port=$p; break
done
[[ -n $port ]] || red "no free emulator port in 5600-5680"
serial="emulator-$port"
export ANDROID_SERIAL="$serial"

emu_pid=""
cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  if [[ -n $emu_pid ]]; then
    "$adb" -s "$serial" emu kill >/dev/null 2>&1 || true
    for _ in $(seq 30); do kill -0 "$emu_pid" 2>/dev/null || break; sleep 1; done
    if kill -0 "$emu_pid" 2>/dev/null; then kill -TERM -- "-$emu_pid" 2>/dev/null || true; sleep 3; fi
    kill -0 "$emu_pid" 2>/dev/null && kill -KILL -- "-$emu_pid" 2>/dev/null || true
    for _ in $(seq 10); do "$adb" devices | grep -q "^$serial[[:space:]]" || break; sleep 1; done
    echo "e2e: stopped $serial"
  fi
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p "$out"
rm -f "$out"/*.png
# setsid: the emulator and its qemu child get their own process group (id = emu_pid), which cleanup can kill whole.
setsid "$emulator" -avd "$avd" -port "$port" -no-window -no-audio -no-snapshot-save -no-boot-anim -wipe-data \
  -gpu swiftshader_indirect > "$out/emulator.log" 2>&1 < /dev/null &
emu_pid=$!
echo "e2e: started $serial (pid $emu_pid), log build/e2e/emulator.log"

booted=""
for _ in $(seq 300); do
  kill -0 "$emu_pid" 2>/dev/null || red "the emulator exited; see build/e2e/emulator.log"
  if [[ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then booted=1; break; fi
  sleep 2
done
[[ -n $booted ]] || red "$serial did not boot in 10 minutes"
echo "e2e: $serial booted"

# Every listening socket of the emulator's process group must be on loopback.
pids="$(pgrep -g "$emu_pid" | paste -sd'|' -)"
open="$(ss -Htlnp | grep -E "pid=($pids)," | awk '{print $4}' | grep -vE '^(127\.0\.0\.1|\[::1\]|\[::ffff:127\.0\.0\.1\]):' || true)"
[[ -z $open ]] || red "the emulator listens beyond loopback: $(echo "$open" | paste -sd' ' -)"

sh() { "$adb" -s "$serial" shell "$@" < /dev/null; }
sh input keyevent KEYCODE_WAKEUP || true
sh wm dismiss-keyguard || true
for s in window_animation_scale transition_animation_scale animator_duration_scale; do sh settings put global "$s" 0; done
sh rm -rf "$device_dir" || true
if ! sh pm path com.google.ar.core | grep -q package:; then
  echo "e2e: ARCore is not installed on $serial: the capture test is skipped (not a failure)"
fi

log="$(mktemp)"
set +e
( flock -w "${GATE_LOCK_WAIT:-3600}" 9 || { echo "e2e: waited too long for /tmp/splatman-gradle.lock"; exit 1; }
  ./gradlew --console=plain --no-daemon -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.daemon.jvmargs=-Xmx1g \
    :app:connectedFullDebugAndroidTest ) 9> /tmp/splatman-gradle.lock 2>&1 | tee "$log"
rc=${PIPESTATUS[0]}
set -e

"$adb" -s "$serial" pull "$device_dir/." "$out/" >/dev/null 2>&1 || true
shots="$(cd "$out" && ls -1 ./*.png 2>/dev/null | sed 's|^\./|build/e2e/|' || true)"

if ((rc)); then
  why="$(grep -E '^(> |e: |FAILED|.*Tests on .* failed|.*tests completed, [0-9]+ failed)' "$log" | tail -3 | paste -sd';' -)" || true
  [[ -n $why ]] || why="$(grep -v '^[[:space:]]*$' "$log" | tail -1)" || true
  rm -f "$log"
  red "$why"
fi
rm -f "$log"

attr() { sed -nE "s/.*<testsuite [^>]* $1=\"([0-9]+)\".*/\1/p" "$2" | head -1; }
tests=0; skipped=0; failed=0
while IFS= read -r -d "" x; do
  [[ -f $x ]] || continue
  t="$(attr tests "$x")"; s="$(attr skipped "$x")"; f="$(attr failures "$x")"; e="$(attr errors "$x")"
  tests=$((tests + ${t:-0})); skipped=$((skipped + ${s:-0})); failed=$((failed + ${f:-0} + ${e:-0}))
done < <(find "$results" -name '*.xml' -print0 2>/dev/null)
((tests > 0)) || red "no test results under $results"
((failed == 0)) || red "$failed of $tests tests failed"
[[ -n $shots ]] || red "no screenshots were pulled to build/e2e/"
echo "$shots"
((skipped == 0)) || echo "e2e: $skipped skipped"
echo "OK ($((tests - skipped)) tests)"
