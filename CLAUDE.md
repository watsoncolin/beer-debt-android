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
(UTC) and the app passes the system zone. `FreezeApplication` is the
only freeze state on disk — the days the user chose; earned counts, honoured
applications and refunds are all derived from the runs at every replay, so a
late import that brings a frozen day to a mile refunds the freeze by simply
not honouring its application. `Rules.streakProtection` decodes
as off when absent; `Rules.OPENING` (on) is what new ledgers use, and
`LedgerStore` appends a rules change turning it on for old ledgers. `engine/src/test/resources/cases.json` comes
from `~/beer-debt-ios/scripts/fixtures.sh`; `FixtureTest` replays all cases
and compares every field to 1e-6. **Never change engine behaviour here
first**: change it on iOS, regenerate the fixtures, copy them over, then port.
Its decoder sets `ignoreUnknownKeys`, so a field iOS adds is silently dropped
until it is named in `FixtureTest`'s `Expected*` classes and asserted: a copied
`cases.json` passing is not by itself proof the port is complete. Declare each
one non-optional, so the reverse — fixtures that predate a field the engine has
— fails the decode loudly instead.

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
complete, and screenshots every screen into `docs/screenshots/`. It pins
`ANDROID_SERIAL` to a single emulator and refuses anything else, because it
runs `pm clear`: a paired handset must never be a candidate target. Set
`ANDROID_SERIAL` yourself to choose between two emulators. Every capture is
checked for having the app's dark green on at least `FLOOR` percent (5) of it,
since a launcher mid-animation or a screen that never drew still writes a
valid PNG; doubtful ones are named at the end and the script exits non-zero.
Re-running rewrites all fifteen, but most differ only by the clock and the
seeded dates — commit the ones your change actually touched. It relies on
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
  `applyFreeze` / `freezeToday` spend a streak freeze (spec §25.1), and allow
  only today or `StreakStatus.repairableDay` — the MVP guard against arbitrary
  history editing. Whether a freeze was in hand is the engine's business: an
  application it cannot honour is ignored on replay, never trusted here.
  A failed write is remembered, not swallowed: `isPersisted` goes false and
  `persist()` retries it. Before discarding the only means of rebuilding
  what was written, call `persist()` first — `HealthSync.sync()` is the live
  case, holding the changes token until the runs it covers are on disk. The
  write never deletes the file it still has; on a failed rename it copies
  into place instead, so a second failure cannot leave the user with no
  ledger at all.
- `health/HealthConnectService` — read-only Health Connect; running
  sessions via the Changes API (token persisted; full re-read on expiry),
  distance aggregated per session; workout ids are UUIDs derived from the
  record id. `HealthSync` mirrors iOS `HealthSync` (foreground sync on
  resume, hourly `SyncWorker`, deletions, debt-free celebration, run
  notifications through `RunNotifier`, whose copy is identical to iOS).
  **Report only what we can't explain:** `HealthFailure` classifies a thrown
  Health Connect error, and a named condition (permission revoked, provider
  missing or stale, provider process gone, transient IO) gets real copy for
  the user and no Sentry event. Only `UNKNOWN` is reported. The hourly
  background sync would otherwise page on every wake. Add a case rather than
  widening what gets reported. It keys off exception types and the SDK status
  `HealthConnectService` already reads, never off message text.
  Widget faces go stale unless something asks for a reload:
  `BalanceWidget.refresh` runs on every store write via `onChange`, and on
  **every** successful sync including one that imports nothing. Don't make
  that last call conditional on the ledger changing; a declined reload is
  otherwise permanent.
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
- No third-party dependencies beyond AndroidX, Kotlin, Health Connect, and
  Sentry (crash reporting only; `telemetry/Telemetry.kt` is the one door,
  the Pourcraft convention: no user identification, no session replay, no
  tracing, hand-reported errors carry a context block). DSN is in
  `app/build.gradle.kts` (public; org `pawfect-edit`, project
  `beer-debt-android`). The release workflow uploads the R8 mapping with
  `sentry-cli` when `SENTRY_AUTH_TOKEN` is set; without it, traces are
  obfuscated but nothing fails. Play Data safety declares crash logs and
  diagnostics because of this.
- Health Connect is read-only, running sessions only. Don't add data types
  without updating the manifest permissions, the Privacy screen, and the
  privacy page in the iOS repo (it is shared and Play reads it).
