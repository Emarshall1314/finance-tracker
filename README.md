# Finance Tracker

A private, single-user Android app that tracks one person's money in British Columbia, Canada:
Scotiabank and Canadian Tire Bank (Triangle Mastercard) statements imported as files, and Questrade
investments over Questrade's official read-only API.

**Privacy by design:** no bank logins, no backend, no cloud sync, no analytics. Everything stays on the
phone in an encrypted database (SQLCipher, key protected by the Android Keystore via Tink). The only
network traffic is to Questrade's login and API servers.

> Status: in development. See [docs/SPEC.md](docs/SPEC.md) for the full spec and milestones, and
> [docs/IMPORT_FORMATS.md](docs/IMPORT_FORMATS.md) for what each bank's files look like.

## Project layout

| Path | What |
|---|---|
| `core/` | Pure Kotlin/JVM: parsers (PDF statement text, CSV, OFX/QFX/QBO), duplicate detection, categorizer, transfer matching, recurring bills, reminder dates, analytics, summary export |
| `app/` | Android app: Compose UI, Room + SQLCipher, Tink secrets, Questrade client, WorkManager |
| `docs/` | Spec and import-format notes |
| `samples/` | Your real exports, for local testing only. **Gitignored; never committed.** |

## Building

Requires JDK 17+ (21 recommended) and the Android SDK (API 37). Android Studio provides both.

```bash
./gradlew testDebugUnitTest     # all unit tests
./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat` instead of `./gradlew`.

CI (GitHub Actions) runs lint, unit tests, a debug build and a gitleaks secret scan on every push, and
uploads the debug APK as a build artifact.

More sections (installing on a phone, connecting Questrade, exporting from each bank) are added as
those milestones land.
