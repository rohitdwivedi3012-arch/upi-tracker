package com.upitracker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserManager
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var store: Store
    private var range = "This month"
    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }

    /** The phone owner: shown as the payee when money is received. */
    private val ownerName get() = prefs.getString("ownerName", null)?.takeIf { it.isNotBlank() } ?: "You"

    /** Best guess at the owner's name from the Android user name (e.g. "12345rohit12345" → "Rohit"). Often not readable. */
    private fun guessOwnerName(): String = try {
        val raw = (getSystemService(USER_SERVICE) as UserManager).userName ?: ""
        val letters = raw.replace(Regex("[^\\p{L} ]"), " ").trim().replace(Regex("\\s+"), " ")
        if (letters.length < 2 || letters.lowercase() in setOf("owner", "user", "phone", "admin")) ""
        else letters.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    } catch (e: Exception) { "" }

    private fun editName(firstTime: Boolean = false) {
        val input = EditText(this).apply {
            setText(prefs.getString("ownerName", null) ?: guessOwnerName())
            hint = "Your name"
            setSingleLine()
            selectAll()
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(if (firstTime) "What's your name?" else "Your name")
            .setMessage("Shown as the payee when you receive money.")
            .setView(box)
            .setCancelable(!firstTime)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("ownerName", input.text.toString().trim()).apply()
                if (store.count() > 0) render()
            }
            .apply { if (!firstTime) setNegativeButton("Cancel", null) }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        range = savedInstanceState?.getString("range") ?: range
        web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "Android")
            fitsSystemWindows = true
        }
        setContentView(web)

        if (checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            if (store.count() == 0L || prefs.getInt("parserVersion", 1) < Parser.VERSION) {
                prefs.edit().putInt("parserVersion", Parser.VERSION).apply()
                rescan()
            } else render()
            if (prefs.getString("ownerName", null) == null) editName(firstTime = true)
        } else {
            showMessage("SMS permission needed", "UPI Tracker reads your bank SMS on this phone to find UPI payments. " +
                "The app has no internet access, so nothing ever leaves your device.")
            requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS), 1)
        }
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("range", range) }

    override fun onResume() {
        super.onResume()
        // pick up transactions the SmsReceiver stored while we were away
        if (::web.isInitialized && checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED && store.count() > 0) render()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putInt("parserVersion", Parser.VERSION).apply()
            rescan()
            if (prefs.getString("ownerName", null) == null) editName(firstTime = true)
        } else showMessage("Permission denied", "Without SMS access the app can't find transactions. " +
            "Enable it in Settings → Apps → UPI Tracker → Permissions → SMS.")
    }

    private fun showMessage(title: String, body: String) {
        web.loadDataWithBaseURL(null, """<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{font:16px system-ui;padding:40px 20px;color:#1d2433;background:#f6f7fb}
            @media(prefers-color-scheme:dark){body{color:#e8ebf2;background:#11141b}}</style></head>
            <body><h2>$title</h2><p>$body</p></body></html>""", "text/html", "utf-8", null)
    }

    private fun rescan() {
        showMessage("Reading your SMS…", "Looking for UPI and bank transactions. This takes a few seconds.")
        thread {
            val added = store.scanInbox()
            runOnUiThread {
                Toast.makeText(this, "Found $added new transactions", Toast.LENGTH_SHORT).show()
                render()
            }
        }
    }

    private fun currentHtml(inApp: Boolean): String {
        val (from, to) = Report.rangeBounds(range)
        val txns = store.load(from, to)
        val df = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
        val label = if (range == "All time" && txns.isNotEmpty()) "All time · ${df.format(txns.first().time)} – ${df.format(txns.last().time)}"
            else "$range · ${df.format(maxOf(from, txns.firstOrNull()?.time ?: from))} – ${df.format(to)}"
        return Report.build(this, txns, if (range == "All time") (txns.firstOrNull()?.time ?: from) else from, to, label, inApp, ownerName)
    }

    private fun render() {
        val y = web.scrollY
        web.loadDataWithBaseURL(null, currentHtml(inApp = true), "text/html", "utf-8", null)
        web.postDelayed({ web.scrollTo(0, y) }, 150)
    }

    /** Save the report to Downloads/UPI Tracker and open the share sheet. */
    private fun share() {
        val name = "UPI_Report_${range.replace(" ", "_")}_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(System.currentTimeMillis())}.html"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/html")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/UPI Tracker")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { it.write(currentHtml(inApp = false).toByteArray()) }
        Toast.makeText(this, "Saved to Downloads/UPI Tracker", Toast.LENGTH_SHORT).show()
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share UPI report"))
    }

    private fun pickCategory(key: String, payee: String) {
        val cats = Categorizer.ALL.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Category for $payee")
            .setItems(cats) { _, i ->
                store.setOverride(key, cats[i])
                Toast.makeText(this, "All payments to $payee → ${cats[i]}", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class Bridge {
        @JavascriptInterface fun setRange(r: String) = runOnUiThread { if (r in Report.RANGES) { range = r; render() } }
        @JavascriptInterface fun share() = runOnUiThread { this@MainActivity.share() }
        @JavascriptInterface fun rescan() = runOnUiThread { this@MainActivity.rescan() }
        @JavascriptInterface fun editName() = runOnUiThread { this@MainActivity.editName() }
        @JavascriptInterface fun pickCategory(key: String, payee: String) = runOnUiThread { this@MainActivity.pickCategory(key, payee.trim()) }
    }
}
