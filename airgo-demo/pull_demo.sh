#!/usr/bin/env bash
# Copy demo recordings off the phone and render the newest one.
#   ./pull_demo.sh            pull all recordings into ./recordings, render the newest
#   ./pull_demo.sh --no-render
set -e
ADB=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
cd "$(dirname "$0")"
mkdir -p recordings
$ADB pull /sdcard/Android/data/com.solos.relay/files/demo/. recordings/ \
  || $ADB exec-out run-as com.solos.relay tar -C files/demo -cf - . | tar -C recordings -xf -
newest=$(ls -d recordings/demo_* | sort | tail -1)
echo "newest: $newest"
[ "$1" = "--no-render" ] || python3 render_demo.py "$newest"

# Send the rendered video back to the phone's gallery (Movies/AirGo).
if [ "$1" != "--no-render" ] && [ -f "$newest/demo.mp4" ]; then
  name="AirGo_$(basename "$newest").mp4"
  $ADB shell mkdir -p /sdcard/Movies/AirGo
  $ADB push "$newest/demo.mp4" "/sdcard/Movies/AirGo/$name"
  $ADB shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Movies/AirGo/$name" >/dev/null
  echo "on the phone: Movies/AirGo/$name"
fi
