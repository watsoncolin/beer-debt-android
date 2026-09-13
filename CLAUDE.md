# Beer Debt Android — Claude Code guide

## Cross-platform parity

Beer Debt ships on iOS and Android. **The iOS repo (`~/beer-debt-ios`) is
the lead repo.** Features are designed, built, and validated there first, then
ported here. The iOS implementation is the reference for the data model, the
accounting rules, screen structure, and copy; diverge only for a platform
reason. Read `~/beer-debt-ios/docs/spec.md` and `docs/decisions.md` before
touching the engine or the UI.

Keep `versionName` equal to iOS's `MARKETING_VERSION` per shipped feature;
build numbers drift freely.

## The engine is a port, pinned by fixtures

`engine/` is pure Kotlin (no Android). `BalanceEngine.kt` mirrors the Swift
`BalanceEngine` line for line, including replaying with `Double` seconds so
floating-point results agree. `StreakEngine.kt` mirrors the Swift
`StreakEngine` (spec §25): calendar days in a `ZoneId`, passed to
`BalanceEngine.report(ledger, at, zone)`; the fixtures carry `timeZone`
(UTC) and the app passes the system zone. `Rules.streakProtection` decodes
as off when absent; `Rules.OPENING` (on) is what new ledgers use, and
`LedgerStore` appends a rules change turning it on for old ledgers. `engine/src/test/resources/cases.json` comes
from `~/beer-debt-ios/scripts/fixtures.sh`; `FixtureTest` replays all cases
and compares every field to 1e-6. **Never change engine behaviour here
first**: change it on iOS, regenerate the fixtures, copy them over, then port.

The ledger JSON is shared byte-for-byte in meaning: `Ledger`, `BeerEntry`,
`RunEntry`, `RulesChange`, `Rules`, ISO 8601 UTC whole-second instants, UUID
strings (iOS writes uppercase; both sides parse either). `healthKitWorkoutID`
keeps its iOS name; on Android it holds a UUID derived from the Health
Connect record id.

## Build

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :engine:test
```

`./gradlew :app:assembleDebug` builds the app. AGP 9 has built-in Kotlin, so
`app/build.gradle.kts` applies only the Android application, Compose
compiler, and serialization plugins (no `kotlin.android`). `compileSdk` 37
is required by Compose 2026.09. AGP auto-downloaded the SDK into
`~/Library/Android/sdk` once the license hashes were present.

## Before a release: smoke-test the shrunk build

`scripts/release-smoke.sh` builds `assembleRelease` (R8 on), installs it on
the running AVD, launches it, and fails on a dead process or a fatal
exception. The first release build ever shipped crashed on every phone
because R8 stripped WorkManager's Room database constructor; the debug
builds the emulator scripts use never run R8. `app/proguard-rules.pro`
carries the keep rules. Run this before `Release to Play`.

## Screenshots / manual testing on the emulator

`scripts/screenshots.sh [outdir]` boots the `beerdebt` AVD headless (create it
once with `scripts/emulator-bootstrap.sh`, or Android Studio's device
manager), installs the debug APK, seeds demo ledgers (debt and credit states)
straight into `files/BeerDebt/ledger.json` via `run-as`, marks onboarding
complete, and screenshots every screen into `docs/screenshots/`. It relies on
the debug-only intent extra `--es debugScreen debt|runs|settings|beerAdded|
beerDetail|debtFree` handled by `DebugLaunch` in `MainActivity` (the iOS
`-debugScreen` equivalent). Keep the seed shapes in that script in sync with
`Ledger`'s JSON and with the iOS script.

## App structure (mirrors iOS)

- `BeerDebtApp` (Application) owns `LedgerStore` and `HealthSync`, no DI.
- `data/LedgerStore` — `StateFlow<Ledger>`, atomic JSON writes to
  `filesDir/BeerDebt/ledger.json`, the same shape as iOS; `addBeer`,
  `updateBeerDate` (30-day window), `removeBeer`, `importRuns` (dedup +
  excluded set), `removeRuns`, `deleteRun`, `updateRules` (forward-only).
- `health/HealthConnectService` — read-only Health Connect; running
  sessions via the Changes API (token persisted; full re-read on expiry),
  distance aggregated per session; workout ids are UUIDs derived from the
  record id. `HealthSync` mirrors iOS `HealthSync` (foreground sync on
  resume, hourly `SyncWorker`, deletions, debt-free celebration, run
  notifications through `RunNotifier`, whose copy is identical to iOS).
- `notify/` — `RunNotifier` (channels `runs` and `weekly`, the pure run
  copy) and `WeeklySummary` (prefs `weekly`; iOS numbering 1 = Sunday; the
  pure `message`/`nextFireDates`; schedules one `WeeklySummaryWorker` via
  WorkManager that builds the copy at fire time from the live ledger, so
  unlike iOS nothing is rescheduled on ledger changes).
- `widget/` — `BalanceWidget` (Glance, responsive small 110×110 / medium
  250×110, copy identical to iOS `BalanceWidgetView`), its receiver, and
  `WidgetRefreshWorker` (re-render at the next interest posting).
  `BeerDebtApp` refreshes every placed widget on each ledger save through
  `LedgerStore.onChange`; `res/xml/balance_widget_info.xml` adds a
  half-hourly tick.
- `ui/streak/Streak.kt` — `StreakFlame`, `StreakCopy` (words identical to
  iOS), `StreakCard` (Home), `StreakScreen` ("Your Streak"),
  `StreakActivatedSheet` (day two, paired with Debt Free).
- `ui/` — `home` (HomeScreen + BeerAddedSheet + DebtFreeSheet), `debt`
  (DebtScreen + BeerDetailSheet), `runs` (RunsScreen with a Canvas bar
  chart), `settings` (rules, Health Connect incl. the Play install link,
  weekly summary, About with the Privacy link), `privacy` (the Health
  Connect rationale screen; also what Health Connect's
  ACTION_SHOW_PERMISSIONS_RATIONALE and VIEW_PERMISSION_USAGE open),
  `onboarding`, `components`, `theme` (Palette from iOS `Theme.swift`,
  `Backdrop`, `ForestBackground`). Everything is dark.
- Art is copied from `~/beer-debt-ios/docs/art` into `res/drawable-nodpi`;
  the adaptive launcher icon uses the transparent trail mug.

## Tests

`./gradlew :engine:test :app:testDebugUnitTest`. The app module's tests are
plain JUnit 4 (`app/src/test/kotlin`): `LedgerStoreTest`, `RunNotifierTest`,
`WeeklySummaryTest`, the same cases as the iOS `BeerDebtTests` with the
same fixture instants in `TestSupport.kt`. Keep pure logic (copy builders,
schedules) free of Android types so it stays testable on the JVM.

## Release

`docs/release.md`: upload key in `~/.signing/`, `keystore.properties`
(gitignored) for local signed builds, `ci.yml` on push, manual `release.yml`
to build a signed AAB and upload to Play. `docs/play/` has the listing
copy and graphics. `versionName` tracks iOS `MARKETING_VERSION`;
`versionCode` is the Actions run number.

## Conventions

- Kotlin, Compose, Material 3, forced dark (forest palette from iOS `Theme`).
- Version catalog in `gradle/libs.versions.toml`; Gradle Kotlin DSL.
- No third-party dependencies beyond AndroidX, Kotlin, and Health Connect.
- Health Connect is read-only, running sessions only. Don't add data types
  without updating the manifest permissions, the Privacy screen, and the
  privacy page in the iOS repo (it is shared and Play reads it).
