# Finance Tracker — notes for Claude Code

A private, single-user Android app that tracks one person's money (BC, Canada; CAD). The full spec is
`docs/SPEC.md`; read it before larger changes. Import format notes are in `docs/IMPORT_FORMATS.md`.

## Non-negotiable rules

1. **No bank passwords, ever.** Bank data only enters the app as files the user imports. No screen
   scraping, no bank logins, no aggregators.
2. **Questrade is read-only.** Read permissions only. Never add code that places, changes or cancels orders.
3. **Everything stays on the phone.** No backend, no cloud sync, no analytics / crash reporting / ads /
   tracking SDKs. The only network traffic is to Questrade's login and API servers, enforced by
   `AllowedHostsInterceptor` (app/.../net). Don't loosen it.
4. **No real financial data in git.** Real exports live in `samples/` (gitignored). Tests use synthetic
   fixtures under `core/src/test/resources/fixtures/` that copy the *structure* only, with made-up rows.
   Never commit tokens, account numbers, statements or screenshots of real data.
5. **Never ask the user to paste a Questrade token into chat.** They enter it in the app. Test the API
   client against MockWebServer.
6. **Money is `Long` cents** (`BigDecimal` only while parsing). Never `Double`/`Float` for money, including
   Questrade JSON (see `BigDecimalSerializer`). Ratios such as percentages may use Double for display.
7. Before adding a dependency that isn't in `gradle/libs.versions.toml`, explain why to the user.
8. Release builds never log tokens or financial data. Don't add `Log.*` calls with amounts, descriptions,
   account numbers or tokens; use `AppLog` which is a no-op in release.

## Layout

- `core/` — pure Kotlin/JVM, **no Android imports**, no third-party runtime deps. Parsers (CSV, OFX,
  statement text), dedup, categorizer + seed rules, merchant cleaner, transfer matcher, recurring
  detection, reminder date maths, analytics, summary/CSV export. Fast JVM tests.
  It compiles to Java 17 bytecode and runs on Android API 26+: stick to Kotlin stdlib, `java.time`,
  `java.math`, `java.security.MessageDigest`. Avoid JDK 9+ only methods (e.g. `InputStream.readAllBytes`,
  `String.repeat` from Java, `HexFormat`).
- `app/` — Android: Compose UI, Room + SQLCipher, DataStore + Tink secrets, Questrade client, WorkManager
  jobs, notifications, file import (SAF + share/open-with), PdfBox text extraction, Hilt DI.

## Conventions

- Kotlin, Compose + Material 3, single activity, Navigation Compose, MVVM with repositories, Hilt, KSP.
- Sign convention everywhere: negative = money left the account. Card purchases negative, payments and
  refunds positive. Balances: positive = asset, negative = owed.
- Transfers (linked pairs or TRANSFER-kind categories) never count as income or spending.
- Analytics totals are CAD only; other currencies are shown separately, never converted with a guessed rate.
- Time zone: the phone's zone (America/Vancouver). Reminder maths lives in `core` with `java.time`.

## Commands (use the wrapper; works on Windows with `gradlew.bat`)

```
./gradlew :core:test                 # fast logic tests
./gradlew testDebugUnitTest          # all unit tests (core + app)
./gradlew lintDebug                  # Android lint
./gradlew assembleDebug              # debug APK -> app/build/outputs/apk/debug/
```

Snapshot test for the summary export: `UPDATE_SNAPSHOTS=true ./gradlew :core:test` rewrites
`core/src/test/resources/snapshots/summary.md`; review the diff before committing.

## When real samples arrive

Bank presets in `CsvProfileInference`, `StatementTextParser` and `MerchantCleaner` were written from
publicly reported formats, not from the user's files. When the user drops exports into `samples/`:
study them, update the presets and cleaner, add synthetic fixtures with the same structure (made-up
rows!), and note what changed in `docs/IMPORT_FORMATS.md`.
