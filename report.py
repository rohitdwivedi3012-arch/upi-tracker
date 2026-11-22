"""
UPI Spend Report
Usage:  python report.py <sms-backup.xml> [-o report.html] [--from 2026-01-01] [--to 2026-12-31]

Input: XML exported by the Android app "SMS Backup & Restore".
"""
import argparse
import html
import json
import re
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

from categorize import categorize, load_overrides
from upi_parser import dedupe, parse_sms

PALETTE = ["#4f6bed", "#e8743b", "#19a979", "#ed4a7b", "#945ecf", "#13a4b4",
           "#d3a01d", "#6c8893", "#c94d3f", "#5d9c2e", "#a35d8a", "#8b8b8b"]


def load_txns(xml_path: str):
    root = ET.parse(xml_path).getroot()
    txns = []
    for sms in root.iter("sms"):
        if sms.get("type") not in (None, "1"):      # 1 = received
            continue
        ts = datetime.fromtimestamp(int(sms.get("date", "0")) / 1000)
        t = parse_sms(sms.get("address", ""), sms.get("body", ""), ts)
        if t:
            txns.append(t)
    txns = dedupe(txns)
    # show the same merchant under one name, whether an SMS gave its name or its UPI ID
    names = {t.vpa: t.payee for t in txns if t.vpa and '@' not in t.payee and t.payee != 'Unknown'}
    known = {re.sub(r"[^a-z0-9]", "", t.payee.lower()): t.payee for t in txns if "@" not in t.payee and t.payee != "Unknown"}
    for t in txns:
        if t.vpa in names:
            t.payee = names[t.vpa]
        elif "@" in t.payee:
            # SMS only gave a UPI ID: match "netflix@hdfcbank" to a known name "Netflix", else prettify it
            local = re.sub(r"[^a-z0-9]", "", t.payee.split("@")[0])
            match = next((n for k, n in known.items() if len(k) >= 3 and (local.startswith(k) or k.startswith(local))), None)
            if match:
                t.payee = match
            elif not local.isdigit():
                t.payee = re.split(r"[.\-_]", t.payee.split("@")[0])[0].title()
    overrides = load_overrides()
    for t in txns:
        t.category = categorize(t, overrides)
    return txns


def inr(x: float) -> str:
    """Indian digit grouping: 1,23,456.78"""
    neg, x = x < 0, abs(x)
    whole, frac = f"{x:.2f}".split(".")
    if len(whole) > 3:
        head, tail = whole[:-3], whole[-3:]
        parts = []
        while len(head) > 2:
            parts.insert(0, head[-2:])
            head = head[:-2]
        if head:
            parts.insert(0, head)
        whole = ",".join(parts) + "," + tail
    s = f"₹{whole}" + (f".{frac}" if frac != "00" else "")
    return "-" + s if neg else s


def donut(items):
    total = sum(v for _, v in items) or 1
    r, c, off, segs = 70, 2 * 3.14159 * 70, 0, []
    for i, (label, v) in enumerate(items):
        frac = v / total
        segs.append(f'<circle r="{r}" cx="90" cy="90" fill="none" stroke="{PALETTE[i % len(PALETTE)]}" '
                    f'stroke-width="28" stroke-dasharray="{frac * c:.2f} {c:.2f}" stroke-dashoffset="{-off * c:.2f}">'
                    f'<title>{html.escape(label)}: {inr(v)} ({frac:.0%})</title></circle>')
        off += frac
    legend = "".join(
        f'<li><span class="sw" style="background:{PALETTE[i % len(PALETTE)]}"></span>{html.escape(l)}'
        f'<b>{inr(v)}</b><em>{v / total:.0%}</em></li>' for i, (l, v) in enumerate(items))
    return (f'<div class="donut"><svg viewBox="0 0 180 180" transform="rotate(-90)">{"".join(segs)}</svg>'
            f'<ul class="legend">{legend}</ul></div>')


def bars(items, color="var(--accent)"):
    mx = max((v for _, v in items), default=1) or 1
    return '<div class="bars">' + "".join(
        f'<div class="bar"><span class="bl">{html.escape(l)}</span>'
        f'<span class="bt"><span style="width:{v / mx * 100:.1f}%;background:{color}"></span></span>'
        f'<span class="bv">{inr(v)}</span></div>' for l, v in items) + "</div>"


def columns(items):
    mx = max((v for _, v in items), default=1) or 1
    return '<div class="cols">' + "".join(
        f'<div class="col" title="{html.escape(l)}: {inr(v)}"><span style="height:{v / mx * 100:.1f}%"></span>'
        f'<small>{html.escape(l)}</small></div>' for l, v in items) + "</div>"


def payee_cell(t, owner: str) -> str:
    """Money received: you are the payee, the sender is shown underneath."""
    if t.direction == "credit":
        sender = html.escape(t.payee) + (f" · {html.escape(t.vpa)}" if t.vpa else "")
        return f"{html.escape(owner)}<small>from {sender}</small>"
    return f"{html.escape(t.payee)}<small>{html.escape(t.vpa)}</small>"


def build_html(txns, start, end, owner: str = "You") -> str:
    debits = [t for t in txns if t.direction == "debit"]
    credits = [t for t in txns if t.direction == "credit"]
    spent, received = sum(t.amount for t in debits), sum(t.amount for t in credits)
    days = max((end - start).days + 1, 1)

    by_cat = Counter()
    by_payee = Counter()
    by_month = defaultdict(float)
    by_dow = defaultdict(float)
    for t in debits:
        by_cat[t.category] += t.amount
        by_payee[t.payee] += t.amount
        by_month[t.date.strftime("%Y-%m")] += t.amount
        by_dow[t.date.strftime("%a")] += t.amount
    months = sorted(by_month)
    month_items = [(datetime.strptime(m, "%Y-%m").strftime("%b %y"), by_month[m]) for m in months]
    dow_items = [(d, by_dow.get(d, 0)) for d in ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]]
    weekend = by_dow.get("Sat", 0) + by_dow.get("Sun", 0)

    cat_items = by_cat.most_common()
    if len(cat_items) > 9:
        cat_items = cat_items[:8] + [("Others", sum(v for _, v in cat_items[8:]))]

    rows = "".join(
        f'<tr class="{t.direction}"><td data-v="{t.date:%Y%m%d%H%M}">{t.date:%d %b}<small>{t.date:%Y}</small></td>'
        f'<td>{payee_cell(t, owner)}</td><td>{html.escape(t.category)}</td>'
        f'<td>{html.escape(t.bank)}{" ··" + t.account if t.account else ""}</td>'
        f'<td class="num" data-v="{t.amount if t.direction == "credit" else -t.amount}">'
        f'{"+" if t.direction == "credit" else "−"}{inr(t.amount)}</td></tr>'
        for t in sorted(txns, key=lambda t: t.date, reverse=True))

    largest = sorted(debits, key=lambda t: -t.amount)[:5]
    largest_html = "".join(
        f'<li><span>{html.escape(t.payee)}<small>{t.date:%d %b} · {html.escape(t.category)}</small></span>'
        f'<b>{inr(t.amount)}</b></li>' for t in largest)

    uncategorized = sorted({t.vpa or t.payee for t in debits if t.category == "Uncategorized"})

    kpis = [
        ("Total spent", inr(spent), f"{len(debits)} payments"),
        ("Total received", inr(received), f"{len(credits)} credits"),
        ("Net flow", inr(received - spent), "received − spent"),
        ("Avg per day", inr(spent / days), f"over {days} days"),
        ("Avg payment", inr(spent / len(debits)) if debits else "—", "per debit"),
        ("Weekend share", f"{weekend / spent:.0%}" if spent else "—", "Sat + Sun"),
    ]
    kpi_html = "".join(f'<div class="kpi"><small>{a}</small><strong>{b}</strong><em>{c}</em></div>' for a, b, c in kpis)

    tpl = Path(__file__).with_name("template.html").read_text(encoding="utf-8")
    return (tpl.replace("{{RANGE}}", f"{start:%d %b %Y} – {end:%d %b %Y}")
               .replace("{{GENERATED}}", datetime.now().strftime("%d %b %Y, %I:%M %p"))
               .replace("{{KPIS}}", kpi_html)
               .replace("{{DONUT}}", donut(cat_items) if cat_items else "<p>No spending found.</p>")
               .replace("{{MONTHS}}", columns(month_items))
               .replace("{{PAYEES}}", bars(by_payee.most_common(10)))
               .replace("{{DOW}}", bars(dow_items, "var(--accent2)"))
               .replace("{{LARGEST}}", largest_html)
               .replace("{{ROWS}}", rows)
               .replace("{{UNCAT}}", json.dumps(uncategorized)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("xml")
    ap.add_argument("-o", "--out", default="upi_report.html")
    ap.add_argument("--from", dest="start")
    ap.add_argument("--to", dest="end")
    ap.add_argument("--name", default="You", help="your name, shown as the payee when you receive money")
    a = ap.parse_args()

    txns = load_txns(a.xml)
    if a.start:
        txns = [t for t in txns if t.date >= datetime.fromisoformat(a.start)]
    if a.end:
        txns = [t for t in txns if t.date <= datetime.fromisoformat(a.end + "T23:59:59")]
    if not txns:
        raise SystemExit("No UPI transactions found in that file / date range.")
    start = datetime.fromisoformat(a.start) if a.start else min(t.date for t in txns)
    end = datetime.fromisoformat(a.end) if a.end else max(t.date for t in txns)

    Path(a.out).write_text(build_html(txns, start, end, a.name), encoding="utf-8")
    unc = sum(1 for t in txns if t.category == "Uncategorized")
    print(f"Parsed {len(txns)} transactions ({unc} uncategorized) -> {Path(a.out).resolve()}")


if __name__ == "__main__":
    main()
