package com.upitracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Telephony

/** Local SQLite storage (private to the app). */
class Store(private val ctx: Context) : SQLiteOpenHelper(ctx, "upi.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE txn(k TEXT PRIMARY KEY, time INTEGER, amount REAL, direction TEXT,
            payee TEXT, vpa TEXT, ref TEXT, account TEXT, bank TEXT)""")
        db.execSQL("CREATE TABLE override(k TEXT PRIMARY KEY, category TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    private fun values(t: Txn) = ContentValues().apply {
        put("k", t.dedupeKey); put("time", t.time); put("amount", t.amount); put("direction", t.direction)
        put("payee", t.payee); put("vpa", t.vpa); put("ref", t.ref); put("account", t.account); put("bank", t.bank)
    }

    /** Adds a transaction; returns false if it was already stored. */
    fun insert(t: Txn): Boolean =
        writableDatabase.insertWithOnConflict("txn", null, values(t), SQLiteDatabase.CONFLICT_IGNORE) != -1L

    /** Adds a transaction, or refreshes it with the latest parsing rules. Returns true if it is new. */
    private fun upsert(t: Txn): Boolean {
        if (insert(t)) return true
        writableDatabase.update("txn", values(t), "k = ?", arrayOf(t.dedupeKey))
        return false
    }

    /** Reads the whole SMS inbox. Returns number of new transactions found; existing ones are re-parsed. */
    fun scanInbox(): Int {
        var added = 0
        val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, cols, null, null, "${Telephony.Sms.DATE} ASC")?.use { c ->
            val db = writableDatabase
            db.beginTransaction()
            try {
                while (c.moveToNext()) {
                    val t = Parser.parse(c.getString(0) ?: "", c.getString(1) ?: "", c.getLong(2)) ?: continue
                    if (upsert(t)) added++
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        return added
    }

    fun overrides(): Map<String, String> = readableDatabase.rawQuery("SELECT k, category FROM override", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
    }

    fun setOverride(key: String, category: String) {
        writableDatabase.insertWithOnConflict("override", null, ContentValues().apply {
            put("k", key); put("category", category)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun load(from: Long, to: Long): List<Txn> {
        val list = readableDatabase.rawQuery(
            "SELECT time, amount, direction, payee, vpa, ref, account, bank FROM txn WHERE time BETWEEN ? AND ? ORDER BY time",
            arrayOf(from.toString(), to.toString())).use { c ->
            buildList {
                while (c.moveToNext()) add(Txn(c.getLong(0), c.getDouble(1), c.getString(2), c.getString(3),
                    c.getString(4), c.getString(5), c.getString(6), c.getString(7)))
            }
        }
        Parser.unifyPayees(list)
        val ov = overrides()
        list.forEach { it.category = Categorizer.categorize(it, ov) }
        return list
    }

    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM txn", null).use { it.moveToFirst(); it.getLong(0) }
}
