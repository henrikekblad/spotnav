#!/usr/bin/env bash
# Take the app's documentation screenshots on a headless emulator against a throwaway local Home Assistant.
#
#   tools/app_screenshots/run.sh [--keep] [--sv] [--langs "en sv"] [shot-name ...]
#
# Starts the Home Assistant repository's demo instance and relay stub on 127.0.0.1 (its
# tools/docs_screenshots pieces, run from a configuration directory of our own), boots the dedicated
# SpotNavDocs emulator from a wiped state, installs a debug build pointed at the relay stub, pairs it,
# drives it and writes docs/images/app-*.png and the Play store set under fastlane/metadata/android.
# --keep leaves Home Assistant and the emulator running (Ctrl-C ends them); --sv also writes Swedish
# documentation pictures (docs/images/sv/); --langs limits the store languages (default: all nine).
# With shot names only those are written.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
HA_REPO="${HA_REPO:-$(cd "$REPO/.." && pwd)/elpris-home-assistant}"
HA_TOOL="$HA_REPO/tools/docs_screenshots"
WORK="$HERE/.work"
CONFIG="$WORK/ha-config"
LOGS="$WORK/logs"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVD=SpotNavDocs
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
EMU_PORT="${EMU_PORT:-5584}"
SERIAL="emulator-$EMU_PORT"
HA_PORT=8129
STUB_PORT=8130
KEEP=0
DOCS_LANGS=(en)
LANGS=(en sv nb da fi de nl es fr)
SHOTS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --keep) KEEP=1 ;;
    --sv) DOCS_LANGS=(en sv) ;;
    --langs) shift; read -r -a LANGS <<< "$1" ;;
    -h|--help) sed -n '2,12p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) SHOTS+=("$1") ;;
  esac
  shift
done

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing: $1" >&2; exit 1; }; }
need node
need python3
[ -d "$HA_TOOL" ] || { echo "no Home Assistant screenshot tool at $HA_TOOL (set HA_REPO)" >&2; exit 1; }
[ -x "$EMULATOR" ] && [ -x "$ADB" ] || { echo "no emulator or adb under $SDK" >&2; exit 1; }

# 1. Python for Home Assistant: the Home Assistant tool's environment when it has one, else our own
#    from its pinned requirements (network needed once).
if [ -x "$HA_TOOL/.venv/bin/python" ]; then
  VENV="$HA_TOOL/.venv"
else
  VENV="$HERE/.venv"
  STAMP="$VENV/.requirements.sha"
  WANT="$(sha256sum "$HA_TOOL/requirements.txt" | cut -d' ' -f1)"
  [ -x "$VENV/bin/python" ] || python3 -m venv "$VENV"
  if [ ! -f "$STAMP" ] || [ "$(cat "$STAMP")" != "$WANT" ]; then
    echo "== installing Home Assistant (first run only, needs network access)"
    "$VENV/bin/pip" install --quiet --upgrade pip
    "$VENV/bin/pip" install --quiet -r "$HA_TOOL/requirements.txt"
    echo "$WANT" > "$STAMP"
  fi
fi

# 2. Nothing else may be using the ports, and no other emulator may hold ours.
for port in "$HA_PORT" "$STUB_PORT" "$EMU_PORT"; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    echo "port $port is already in use; stop whatever listens there first" >&2
    exit 1
  fi
done

# 3. A fresh Home Assistant configuration, laid out as the Home Assistant tool lays out its own.
rm -rf "$CONFIG" "$LOGS"
mkdir -p "$CONFIG/custom_components" "$LOGS"
ln -s "$HA_REPO/custom_components/spotnav" "$CONFIG/custom_components/spotnav"
for demo in "$HA_TOOL"/demo_components/*/; do
  target="$CONFIG/custom_components/$(basename "$demo")"
  cp -r "${demo%/}" "$target"
  mv "$target/manifest.demo.json" "$target/manifest.json"
done
cat > "$CONFIG/configuration.yaml" <<YAML
http:
  server_host: 127.0.0.1
  server_port: $HA_PORT
frontend:
config:
logger:
  default: warning
  logs:
    custom_components.spotnav: info
YAML

PIDS=()
cleanup() {
  if [ -n "${EMU_PID:-}" ]; then
    "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
    for _ in $(seq 1 30); do kill -0 "$EMU_PID" 2>/dev/null || break; sleep 1; done
    kill "$EMU_PID" 2>/dev/null || true
  fi
  for pid in "${PIDS[@]:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null || true; done
  for pid in "${PIDS[@]:-}"; do [ -n "$pid" ] && wait "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT INT TERM

# 4. The relay stub and Home Assistant, from the Home Assistant tool, on 127.0.0.1 only.
"$VENV/bin/python" "$HA_TOOL/relay_stub.py" "$STUB_PORT" >"$LOGS/relay_stub.log" 2>&1 &
PIDS+=($!)
DOCS_SEED_SESSIONS=1 RELAY_STUB_URL="http://127.0.0.1:$STUB_PORT" \
  "$VENV/bin/python" "$HA_TOOL/ha_launch.py" -c "$CONFIG" >"$LOGS/home-assistant.log" 2>&1 &
PIDS+=($!)

# 5. The dedicated emulator, created once from the installed Android 36 image and wiped on every boot.
"$HERE/make_avd.sh" "$AVD" "$AVD_HOME" "$SDK"
echo "== booting the $AVD emulator ($SERIAL)"
"$EMULATOR" -avd "$AVD" -port "$EMU_PORT" -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data \
  -gpu swiftshader_indirect -timezone Europe/Stockholm >"$LOGS/emulator.log" 2>&1 &
EMU_PID=$!

# 6. The debug build, pointed at the relay stub (the emulator reaches the host's 127.0.0.1 as 10.0.2.2).
#    The Play variant (what the store listing shows), without Firebase.
echo "== building the debug app"
(cd "$REPO" && ./gradlew -q -Pstore=play -Ppush=false -PrelayBaseUrl="http://10.0.2.2:$STUB_PORT" assembleDebug)
APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"

# 7. Home Assistant's demo devices and SpotNav entries.
export HA_URL="http://127.0.0.1:$HA_PORT"
export HA_DRIVER="$HA_TOOL/driver"
export WORK
node "$HERE/ha_setup.mjs" setup

# 8. The app.
"$ADB" -s "$SERIAL" wait-for-device
for _ in $(seq 1 180); do
  [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
  sleep 1
done
sleep 15   # the launcher and system UI settle after boot_completed
START=$SECONDS
status=0
SERIAL="$SERIAL" ADB="$ADB" APK="$APK" OUT="$REPO/docs/images" HA_PORT="$HA_PORT" \
  "$VENV/bin/python" "$HERE/drive.py" --langs "${LANGS[@]}" --docs-langs "${DOCS_LANGS[@]}" -- "${SHOTS[@]}" || status=$?
echo "== finished in $((SECONDS - START)) s (exit $status); logs in $LOGS"
if [ "$KEEP" = 1 ]; then
  echo "== Home Assistant ($HA_URL) and $SERIAL stay up until you press Ctrl-C"
  wait "${PIDS[1]}" || true
fi
exit "$status"
