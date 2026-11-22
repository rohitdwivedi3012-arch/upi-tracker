package com.upitracker

/** Keyword rules + your own overrides. Port of categorize.py. */
object Categorizer {
    val RULES: Map<String, List<String>> = linkedMapOf(
        "Food & Dining" to listOf("swiggy", "zomato", "dominos", "pizza", "mcdonald", "kfc", "burger", "cafe",
            "restaurant", "starbucks", "chai", "dhaba", "eatclub", "biryani", "haldiram",
            "ice cr", "icecream", "bakery", "bakers", "sweet", "juice", "hotel", "canteen", "foods",
            "kitchen", "snack", "dosa", "bhel", "tea "),
        "Groceries" to listOf("blinkit", "zepto", "bigbasket", "grofers", "dmart", "instamart", "jiomart",
            "kirana", "reliance fresh", "more retail", "milk", "dairy", "vegetable", "grocer",
            "general st", "provision", "supermarket", "super market", "fruit"),
        "Shopping" to listOf("amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "tatacliq", "decathlon",
            "croma", "reliance digital", "lenskart"),
        "Travel & Transport" to listOf("uber", "ola", "rapido", "irctc", "redbus", "makemytrip", "goibibo", "indigo",
            "metro", "fastag", "petrol", "fuel", "hpcl", "bpcl", "iocl", "indianoil", "shell", "parking"),
        "Bills & Recharge" to listOf("airtel", "jio", "vodafone", "vi ", "bsnl", "electricity", "bescom", "tneb",
            "msedcl", "tatapower", "adani", "gas", "broadband", "act fibernet", "dth", "tata play", "billdesk",
            "bbps", "recharge", "water"),
        "Entertainment" to listOf("netflix", "hotstar", "prime", "spotify", "bookmyshow", "pvr", "inox", "youtube",
            "sonyliv", "zee5", "gaming", "steam"),
        "Health" to listOf("pharmacy", "apollo", "medplus", "1mg", "pharmeasy", "netmeds", "hospital", "clinic",
            "diagnostic", "lab", "doctor", "practo", "medical", "medico", "chemist", "pharma", "dental", "optical"),
        "Education" to listOf("school", "college", "university", "udemy", "coursera", "byju", "unacademy", "fees", "tuition"),
        "Investments" to listOf("zerodha", "groww", "upstox", "mutual fund", "sip", "indmoney", "kuvera", "nps", "lic"),
        "Rent & Housing" to listOf("rent", "nobroker", "society", "maintenance", "housing"),
    )
    val ALL = RULES.keys.toList() + listOf("Shops & Services", "Person to Person", "Family", "Income / Received", "Other", "Uncategorized")

    /** Words that mark a business rather than a person ("Gajanan Enterpr" = truncated "Enterprises"). */
    private val MERCHANT_WORDS = listOf("enterpr", "traders", "trading", "store", "shop", "agenc", "center", "centre",
        "services", "pvt", "ltd", "llp", "industr", "emporium", "bazaar", "collection", "garment", "electronic",
        "mobile", "hardware", "solutions", "works", "and sons", "& sons")

    // keywords match at the start of a word only, so "ola" doesn't match "Bholanath"
    private fun wordStart(words: List<String>) = Regex(words.joinToString("|") { "(?<![a-z0-9])" + Regex.escape(it) })
    private val CAT_RX = RULES.mapValues { wordStart(it.value) }
    private val MERCHANT_RX = wordStart(MERCHANT_WORDS)

    private val PERSON_VPA = Regex("""^(\d{10}|[a-z.]+\d*)@(ybl|okaxis|okhdfcbank|oksbi|okicici|paytm|ibl|axl|upi|apl)$""")

    /** Key used to remember your correction for a payee. */
    /** Key used to remember your correction. An unreadable payee gets a per-transaction key, so one choice
     *  doesn't spread to every "Unknown" transaction. */
    fun overrideKey(t: Txn) = t.vpa.ifEmpty { if (t.payee == "Unknown") "txn:" + t.dedupeKey else t.payee.lowercase() }

    fun categorize(t: Txn, overrides: Map<String, String>): String {
        overrides[overrideKey(t)]?.let { return it }
        if (t.vpa.isNotEmpty()) overrides[t.vpa]?.let { return it }
        if (t.payee != "Unknown") overrides[t.payee.lowercase()]?.let { return it }
        if (t.direction == "credit") return "Income / Received"
        val text = "${t.vpa} ${t.payee}".lowercase()
        CAT_RX.forEach { (cat, rx) -> if (rx.containsMatchIn(text)) return cat }
        if (MERCHANT_RX.containsMatchIn(text)) return "Shops & Services"
        if (t.vpa.isNotEmpty() && PERSON_VPA.matches(t.vpa)) return "Person to Person"
        if (t.payee != "Unknown" && '@' !in t.payee && t.payee.split(" ").size <= 3) return "Person to Person"
        return "Uncategorized"
    }
}
