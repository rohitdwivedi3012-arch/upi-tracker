"""Rule-based categorization + user overrides (learned from your corrections)."""
import json
import re
from pathlib import Path

OVERRIDES_FILE = Path(__file__).with_name("my_categories.json")

RULES = {
    "Food & Dining": ["swiggy", "zomato", "dominos", "pizza", "mcdonald", "kfc", "burger", "cafe",
                      "restaurant", "starbucks", "chai", "dhaba", "eatclub", "biryani", "haldiram",
                      "ice cr", "icecream", "bakery", "bakers", "sweet", "juice", "hotel", "canteen", "foods",
                      "kitchen", "snack", "dosa", "bhel", "tea "],
    "Groceries": ["blinkit", "zepto", "bigbasket", "grofers", "dmart", "instamart", "jiomart",
                  "kirana", "reliance fresh", "more retail", "milk", "dairy", "vegetable", "grocer",
                  "general st", "provision", "supermarket", "super market", "fruit"],
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
               "diagnostic", "lab", "doctor", "practo", "medical", "medico", "chemist", "pharma", "dental", "optical"],
    "Education": ["school", "college", "university", "udemy", "coursera", "byju", "unacademy", "fees", "tuition"],
    "Investments": ["zerodha", "groww", "upstox", "mutual fund", "sip", "indmoney", "kuvera", "nps", "lic"],
    "Rent & Housing": ["rent", "nobroker", "society", "maintenance", "housing"],
}

# Words that mark a business rather than a person ("Gajanan Enterpr" = truncated "Enterprises")
MERCHANT_WORDS = ["enterpr", "traders", "trading", "store", "shop", "agenc", "center", "centre", "services",
                  "pvt", "ltd", "llp", "industr", "emporium", "bazaar", "collection", "garment", "electronic",
                  "mobile", "hardware", "solutions", "works", "and sons", "& sons"]

# keywords match at the start of a word only, so "ola" doesn't match "Bholanath"
_CAT_RX = {cat: re.compile("|".join(r"(?<![a-z0-9])" + re.escape(w) for w in words)) for cat, words in RULES.items()}
_MERCHANT_RX = re.compile("|".join(r"(?<![a-z0-9])" + re.escape(w) for w in MERCHANT_WORDS))

PERSON_VPA = re.compile(r"^(\d{10}|[a-z.]+\d*)@(ybl|okaxis|okhdfcbank|oksbi|okicici|paytm|ibl|axl|upi|apl)$")


def load_overrides() -> dict:
    if OVERRIDES_FILE.exists():
        return json.loads(OVERRIDES_FILE.read_text(encoding="utf-8"))
    return {}


def categorize(txn, overrides: dict) -> str:
    if txn.direction == "credit":
        return "Income / Received"
    for key in (txn.vpa, txn.payee.lower()):
        if key and key != "unknown" and key in overrides:
            return overrides[key]
    text = f"{txn.vpa} {txn.payee}".lower()
    for cat, rx in _CAT_RX.items():
        if rx.search(text):
            return cat
    if _MERCHANT_RX.search(text):
        return "Shops & Services"
    if txn.vpa and PERSON_VPA.match(txn.vpa):
        return "Person to Person"
    if txn.payee and txn.payee != "Unknown" and len(txn.payee.split()) <= 3 and "@" not in txn.payee:
        return "Person to Person"
    return "Uncategorized"
