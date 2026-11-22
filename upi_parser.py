"""Parse bank / UPI transaction SMS into structured transactions."""
import re
from dataclasses import dataclass, field
from datetime import datetime

AMOUNT = r"(?:rs\.?|inr|₹)\s*([\d,]+(?:\.\d{1,2})?)"

# Messages that mention money but are not real transactions
IGNORE = re.compile(
    r"\botp\b|one time password|requested money|payment request|collect request|"
    r"cashback|offer|win |reward|pre-approved|loan|emi of|due on|minimum due|"
    r"will be debited|is due|failed|declined|reversed",
    re.I,
)
DEBIT = re.compile(r"debited|\bsent\b|\bpaid\b|spent|transferred to|withdrawn|\bdr\b", re.I)
CREDIT = re.compile(r"credited|received|deposited|\bcr\b", re.I)
UPI_HINT = re.compile(r"\bupi\b|vpa|@[a-z]+|imps|neft|rtgs|ref", re.I)

REF = re.compile(r"(?:upi\s*ref(?:\s*no)?|upi\s*:|ref(?:erence)?(?:\s*no)?|rrn|txn\s*id)[\s.:#-]*(\d{9,16})", re.I)
VPA = re.compile(r"([a-z0-9.\-_]{2,}@[a-z]{2,})", re.I)
PAYEE = re.compile(
    r"(?:\bto\b|\btrf to\b|\bpaid to\b|\bat\b|\bfrom\b|\bby\b)\s+(?:vpa\s+)?"
    r"([A-Za-z0-9][A-Za-z0-9 .&'_@-]{1,40}?)"
    r"(?=\s+(?:on|ref\w*|upi|via|a/c|ac|dated|avl|bal)\b|[.;,(]|\s*$)",
    re.I,
)
OTHER_PARTY = re.compile(r";\s*([^;]{2,40}?)\s+(credited|debited)\b", re.I)
TITLE = re.compile(r"^(?:mr|mrs|ms|miss|dr|shri|smt|m/s)\.?\s+", re.I)
ACCOUNT = re.compile(r"(?:a/c|ac|acct|account|card)[\s.:no]*[x*]*(\d{3,4})", re.I)
DATE_PATTERNS = [
    (re.compile(r"(\d{1,2})[-/](\d{1,2})[-/](\d{2,4})"), "dmy"),
    (re.compile(r"(\d{1,2})[- ]?([A-Za-z]{3})[- ]?(\d{2,4})"), "dbY"),
]

BANKS = {
    "HDFC": "HDFC", "SBI": "SBI", "ICICI": "ICICI", "AXIS": "Axis", "KOTAK": "Kotak",
    "PNB": "PNB", "BOB": "Bank of Baroda", "CANARA": "Canara", "UNION": "Union",
    "IDFC": "IDFC First", "YES": "Yes Bank", "INDUS": "IndusInd", "PAYTM": "Paytm",
    "AIRTEL": "Airtel Payments", "FEDERAL": "Federal",
}

SELF_WORDS = {"your", "a/c", "ac", "account", "bank", "you", "rs", "rs.", "inr", "date"}


@dataclass
class Txn:
    date: datetime
    amount: float
    direction: str          # "debit" | "credit"
    payee: str
    vpa: str
    ref: str
    account: str
    bank: str
    body: str
    category: str = field(default="Uncategorized")


def _bank(sender: str, body: str) -> str:
    text = (sender + " " + body).upper()
    for key, name in BANKS.items():
        if key in text:
            return name
    return "Other"


def _date(body: str, fallback: datetime) -> datetime:
    for rx, kind in DATE_PATTERNS:
        m = rx.search(body)
        if not m:
            continue
        d, mo, y = m.groups()
        y = int(y) + 2000 if len(y) == 2 else int(y)
        try:
            if kind == "dmy":
                dt = datetime(y, int(mo), int(d))
            else:
                dt = datetime.strptime(f"{d} {mo.title()} {y}", "%d %b %Y")
            # keep SMS time of day; trust SMS timestamp if parsed date is way off
            if abs((dt - fallback.replace(hour=0, minute=0, second=0, microsecond=0)).days) <= 3:
                return dt.replace(hour=fallback.hour, minute=fallback.minute)
        except ValueError:
            pass
    return fallback


def _payee(body: str, direction: str, vpa: str) -> str:
    candidates = []
    # ICICI style: "...debited for Rs 2000.00 on 07-Nov-26; Mr John Doe credited. UPI:..."
    # (for a debit the other party is "credited", and vice versa)
    other = OTHER_PARTY.search(body)
    if other and (direction == "debit") == (other.group(2).lower() == "credited"):
        candidates.append(other.group(1))
    candidates += [m.group(1) for m in PAYEE.finditer(body)]
    for raw in candidates:
        name = re.sub(r"^(?:neft|imps|rtgs|upi)\s+(?:from|by|to)?\s*", "", raw.strip(" .-"), flags=re.I)
        name = TITLE.sub("", name).strip()
        low = name.lower()
        if not low or low.split()[0] in SELF_WORDS or re.fullmatch(r"[x*\d ]+", low):
            continue
        if re.match(r"(hdfc|sbi|icici|axis|kotak|your)\b", low) and "bank" in low:
            continue  # that's your own bank, not the other party
        return name.title() if "@" not in name else name.lower()
    return vpa or "Unknown"


def parse_sms(sender: str, body: str, ts: datetime) -> Txn | None:
    if IGNORE.search(body):
        return None
    amt = re.search(AMOUNT, body, re.I)
    if not amt:
        return None
    is_debit, is_credit = DEBIT.search(body), CREDIT.search(body)
    if not (is_debit or is_credit) or not UPI_HINT.search(body):
        return None
    # if both words appear, whichever comes first wins ("debited ... credited to X")
    if is_debit and is_credit:
        direction = "debit" if is_debit.start() < is_credit.start() else "credit"
    else:
        direction = "debit" if is_debit else "credit"

    vpa_m = VPA.search(body)
    vpa = vpa_m.group(1).lower() if vpa_m else ""
    ref_m = REF.search(body)
    acc_m = ACCOUNT.search(body)
    return Txn(
        date=_date(body, ts),
        amount=float(amt.group(1).replace(",", "")),
        direction=direction,
        payee=_payee(body, direction, vpa),
        vpa=vpa,
        ref=ref_m.group(1) if ref_m else "",
        account=acc_m.group(1) if acc_m else "",
        bank=_bank(sender, body),
        body=body,
    )


def dedupe(txns: list[Txn]) -> list[Txn]:
    """Bank + UPI app often send separate SMS for the same payment."""
    seen, out = set(), []
    for t in sorted(txns, key=lambda t: t.date):
        key = ("ref", t.ref) if t.ref else ("fuzzy", t.amount, t.direction, t.date.strftime("%Y%m%d%H"), t.vpa or t.payee)
        if key in seen:
            continue
        seen.add(key)
        out.append(t)
    return out
