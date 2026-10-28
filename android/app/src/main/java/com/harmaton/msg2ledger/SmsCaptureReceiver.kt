package com.harmaton.msg2ledger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class SmsCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            .filter { it.originatingAddress.equals("MPESA", ignoreCase = true) }
        if (messages.isEmpty()) return

        val sender = messages.first().originatingAddress ?: return
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        if (body.isBlank()) return

        Log.i("Msg2Ledger", "Accepted incoming MPESA message for background processing")
        val input = Data.Builder()
            .putString(SmsIngestWorker.SENDER, sender)
            .putString(SmsIngestWorker.BODY, body)
            .build()
        val work = OneTimeWorkRequestBuilder<SmsIngestWorker>()
            .setInputData(input)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "mpesa-sms-${work.id}",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            work,
        )
    }
}
