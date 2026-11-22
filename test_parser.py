"""Real-world SMS formats (personal details replaced). Run: python test_parser.py"""
from datetime import datetime

from categorize import categorize
from upi_parser import parse_sms

TS = datetime(2026, 11, 7, 14, 5)

CASES = [
    # (sender, body, expected fields)
    ("JD-ICICIT",
     "ICICI Bank Acct XX111 debited for Rs 2000.00 on 07-Nov-26; Mr John Doe credited. UPI:631165706384. "
     "Call 18002662 for dispute. SMS BLOCK 111 to 9215676766.",
     dict(amount=2000.0, direction="debit", payee="John Doe", ref="631165706384", account="111", bank="ICICI",
          category="Person to Person")),
    ("JD-ICICIT",
     "ICICI Bank Acct XX111 debited for Rs 349.00 on 07-Nov-26; swiggy@icici credited. UPI:631165706385. "
     "Call 18002662 for dispute. SMS BLOCK 111 to 9215676766.",
     dict(amount=349.0, direction="debit", payee="swiggy@icici", category="Food & Dining")),
    ("JD-ICICIT",
     "Dear Customer, Acct XX111 is credited with Rs 820.00 on 18-Nov-26 from JANE R SMITH. UPI:632233752881-ICICI Bank.",
     dict(amount=820.0, direction="credit", payee="Jane R Smith", ref="632233752881", account="111", bank="ICICI",
          category="Income / Received")),
    ("AX-HDFCBK",
     "Sent Rs.500.00\nFrom HDFC Bank A/C *4521\nTo RAHUL KUMAR\nOn 07/11/26\nRef 432198765432\nNot You? Call 18002586161",
     dict(amount=500.0, direction="debit", payee="Rahul Kumar", ref="432198765432", bank="HDFC")),
    ("VK-PHONPE", "Paid ₹1,250.50 to Blinkit (blinkit.payu@hdfcbank) UPI Ref No 432112345678",
     dict(amount=1250.5, direction="debit", category="Groceries")),
    ("AX-HDFCBK", "123456 is your OTP for UPI payment of Rs.500. Do not share.", None),
    ("VM-PAYTMB", "Get Rs.100 cashback on your next UPI payment! Offer ends soon.", None),
]

failed = 0
for sender, body, want in CASES:
    t = parse_sms(sender, body, TS)
    if want is None:
        ok = t is None
        got = "ignored" if ok else vars(t)
    else:
        if t:
            t.category = categorize(t, {})
        got = {k: getattr(t, k, None) for k in want} if t else None
        ok = got == want
    failed += not ok
    print(("PASS" if ok else "FAIL"), body[:60].replace("\n", " "), "" if ok else f"\n   want {want}\n   got  {got}")

from upi_parser import Txn

# ICICI truncates payee names to ~15 characters, so keywords must work on partial words
CATEGORY_CASES = [
    ("Om Sai Medical", "", "Health"),
    ("Ganraj Ice Crea", "", "Food & Dining"),
    ("Gajanan Enterpr", "", "Shops & Services"),
    ("Bholanath Prasad", "", "Person to Person"),   # contains "ola" but is not Ola cabs
    ("Ola", "olacabs@ybl", "Travel & Transport"),
    ("Priya Sharma", "", "Person to Person"),
]
for payee, vpa, want in CATEGORY_CASES:
    t = Txn(TS, 100.0, "debit", payee, vpa, "", "", "ICICI", "")
    got = categorize(t, {})
    ok = got == want
    failed += not ok
    print(("PASS" if ok else "FAIL"), f"category of {payee!r}", "" if ok else f"-> want {want}, got {got}")

total = len(CASES) + len(CATEGORY_CASES)
print(f"\n{total - failed}/{total} passed")
raise SystemExit(failed)
