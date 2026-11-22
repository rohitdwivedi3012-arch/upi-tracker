# UPI Spend Tracker — Phase 1 (PC prototype)

1. On your Android phone install **SMS Backup & Restore** (SyncTech) → Back up → Messages only → save the XML.
2. Copy the `sms-*.xml` file to this folder.
3. Run:
   ```
   python report.py sms-20261122.xml -o my_report.html
   python report.py sms-20261122.xml --from 2026-11-01 --to 2026-11-30 -o nov.html
   ```
4. Fix wrong categories: copy `my_categories.example.json` to `my_categories.json` and map a
   UPI ID or payee name (lowercase) to any category name. Re-run the report.

Files: `upi_parser.py` (SMS → transactions), `categorize.py` (rules), `report.py` + `template.html` (HTML report),
`make_sample.py` (fake test data). Everything runs offline; no data leaves your PC.
