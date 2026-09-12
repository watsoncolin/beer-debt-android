#!/bin/bash
# Boot the `beerdebt` AVD headless (see emulator-bootstrap.sh), install the
# debug APK, seed demo ledgers straight into the app's files dir, and
# screenshot every screen into docs/screenshots/. Mirrors
# beer-debt-ios/scripts/screenshots.sh; keep the seed shapes in sync with
# `Ledger`'s JSON. Usage: scripts/screenshots.sh [outdir]
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB=$ANDROID_HOME/platform-tools/adb
OUT=${1:-$ROOT/docs/screenshots}; mkdir -p "$OUT"
APK=$ROOT/app/build/outputs/apk/debug/app-debug.apk
PKG=me.colinwatson.beerdebt
TMP=$(mktemp -d)

[ -f "$APK" ] || { echo "no APK; run ./gradlew :app:assembleDebug"; exit 1; }
if ! $ADB devices | grep -q "emulator-"; then
  echo "booting AVD beerdebt"
  nohup "$ANDROID_HOME/emulator/emulator" -avd beerdebt -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect > "$TMP/emulator.log" 2>&1 &
  $ADB wait-for-device
fi
until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
sleep 5
for k in window_animation_scale transition_animation_scale animator_duration_scale; do $ADB shell settings put global $k 0 >/dev/null; done
$ADB install -r "$APK" | tail -1

seed() { # seed <debt|credit>
python3 - "$1" <<'PY' > "$TMP/ledger.json"
import json, sys, uuid
from datetime import datetime, timedelta, timezone
mode = sys.argv[1]
now = datetime.now(timezone.utc).replace(microsecond=0)
iso = lambda d: d.strftime("%Y-%m-%dT%H:%M:%SZ")
H, D = timedelta(hours=1), timedelta(days=1)
rules = {"milesPerBeer": 1.0, "interestRate": 0.1, "interestPeriod": "daily", "gracePeriod": 86400.0, "maximumCreditBeers": 3.0, "creditDecayRatePerWeek": 0.1}
if mode == "debt":
    beers = [now - 5*D - H, now - 3*D - 2*H, now - 2*D - 3*H, now - 6*H]
    runs = [(now - 9*D, 2.1), (now - 4*D + H, 1.0), (now - 2*D, 0.8)]
else:
    beers = [now - 6*D]
    runs = [(now - 8*D, 1.6), (now - 5*D, 1.0), (now - 1*D, 2.4)]
ledger = {"version": 1, "booksOpenedAt": iso(now - 10*D), "rulesHistory": [{"effectiveAt": iso(now - 10*D), "rules": rules}],
  "beers": [{"id": str(uuid.uuid4()).upper(), "createdAt": iso(b), "recordedAt": iso(b)} for b in beers],
  "runs": [{"id": str(uuid.uuid4()).upper(), "healthKitWorkoutID": str(uuid.uuid4()).upper(), "startedAt": iso(e - timedelta(minutes=int(m*9.5))), "endedAt": iso(e),
            "distanceMeters": m * 1609.344, "importedAt": iso(e + H), "sourceName": "com.google.android.apps.fitness"} for e, m in runs],
  "excludedWorkoutIDs": []}
json.dump(ledger, sys.stdout)
PY
  $ADB shell am force-stop $PKG
  $ADB push "$TMP/ledger.json" /data/local/tmp/ledger.json >/dev/null
  $ADB shell run-as $PKG mkdir -p files/BeerDebt
  $ADB shell run-as $PKG cp /data/local/tmp/ledger.json files/BeerDebt/ledger.json
  $ADB shell run-as $PKG sh -c "'mkdir -p shared_prefs && printf \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\" standalone=\\\"yes\\\"?><map><boolean name=\\\"onboardingComplete\\\" value=\\\"true\\\" /></map>\" > shared_prefs/app.xml'"
}
launch() { # launch [debugScreen]
  $ADB shell am force-stop $PKG
  if [ -n "${1:-}" ]; then $ADB shell am start -n $PKG/.MainActivity --es debugScreen "$1" >/dev/null; else $ADB shell am start -n $PKG/.MainActivity >/dev/null; fi
}
shoot() { sleep 3; $ADB exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }

$ADB shell am force-stop $PKG; $ADB shell pm clear $PKG >/dev/null
launch; shoot onboarding
seed debt
launch; shoot home-debt
launch beerAdded; shoot beer-added
launch debt; shoot debt
launch beerDetail; shoot beer-detail
launch runs; shoot runs
launch settings; shoot settings
launch debtFree; shoot debt-free
seed credit
launch; shoot home-credit
launch runs; shoot runs-credit
rm -rf "$TMP"
echo "done → $OUT"
