package com.upitracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Captures new bank SMS as they arrive, so the app stays up to date without rescanning. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        // a long SMS arrives in several parts; join them
        val body = parts.joinToString("") { it.messageBody ?: "" }
        val sender = parts[0].originatingAddress ?: ""
        val t = Parser.parse(sender, body, parts[0].timestampMillis) ?: return
        Store(ctx).use { it.insert(t) }
    }
}
