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
AVD=${AVD:-beerdebt}

[ -f "$APK" ] || { echo "no APK; run ./gradlew :app:assembleDebug"; exit 1; }

# Pin every adb call to one emulator. This script wipes app data (pm clear)
# and force-stops the app, so it must never be able to reach a real phone --
# and a plugged-in or wirelessly-paired handset is otherwise both a candidate
# for adb's implicit target and, with two devices attached, a hard error
# halfway through. Exporting ANDROID_SERIAL pins the adb calls further down
# without threading -s through every one of them.
emulators() { $ADB devices | awk '/^emulator-[0-9]+\tdevice$/{print $1}'; }
if [ -z "$(emulators)" ]; then
  echo "booting AVD $AVD"
  nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect > "$TMP/emulator.log" 2>&1 &
  for _ in $(seq 1 60); do [ -n "$(emulators)" ] && break; sleep 5; done
  [ -n "$(emulators)" ] || { echo "AVD $AVD never came up; see $TMP/emulator.log"; exit 1; }
fi
if [ -n "${ANDROID_SERIAL:-}" ]; then
  emulators | grep -qx "$ANDROID_SERIAL" || { echo "ANDROID_SERIAL=$ANDROID_SERIAL is not an attached emulator; attached:"; emulators; exit 1; }
else
  n=$(emulators | wc -l | tr -d ' ')
  [ "$n" = "1" ] || { echo "$n emulators attached; set ANDROID_SERIAL to pick one:"; emulators; exit 1; }
  export ANDROID_SERIAL=$(emulators)
fi
echo "targeting $ANDROID_SERIAL"

until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
sleep 5
for k in window_animation_scale transition_animation_scale animator_duration_scale; do $ADB shell settings put global $k 0 >/dev/null; done

# Not `| tail -1`: the pipe discards adb's exit status, and adb reports a
# rejected install on stdout anyway, so a stale build would be screenshotted
# as though it were this one. The usual cause is a debug keystore that no
# longer matches, which only an uninstall clears.
apk_install() { $ADB install -r "$APK" 2>&1; }
out=$(apk_install) || true
if printf '%s' "$out" | grep -q "Failure \[INSTALL_FAILED"; then
  echo "install rejected, uninstalling and retrying:"; printf '%s\n' "$out" | grep Failure
  $ADB uninstall $PKG >/dev/null 2>&1 || true
  out=$(apk_install) || true
fi
printf '%s' "$out" | grep -q "Success" || { echo "install failed:"; printf '%s\n' "$out"; exit 1; }
echo "installed"

seed() { # seed <debt|credit>
python3 - "$1" <<'PY' > "$TMP/ledger.json"
import json, sys, uuid
from datetime import datetime, timedelta, timezone
mode = sys.argv[1]
now = datetime.now(timezone.utc).replace(microsecond=0)
iso = lambda d: d.strftime("%Y-%m-%dT%H:%M:%SZ")
H, D = timedelta(hours=1), timedelta(days=1)
rules = {"milesPerBeer": 1.0, "interestRate": 0.1, "interestPeriod": "daily", "gracePeriod": 86400.0, "maximumCreditBeers": 3.0, "creditDecayRatePerWeek": 0.1, "streakProtection": True}
# Runs at 07:12 local on given days back, so the streak reads as consecutive calendar days (same seeds as iOS).
local = now.astimezone()
freezes = []
def morning(days_back, miles): return (local.replace(hour=7, minute=12, second=0) - days_back*D).astimezone(timezone.utc), miles
if mode == "freeze":
    # Six running days through yesterday earns a freeze, nothing yet today:
    # the "Use Freeze Today" offer, with a live streak and interest paused.
    beers = [now - 6*D - H, now - 5*D - 2*H, now - 4*D - 3*H, now - 3*D - H]
    runs = [morning(n, 1.0 + 0.1*n) for n in range(1, 7)]
elif mode == "restday":
    # Same, with the freeze already spent on today: the rest-day state.
    beers = [now - 6*D - H, now - 5*D - 2*H, now - 4*D - 3*H, now - 3*D - H]
    runs = [morning(n, 1.0 + 0.1*n) for n in range(1, 7)]
    # Freeze applications are keyed to midday of the local day (spec §25.1).
    freezes = [local.replace(hour=12, minute=0, second=0).astimezone(timezone.utc)]
elif mode == "debt":
    beers = [now - 6*D - H, now - 5*D - 2*H, now - 4*D - 3*H, now - 3*D - H, now - 2*D - 2*H, now - 6*H]
    runs = [morning(5, 1.0), morning(2, 1.1), morning(1, 1.2), morning(0, 1.0)]
else:
    beers = [now - 6*D]
    runs = [morning(3, 1.0), morning(2, 1.2), morning(1, 2.4)]
ledger = {"version": 1, "booksOpenedAt": iso(now - 10*D), "rulesHistory": [{"effectiveAt": iso(now - 10*D), "rules": rules}],
  "beers": [{"id": str(uuid.uuid4()).upper(), "createdAt": iso(b), "recordedAt": iso(b)} for b in beers],
  "runs": [{"id": str(uuid.uuid4()).upper(), "healthKitWorkoutID": str(uuid.uuid4()).upper(), "startedAt": iso(e - timedelta(minutes=int(m*9.5))), "endedAt": iso(e),
            "distanceMeters": m * 1609.344, "importedAt": iso(e + H), "sourceName": "com.google.android.apps.fitness"} for e, m in runs],
  "excludedWorkoutIDs": [],
  "freezeApplications": [{"id": str(uuid.uuid4()).upper(), "day": iso(d), "appliedAt": iso(d)} for d in freezes]}
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
# A screencap always "succeeds": a launcher caught mid-animation, a sheet that
# never opened, or a home screen showing the page without the widget all write
# a perfectly valid PNG. widget-home came back as bare wallpaper exactly that
# way, and went unnoticed into a commit.
#
# What separates a real capture is the app's own dark green, which covers
# 11-87% of every screenshot here -- 29% even of widget-home, from the three
# widget cards -- against 0.9% for a home screen with no widget on it. Note
# that "how varied is the image" does NOT work: that same wallpaper has more
# distinct colours than the good capture, being a photograph.
FLOOR=${FLOOR:-5}
SUSPECT=""
shoot() { # shoot <name>
  sleep 3
  $ADB exec-out screencap -p > "$OUT/$1.png"
  verdict=$(python3 - "$OUT/$1.png" "$FLOOR" <<'PY'
import sys
path, floor = sys.argv[1], float(sys.argv[2])
try:
    from PIL import Image
except ImportError:
    print("skip"); raise SystemExit
try:
    im = Image.open(path).convert("RGB")
except Exception as e:
    print(f"unreadable: {e}"); raise SystemExit
CARD, TOL = (0x2A, 0x3C, 0x37), 26  # Palette.card; forest/forestDeep are within tolerance
px = list(im.resize((im.width // 6, im.height // 6)).getdata())
pct = 100.0 * sum(1 for p in px if all(abs(p[i] - CARD[i]) <= TOL for i in range(3))) / len(px)
print("ok" if pct >= floor else f"{pct:.1f}% app background, under {floor:g}% -- wrong screen or nothing drawn")
PY
)
  case "$verdict" in
    ok|skip) echo "shot $1" ;;
    *) echo "shot $1 -- SUSPECT: $verdict"; SUSPECT="$SUSPECT $1" ;;
  esac
}

$ADB shell am force-stop $PKG; $ADB shell pm clear $PKG >/dev/null
launch; shoot onboarding
seed debt
launch; shoot home-debt
launch beerAdded; shoot beer-added
launch debt; shoot debt
launch paid; shoot paid
launch beerDetail; shoot beer-detail
launch runs; shoot runs
launch settings; shoot settings
launch streak; shoot streak
launch streakActivated; shoot streak-activated
launch privacy; shoot privacy
launch debtFree; shoot debt-free
# Weekly summary on, so Settings shows the day/time rows.
$ADB shell run-as $PKG sh -c "'printf \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\" standalone=\\\"yes\\\"?><map><boolean name=\\\"enabled\\\" value=\\\"true\\\" /></map>\" > shared_prefs/weekly.xml'"
launch settings; sleep 2; $ADB shell input swipe 540 2000 540 700 500; shoot settings-weekly
# Pin the widget, then the home screen. The tap is the launcher's confirm
# button where the Pixel 6 profile puts it, making this the most
# device-profile-bound step here, and it fails in two different ways: nothing
# is pinned at all, or it is pinned and the launcher still shows a page
# without it. dumpsys tells those apart, and the colour floor on the shot
# catches the second.
launch widget; sleep 3; $ADB shell input tap 844 2252; sleep 2
pinned=$($ADB shell dumpsys appwidget 2>/dev/null | awk '/^Widgets:/{f=1;next} /^[A-Za-z].*:/{f=0} f' | grep -c "$PKG" || true)
[ "$pinned" -gt 0 ] || echo "WARNING: nothing pinned; the confirm tap missed (coordinates are for the Pixel 6 profile)"
$ADB shell input keyevent KEYCODE_HOME; sleep 2
shoot widget-home
seed freeze
launch; shoot home-freeze
launch streak; shoot streak-freeze
launch freezeEarned; shoot freeze-earned
seed restday
launch streak; shoot streak-restday
launch; shoot home-restday
seed credit
launch; shoot home-credit
launch runs; shoot runs-credit
rm -rf "$TMP"
echo "done → $OUT"
[ -z "$SUSPECT" ] || { echo "SUSPECT, look before committing:$SUSPECT"; exit 1; }
