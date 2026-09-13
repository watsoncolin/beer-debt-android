# Release pipeline (Android)

Beer Debt for Android ships through **GitHub Actions → Google Play**, the
same shape as pourcraft-android. The iOS pipeline is documented in
`beer-debt-ios/docs/release.md`.

## Google Play

| | |
|---|---|
| Package | `me.colinwatson.beerdebt` |
| App name | Beer Debt |
| Play Console app | created 2026-09-12, app ID `4972402712627858415` (developer account `8797158237570022507`) |
| Internal testing | track `4701306367860282704`; testers join at https://play.google.com/apps/internaltest/4701306367860282704 (the account-level "Internal" list, shared with Pour Craft Cocktails) |
| Category | Health & Fitness |
| Price | Free, no ads, no in-app purchases |
| Privacy policy | https://watsoncolin.github.io/beer-debt-ios/privacy.html (shared with iOS; has a Health Connect section) |
| Support | https://watsoncolin.github.io/beer-debt-ios/support.html |
| First version | 1.0 (`versionName` in `app/build.gradle.kts`, matches iOS `MARKETING_VERSION`) |

Listing copy, screenshots, and the graphics live in `docs/play/` (see
`docs/play/listing.md`).

**State on 2026-09-13 (early):** the first internal build a tester ran
(versionCode 3) crashed at launch: R8 stripped WorkManager's Room database
constructor (fixed in `app/proguard-rules.pro`, verified with
`scripts/release-smoke.sh`; run 4 carried the fix, run 5 added Sentry).
**versionCode 5 (1.0) is live on the internal track.** The production
release that referenced the crashing bundle was pulled out of review and
recreated as "5 (1.0)" with the same notes and all countries; it sits in
Publishing overview as one of 12 changes not yet sent for review, next to
the updated Data safety form (crash logs, diagnostics, device IDs:
collected, not shared, encrypted, auto-deleted). What remains is the
**Submit changes for review** button in Publishing overview, which starts
Play's review (including the Health Connect declaration) and, since
managed publishing is off, the production rollout on approval. That click
is Colin's, after the internal build has been checked on a real phone.

## Signing

Play App Signing: Google holds the app signing key; we hold an **upload
key**. It was generated 2026-09-12 with Android Studio's JBR `keytool`:

- Keystore `~/.signing/beerdebt-upload.jks`, alias `upload`, RSA 2048,
  valid to 2054. Passwords are in `~/.signing/beerdebt-upload.properties`
  (mode 600). The public certificate is exported to
  `~/.signing/beerdebt-upload-cert.pem` for the Play Console.
- `keystore.properties` at the repo root (gitignored) is a copy of that
  properties file; `app/build.gradle.kts` reads it and signs `release`
  builds when it exists. Without it, release builds are unsigned.
- **Back the keystore up** somewhere other than this Mac. With Play App
  Signing an upload key can be reset through Play support if lost, but it
  takes days.

CI has the four signing secrets (set 2026-09-12) plus
`PLAY_SERVICE_ACCOUNT_JSON`, a key for Pourcraft's
`play-publisher@pourcraft-dev.iam.gserviceaccount.com`, which has release
permissions on Beer Debt in Users and permissions. To rotate the signing
secrets:

```sh
cd ~/beer-debt-android
PW=$(grep storePassword ~/.signing/beerdebt-upload.properties | cut -d= -f2)
base64 -i ~/.signing/beerdebt-upload.jks | gh secret set UPLOAD_KEYSTORE_BASE64
printf '%s' "$PW" | gh secret set UPLOAD_KEYSTORE_PASSWORD
printf 'upload'  | gh secret set UPLOAD_KEY_ALIAS
printf '%s' "$PW" | gh secret set UPLOAD_KEY_PASSWORD
```

## Sentry

Project `beer-debt-android` in the `pawfect-edit` org (created 2026-09-12).
The app sends crashes, ANRs, and hand-reported sync/store errors; nothing
that identifies the user. For readable stack traces the release workflow
uploads the R8 mapping with `sentry-cli` when the `SENTRY_AUTH_TOKEN`
repository secret exists (an org auth token with `project:releases` and
`project:write`, from Sentry → Settings → Auth Tokens). Without it the
build still ships.

## Workflows

- **`ci.yml`** on every push and pull request: engine fixture tests, debug
  APK, app unit tests, lint. No secrets needed.
- **`release.yml`**, manual (Actions → Release to Play → Run workflow):
  runs the tests, writes `keystore.properties` from the secrets, builds
  `bundleRelease` with `versionCode = run number`, verifies the signature,
  and attaches the AAB as an artifact. If `PLAY_SERVICE_ACCOUNT_JSON` is set
  it also uploads to the chosen track with the given status and release
  notes; otherwise it stops at the artifact and says so in the summary.

### Versioning

- `versionName` in `app/build.gradle.kts` is the store version; bump it by
  hand with iOS's `MARKETING_VERSION`.
- `versionCode` is the GitHub Actions run number (`-PversionCode=`); local
  builds default to 1. If an AAB is ever uploaded by hand with a higher
  code, add an offset in `release.yml` so CI lands past it.

## One-time Play Console setup

Done 2026-09-12 through the web UI (the Play Developer API cannot create an
app or accept the first bundle): app created, store listing (copy, icon,
feature graphic, seven screenshots), category Health & Fitness, contact
details, privacy policy URL, all App content declarations (no ads, no
sign-in, target audience 18+, data safety "no data collected", not a
government app, no financial features, Health apps → activity and fitness,
no advertising ID, IARC content rating with alcohol references, which came
back as PEGI 3 / USK 0 / IARC 3+ / ClassInd 10), the first bundle
(versionCode 1 from release run #1) rolled out to internal testing with the
"Internal" tester list, and the service account granted release
permissions. The steps below are kept for reference.

1. **Create the app** at https://play.google.com/console: name Beer Debt,
   default language English (US), App, Free. Accept the declarations.
2. **App integrity → Play App Signing**: choose "Use a different upload
   key"? No: let Google generate the app signing key, then under *Upload
   key certificate* upload `~/.signing/beerdebt-upload-cert.pem` so bundles
   signed with our upload key are accepted. (If the first upload is made
   with the upload key before this step, Play adopts it automatically.)
3. **Store listing**: paste from `docs/play/listing.md`; upload
   `docs/play/icon-512.png`, `docs/play/feature-graphic.png`, and the phone
   screenshots in `docs/play/screenshots/`.
4. **App content** (Policy → App content):
   - Privacy policy URL (above).
   - Ads: no. App access: all functionality available without login.
   - Content rating questionnaire: Health & Fitness app; answer yes to
     "references to alcohol" only. Expect Teen or Mature 17+ (Apple gave 17+).
   - Target audience: 18 and over. Not designed for children.
   - News app: no. COVID-19: no. Government app: no. Financial features: none.
   - **Data safety**: no data collected or shared. Health Connect data is
     read on-device only and never leaves the device, which Play counts as
     "not collected"; say so in the optional explanation.
   - **Health apps → Health Connect**: declare the app reads Health
     Connect data (exercise sessions, distance) for its core fitness
     feature, on-device only. Link the privacy policy; the app shows its
     rationale screen (Settings → Privacy Policy, also opened by Health
     Connect itself). Play reviews this declaration; the app cannot be
     published to production until it is approved.
   - Advertising ID: not used. Photo/video permissions: none.
5. **First bundle**: run `Release to Play` (any track) with no
   `PLAY_SERVICE_ACCOUNT_JSON` secret, download the AAB artifact, and
   upload it by hand under Testing → Internal testing. Add yourself as a
   tester.
6. **API access** (optional, for automatic uploads afterwards): Play
   Console → Setup → API access → link a Google Cloud project → create a
   service account with the *Release manager* role → download its JSON key
   → `gh secret set PLAY_SERVICE_ACCOUNT_JSON < key.json`.
7. Production release after the internal build has been installed on a
   real phone with Health Connect and at least one run has synced.

## Health Connect on real phones

- Android 14+: Health Connect is part of the OS.
- Android 9–13: it is a Play Store app. The onboarding and Settings show
  an "Install Health Connect" link (a Play deep link to Health Connect's
  onboarding) when it is missing; `minSdk` is 28.
- The manifest declares the permission rationale intent filter and the
  Android 14 `VIEW_PERMISSION_USAGE` alias; both open the in-app Privacy
  screen.
