package ca.emarshall.finance.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ca.emarshall.finance.MainActivity
import ca.emarshall.finance.R
import ca.emarshall.finance.work.SnoozeReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** All notifications. They never include amounts, balances or account numbers. */
@Singleton
class Notifier @Inject constructor(@ApplicationContext private val context: Context) {

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDERS, "Statement reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminds you to download and import a statement after it closes"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_QUESTRADE, "Questrade connection", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when Questrade needs a new token"
            },
        )
    }

    fun canPost(): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** "Scotiabank statement should be ready — download it on your computer and import it." */
    fun statementReady(reminderId: Long, accountId: Long?, title: String, institution: String) {
        val body = if (accountId == null) {
            "Time to import your statements — download them on your computer and import them."
        } else {
            "$institution statement should be ready — download it on your computer and import it."
        }
        val importIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_IMPORT
            putExtra(MainActivity.EXTRA_ACCOUNT_ID, accountId ?: -1L)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val importPi = PendingIntent.getActivity(
            context, (reminderId * 10 + 1).toInt(), importIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snoozePi = PendingIntent.getBroadcast(
            context, (reminderId * 10 + 2).toInt(),
            Intent(context, SnoozeReceiver::class.java).putExtra(SnoozeReceiver.EXTRA_REMINDER_ID, reminderId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(importPi)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "Import now", importPi)
            .addAction(0, "Snooze 2 days", snoozePi)
            .build()
        post(notificationId(reminderId), n)
    }

    fun testNotification() = statementReady(TEST_ID, 0L, "Test reminder", "Your bank's")

    fun questradeDisconnected() {
        val pi = PendingIntent.getActivity(
            context, 9001,
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_QUESTRADE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CH_QUESTRADE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Reconnect Questrade")
            .setContentText("Questrade needs a new token. Tap to reconnect.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        post(9001, n)
    }

    fun cancel(reminderId: Long) = NotificationManagerCompat.from(context).cancel(notificationId(reminderId))

    private fun notificationId(reminderId: Long) = 1000 + reminderId.toInt()

    private fun post(id: Int, n: android.app.Notification) {
        if (!canPost()) return
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    companion object {
        const val CH_REMINDERS = "statement_reminders"
        const val CH_QUESTRADE = "questrade"
        const val TEST_ID = 999L
    }
}
