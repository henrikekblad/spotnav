#!/usr/bin/env bash
# Create the screenshot tool's own emulator, once: make_avd.sh <name> <avd home> <sdk>.
#
# Uses avdmanager when the SDK has command-line tools, otherwise writes the AVD by hand from the
# installed android-36 google_apis_playstore x86_64 image. The screen is 1080x2160 at 420 dpi: the
# medium phone's width, and a long side no more than twice the short one, as Google Play requires of
# store screenshots. Existing AVDs are never touched; the settings of this one are rewritten each time.
set -euo pipefail
NAME="$1" AVD_HOME="$2" SDK="$3"
DIR="$AVD_HOME/$NAME.avd"
IMAGE="system-images/android-36/google_apis_playstore/x86_64/"
[ -d "$SDK/$IMAGE" ] || { echo "missing $SDK/$IMAGE (install it with the SDK manager)" >&2; exit 1; }
AVDMANAGER="$(ls "$SDK"/cmdline-tools/*/bin/avdmanager 2>/dev/null | head -1 || true)"
if [ -f "$DIR/config.ini" ]; then
  :
elif [ -n "$AVDMANAGER" ]; then
  echo no | "$AVDMANAGER" create avd -n "$NAME" -k "system-images;android-36;google_apis_playstore;x86_64" -d medium_phone -p "$DIR"
else
  mkdir -p "$DIR"
  cat > "$AVD_HOME/$NAME.ini" <<INI
avd.ini.encoding=UTF-8
path=$DIR
path.rel=avd/$NAME.avd
target=android-36
INI
fi
# The settings that matter for the pictures, whichever way the AVD was made.
cat > "$DIR/config.ini" <<INI
AvdId=$NAME
PlayStore.enabled=true
abi.type=x86_64
avd.ini.displayname=SpotNav docs
avd.ini.encoding=UTF-8
disk.dataPartition.size=6G
fastboot.forceColdBoot=yes
hw.battery=yes
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.device.manufacturer=Generic
hw.device.name=medium_phone
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.initialOrientation=portrait
hw.keyboard=yes
hw.lcd.density=420
hw.lcd.height=2160
hw.lcd.width=1080
hw.mainKeys=no
hw.ramSize=4096
hw.sdCard=no
image.sysdir.1=$IMAGE
showDeviceFrame=no
skin.dynamic=yes
skin.name=1080x2160
skin.path=_no_skin
tag.display=Google Play
tag.id=google_apis_playstore
target=android-36
vm.heapSize=336
INI

