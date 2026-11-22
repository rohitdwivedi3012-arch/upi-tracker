# UPI Tracker

Track your UPI spending automatically from bank SMS — categorized, summarized, and turned into a
clean offline HTML report. Works entirely on your own device.

The project has two parts that share the same parsing and categorization logic:

| Part | What it is | Use it when |
|---|---|---|
| **Android app** (`android/`) | Reads bank SMS on your phone, stores transactions, shows a live report, captures new payments as they arrive | You want it automatic |
| **Python tool** (repo root) | Turns an SMS backup XML into an HTML report on your PC | You want a quick one-off report, or to test parsing changes |

## Privacy

- The Android app **does not request the `INTERNET` permission** — Android itself guarantees it cannot send your
  data anywhere. Its only permissions are `READ_SMS` and `RECEIVE_SMS`.
- Transactions are stored in the app's private SQLite database; app backup is disabled.
- The HTML report is a single self-contained file with no external scripts, fonts, or trackers.
- `sample_sms.xml` and `sample_report.html` in this repo are **generated fake data** (`make_sample.py`).

## Features

- Extracts amount, debit/credit, payee name or UPI ID, UPI reference number, account (last 4 digits) and bank
- Ignores OTPs, payment/collect requests, cashback and offer messages, failed or reversed payments
- Removes duplicates when both your bank and your UPI app (PhonePe, GPay, Paytm) send an SMS for the same payment
- Merges a merchant's name and UPI ID into one payee (`netflix@hdfcbank` → Netflix)
- Auto-categorizes into Food & Dining, Groceries, Shopping, Travel & Transport, Bills & Recharge, Entertainment,
  Health, Education, Investments, Rent & Housing, Person to Person, Income
- Learns from you: correct one payment's category and every payment to that payee follows
- Report: total spent / received / net, average per day and per payment, weekend share, category donut,
  monthly trend, top 10 payees, spending by weekday, largest payments, and a searchable, sortable transaction table
- Follows your phone's light / dark mode

## Android app

### Install a build
Requirements: Android 10 (API 29) or newer.

**With Android Studio**
1. *File → Open* and select the `android/` folder, then *Trust Project* and wait for Gradle sync.
2. On your phone enable *Developer options* (tap *Settings → About phone → Build number* 7 times) and *USB debugging*.
3. Connect the phone and press **▶ Run**.

**From the command line**
```bash
cd android
./gradlew assembleDebug            # Windows: gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Gradle needs JDK 17+; Android Studio's bundled JDK works (`JAVA_HOME="<Android Studio>/jbr"`).

> The app is meant to be sideloaded for personal use. Google Play restricts SMS permissions to a few app
> categories, so it is not published there.

### Using it
1. Open **UPI Tracker** and allow SMS access. It scans your inbox once (a few seconds).
2. Pick a period: *This month, Last month, Last 3 months, This year, All time*.
3. **Tap any transaction** to set its category — the choice applies to all payments to that payee.
4. **Share report** saves the HTML to `Downloads/UPI Tracker/` and opens the share sheet (WhatsApp, email, Drive…).
5. **Rescan SMS** re-reads the inbox, e.g. after adding a new bank parsing rule. New SMS are picked up automatically.

### Code layout
```
android/app/src/main/java/com/upitracker/
├── Parser.kt        SMS text → Txn (regex rules), payee merging
├── Categorizer.kt   keyword rules + your overrides
├── Store.kt         SQLite storage, inbox scan, de-duplication
├── SmsReceiver.kt   captures new bank SMS in the background
├── Report.kt        builds the HTML report from assets/template.html
└── MainActivity.kt  WebView UI, period picker, category picker, share
```
No third-party libraries — just the Android SDK and Kotlin.

## Python tool

Requires Python 3.10+; no packages to install.

1. On your phone, install **SMS Backup & Restore** (SyncTech) → *Back up* → *Messages only* → copy the `sms-*.xml` file here.
2. Run:
   ```bash
   python report.py sms-20261122.xml -o my_report.html
   python report.py sms-20261122.xml --from 2026-11-01 --to 2026-11-30 -o nov.html
   ```
3. To fix categories, copy `my_categories.example.json` to `my_categories.json` and map a UPI ID or lowercase payee
   name to a category, then re-run.

Try it without your own data: `python make_sample.py && python report.py sample_sms.xml`.

| File | Purpose |
|---|---|
| `upi_parser.py` | SMS text → transactions, de-duplication |
| `categorize.py` | Category rules and overrides |
| `report.py`, `template.html` | HTML report |
| `make_sample.py` | Generates fake test SMS |

Your `sms-*.xml`, `my_categories.json` and `my_report*.html` files are git-ignored.

## Adding support for your bank

Bank SMS formats vary. If a transaction is missed or parsed wrongly, add or adjust a pattern in
`Parser.kt` (app) and `upi_parser.py` (Python) — they mirror each other. The current rules are tested against
synthetic messages in HDFC, SBI, ICICI and PhonePe styles; real-world formats from other banks may need tweaks.

## Limitations

- Android only — iOS does not allow apps to read SMS.
- Only sees payments that generate an SMS; if your bank sends only app notifications or email, those are missed.
- Small shops with short names may be filed as *Person to Person* until you recategorize them once.
