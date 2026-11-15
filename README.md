# Finance Tracker

A private, single-user Android app that tracks one person's money in British Columbia, Canada:
Scotiabank (chequing and Scotia Visa) and Canadian Tire Bank (Triangle Mastercard) statements
imported as files, and Questrade investments over Questrade's official **read-only** API.

**Privacy by design:** no bank logins, no backend, no cloud sync, no analytics, crash-reporting or ad
SDKs. Everything stays on the phone in an encrypted database. The only network traffic is to
Questrade's login and API servers.

See [docs/SPEC.md](docs/SPEC.md) for the full spec and [docs/IMPORT_FORMATS.md](docs/IMPORT_FORMATS.md)
for what each bank's files look like.

## What it does

- **Import** PDF e-statements (Scotiabank chequing, Scotia Visa, Triangle Mastercard), CSV downloads
  (with a column-mapping screen for new layouts) and OFX/QFX/QBO files. Share a file to the app from
  Files, Drive or Gmail, or pick one in the app. Duplicates are skipped; every import can be undone.
  There's no need to add accounts by hand: each statement says which bank, kind of account and card
  ending it's for, and the app sets the account up from that, then asks on Home whether its guess is
  right (keep it, change it, or say it's the same as an account you have, e.g. a reissued card).
  PDF statements are checked against their own opening/closing totals.
- **Categorise** automatically: about 2,100 built-in rules for Canadian and BC chains plus words that
  say what a place is ("sushi", "dental", "barber"). Recategorise a transaction and choose "Always do
  this for <merchant>" to teach it, or use **Sort out Other** (Home, or Settings → Categories & rules)
  to give every unrecognised place a category in one pass.
- **Known payments** (Settings → Known payments, or "I pay this amount regularly" on a transaction):
  "Rent, $1,800, around the 1st" makes every payment of that amount Rent, even an e-Transfer whose
  description says nothing. Transfers and categories you picked by hand are never changed.
- **Backup & restore** (Settings): one password-encrypted file holding everything in the app, and
  optionally the original statement PDFs, to keep in Drive or on a computer. Restoring it on a new phone
  (or after a reinstall) brings everything back. The Questrade token and AI key are never in it.
- **Graphics you can touch**: a money-flow diagram (income on the left pouring into where it went), a
  calendar shaded by daily spending, monthly savings bars, a year-to-date donut, net worth and portfolio
  lines. Tap anything for its details. Insights opens with the month in a few big numbers.
- **Transfers** between your own accounts (card payments, deposits to Questrade) are paired and never
  counted as income or spending.
- **Bills & subscriptions**: recurring monthly charges with their cost and next date.
- **Overview**: income, spending, net saved and savings rate per month; spending by category; 12-month
  chart; top merchants; fees and interest; upcoming bills; net worth with a trend; data freshness.
- **Investments**: Questrade equity, cash, holdings and allocation, dividends, and deposits per account
  this year (for watching TFSA/RRSP/FHSA contributions — room is not calculated).
- **Statement reminders** a few days after each statement closes, with "Import now" and "Snooze 2 days".
- **Open with from your banking app**: when a banking app shows a statement and asks which app to open it
  with, pick Finance Tracker. It's imported straight away (or shown for a quick check if anything's unclear)
  and kept, encrypted, in the **statement library**, where you can view it in the app.
- **Missing statements**: the app learns each account's statement cycle and tells you which statements are
  out but not imported yet.
- **Insights** (on the phone, no network): unusually large or first-time charges, possible double charges,
  subscription price rises and new subscriptions, category spikes, interest and fees, overdrafts, months you
  spent more than you earned, late paycheques. You get a notification when an import turns up something new.
- **Goals**: save an amount by a date, keep a category under a monthly limit, or hit a savings rate.
- **AI coach (optional, off by default)**: a monthly check-in with feedback and one-tap goal
  suggestions, and a chat for questions about your money, with the AI of your choice. See below.
- **Summary for Claude**: a Markdown report (nicknames only, no account numbers) to share or copy.
- **Export** transactions as CSV, or all data as a JSON backup. **Delete all data** in Settings.

## Install on your phone

### Option 1: download the build from GitHub (no tools needed)

1. On GitHub, open the repo → **Actions** → the latest green **CI** run → **Artifacts** →
   download **finance-tracker-debug-apk** (a zip). Unzip it to get `app-debug.apk`.
2. Copy `app-debug.apk` to the phone (USB, Google Drive, or email it to yourself).
3. On the phone, open it from Files. Android asks to allow installs from that app ("Install unknown
   apps") — allow it for Files/Drive, then tap **Install**.

### Option 2: USB with adb

1. On the phone: **Settings → About phone →** tap **Build number** 7 times, then **Settings → System →
   Developer options → USB debugging** on.
2. Connect the phone by USB and accept the "Allow USB debugging?" prompt.
3. On the computer (in the project folder):

   ```bash
   ./gradlew assembleDebug
   ```

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   On Windows use `gradlew.bat assembleDebug` and the same `adb install` line.

The debug build installs as **Finance Tracker** with the package `ca.emarshall.finance.debug`, so it can
sit next to a release build.

## Getting your statements

The app never logs in to your bank. Download files on a computer (or phone browser) and share them to
the app.

- **Scotiabank chequing** — Scotia OnLine → your account → **Statements** (e-statements) → download the
  PDF. Or **Download Transactions** → CSV.
- **Scotia Visa** — Scotia OnLine → the card → **Statements** → download the PDF.
- **Triangle Mastercard** — triangle.com/account → **Statements** → download the PDF.

PDF statements are the format verified against real files; CSV and OFX downloads are supported but were
only tested with synthetic files so far.

## Connecting Questrade (read-only)

In the app: **Investments → Connect** walks you through it.

1. Sign in to Questrade on the web → **App Hub / API Centre** → turn on API access.
2. **Register a personal app.** Tick **only the read permissions** (account data and market data). Never
   tick trading permissions — the app has no code that can trade anyway.
3. Under the app, **New manual authorization → Generate new token**. Copy it.
4. Paste it in the app straight away (tokens work once and expire 7 days after they're created).

The app swaps it for a session, stores the rotating refresh token encrypted on the phone, and syncs once
a day so the connection never goes stale. If Questrade ever rejects it, you get a notification to
reconnect with a new token.

## AI coach (optional)

**Insights → Set up AI coach**, or **Settings → AI coach**.

- Pick any AI: Claude, OpenAI (ChatGPT models), Google Gemini, Mistral, DeepSeek, Groq, OpenRouter (hundreds of
  models with one key), or any other OpenAI-compatible service by its HTTPS address. Enter the model name and
  your API key from that provider. API use is billed by the provider (a check-in is usually a few cents); chat
  subscriptions such as Claude.ai or ChatGPT Plus don't include API use.
- When you tap **Monthly check-in** or send a question, the app sends that provider the same privacy-filtered
  summary as "Share summary" (nicknames, monthly totals, categories, merchants, bills, balances, holdings),
  plus flagged items, goals and missing statements. Account and card numbers are never included. The setup
  screen shows exactly what's sent. Nothing is sent in the background, and the app can only reach the one
  provider you picked.
- Turning it off deletes the key from the phone. Chats are kept in memory only.

## Building

Requires JDK 17+ (21 recommended) and the Android SDK (API 37). Android Studio provides both.

```bash
./gradlew testDebugUnitTest
```

```bash
./gradlew lintDebug assembleDebug
```

CI (GitHub Actions) runs unit tests, lint, a debug build and a gitleaks secret scan on every push, and
uploads the debug APK as an artifact.

### Release build

Release builds are minified with R8 and strip all logging. Signing uses a keystore kept **outside** the
repo:

1. Create a keystore once (keep it and its passwords safe; losing it means you can't update the app):

   ```bash
   keytool -genkeypair -v -keystore ~/finance-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias finance
   ```

2. Create `keystore.properties` in the project root (gitignored):

   ```properties
   storeFile=/home/you/finance-release.jks
   storePassword=...
   keyAlias=finance
   keyPassword=...
   ```

3. Build:

   ```bash
   ./gradlew assembleRelease
   ```

   The signed APK is in `app/build/outputs/apk/release/`.

## Project layout

| Path | What |
|---|---|
| `core/` | Pure Kotlin/JVM: parsers (PDF statement text, CSV, OFX/QFX/QBO), duplicate detection, categorizer, transfer matching, recurring bills, reminder dates, analytics, summary export |
| `app/` | Android app: Compose UI, Room + SQLCipher, Tink secrets, Questrade client, WorkManager |
| `docs/` | Spec and import-format notes |
| `samples/` | Your real exports, for local testing only. **Gitignored; never committed.** |

## Privacy notes

- **Storage:** Room database encrypted with SQLCipher. Its random passphrase, and the Questrade tokens,
  are encrypted with Google Tink (AES-256-GCM) whose key lives in the Android Keystore.
- **Network:** an OkHttp interceptor refuses every host except `login.questrade.com` and
  `apiNN.iq.questrade.com`; cleartext HTTP is disabled. If you turn on the optional AI coach, the app may also
  reach the one provider you chose (HTTPS only), and only when you tap an AI button.
- **Statement library:** imported files are encrypted with the same Keystore-backed key and kept in
  no-backup storage; the viewer decrypts to a private temp file only while it's open.
- **Backups:** `allowBackup=false` and data-extraction rules exclude everything from cloud backup and
  device transfer, so the Keystore-bound data never leaves the phone.
- **Screen:** app lock (fingerprint/face/screen lock) on launch and after 5 minutes in the background,
  adjustable; content hidden in Recents and screenshots blocked by default.
- **Exports** (summary, CSV, JSON backup) are plain text you choose to share — keep them private. They use
  account nicknames, and mask long digit runs that look like account numbers.
- **Git:** real statements belong in `samples/`, which is gitignored along with every bank file format
  outside the test-fixture folders. Tests use synthetic fixtures only.
