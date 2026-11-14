# Import formats

What the importer expects from each bank, and how it was checked. All fixtures under
`core/src/test/resources/fixtures/` are synthetic: same structure as the real files, made-up rows.

## Verified against real statements (November 2026)

33 real PDF e-statements (Jan–Nov 2026) were extracted with PdfBox 2.0.27 (`PDFTextStripper`,
`sortByPosition = true`, the same engine as PdfBox-Android in the app) and parsed by
`StatementTextParser`. **All 33 reconcile to the cent** against the statement's own totals. The
real files never entered git; to re-run the check locally see `RealStatementsCheck`.

### Scotiabank chequing ("Your Preferred Package account") — `StatementLayout.SCOTIA_ACCOUNT`

```
Opening Balance on January 1, 2026 $1,000.00
Closing Balance on January 31, 2026 $2,415.25
Date Transactions withdrawn ($) deposited ($) Balance ($)
Jan 1 Opening Balance 1,000.00
Dec 31 Point of sale purchase 25.00 975.00
Fpos Save ON Foods #1111 Vancouver BCCA
Jan 5 Payroll dep. 2,000.00 2,775.00
Example Employer Ltd
```

- One date per row, then the transaction type, the amount, and the running balance.
- Withdrawn vs deposited columns are indistinguishable in extracted text, so the **sign comes from the
  change in running balance**. If a row ever disagrees with the balance, the sign is guessed from the type
  and a warning is shown.
- The merchant / payee is on the **next line**. POS merchants look like `Fpos <20-char name><city> BCCA`
  (city often glued to the name, `BCCA` = province + country).
- Rows can be dated just before the period (`Dec 31` on a January statement).
- A promotional side box can append text after the balance and after the merchant; it is ignored.
- Waived fees appear as `0.00` and are skipped.
- Row types seen: Point of sale purchase, Withdrawal / Deposit (Interac e-Transfer), Payroll dep.,
  IO Transfer to (investments), MB-Transfer from, Credit Card/LOC payment, Misc. payment (Questrade),
  Auto insurance (ICBC), Hydro bill, Error correction, Returned NSF cheque, NSF cheque charge,
  (Shared) ABM withdrawal.
- Balance snapshots: end of each day, plus the closing balance.

### Scotiabank Visa (Scotia Momentum) — `StatementLayout.SCOTIA_VISA`

```
Statement Period Dec 16, 2025 - Jan 15, 2026
New Balance = $185.66
Previous balance, Dec 15/25 $100.00
REF.# DATE DATE DETAILS AMOUNT($)
001 Dec 15 Dec 16 EXAMPLE HARDWARE         VANCOUVER    BC 45.10
004 Jan 5 Jan 5 SCOTIABANK TRANSIT 10520 VANCOUVER    BC 100.00-
```

- REF#, transaction date, posting date, details, amount. The **transaction date** is used.
- Charges are positive; payments and credits have a **trailing minus**. Converted to the app's convention
  (purchases negative, payments positive).
- Long details wrap onto a second line; the wrapped part is dropped (the first line is enough).
- Payments from the chequing account appear as `SCOTIABANK TRANSIT …`.
- Interest: `INTEREST CHARGES-PURCHASE`.
- Reconciles: previous balance − rows = new balance.

### Canadian Tire Bank Triangle Mastercard — `StatementLayout.TRIANGLE`

```
For the period: October 6, 2026 to November 5, 2026
Balance from your last statement $50.00
Your New Balance $84.88
Oct 21 Oct 22 CTFS.COM/PAYMENTS OAKVILLE ON -50.00 If your card has been stolen or lost: ...
Oct 09 Oct 12 PETRO-CANADA 12345 VANCOUVER BC 60.00 • excluding charges for optional products ...
2.99 EUR @ 1.632107023
```

- Two dates, description, amount. Payments and returns are **negative**; purchases positive.
- The page has a second text column on the right; its text lands on the same line **after** the amount.
  The first money token after the description is the amount; the rest is ignored.
- Foreign-currency rows add a line like `2.99 EUR @ 1.632…`; the CAD amount on the row is used.
- **"Details of your Canadian Tire store purchases"** lists individual store items with dates. Parsing
  stops at that heading so items aren't counted twice.
- Payments appear as `CTFS.COM/PAYMENTS OAKVILLE ON`; Canadian Tire stores as `CDN TIRE STORE #…`.

## Not yet seen: downloads (CSV / OFX / QFX / QBO)

No CSV or OFX downloads were provided, so these paths are built from publicly reported formats and
tested only against synthetic files. They are in place for when exports are downloaded instead of
statements:

- Scotiabank CSV: header-based detection handles `Date, Description, Sub-description, Debit, Credit,
  Balance`, the `Type of Transaction` + unsigned `Amount` layout, and the older headerless
  `date, amount, "-", type, description` layout.
- Canadian Tire CSV: header-based detection of `TRANSACTION DATE`, `POSTED DATE`, `DESCRIPTION`,
  `AMOUNT` (purchases positive for cards → inverted).
- Anything else: the column-mapping screen, saved per account as a CsvProfile.
- OFX/QFX/QBO: SGML v1 and XML v2, bank and credit card statements; repeated FITIDs inside one file
  (a reported QFX quirk) are dropped.

If a real CSV/OFX export disagrees with this, add a synthetic fixture with its structure and adjust
`CsvProfileInference` / `OfxParser`.

## Merchant cleaning and seed rules

On the 941 real rows, the seed rules categorise about 81%; the rest are one-off local merchants and
purchases abroad, which "Always do this for <merchant>" handles. Seed rules only name chains and
public companies, never anything personal.
