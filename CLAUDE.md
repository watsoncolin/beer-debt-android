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
floating-point results agree. `engine/src/test/resources/cases.json` comes
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

`settings.gradle.kts` includes only `:engine` until the app module lands and
the Android SDK is installed on the machine.

## Conventions

- Kotlin, Compose, Material 3, forced dark (forest palette from iOS `Theme`).
- Version catalog in `gradle/libs.versions.toml`; Gradle Kotlin DSL.
- No third-party dependencies beyond AndroidX, Kotlin, and Health Connect.
- Health Connect is read-only, running sessions only.
