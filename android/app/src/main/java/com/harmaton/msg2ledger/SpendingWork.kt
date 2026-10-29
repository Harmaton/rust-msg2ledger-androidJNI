package com.harmaton.msg2ledger

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val LEDGER_DB = "ledger.db"
private const val NOTIFICATION_CHANNEL = "spending_checkins"
private const val NOTIFICATION_WORK = "spending-checkins"
const val PREFERENCES = "spending_preferences"
private val embeddedHostStartLock = Any()

class SmsIngestWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sender = inputData.getString(SENDER) ?: return Result.failure()
        val body = inputData.getString(BODY) ?: return Result.failure()
        return try {
            openLedgerAndStartHost(applicationContext)
            check(NativeBridge.nativeQueueMessage(sender, body)) {
                "The sender was not accepted for the M-PESA queue"
            }
            val deadline = System.currentTimeMillis() + 30_000
            while (
                System.currentTimeMillis() < deadline &&
                !NativeBridge.nativeHasMessage(sender, body)
            ) {
                delay(250)
            }
            check(NativeBridge.nativeHasMessage(sender, body)) {
                "The background ledger worker did not finish processing the M-PESA message"
            }
            Log.i("Msg2Ledger", "M-PESA message accepted into the durable processing queue")
            queueImmediateBudgetAlert(applicationContext)
            Result.success()
        } catch (error: Exception) {
            Log.e("Msg2Ledger", "Could not queue an M-PESA message", error)
            Result.retry()
        }
    }

    companion object {
        const val SENDER = "sender"
        const val BODY = "body"
    }
}

class SpendingNotificationWorker(
    context: Context,
    private val workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    override suspend fun doWork(): Result {
        return try {
            openLedgerAndStartHost(applicationContext)
            val now = LocalDate.now()
            val dashboard = JSONObject(NativeBridge.nativeDashboard(now.toString()))
            notifySpending(
                applicationContext,
                dashboard,
                now,
                workerParameters.inputData.getInt("interval_hours", 24),
                workerParameters.inputData.getBoolean("alerts_only", false),
            )
            Result.success()
        } catch (error: Exception) {
            Log.e("Msg2Ledger", "Could not create a spending notification", error)
            Result.retry()
        }
    }
}

fun scheduleSpendingNotifications(context: Context, intervalHours: Int) {
    val validHours = setOf(1, 2, 3, 24, 168)
    require(intervalHours in validHours)
    val work = PeriodicWorkRequestBuilder<SpendingNotificationWorker>(
        intervalHours.toLong(),
        TimeUnit.HOURS,
    )
        .setInputData(Data.Builder().putInt("interval_hours", intervalHours).build())
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        NOTIFICATION_WORK,
        ExistingPeriodicWorkPolicy.UPDATE,
        work,
    )
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putInt("notification_interval_hours", intervalHours)
        .apply()
}

private fun queueImmediateBudgetAlert(context: Context) {
    val interval = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getInt("notification_interval_hours", 24)
    val work = OneTimeWorkRequestBuilder<SpendingNotificationWorker>()
        .setInputData(
            Data.Builder()
                .putInt("interval_hours", interval)
                .putBoolean("alerts_only", true)
                .build(),
        )
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        "budget-alert-${work.id}",
        androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
        work,
    )
}

internal fun openLedgerAndStartHost(context: Context) {
    val database = context.getDatabasePath(LEDGER_DB)
    database.parentFile?.mkdirs()
    check(NativeBridge.nativeOpen(database.absolutePath)) {
        "Could not open the on-device ledger database"
    }
    ensureLedgerHostStarted(
        database.parentFile?.absolutePath
            ?: error("The private ledger database has no parent directory."),
    )
}

internal fun ensureLedgerHostStarted(rootPath: String) {
    synchronized(embeddedHostStartLock) {
        var status = NativeHost.nativeStatus()
        if (status == 5) {
            Thread.sleep(5_000)
            status = NativeHost.nativeStatus()
        }
        when (status) {
                1, 2 -> return
                3, 4 -> {
                    repeat(100) {
                        Thread.sleep(50)
                        when (NativeHost.nativeStatus()) {
                            1, 2 -> return
                            0, 5 -> {
                                startLedgerHost(rootPath)
                                return
                            }
                        }
                    }
                    error("The background ledger service did not finish starting or stopping in time.")
                }
                0, 5 -> {
                    startLedgerHost(rootPath)
                    return
                }
                else -> error("The background ledger service returned an unknown status: $status")
        }
    }
}

private fun startLedgerHost(rootPath: String) {
    val result = NativeHost.nativeStart(arrayOf("--root", rootPath))
    check(result == 0 || result == 1) {
        NativeHost.nativeLastError().ifBlank {
            "Could not start the background ledger service (code $result)."
        }
    }
}

private fun notifySpending(
    context: Context,
    dashboard: JSONObject,
    day: LocalDate,
    interval: Int,
    alertsOnly: Boolean,
) {
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }

    val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val dailySpend = dashboard.optLong("spent_cents")
    val weeklySpend = dashboard.optLong("weekly_spent_cents")
    val dailyBudget = preferences.getLong("daily_budget_cents", 0)
    val weeklyBudget = preferences.getLong("weekly_budget_cents", 0)
    val alerts = mutableListOf<String>()
    if (dailyBudget > 0 && dailySpend > dailyBudget) {
        val key = "daily-alert-${day}"
        if (!preferences.getBoolean(key, false)) {
            alerts += "Daily budget exceeded: ${formatKsh(dailySpend)} spent."
            preferences.edit().putBoolean(key, true).apply()
        }
    }
    if (weeklyBudget > 0 && weeklySpend > weeklyBudget) {
        val week = day.get(WeekFields.of(Locale.getDefault()).weekOfWeekBasedYear())
        val key = "weekly-alert-${day.year}-$week"
        if (!preferences.getBoolean(key, false)) {
            alerts += "Weekly budget exceeded: ${formatKsh(weeklySpend)} spent."
            preferences.edit().putBoolean(key, true).apply()
        }
    }

    val payees = dashboard.optJSONArray("counterparties")
    if (payees != null) {
        for (index in 0 until payees.length()) {
            val payee = payees.optJSONObject(index) ?: continue
            val limit = payee.optLong("limit_cents")
            val spent = payee.optLong("today_spent_cents")
            if (limit <= 0 || spent <= limit) continue
            val name = payee.optString("name")
            val key = "payee-alert-${day}-${name.hashCode()}"
            if (!preferences.getBoolean(key, false)) {
                alerts += "$name limit exceeded: ${formatKsh(spent)} spent today."
                preferences.edit().putBoolean(key, true).apply()
            }
        }
    }
    if (alertsOnly && alerts.isEmpty()) return

    val normalText = when (interval) {
        168 -> "You spent ${formatKsh(weeklySpend)} over the last 7 days."
        else -> "You've spent ${formatKsh(dailySpend)} today."
    }
    val title = if (alerts.isEmpty()) "Spending check-in" else "Budget alert"
    val text = (alerts.ifEmpty { listOf(normalText) }).joinToString(" ")
    createNotificationChannel(context)
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    val pendingIntent = launch?.let {
        PendingIntent.getActivity(
            context,
            0,
            it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(day.toEpochDay().toInt(), notification)
}

private fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            NOTIFICATION_CHANNEL,
            "Spending check-ins",
            NotificationManager.IMPORTANCE_DEFAULT,
        ),
    )
}

private fun formatKsh(cents: Long): String =
    "Ksh${(cents / 100).toString().reversed().chunked(3).joinToString(",").reversed()}"
