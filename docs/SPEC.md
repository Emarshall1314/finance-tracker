# Finance Tracker for Android: build spec for Claude Code

> **How to use this file:** On your computer, install Android Studio, Git, the GitHub CLI (then run `gh auth login`) and Claude Code. Make an empty folder, put this file in it, open Claude Code in that folder, and say: *"Read finance-tracker-spec.md and follow it."* Claude Code will stop at every ✋ to check in with you.

Written November 2026. Where this spec names versions or APIs, check them against current docs before relying on them.

---

You're building a private, single-user Android app that tracks my money. Read this whole file before you start. Work through the milestones in order, commit as you go, and stop at every ✋ checkpoint to check in with me.

## About me and my accounts

- I live in British Columbia, Canada. Everything is in CAD unless an account says otherwise. Use the phone's time zone (America/Vancouver).
- **Scotiabank**: my bank accounts (and possibly a credit card). Data comes in as files I export and import.
- **Canadian Tire Bank**: my Triangle Mastercard. Data comes in as files or PDF statements I import.
- **Questrade**: my investment accounts. Connect through Questrade's official API, read-only.
- Android only for now. No iOS or web, but keep the business logic portable (see Architecture).
- My computer may be Windows. Use the Gradle wrapper and cross-platform commands.

## Non-negotiable rules

1. **No bank passwords, ever.** Bank data only enters the app as files I import. Never build screen scraping, and never ask for bank logins.
2. **Questrade is read-only.** Use read permissions only. Never build anything that places, changes or cancels orders.
3. **Everything stays on the phone.** No backend, no cloud sync, and no analytics, crash-reporting, ad or tracking SDKs. The only network traffic is to Questrade's login and API servers. Add an OkHttp interceptor that refuses any other host.
4. **No real financial data in git.** My real exports go in `samples/`, which is gitignored. Tests use synthetic fixtures that copy the *structure* of my files with made-up rows. Never commit tokens, account numbers, statements or screenshots of real data.
5. **Don't ask me to paste my Questrade token into this chat.** I'll enter it in the app on my phone. Test the API client against mocked responses.
6. **Money is `Long` cents** (`BigDecimal` while parsing). Never use `Double` for money, including when parsing Questrade's JSON.
7. Before adding a dependency that isn't listed below, tell me why.

## Tech stack

- Kotlin, Jetpack Compose, Material 3 (dynamic colour and dark mode), a single activity, Navigation Compose.
- MVVM with repositories, Hilt, coroutines and Flow, KSP (not kapt).
- Room for the database, encrypted with SQLCipher for Android (`net.zetetic:sqlcipher-android`). Generate a random database passphrase and store it encrypted (next point).
- Secrets (the database passphrase and Questrade tokens): Jetpack DataStore plus Google Tink AEAD, with Tink's master key in the Android Keystore. **Do not use `androidx.security:security-crypto` / `EncryptedSharedPreferences`**, which are deprecated.
- Networking: OkHttp, Retrofit and kotlinx.serialization.
- Background sync and reminders: WorkManager.
- Charts: Vico (for Compose).
- PDF text extraction, only if Canadian Tire statements need it: PdfBox-Android (`com.tom-roush:pdfbox-android`).
- App lock: `androidx.biometric` BiometricPrompt with a device-credential fallback.
- Build: Gradle Kotlin DSL and a version catalog. Use the latest stable AGP, Kotlin, Compose BOM, and compile/target SDK at the time you build. minSdk 26.
- Tests: JUnit, kotlinx-coroutines-test, Turbine, OkHttp MockWebServer.

## Architecture

```
finance-tracker/
├── core/          Pure Kotlin/JVM module with no Android imports: models, CSV and OFX
│                  parsers, statement-text parser, categorizer, transfer matcher,
│                  recurring detection, reminder date maths, summary export
├── app/           Android: UI, Room + SQLCipher, DataStore + Tink, Questrade client,
│                  WorkManager jobs, notifications, file import, PDF text extraction
├── docs/SPEC.md   a copy of this file
├── samples/       gitignored: my real exports, never committed
├── CLAUDE.md      project conventions plus the non-negotiable rules, for future sessions
└── README.md
```

Keeping the logic in `core` makes it fast to test on the JVM, and easy to share with iOS later (for example with Kotlin Multiplatform).

## Data model (change it if the samples give you a reason)

- **Account**: institution (Scotiabank, Canadian Tire Bank or Questrade), nickname, type (chequing, savings, credit card, line of credit, TFSA, RRSP, FHSA, margin, cash, other), currency, optional last 4 digits, optional statement closing day (1–31), reminder settings.
- **Transaction**: account, date (`LocalDate`), amount in cents (negative means money left this account), currency, raw description, cleaned merchant name, category, transfer link (nullable), source (CSV, OFX, PDF or Questrade), import batch, fingerprint, OFX FITID (nullable), notes.
- **Category**: name, kind (expense, income or transfer), icon, colour, built-in or mine.
- **CategoryRule**: text to match (contains or regex), target category, priority, and whether it was learned from one of my edits.
- **ImportBatch**: account, file name, SHA-256 of the file, format, date range, counts (rows read, new, duplicates), timestamp. A whole batch can be undone.
- **CsvProfile** (per account): delimiter, header row, date format, which columns hold the date, description, and amount or debit + credit, and the sign convention.
- **BalanceSnapshot**: account, date, balance (from running-balance columns, OFX ledger balances or Questrade).
- **Questrade**: accounts, balance snapshots (per currency and combined CAD), position snapshots, activities.
- **Reminder**: account, closing day, days-after offset, time of day, enabled, next fire time.

## Milestones

### M0: Plan, project and repo ✋

1. Read this file. Check my environment (JDK, Android SDK, `adb`, `git`, `gh` and whether it's signed in) and tell me what's missing.
2. Reply with your plan and these questions:
   - App name and package name.
   - Repo name (default `finance-tracker`) and visibility (default **private**).
   - Which Scotiabank, Canadian Tire Bank and Questrade accounts I have.
   - My statement closing days.
   - My phone model and Android version.
3. After I answer, create the project with:
   - `.gitignore`: Android defaults, plus `samples/`, `*.jks`, `*.keystore`, `keystore.properties`, `local.properties`, and any `.csv`, `.ofx`, `.qfx`, `.qbo` or `.pdf` outside the test-fixture folders.
   - `CLAUDE.md`, `README.md` and `docs/SPEC.md`.
4. Create the GitHub repo with `gh repo create <name> --private --source=. --push`.
5. Add GitHub Actions CI: lint, unit tests, a debug build, and a secret scan (for example gitleaks).
6. Make small, logical commits for the rest of the project.

### M1: A skeleton that runs on my phone ✋

- Bottom navigation: Overview, Transactions, Investments, Settings.
- Overview starts as a setup checklist: add accounts, import first statements, connect Questrade, set reminders.
- App lock on launch and after 5 minutes in the background (adjustable).
- A setting to hide the app's content in recent apps and block screenshots (`FLAG_SECURE`), on by default.
- Add accounts by hand: nickname, type, last 4 digits (optional).
- Turn off backups and device transfer for all app data: `android:allowBackup="false"` plus data-extraction rules that exclude everything. Cleartext traffic off.
- Tell me exactly how to install the build: USB with `adb install`, or copying the APK to the phone.

### M2: Statement import ✋ (ask me for samples first)

Before you write any parser, ask me for one real export from each account. I'll put them in `samples/`. Study them, tell me what you found, then build synthetic fixtures with the same structure.

What I know about the exports (verify all of it against the samples):

- **Scotiabank**: on a desktop browser, Scotia OnLine → the account → Download Transactions → CSV. The mobile app can't export. The CSVs reportedly have separate Debit and Credit columns. Scotiabank also offers Excel and Quicken (QFX) downloads, and QFX has been reported to duplicate some rows.
- **Canadian Tire Bank**: the online account is at triangle.com/account. Canadian Tire says you can download your information into tools like QuickBooks and Microsoft Money, so expect QBO/OFX (maybe CSV). Monthly e-statements are PDFs. If there's no usable download, parse the PDF: extract text with PdfBox-Android in `app`, and parse the text in `core`.

Import requirements:

- **Formats**: CSV, OFX/QFX/QBO, and the PDF path above if needed.
  - CSV: a built-in preset for each bank, plus a column-mapping screen for anything new, saved as a CsvProfile.
  - OFX: SGML v1 and XML v2, for both bank and credit-card statements.
- **Entry points**: a file picker (Storage Access Framework, so no storage permission), plus "Share to" and "Open with" from Files, Drive or Gmail.
- **Flow**:
  1. Detect the format and bank.
  2. Confirm the account.
  3. Preview the date range, row count, new vs duplicate rows, and totals.
  4. Import, categorize, and show a summary.

  A whole import can be undone.
- **Signs**: money out is negative. Credit card purchases are negative; payments and refunds are positive.
- **Duplicates**: warn if the same file (by SHA-256) was imported before. Skip rows already in the database. Use the FITID for OFX files. For other files, fingerprint account + date + amount + normalized description + occurrence number, so two identical coffees on the same day both survive.
- **Messy files**: handle quoted fields containing commas, a BOM, blank trailing lines, `$` signs, thousands separators, negatives shown as parentheses or CR/DR, and ambiguous dates (store the date format in the profile).
- **Balances**: save them whenever the file has them.

### M3: Categories, transfers and subscriptions

- **Default categories**:
  - Spending: Groceries, Dining & Coffee, Fuel, Auto & Parts, Transit & Parking, Shopping, Home & Hardware, Bills & Utilities, Phone & Internet, Insurance, Health & Pharmacy, Entertainment, Subscriptions, Travel, Gifts & Donations, e-Transfers Sent, Fees & Interest, Cash & ATM, Other.
  - Income: Paycheque, Investment Income, Refunds, Other Income.
  - Transfers: Card Payment, Between My Accounts, To/From Investments.
- **Seed rules**:
  - Common BC and Canadian merchants: Save-On-Foods, Superstore, Safeway, Costco, Petro-Canada, Shell, Esso, Chevron, Tim Hortons, Starbucks, ICBC, BC Hydro, FortisBC, Telus, Rogers, Bell, Freedom, Netflix, Spotify, Shoppers Drug Mart, London Drugs, and so on.
  - Interest charges, annual fees and service charges.
  - Extend the list from my samples.
- **Merchant names**: clean them up (strip store numbers, location suffixes and bank prefixes), based on what the samples actually contain.
- **Learning**: when I recategorize a transaction, offer "Always do this for <merchant>". That creates a rule and applies it to past transactions too.
- **Transfer matching**: pair opposite amounts between my own accounts within ±5 days, such as Scotiabank paying the Triangle card or deposits into Questrade. Keywords can help. Transfers never count as income or spending. Let me confirm or unlink pairs.
- **Recurring detection**: the same merchant with a similar amount (within about 10%), roughly monthly, at least 3 times. Show these in a "Bills & subscriptions" list with the monthly cost and next expected date.

### M4: Overview dashboard

- A month picker, plus cards for income, spending, net saved and savings rate.
- Spending by category. Tapping a category shows its transactions.
- 12 months of income vs spending as bars, with a net line.
- Top merchants, fees and interest paid, and upcoming bills.
- Net worth: latest bank balances + Questrade total equity − card balances, with a trend over time.
- Data freshness for each account (for example "data through Oct 31"), with a warning when it's more than 35 days old.
- Transactions screen: search; filter by account, category and date; edit the category; add notes.

### M5: Questrade (read-only) ✋

**Connecting, option A (build this first).** A guided screen walks me through Questrade's site:

1. Turn on API access if it's off.
2. Register a personal app in the API Centre. Tell me to tick only the read permissions (account data and market data).
3. Create a manual authorization and generate a token.
4. Paste the token into the app.

Token handling:

- Redeem the token at `https://login.questrade.com/oauth2/token?grant_type=refresh_token&refresh_token=<token>`. The response contains `access_token`, `token_type`, `expires_in`, `refresh_token` and `api_server`.
- **Refresh tokens are single-use.** Each refresh returns a new refresh token and invalidates the old one. Save the new one (encrypted and atomically) before using the access token. Guard refreshes with a Mutex so two refreshes can never run at once.
- Use `api_server` from the token response as the base URL. Use `expires_in` rather than hard-coding a lifetime.
- On a 401, refresh once and retry. If the refresh fails, mark Questrade as disconnected and notify me to reconnect.
- Questrade's manually generated tokens expire 7 days after they're created, so redeem a new one right away. Run a daily background sync (WorkManager, network required) so the refresh token never goes stale.

**Connecting, option B (stretch).** "Sign in with Questrade" in a Custom Tab, using Questrade's implicit flow for mobile apps: `https://login.questrade.com/oauth2/authorize?client_id=<consumer key>&response_type=token&redirect_uri=<uri>`. The tokens come back in the URL fragment. Only build this if Questrade accepts a redirect URI the app can catch; otherwise keep option A.

**Data:**

- Endpoints: `GET /v1/accounts`, `/v1/accounts/{id}/balances`, `/v1/accounts/{id}/positions`, and `/v1/accounts/{id}/activities?startTime=…&endTime=…`.
- Activities allow at most 31 days per request, so chunk requests into 30-day windows. Backfill 12 months on the first sync, then sync incrementally.
- Respect `X-RateLimit-Remaining` and `X-RateLimit-Reset`, and back off on HTTP 429.
- Parse money from the JSON without going through `Double`.
- Personal (non-partner) API access can't place trades anyway, but still request read permissions only.
- Check the details against the current docs at questrade.com/api/documentation.

**Investments screen:**

- Total equity (combined CAD), plus equity and cash for each account.
- Positions: symbol, quantity, market value, % of portfolio, open P&L. An allocation chart.
- Recent activity, and dividends received over the last 12 months and this year.
- Deposits per account this year, so I can watch TFSA/RRSP/FHSA contributions. Don't try to calculate contribution room.
- Deposits and withdrawals feed into transfer matching. Dividends and interest count as Investment Income.

### M6: Statement reminders

- **Settings per account**: statement closing day (1–31), "remind me N days after" (default 2), time of day (default 9:00), and on/off. Also an optional general monthly "import your statements" reminder.
- **Scheduling**: WorkManager, with one unique one-time job per reminder and an initial delay up to the next fire time. When a job fires, it posts the notification and schedules the next one. Recompute all reminders on app start and whenever settings change. Don't use exact alarms (no `SCHEDULE_EXACT_ALARM`).
- **Date maths** (in `core`, with `java.time`): if the closing day is 29–31, use the month's last day in shorter months. Handle leap years, DST changes, and offsets that cross into the next month.
- **The notification**: "Scotiabank statement should be ready — download it on your computer and import it." Add two actions: "Import now" (opens the import for that account) and "Snooze 2 days".
- **Skipping**: if an import already covers that statement period, skip that month's reminder.
- **Permission**: ask for `POST_NOTIFICATIONS` (Android 13+) when I first turn on a reminder, explain why, and show a banner if I deny it.
- A "Send test notification" button.

### M7: Summary for Claude, and backup

- **"Share summary"** makes a Markdown report for a period I choose (default: the last 6 months). It's for pasting into Claude for financial advice.
  - It opens with context: BC, Canada, amounts in CAD, and the period covered.
  - It includes:
    - monthly income, spending and net
    - spending by category per month
    - top merchants
    - bills and subscriptions
    - fees and interest
    - balances and net worth
    - Questrade holdings, allocation, dividends, and contributions by account type
  - Use account nicknames only, never account numbers. Share it through the share sheet or copy it to the clipboard.
- An option to also export all transactions as CSV (without account numbers).
- **"Export all data"** to a file I choose (Storage Access Framework), for my own backup. Restoring from it is a stretch goal.
- **"Delete all data"** in Settings, with a confirmation step.

### M8: Polish and release ✋

- Settings to manage categories and rules, the app lock timeout, and screenshot blocking.
- Basic accessibility: content descriptions, and layouts that survive large font sizes.
- A release build signed with a keystore kept outside the repo (`keystore.properties`, gitignored), with R8 on. Release builds never log tokens or financial data.
- README: building, installing, connecting Questrade, exporting from each bank, and privacy notes.

## Tests (at minimum)

- CSV and OFX parsers against synthetic fixtures for each bank, including messy files.
- Duplicate detection with overlapping imports and identical same-day rows.
- Sign handling for credit cards.
- Transfer matching and recurring detection.
- Reminder fire times: the 31st in short months, Feb 29 2028, DST changes, and offsets that cross into the next month.
- The Questrade client on MockWebServer: token rotation, the refresh Mutex under concurrent calls, 401 retry, 429 backoff, and 31-day chunking.
- A snapshot test of the summary export.

## Done means

`./gradlew testDebugUnitTest assembleDebug` passes in CI, and the app installs on my phone. On the phone, I can:

- connect Questrade
- import a file from each bank
- see the dashboard
- get a test reminder
- share a summary

## Not in scope for now

iOS, web, cloud sync, multiple users, budgets, automatic bank connections, trading, in-app AI, and publishing on the Play Store.

---

## Amendments (November 14, 2026)

Requested by the owner after the first build:

1. **Open with from banking apps.** When a banking app shows a statement and asks which app to open it
   with, Finance Tracker is offered (VIEW/SEND/SEND_MULTIPLE for PDF, CSV and OFX types). Choosing it
   imports the statement straight away when the bank and account are clear and the statement reconciles;
   anything uncertain goes to the review screens. Imported files are kept, encrypted, in a statement library.
2. **Missing statements.** The app infers each account's statement cycle and lists statements that should be
   available but haven't been imported (Overview, Insights, and the statement library).
3. **Claude coach, unusual activity and goals.**
   - Unusual-activity warnings and goal tracking run on the phone (no network) and notify after imports.
   - An **opt-in** Claude coach (monthly check-in with goal suggestions, and chat) uses the owner's own
     Anthropic API key and the official Anthropic Java SDK. It sends only the privacy-filtered summary
     (nicknames, masked numbers) and only when the owner taps a Claude button.
   - This replaces "in-app AI" in *Not in scope*, and adds one exception to rule 3: while the coach is turned
     on, the app may also talk to `api.anthropic.com`. It is off by default.

### Amendment (November 15, 2026)

- **Any AI.** The coach works with the AI the owner chooses: Claude (official Anthropic SDK) or any service
  with an OpenAI-compatible Chat Completions API (OpenAI, Google Gemini, Mistral, DeepSeek, Groq,
  OpenRouter, or a custom HTTPS endpoint). The rule-3 exception now covers that one chosen host, HTTPS only.
- **Complete months only.** The dashboard shows a month only once every statement account covers it.
- **Income from bank accounts only.** Card refunds reduce spending; activity inside investment accounts is
  neither income nor spending.
