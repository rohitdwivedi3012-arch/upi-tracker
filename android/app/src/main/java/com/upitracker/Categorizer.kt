package com.upitracker

/** Keyword rules + your own overrides. Port of categorize.py. */
object Categorizer {
    val RULES: Map<String, List<String>> = linkedMapOf(
        "Food & Dining" to listOf("swiggy", "zomato", "dominos", "pizza", "mcdonald", "kfc", "burger", "cafe",
            "restaurant", "starbucks", "chai", "dhaba", "eatclub", "biryani", "haldiram"),
        "Groceries" to listOf("blinkit", "zepto", "bigbasket", "grofers", "dmart", "instamart", "jiomart",
            "kirana", "reliance fresh", "more retail", "milk", "dairy", "vegetable"),
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
            "diagnostic", "lab", "doctor", "practo"),
        "Education" to listOf("school", "college", "university", "udemy", "coursera", "byju", "unacademy", "fees", "tuition"),
        "Investments" to listOf("zerodha", "groww", "upstox", "mutual fund", "sip", "indmoney", "kuvera", "nps", "lic"),
        "Rent & Housing" to listOf("rent", "nobroker", "society", "maintenance", "housing"),
    )
    val ALL = RULES.keys.toList() + listOf("Person to Person", "Family", "Income / Received", "Other", "Uncategorized")

    private val PERSON_VPA = Regex("""^(\d{10}|[a-z.]+\d*)@(ybl|okaxis|okhdfcbank|oksbi|okicici|paytm|ibl|axl|upi|apl)$""")

    /** Key used to remember your correction for a payee. */
    fun overrideKey(t: Txn) = t.vpa.ifEmpty { t.payee.lowercase() }

    fun categorize(t: Txn, overrides: Map<String, String>): String {
        overrides[t.vpa]?.let { return it }
        overrides[t.payee.lowercase()]?.let { return it }
        if (t.direction == "credit") return "Income / Received"
        val text = "${t.vpa} ${t.payee}".lowercase()
        RULES.forEach { (cat, words) -> if (words.any { it in text }) return cat }
        if (t.vpa.isNotEmpty() && PERSON_VPA.matches(t.vpa)) return "Person to Person"
        if (t.payee != "Unknown" && '@' !in t.payee && t.payee.split(" ").size <= 3) return "Person to Person"
        return "Uncategorized"
    }
}
