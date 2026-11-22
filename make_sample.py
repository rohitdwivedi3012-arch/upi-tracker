"""Create a fake SMS-Backup-&-Restore XML (sample_sms.xml) for testing."""
import random
from datetime import datetime, timedelta
from xml.sax.saxutils import quoteattr

random.seed(7)
payees = [("swiggy@icici", "Swiggy", 150, 700), ("zomato@hdfcbank", "Zomato", 200, 900),
          ("blinkit.payu@hdfcbank", "Blinkit", 100, 1200), ("uber.india@axisbank", "Uber", 90, 450),
          ("amazonpay@apl", "Amazon", 300, 4000), ("airtelpayments@airtel", "Airtel", 299, 999),
          ("9876543210@ybl", "Rahul Kumar", 100, 3000), ("netflix@hdfcbank", "Netflix", 199, 649),
          ("apollopharmacy@icici", "Apollo Pharmacy", 120, 1500), ("sharmastores@okaxis", "Sharma Stores", 40, 600),
          ("irctc@sbi", "IRCTC", 500, 3500)]
start = datetime(2026, 8, 1)
out = []


def add(sender, body, ts):
    out.append(f'<sms protocol="0" address={quoteattr(sender)} date="{int(ts.timestamp() * 1000)}" type="1" body={quoteattr(body)} read="1" />')


for _ in range(140):
    ts = start + timedelta(days=random.uniform(0, 113), hours=random.uniform(8, 22))
    vpa, name, lo, hi = random.choice(payees)
    amt = round(random.uniform(lo, hi), random.choice([0, 2]))
    ref = str(random.randint(10**11, 10**12 - 1))
    d = ts.strftime("%d-%m-%y")
    style = random.randint(0, 2)
    if style == 0:
        add("AX-HDFCBK", f"Sent Rs.{amt:.2f}\nFrom HDFC Bank A/C *4521\nTo {name}\nOn {ts:%d/%m/%y}\nRef {ref}\nNot You? Call 18002586161/SMS BLOCK UPI to 7308080808", ts)
    elif style == 1:
        add("VM-SBIUPI", f"Dear UPI user A/C X7788 debited by {amt:.2f} on date {ts:%d%b%y} trf to {name} Refno {ref}. If not u? call 1800111109. -SBI", ts)
        # pretend Rs. format for parser
        out[-1] = out[-1].replace(f"debited by {amt:.2f}", f"debited by Rs.{amt:.2f}")
    else:
        add("JD-ICICIT", f"ICICI Bank Acct XX903 debited for Rs {amt:,.2f} on {d}; {vpa} credited. UPI:{ref}. Call 18002662 for dispute.", ts)
    if random.random() < 0.25:   # duplicate notification from UPI app
        add("VK-PHONPE", f"Paid Rs.{amt:.2f} to {name} ({vpa}) UPI Ref No {ref}", ts + timedelta(seconds=20))

for m in range(8, 12):
    ts = datetime(2026, m, 1, 10)
    add("AX-HDFCBK", f"Rs.65000.00 credited to HDFC Bank A/c XX4521 on {ts:%d-%m-%y} by NEFT from ACME TECH PVT LTD. Avl bal Rs.92,310.50", ts)
    ts2 = datetime(2026, m, 5, 9)
    add("AX-HDFCBK", f"Sent Rs.18000.00 From HDFC Bank A/C *4521 To Landlord Rent On {ts2:%d/%m/%y} Ref {random.randint(10**11, 10**12 - 1)}", ts2)

# noise that must be ignored
add("AX-HDFCBK", "123456 is your OTP for UPI payment of Rs.500. Do not share.", start)
add("VM-PAYTMB", "Get Rs.100 cashback on your next UPI payment! Offer ends soon.", start)
add("AX-HDFCBK", "Rahul has requested money Rs.250 from you on UPI. Ignore if not known.", start)

open("sample_sms.xml", "w", encoding="utf-8").write(
    "<?xml version='1.0' encoding='UTF-8'?>\n<smses count=\"%d\">\n%s\n</smses>\n" % (len(out), "\n".join(out)))
print(f"wrote sample_sms.xml with {len(out)} messages")
