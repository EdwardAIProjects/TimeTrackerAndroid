package dev.hydranet.timetrackerapp

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal const val DAILY_REMINDER_ENABLED_KEY = "daily_reminder_enabled"
internal const val DAILY_REMINDER_HOUR_KEY = "daily_reminder_hour"
internal const val DAILY_REMINDER_MINUTE_KEY = "daily_reminder_minute"
internal const val DEFAULT_REMINDER_HOUR = 8
internal const val DEFAULT_REMINDER_MINUTE = 0

private const val REMINDER_CHANNEL_ID = "daily_progress_reminder"
private const val REMINDER_NOTIFICATION_ID = 1001
private const val REMINDER_REQUEST_CODE = 2001

internal object DailyReminder {
    fun schedule(context: Context, hour: Int, minute: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextTriggerMillis(hour, minute),
            reminderPendingIntent(context)
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(reminderPendingIntent(context))
    }

    private fun reminderPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DailyReminderReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REMINDER_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        var next = now.withHour(hour.coerceIn(0, 23))
            .withMinute(minute.coerceIn(0, 59))
            .withSecond(0)
            .withNano(0)
        if (!next.isAfter(now)) {
            next = next.plusDays(1)
        }
        return next.toInstant().toEpochMilli()
    }
}

class DailyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(DAILY_REMINDER_ENABLED_KEY, false)) {
            return
        }
        val hour = preferences.getInt(DAILY_REMINDER_HOUR_KEY, DEFAULT_REMINDER_HOUR)
        val minute = preferences.getInt(DAILY_REMINDER_MINUTE_KEY, DEFAULT_REMINDER_MINUTE)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { buildReminderMessage(appContext) }
                    .onSuccess { postReminderNotification(appContext, it) }
            } finally {
                // Inexact alarms fire once, so queue up the next day before finishing.
                DailyReminder.schedule(appContext, hour, minute)
                pendingResult.finish()
            }
        }
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        val preferences = context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(DAILY_REMINDER_ENABLED_KEY, false)) {
            return
        }
        DailyReminder.schedule(
            context,
            preferences.getInt(DAILY_REMINDER_HOUR_KEY, DEFAULT_REMINDER_HOUR),
            preferences.getInt(DAILY_REMINDER_MINUTE_KEY, DEFAULT_REMINDER_MINUTE)
        )
    }
}

private suspend fun buildReminderMessage(context: Context): String {
    val preferences = context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
    val serverUrl = preferences.getString(SERVER_URL_KEY, DEFAULT_WEB_BASE_URL) ?: DEFAULT_WEB_BASE_URL
    val tracker = fetchTrackerState(serverUrl.toServerConfig().apiBaseUrl)
    val progress = tracker.event.progressAt(Instant.now())
    val greeting = greetingForHour(ZonedDateTime.now(ZoneId.systemDefault()).hour)
    return "$greeting! You are ${progress.elapsedDays}/${progress.totalDays} days " +
        "(${progress.percent.percentString()}%) the way through your ${tracker.event.name}! " +
        "Here's your daily progress check-in."
}

private fun greetingForHour(hour: Int): String =
    when {
        hour < 12 -> "Good Morning"
        hour < 18 -> "Good Afternoon"
        else -> "Good Evening"
    }

private fun postReminderNotification(context: Context, message: String) {
    ensureReminderChannel(context)

    val openAppIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_tt)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setContentIntent(openAppIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()

    val manager = NotificationManagerCompat.from(context)
    if (manager.areNotificationsEnabled()) {
        runCatching { manager.notify(REMINDER_NOTIFICATION_ID, notification) }
    }
}

private fun ensureReminderChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val channel = NotificationChannel(
        REMINDER_CHANNEL_ID,
        "Daily progress reminder",
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = "A daily check-in on how far you are through your event."
    }
    manager.createNotificationChannel(channel)
}
