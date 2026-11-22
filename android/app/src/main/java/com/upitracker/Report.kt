package com.upitracker

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** Builds the self-contained HTML report. Port of report.py; same template.html. */
object Report {
    private val PALETTE = listOf("#4f6bed", "#e8743b", "#19a979", "#ed4a7b", "#945ecf", "#13a4b4",
        "#d3a01d", "#6c8893", "#c94d3f", "#5d9c2e", "#a35d8a", "#8b8b8b")

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun fmt(pattern: String, t: Long) = SimpleDateFormat(pattern, Locale.ENGLISH).format(t)

    /** Indian digit grouping: ₹1,23,456.78 */
    fun inr(x: Double): String {
        val parts = String.format(Locale.US, "%.2f", abs(x)).split(".")
        var whole = parts[0]
        if (whole.length > 3) {
            var head = whole.dropLast(3)
            val groups = ArrayList<String>()
            while (head.length > 2) { groups.add(0, head.takeLast(2)); head = head.dropLast(2) }
            if (head.isNotEmpty()) groups.add(0, head)
            whole = groups.joinToString(",") + "," + whole.takeLast(3)
        }
        return (if (x < 0) "-" else "") + "₹" + whole + (if (parts[1] != "00") "." + parts[1] else "")
    }

    private fun pct(a: Double, b: Double) = if (b == 0.0) "—" else "${Math.round(a / b * 100)}%"

    private fun donut(items: List<Pair<String, Double>>): String {
        val total = items.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
        val c = 2 * Math.PI * 70
        var off = 0.0
        val segs = StringBuilder()
        val legend = StringBuilder()
        items.forEachIndexed { i, (label, v) ->
            val f = v / total
            val col = PALETTE[i % PALETTE.size]
            segs.append("""<circle r="70" cx="90" cy="90" fill="none" stroke="$col" stroke-width="28" """ +
                """stroke-dasharray="${"%.2f".format(Locale.US, f * c)} ${"%.2f".format(Locale.US, c)}" """ +
                """stroke-dashoffset="${"%.2f".format(Locale.US, -off * c)}"><title>${esc(label)}: ${inr(v)}</title></circle>""")
            legend.append("""<li><span class="sw" style="background:$col"></span>${esc(label)}<b>${inr(v)}</b><em>${pct(v, total)}</em></li>""")
            off += f
        }
        return """<div class="donut"><svg viewBox="0 0 180 180" transform="rotate(-90)">$segs</svg><ul class="legend">$legend</ul></div>"""
    }

    private fun bars(items: List<Pair<String, Double>>, color: String = "var(--accent)"): String {
        val mx = items.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
        return """<div class="bars">""" + items.joinToString("") { (l, v) ->
            """<div class="bar"><span class="bl">${esc(l)}</span><span class="bt"><span style="width:${"%.1f".format(Locale.US, v / mx * 100)}%;background:$color"></span></span><span class="bv">${inr(v)}</span></div>"""
        } + "</div>"
    }

    private fun columns(items: List<Pair<String, Double>>): String {
        val mx = items.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
        return """<div class="cols">""" + items.joinToString("") { (l, v) ->
            """<div class="col" title="${esc(l)}: ${inr(v)}"><span style="height:${"%.1f".format(Locale.US, v / mx * 100)}%"></span><small>${esc(l)}</small></div>"""
        } + "</div>"
    }

    fun build(ctx: Context, txns: List<Txn>, from: Long, to: Long, rangeLabel: String, inApp: Boolean): String {
        val debits = txns.filter { it.direction == "debit" }
        val credits = txns.filter { it.direction == "credit" }
        val spent = debits.sumOf { it.amount }
        val received = credits.sumOf { it.amount }
        val days = maxOf(((minOf(to, System.currentTimeMillis()) - from) / 86_400_000L) + 1, 1)

        val byCat = debits.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }.toList().sortedByDescending { it.second }
        val cats = if (byCat.size > 9) byCat.take(8) + ("Others" to byCat.drop(8).sumOf { it.second }) else byCat
        val byPayee = debits.groupBy { it.payee }.mapValues { e -> e.value.sumOf { it.amount } }.toList().sortedByDescending { it.second }.take(10)
        val byMonth = debits.groupBy { fmt("yyyy-MM", it.time) }.toSortedMap()
            .map { (_, l) -> fmt("MMM yy", l[0].time) to l.sumOf { it.amount } }
        val dowOrder = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val byDow = debits.groupBy { fmt("EEE", it.time) }.mapValues { e -> e.value.sumOf { it.amount } }
        val dow = dowOrder.map { it to (byDow[it] ?: 0.0) }
        val weekend = (byDow["Sat"] ?: 0.0) + (byDow["Sun"] ?: 0.0)

        val kpis = listOf(
            Triple("Total spent", inr(spent), "${debits.size} payments"),
            Triple("Total received", inr(received), "${credits.size} credits"),
            Triple("Net flow", inr(received - spent), "received − spent"),
            Triple("Avg per day", inr(spent / days), "over $days days"),
            Triple("Avg payment", if (debits.isEmpty()) "—" else inr(spent / debits.size), "per debit"),
            Triple("Weekend share", pct(weekend, spent), "Sat + Sun"),
        ).joinToString("") { (a, b, c) -> """<div class="kpi"><small>$a</small><strong>$b</strong><em>$c</em></div>""" }

        val rows = txns.sortedByDescending { it.time }.joinToString("") { t ->
            val sign = if (t.direction == "credit") "+" else "−"
            val v = if (t.direction == "credit") t.amount else -t.amount
            """<tr class="${t.direction}" data-key="${esc(Categorizer.overrideKey(t))}"><td data-v="${fmt("yyyyMMddHHmm", t.time)}">${fmt("dd MMM yyyy", t.time)}</td>""" +
                """<td>${esc(t.payee)}<small>${esc(t.vpa)}</small></td><td>${esc(t.category)}</td>""" +
                """<td>${esc(t.bank)}${if (t.account.isNotEmpty()) " ··" + t.account else ""}</td>""" +
                """<td class="num" data-v="$v">$sign${inr(t.amount)}</td></tr>"""
        }
        val largest = debits.sortedByDescending { it.amount }.take(5).joinToString("") { t ->
            """<li><span>${esc(t.payee)}<small>${fmt("dd MMM", t.time)} · ${esc(t.category)}</small></span><b>${inr(t.amount)}</b></li>"""
        }
        val uncat = debits.filter { it.category == "Uncategorized" }.map { it.payee }.distinct().sorted()
        val uncatJs = uncat.joinToString(",", "[", "]") {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\""
        }

        var html = ctx.assets.open("template.html").bufferedReader().readText()
            .replace("{{RANGE}}", esc(rangeLabel))
            .replace("{{GENERATED}}", fmt("dd MMM yyyy, hh:mm a", System.currentTimeMillis()))
            .replace("{{KPIS}}", kpis)
            .replace("{{DONUT}}", if (cats.isEmpty()) "<p>No spending in this period.</p>" else donut(cats))
            .replace("{{MONTHS}}", columns(byMonth))
            .replace("{{PAYEES}}", bars(byPayee))
            .replace("{{DOW}}", bars(dow, "var(--accent2)"))
            .replace("{{LARGEST}}", largest)
            .replace("{{ROWS}}", rows)
            .replace("{{UNCAT}}", uncatJs)
            .replace("add them to my_categories.json", if (inApp) "tap a row to set its category" else "set them in the app")

        if (inApp) html = html.replace("<main>", "<main>" + appToolbar(rangeLabel)).replace("</body>", APP_SCRIPT + "</body>")
        return html
    }

    val RANGES = listOf("This month", "Last month", "Last 3 months", "This year", "All time")

    fun rangeBounds(label: String): Pair<Long, Long> {
        val c = Calendar.getInstance()
        fun startOfMonth(cal: Calendar) = cal.apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val now = System.currentTimeMillis()
        return when (label) {
            "This month" -> startOfMonth(c).timeInMillis to now
            "Last month" -> { startOfMonth(c); val end = c.timeInMillis - 1; c.add(Calendar.MONTH, -1); c.timeInMillis to end }
            "Last 3 months" -> { startOfMonth(c); c.add(Calendar.MONTH, -2); c.timeInMillis to now }
            "This year" -> { startOfMonth(c); c.set(Calendar.MONTH, 0); c.timeInMillis to now }
            else -> 0L to now
        }
    }

    private fun appToolbar(current: String): String {
        val opts = RANGES.joinToString("") { """<option${if (it == current) " selected" else ""}>$it</option>""" }
        return """<div class="tools" style="margin-bottom:14px"><select onchange="Android.setRange(this.value)">$opts</select>""" +
            """<button onclick="Android.share()" style="background:var(--accent);color:#fff;border:0;border-radius:8px;padding:8px 14px;font:inherit">Share report</button>""" +
            """<button onclick="Android.rescan()" style="background:var(--card);color:var(--ink);border:1px solid var(--line);border-radius:8px;padding:8px 14px;font:inherit">Rescan SMS</button></div>"""
    }

    private val APP_SCRIPT = """<script>
document.querySelectorAll('#t tbody tr').forEach(r=>{r.style.cursor='pointer';r.onclick=()=>Android.pickCategory(r.dataset.key,r.cells[1].firstChild.textContent)});
</script>"""
}
