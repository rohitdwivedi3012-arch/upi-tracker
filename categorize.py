"""Rule-based categorization + user overrides (learned from your corrections)."""
import json
import re
from pathlib import Path

OVERRIDES_FILE = Path(__file__).with_name("my_categories.json")

RULES = {
    "Food & Dining": ["swiggy", "zomato", "dominos", "pizza", "mcdonald", "kfc", "burger", "cafe",
                      "restaurant", "starbucks", "chai", "dhaba", "eatclub", "biryani", "haldiram"],
    "Groceries": ["blinkit", "zepto", "bigbasket", "grofers", "dmart", "instamart", "jiomart",
                  "kirana", "reliance fresh", "more retail", "milk", "dairy", "vegetable"],
    "Shopping": ["amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "tatacliq", "decathlon",
                 "croma", "reliance digital", "lenskart"],
    "Travel & Transport": ["uber", "ola", "rapido", "irctc", "redbus", "makemytrip", "goibibo", "indigo",
                           "metro", "fastag", "petrol", "fuel", "hpcl", "bpcl", "iocl", "indianoil", "shell", "parking"],
    "Bills & Recharge": ["airtel", "jio", "vodafone", "vi ", "bsnl", "electricity", "bescom", "tneb",
                         "msedcl", "tatapower", "adani", "gas", "broadband", "act fibernet", "dth", "tata play",
                         "billdesk", "bbps", "recharge", "water"],
    "Entertainment": ["netflix", "hotstar", "prime", "spotify", "bookmyshow", "pvr", "inox", "youtube",
                      "sonyliv", "zee5", "gaming", "steam"],
    "Health": ["pharmacy", "apollo", "medplus", "1mg", "pharmeasy", "netmeds", "hospital", "clinic",
               "diagnostic", "lab", "doctor", "practo"],
    "Education": ["school", "college", "university", "udemy", "coursera", "byju", "unacademy", "fees", "tuition"],
    "Investments": ["zerodha", "groww", "upstox", "mutual fund", "sip", "indmoney", "kuvera", "nps", "lic"],
    "Rent & Housing": ["rent", "nobroker", "society", "maintenance", "housing"],
}

PERSON_VPA = re.compile(r"^(\d{10}|[a-z.]+\d*)@(ybl|okaxis|okhdfcbank|oksbi|okicici|paytm|ibl|axl|upi|apl)$")


def load_overrides() -> dict:
    if OVERRIDES_FILE.exists():
        return json.loads(OVERRIDES_FILE.read_text(encoding="utf-8"))
    return {}


def categorize(txn, overrides: dict) -> str:
    if txn.direction == "credit":
        return "Income / Received"
    for key in (txn.vpa, txn.payee.lower()):
        if key and key in overrides:
            return overrides[key]
    text = f"{txn.vpa} {txn.payee}".lower()
    for cat, words in RULES.items():
        if any(w in text for w in words):
            return cat
    if txn.vpa and PERSON_VPA.match(txn.vpa):
        return "Person to Person"
    if txn.payee and txn.payee != "Unknown" and len(txn.payee.split()) <= 3 and "@" not in txn.payee:
        return "Person to Person"
    return "Uncategorized"
