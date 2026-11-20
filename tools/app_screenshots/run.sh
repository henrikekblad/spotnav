#!/usr/bin/env bash
# Take the app's documentation screenshots on a headless emulator against a throwaway local Home Assistant.
#
#   tools/app_screenshots/run.sh [--keep] [--fresh] [--workers N] [--sv] [--langs "en sv"] [shot-name ...]
#
# Starts the Home Assistant repository's demo instance and ECB rates stub on 127.0.0.1 (its
# tools/docs_screenshots pieces, run from a configuration directory of our own), boots the dedicated
# SpotNavDocs emulator, installs a debug build, pairs it,
# drives it and writes docs/images/app-*.png and the Play store set under fastlane/metadata/android.
# --keep leaves Home Assistant and the emulator running (Ctrl-C ends them); --sv also writes Swedish
# documentation pictures (docs/images/sv/); --langs limits the store languages (default: all nine).
# With shot names only those are written. A run that has to pair (no names, or app-standalone or app-pairing-code
# named, or --fresh, or nothing kept from today) starts from a wiped emulator and a new Home Assistant
# configuration and keeps the paired result (an emulator snapshot and a copy of the configuration); any other run
# starts from those, which skips the setup, the pairing and the widget. --workers N (default 3) takes the store
# languages on N emulators at once. Phases are timed on stderr ("[timing] ...").
set -euo pipefail
T0=$SECONDS
PHASE=""
PHASE_AT=0
# Every phase's start and end goes to stderr as "[timing] ...".
phase() {
  local now=$((SECONDS - T0))
  if [ -n "$PHASE" ]; then echo "[timing] end   $PHASE: $((now - PHASE_AT)) s" >&2; fi
  PHASE="${1:-}"
  PHASE_AT=$now
  if [ -n "$PHASE" ]; then echo "[timing] start $PHASE (t=${now} s)" >&2; fi
}

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
HA_REPO="${HA_REPO:-$(cd "$REPO/.." && pwd)/elpris-home-assistant}"
HA_TOOL="$HA_REPO/tools/docs_screenshots"
WORK="$HERE/.work"
CONFIG="$WORK/ha-config"
GOLDEN="$WORK/ha-golden"        # the paired installation, kept (see below)
LOGS="$WORK/logs"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVD=SpotNavDocs
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
SNAPSHOT=docs-paired            # the emulator snapshot: app installed, paired, widget on the home screen
EMU_PORT="${EMU_PORT:-5584}"
SERIAL="emulator-$EMU_PORT"
HA_PORT="${HA_PORT:-8129}"
STUB_PORT="${STUB_PORT:-8130}"
KEEP=0
FRESH=0
WORKERS=3
DOCS_LANGS=(en)
LANGS=(en sv nb da fi de nl es fr)
SHOTS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --keep) KEEP=1 ;;
    --fresh) FRESH=1 ;;
    --workers) shift; WORKERS="$1" ;;
    --sv) DOCS_LANGS=(en sv) ;;
    --langs) shift; read -r -a LANGS <<< "$1" ;;
    -h|--help) sed -n '2,16p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) SHOTS+=("$1") ;;
  esac
  shift
done

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing: $1" >&2; exit 1; }; }
need node
need python3
[ -d "$HA_TOOL" ] || { echo "no Home Assistant screenshot tool at $HA_TOOL (set HA_REPO)" >&2; exit 1; }
[ -x "$EMULATOR" ] && [ -x "$ADB" ] || { echo "no emulator or adb under $SDK" >&2; exit 1; }

phase "python environment"
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

phase "ports"
# 2. Nothing else may be using the ports, and no other emulator may hold ours.
for port in "$HA_PORT" "$STUB_PORT" "$EMU_PORT"; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    echo "port $port is already in use; stop whatever listens there first" >&2
    exit 1
  fi
done

# Which way: a run that has to pair starts from a wiped emulator and builds a new installation (the FULL path) and
# keeps the result: the emulator snapshot and a copy of the Home Assistant configuration, both stamped with the day
# (the demo house's weeks end at the day they were built). Any other run starts from those (the KEPT path).
TODAY="$(date +%F)"
FULL=1
if [ "$FRESH" = 0 ] && [ -f "$GOLDEN/.kept" ] && [ "$(cat "$GOLDEN/.kept")" = "$TODAY" ] \
   && [ -d "$AVD_HOME/$AVD.avd/snapshots/$SNAPSHOT" ] && [ ${#SHOTS[@]} -gt 0 ]; then
  FULL=0
  for name in "${SHOTS[@]}"; do
    case "$name" in app-standalone|app-pairing-code) FULL=1 ;; esac
  done
fi
if [ "$FULL" = 1 ]; then echo "== full path: pairing from a wiped emulator (names without the unpaired pictures start from the kept state)"
else echo "== kept path: the paired emulator snapshot and installation of $TODAY (--fresh rebuilds them)"; fi

PIDS=()
HA_PID=""
EMU_PIDS=()
EMU_SERIALS=()
cleanup() {
  local i
  for i in "${!EMU_SERIALS[@]}"; do "$ADB" -s "${EMU_SERIALS[$i]}" emu kill >/dev/null 2>&1 || true; done
  for pid in "${EMU_PIDS[@]:-}"; do
    [ -n "$pid" ] || continue
    for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill "$pid" 2>/dev/null || true
  done
  for pid in "$HA_PID" "${PIDS[@]:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null || true; done
  for pid in "$HA_PID" "${PIDS[@]:-}"; do [ -n "$pid" ] && wait "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT INT TERM

# A Home Assistant configuration laid out as the Home Assistant tool lays out its own: the demo integrations and the
# integration under test, linked in. The state (.storage and the rest) of a kept one stays.
lay_out_config() {
  mkdir -p "$CONFIG/custom_components" "$LOGS"
  rm -rf "$CONFIG/custom_components"
  mkdir -p "$CONFIG/custom_components"
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
recorder:
history:
energy:
logger:
  default: warning
  logs:
    custom_components.spotnav: info
YAML
}

# The ECB rates stub and Home Assistant, from the Home Assistant tool, on 127.0.0.1 only. Prices come from
# the demo Nord Pool integration (the recorded SE4 day, moved to today and tomorrow); the stub serves the
# exchange rate that converts them to SEK. DOCS_FAST_CHECK: the demo's quick battery check and price backfill.
start_stub() {
  "$VENV/bin/python" "$HA_TOOL/rates_stub.py" "$STUB_PORT" >"$LOGS/rates_stub.log" 2>&1 &
  PIDS+=($!)
}
start_ha() {
  DOCS_SEED_SESSIONS=1 DOCS_SEED_HOUSE=1 DOCS_FAST_CHECK=1 DOCS_PRICE_FIXTURE="$HA_REPO/tests/fixtures/relay/day_SE4_2026-09-22_96.json" \
    RATES_STUB_URL="http://127.0.0.1:$STUB_PORT" \
    "$VENV/bin/python" "$HA_TOOL/ha_launch.py" -c "$CONFIG" >>"$LOGS/home-assistant.log" 2>&1 &
  HA_PID=$!
}
stop_ha() {
  kill "$HA_PID" 2>/dev/null || true
  wait "$HA_PID" 2>/dev/null || true
  HA_PID=""
}

# The dedicated emulator: created once from the installed Android 36 image. start_emu <port> <wipe|snapshot> [read-only]
start_emu() {
  local port="$1" how="$2" extra=()
  # Several emulators of one AVD all run -read-only (a snapshot start never writes its disk back anyway).
  [ "$how" = wipe ] && extra=(-no-snapshot -wipe-data) || extra=(-snapshot "$SNAPSHOT" -no-snapshot-save -read-only)
  "$EMULATOR" -avd "$AVD" -port "$port" -no-window -no-audio -no-boot-anim "${extra[@]}" \
    -gpu swiftshader_indirect -timezone Europe/Stockholm >"$LOGS/emulator-$port.log" 2>&1 &
  EMU_PIDS+=($!)
  EMU_SERIALS+=("emulator-$port")
}
# wait_boot <serial> <cold>: booted; a cold boot also waits for the launcher (a snapshot is already settled).
wait_boot() {
  local serial="$1" cold="$2"
  "$ADB" -s "$serial" wait-for-device
  for _ in $(seq 1 180); do
    [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
    sleep 1
  done
  if [ "$cold" = 1 ]; then
    for _ in $(seq 1 60); do
      "$ADB" -s "$serial" shell dumpsys window 2>/dev/null | grep -i 'mCurrentFocus' | grep -qi 'launcher' && break
      sleep 1
    done
    sleep 3
  fi
}

export HA_URL="http://127.0.0.1:$HA_PORT"
export HA_DRIVER="$HA_TOOL/driver"
export WORK
export HA_LOG="$LOGS/home-assistant.log"
mkdir -p "$WORK"
"$HERE/make_avd.sh" "$AVD" "$AVD_HOME" "$SDK"

phase "Home Assistant configuration"
rm -rf "$LOGS"; mkdir -p "$LOGS"
if [ "$FULL" = 1 ]; then
  rm -rf "$CONFIG"
else
  rm -rf "$CONFIG"; cp -a "$GOLDEN" "$CONFIG"; rm -f "$CONFIG/.kept"
fi
lay_out_config

phase "start Home Assistant"
start_stub
start_ha

phase "boot emulator"
echo "== booting the $AVD emulator ($SERIAL)"
if [ "$FULL" = 1 ]; then start_emu "$EMU_PORT" wipe; else start_emu "$EMU_PORT" snapshot; fi

# Split the store languages over the emulators: this one takes the first (the documentation pictures, the house and
# heat pages and the identification are its own), the workers share the rest. Only a full set is worth it.
WORKER_LANGS=()
MAIN_LANGS=("${LANGS[@]}")
if [ ${#SHOTS[@]} -eq 0 ] && [ "$WORKERS" -gt 1 ] && [ ${#LANGS[@]} -ge 3 ]; then
  MAIN_LANGS=("${LANGS[0]}")
  for i in $(seq 1 $((WORKERS - 1))); do WORKER_LANGS+=(""); done
  k=0
  for lang in "${LANGS[@]:1}"; do
    idx=$((k % (WORKERS - 1)))
    WORKER_LANGS[$idx]="${WORKER_LANGS[$idx]} $lang"
    k=$((k + 1))
  done
fi
worker_serial() { echo "emulator-$((EMU_PORT + 2 * ($1 + 1)))"; }   # worker 0 = EMU_PORT+2 ...
if [ "$FULL" = 0 ]; then
  for w in "${!WORKER_LANGS[@]}"; do start_emu "$((EMU_PORT + 2 * (w + 1)))" snapshot; done
fi

phase "gradle build"
# The debug build: the Play variant (what the store listing shows), without Firebase. It asks the relay for
# nothing (its prices are the paired Home Assistant's, and push is off), so it is not pointed at the stub.
echo "== building the debug app"
(cd "$REPO" && ./gradlew -q -Pstore=play -Ppush=false assembleDebug)
APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"

phase "Home Assistant setup"
if [ "$FULL" = 1 ]; then
  # Home Assistant's demo devices and SpotNav entries.
  node "$HERE/ha_setup.mjs" setup
else
  export HA_ONBOARDED=1   # a kept installation has no onboarding page
  node "$HERE/ha_setup.mjs" ready
fi

phase "wait for emulator boot"
wait_boot "$SERIAL" "$FULL"
for w in "${!WORKER_LANGS[@]}"; do [ "$FULL" = 0 ] && wait_boot "$(worker_serial "$w")" 0; done

drive() {   # drive <serial> <args...>
  local serial="$1"; shift
  SERIAL="$serial" ADB="$ADB" APK="$APK" OUT="$REPO/docs/images" HA_PORT="$HA_PORT" "$VENV/bin/python" "$HERE/drive.py" "$@"
}

status=0
if [ "$FULL" = 1 ]; then
  phase "app setup (install, pairing, widget)"
  drive "$SERIAL" --phase setup -- "${SHOTS[@]}" || status=$?
  [ "$status" = 0 ] || exit "$status"
  phase "keep the paired state"
  node "$HERE/ha_setup.mjs" settle
  "$ADB" -s "$SERIAL" emu avd snapshot save "$SNAPSHOT" >/dev/null
  stop_ha
  rm -rf "$GOLDEN"; cp -a "$CONFIG" "$GOLDEN"; echo "$TODAY" > "$GOLDEN/.kept"
  start_ha
  export HA_ONBOARDED=1
  node "$HERE/ha_setup.mjs" ready
  if [ ${#WORKER_LANGS[@]} -gt 0 ]; then
    phase "boot the emulators from the snapshot"
    # The wiped emulator holds the AVD for writing; the several that now run side by side all start read-only.
    "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
    for pid in "${EMU_PIDS[@]}"; do for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done; done
    EMU_PIDS=(); EMU_SERIALS=()
    start_emu "$EMU_PORT" snapshot
    for w in "${!WORKER_LANGS[@]}"; do start_emu "$((EMU_PORT + 2 * (w + 1)))" snapshot; done
    wait_boot "$SERIAL" 0
    for w in "${!WORKER_LANGS[@]}"; do wait_boot "$(worker_serial "$w")" 0; done
  fi
fi

phase "pictures"
START=$SECONDS
read -r -a MAIN <<< "${MAIN_LANGS[*]}"
if [ ${#WORKER_LANGS[@]} -eq 0 ]; then
  drive "$SERIAL" --phase pictures --langs "${MAIN[@]}" --docs-langs "${DOCS_LANGS[@]}" -- "${SHOTS[@]}" || status=$?
else
  WPIDS=()
  for w in "${!WORKER_LANGS[@]}"; do
    read -r -a wl <<< "${WORKER_LANGS[$w]}"
    drive "$(worker_serial "$w")" --phase pictures --no-site --no-identify --langs "${wl[@]}" --docs-langs "${DOCS_LANGS[@]}" \
      >"$LOGS/worker-$w.log" 2>&1 &
    WPIDS+=($!)
  done
  drive "$SERIAL" --phase pictures --no-identify --langs "${MAIN[@]}" --docs-langs "${DOCS_LANGS[@]}" || status=$?
  phase "pictures: waiting for the workers"
  for pid in "${WPIDS[@]}"; do wait "$pid" || status=$?; done
  phase "identification"
  WPIDS=()
  for w in "${!WORKER_LANGS[@]}"; do
    read -r -a wl <<< "${WORKER_LANGS[$w]}"
    drive "$(worker_serial "$w")" --phase identify-shots --langs "${wl[@]}" --docs-langs "${DOCS_LANGS[@]}" \
      >>"$LOGS/worker-$w.log" 2>&1 &
    WPIDS+=($!)
  done
  drive "$SERIAL" --phase identify --langs "${MAIN[@]}" --docs-langs "${DOCS_LANGS[@]}" || status=$?
  for pid in "${WPIDS[@]}"; do wait "$pid" || status=$?; done
  for w in "${!WORKER_LANGS[@]}"; do echo "== worker $w (${WORKER_LANGS[$w]# }):"; grep -E "written:|FAILED" "$LOGS/worker-$w.log" || true; done
fi
phase ""
echo "== finished in $((SECONDS - START)) s (exit $status); logs in $LOGS"
if [ "$KEEP" = 1 ]; then
  echo "== Home Assistant ($HA_URL) and $SERIAL stay up until you press Ctrl-C"
  wait "$HA_PID" || true
fi
exit "$status"
