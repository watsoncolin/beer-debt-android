#!/bin/bash
# Build the shrunk, signed release APK, install it on the `beerdebt` AVD (or
# any connected device), launch it, and fail if the process dies or logcat
# shows a fatal exception. Debug builds don't run R8, so this is the only
# local check that a keep rule is missing. Run before every release.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ADB=$ANDROID_HOME/platform-tools/adb
PKG=me.colinwatson.beerdebt
cd "$ROOT" && ./gradlew :app:assembleRelease -q 2>&1 | grep -v "^WARNING\|^w: \|^$" || true
APK=$ROOT/app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "no release APK (is keystore.properties in place?)"; exit 1; }
$ADB devices | grep -q "device$" || { echo "no device; boot the AVD first (scripts/screenshots.sh does)"; exit 1; }
$ADB uninstall $PKG >/dev/null 2>&1 || true
$ADB install -r "$APK" | tail -1
$ADB logcat -c
$ADB shell am start -n $PKG/.MainActivity >/dev/null
sleep 6
if [ -z "$($ADB shell pidof $PKG)" ] || $ADB logcat -d | grep -q "FATAL EXCEPTION"; then
  echo "RELEASE BUILD CRASHED:"; $ADB logcat -d | grep -A25 "FATAL EXCEPTION" | head -40; exit 1
fi
echo "release build launched and is running"
