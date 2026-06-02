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
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal const val PROGRESS_NOTIFICATION_ENABLED_KEY = "progress_notification_enabled"

private const val CACHED_EVENT_NAME_KEY = "progress_cache_event_name"
private const val CACHED_EVENT_ACCENT_KEY = "progress_cache_event_accent"
private const val CACHED_EVENT_START_KEY = "progress_cache_event_start"
private const val CACHED_EVENT_END_KEY = "progress_cache_event_end"
private const val CACHED_FETCH_TIME_KEY = "progress_cache_fetch_time"

private const val PROGRESS_CHANNEL_ID = "persistent_progress"
private const val PROGRESS_NOTIFICATION_ID = 1002
private const val PROGRESS_REQUEST_CODE = 2002

// How often the ongoing notification re-evaluates progress. Progress is recomputed
// locally from the cached event dates each tick, so this is cheap; only an occasional
// tick refetches fresh event data from the server.
private const val PROGRESS_UPDATE_INTERVAL_MS = 15 * 60 * 1000L
// Refetch event data from the server at most this often; otherwise recompute locally.
private const val PROGRESS_REFETCH_INTERVAL_MS = 6 * 60 * 60 * 1000L
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
            runCatching { refreshFromServer(appContext) }
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
        val lastFetch = preferences.getLong(CACHED_FETCH_TIME_KEY, 0L)
        val isStale = System.currentTimeMillis() - lastFetch >= PROGRESS_REFETCH_INTERVAL_MS
        if (cached != null && !isStale) {
            return cached
        }
        return runCatching { refreshFromServer(context) }.getOrNull() ?: cached
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

/** Fetch fresh tracker state, update the cache, and return the event used for progress. */
private suspend fun refreshFromServer(context: Context): EventSummary {
    val preferences = context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
    val serverUrl = preferences.getString(SERVER_URL_KEY, DEFAULT_WEB_BASE_URL) ?: DEFAULT_WEB_BASE_URL
    val tracker = fetchTrackerState(serverUrl.toServerConfig().apiBaseUrl)
    preferences.cacheTrackerEvent(tracker.event)
    return tracker.event
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
        .setSmallIcon(R.drawable.ic_refresh)
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

private fun SharedPreferences.cacheTrackerEvent(event: EventSummary) {
    edit()
        .putString(CACHED_EVENT_NAME_KEY, event.name)
        .putString(CACHED_EVENT_ACCENT_KEY, event.accentColor)
        .putString(CACHED_EVENT_START_KEY, event.startDate.toString())
        .putString(CACHED_EVENT_END_KEY, event.endDate.toString())
        .putLong(CACHED_FETCH_TIME_KEY, System.currentTimeMillis())
        .apply()
}

private fun SharedPreferences.cachedTrackerEvent(): EventSummary? {
    val name = getString(CACHED_EVENT_NAME_KEY, null) ?: return null
    val accent = getString(CACHED_EVENT_ACCENT_KEY, null) ?: return null
    val start = getString(CACHED_EVENT_START_KEY, null)?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    } ?: return null
    val end = getString(CACHED_EVENT_END_KEY, null)?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    } ?: return null

    return EventSummary(
        id = "cached",
        name = name,
        accentColor = accent,
        showDateTimeBanner = false,
        startDate = start,
        endDate = end
    )
}
