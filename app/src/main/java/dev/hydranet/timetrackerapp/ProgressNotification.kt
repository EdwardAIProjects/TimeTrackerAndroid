package dev.hydranet.timetrackerapp

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal const val PROGRESS_NOTIFICATION_ENABLED_KEY = "progress_notification_enabled"

private const val PROGRESS_CHANNEL_ID = "persistent_progress"
private const val PROGRESS_NOTIFICATION_ID = 1002
private const val PROGRESS_REQUEST_CODE = 2002

// How often the ongoing notification re-evaluates progress. Progress is recomputed
// locally from the cached event dates each tick, so this is cheap; only an occasional
// tick refetches fresh event data from the server.
private const val PROGRESS_UPDATE_INTERVAL_MS = 15 * 60 * 1000L
// Notification progress bar resolution: basis points give two decimals of precision.
private const val PROGRESS_BAR_MAX = 10_000

internal object ProgressNotification {
    /**
     * Turn the persistent notification on: post it right away (fetching fresh data) and
     * schedule the recurring local updates.
     */
    fun enable(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { fetchAndCacheTrackerEvent(appContext) }
                .getOrNull()
                ?.let { postProgressNotification(appContext, it) }
        }
        schedule(appContext)
    }

    /** Turn the persistent notification off: cancel updates and remove the notification. */
    fun disable(context: Context) {
        val appContext = context.applicationContext
        cancelAlarm(appContext)
        NotificationManagerCompat.from(appContext).cancel(PROGRESS_NOTIFICATION_ID)
    }

    /**
     * Re-post the notification using an already-loaded tracker, so opening or refreshing
     * the app pushes a fresh value immediately. No-op when the feature is disabled.
     */
    fun onTrackerLoaded(context: Context, tracker: TrackerState) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(PROGRESS_NOTIFICATION_ENABLED_KEY, false)) {
            return
        }
        preferences.cacheTrackerEvent(tracker.event)
        postProgressNotification(appContext, tracker.event)
    }

    internal fun schedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + PROGRESS_UPDATE_INTERVAL_MS,
            progressPendingIntent(context)
        )
    }

    private fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(progressPendingIntent(context))
    }

    private fun progressPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ProgressNotificationReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            PROGRESS_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class ProgressNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(PROGRESS_NOTIFICATION_ENABLED_KEY, false)) {
            NotificationManagerCompat.from(appContext).cancel(PROGRESS_NOTIFICATION_ID)
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val event = resolveEvent(appContext, preferences)
                if (event != null) {
                    postProgressNotification(appContext, event)
                }
            } finally {
                // Inexact alarms fire once, so queue the next tick before finishing.
                ProgressNotification.schedule(appContext)
                pendingResult.finish()
            }
        }
    }

    /**
     * Prefer the locally cached event so updates stay live and work offline. Only hit the
     * network when there is no cache yet, or the cache has gone stale.
     */
    private suspend fun resolveEvent(context: Context, preferences: SharedPreferences): EventSummary? {
        val cached = preferences.cachedTrackerEvent()
        if (cached != null && !preferences.isCachedTrackerEventStale()) {
            return cached
        }
        return runCatching { fetchAndCacheTrackerEvent(context) }.getOrNull() ?: cached
    }
}

class ProgressNotificationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        val preferences = context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(PROGRESS_NOTIFICATION_ENABLED_KEY, false)) {
            return
        }
        ProgressNotification.enable(context)
    }
}

private fun postProgressNotification(context: Context, event: EventSummary) {
    ensureProgressChannel(context)

    val progress = event.progressAt(Instant.now())
    val percentText = progress.percent.percentString()
    val accent = runCatching {
        android.graphics.Color.parseColor(event.accentColor)
    }.getOrNull()

    val openAppIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val builder = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_tt)
        .setContentTitle("$percentText% through ${event.name}")
        .setContentText(
            "${progress.elapsedDays}/${progress.totalDays} days · ${progress.daysLeft} days left"
        )
        .setProgress(PROGRESS_BAR_MAX, (progress.percentFraction * PROGRESS_BAR_MAX).toInt(), false)
        .setContentIntent(openAppIntent)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)
    if (accent != null) {
        builder.color = accent
    }

    val manager = NotificationManagerCompat.from(context)
    if (manager.areNotificationsEnabled()) {
        runCatching { manager.notify(PROGRESS_NOTIFICATION_ID, builder.build()) }
    }
}

private fun ensureProgressChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val channel = NotificationChannel(
        PROGRESS_CHANNEL_ID,
        "Persistent progress",
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        description = "An ongoing notification showing how far you are through your event."
        setShowBadge(false)
    }
    manager.createNotificationChannel(channel)
}
