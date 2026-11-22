package com.upitracker

/** One UPI / bank transaction parsed from an SMS. Port of upi_parser.py. */
data class Txn(
    val time: Long,          // epoch millis (SMS timestamp)
    val amount: Double,
    val direction: String,   // "debit" | "credit"
    var payee: String,
    val vpa: String,
    val ref: String,
    val account: String,
    val bank: String,
    var category: String = "Uncategorized",
) {
    /** Bank + UPI app often both send an SMS for one payment; this key collapses them. */
    val dedupeKey: String
        get() = if (ref.isNotEmpty()) "ref:$ref"
        else "fz:$amount:$direction:${time / 3_600_000}:${vpa.ifEmpty { payee }}"
}

object Parser {
    /** Bump whenever parsing rules change: the app then re-parses the inbox once on next launch. */
    const val VERSION = 2

    private val I = RegexOption.IGNORE_CASE
    private val AMOUNT = Regex("""(?:rs\.?|inr|₹)\s*([\d,]+(?:\.\d{1,2})?)""", I)
    private val IGNORE = Regex(
        """\botp\b|one time password|requested money|payment request|collect request|cashback|offer|win |reward|""" +
            """pre-approved|loan|emi of|due on|minimum due|will be debited|is due|failed|declined|reversed""", I)
    private val DEBIT = Regex("""debited|\bsent\b|\bpaid\b|spent|transferred to|withdrawn|\bdr\b""", I)
    private val CREDIT = Regex("""credited|received|deposited|\bcr\b""", I)
    private val UPI_HINT = Regex("""\bupi\b|vpa|@[a-z]+|imps|neft|rtgs|ref""", I)
    private val REF = Regex("""(?:upi\s*ref(?:\s*no)?|upi\s*:|ref(?:erence)?(?:\s*no)?|rrn|txn\s*id)[\s.:#-]*(\d{9,16})""", I)
    private val VPA = Regex("""([a-z0-9.\-_]{2,}@[a-z]{2,})""", I)
    private val PAYEE = Regex(
        """(?:\bto\b|\btrf to\b|\bpaid to\b|\bat\b|\bfrom\b|\bby\b)\s+(?:vpa\s+)?""" +
            """([A-Za-z0-9][A-Za-z0-9 .&'_@-]{1,40}?)""" +
            """(?=\s+(?:on|ref\w*|upi|via|a/c|ac|dated|avl|bal)\b|[.;,(\n]|\s*$)""", I)
    private val OTHER_PARTY = Regex(""";\s*([^;]{2,40}?)\s+(credited|debited)\b""", I)
    private val TITLE = Regex("""^(?:mr|mrs|ms|miss|dr|shri|smt|m/s)\.?\s+""", I)
    private val ACCOUNT = Regex("""(?:a/c|ac|acct|account|card)[\s.:no]*[x*]*(\d{3,4})""", I)
    private val TRANSFER_PREFIX = Regex("""^(?:neft|imps|rtgs|upi)\s+(?:from|by|to)?\s*""", I)
    private val OWN_BANK = Regex("""^(hdfc|sbi|icici|axis|kotak|your)\b""", I)
    private val SELF_WORDS = setOf("your", "a/c", "ac", "account", "bank", "you", "rs", "rs.", "inr", "date")

    private val BANKS = linkedMapOf(
        "HDFC" to "HDFC", "SBI" to "SBI", "ICICI" to "ICICI", "AXIS" to "Axis", "KOTAK" to "Kotak",
        "PNB" to "PNB", "BOB" to "Bank of Baroda", "CANARA" to "Canara", "UNION" to "Union",
        "IDFC" to "IDFC First", "YES" to "Yes Bank", "INDUS" to "IndusInd", "PAYTM" to "Paytm",
        "AIRTEL" to "Airtel Payments", "FEDERAL" to "Federal",
    )

    private fun titleCase(s: String) =
        s.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun payee(body: String, direction: String, vpa: String): String {
        val candidates = ArrayList<String>()
        // ICICI style: "...debited for Rs 2000.00 on 07-Nov-26; Mr John Doe credited. UPI:..."
        // (for a debit the other party is "credited", and vice versa)
        OTHER_PARTY.find(body)?.let { m ->
            if ((direction == "debit") == m.groupValues[2].equals("credited", ignoreCase = true)) candidates.add(m.groupValues[1])
        }
        PAYEE.findAll(body).forEach { candidates.add(it.groupValues[1]) }
        for (raw in candidates) {
            val name = raw.trim(' ', '.', '-').replace(TRANSFER_PREFIX, "").replace(TITLE, "").trim()
            val low = name.lowercase()
            if (low.isBlank() || low.split(" ")[0] in SELF_WORDS || Regex("""[x*\d ]+""").matches(low)) continue
            if (OWN_BANK.containsMatchIn(low) && "bank" in low) continue
            return if ('@' in name) low else titleCase(name)
        }
        return vpa.ifEmpty { "Unknown" }
    }

    fun parse(sender: String, body: String, time: Long): Txn? {
        if (IGNORE.containsMatchIn(body)) return null
        val amt = AMOUNT.find(body) ?: return null
        val d = DEBIT.find(body)
        val c = CREDIT.find(body)
        if ((d == null && c == null) || !UPI_HINT.containsMatchIn(body)) return null
        val direction = when {
            d != null && c != null -> if (d.range.first < c.range.first) "debit" else "credit"
            d != null -> "debit"
            else -> "credit"
        }
        val vpa = VPA.find(body)?.groupValues?.get(1)?.lowercase() ?: ""
        val upper = "$sender $body".uppercase()
        return Txn(
            time = time,
            amount = amt.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null,
            direction = direction,
            payee = payee(body, direction, vpa),
            vpa = vpa,
            ref = REF.find(body)?.groupValues?.get(1) ?: "",
            account = ACCOUNT.find(body)?.groupValues?.get(1) ?: "",
            bank = BANKS.entries.firstOrNull { it.key in upper }?.value ?: "Other",
        )
    }

    /** Show one merchant under one name whether an SMS gave its name or only its UPI ID. Port of report.py. */
    fun unifyPayees(txns: List<Txn>) {
        val clean = { s: String -> s.lowercase().replace(Regex("[^a-z0-9]"), "") }
        val byVpa = txns.filter { it.vpa.isNotEmpty() && '@' !in it.payee && it.payee != "Unknown" }
            .associate { it.vpa to it.payee }
        val known = txns.filter { '@' !in it.payee && it.payee != "Unknown" }.associate { clean(it.payee) to it.payee }
        for (t in txns) {
            byVpa[t.vpa]?.let { t.payee = it; continue }
            if ('@' !in t.payee) continue
            val handle = t.payee.substringBefore('@')
            val local = clean(handle)
            val match = known.entries.firstOrNull { (k, _) -> k.length >= 3 && (local.startsWith(k) || k.startsWith(local)) }
            when {
                match != null -> t.payee = match.value
                !local.all { it.isDigit() } -> t.payee = titleCase(handle.split('.', '-', '_')[0])
            }
        }
    }
}
