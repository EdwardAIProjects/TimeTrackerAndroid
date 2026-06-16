package dev.hydranet.timetrackerapp

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

internal const val TRACKER_EVENT_REFETCH_INTERVAL_MS = 6 * 60 * 60 * 1000L

private const val CACHED_EVENT_NAME_KEY = "progress_cache_event_name"
private const val CACHED_EVENT_ACCENT_KEY = "progress_cache_event_accent"
private const val CACHED_EVENT_START_KEY = "progress_cache_event_start"
private const val CACHED_EVENT_END_KEY = "progress_cache_event_end"
private const val CACHED_FETCH_TIME_KEY = "progress_cache_fetch_time"

internal suspend fun fetchAndCacheTrackerEvent(context: Context): EventSummary {
    val preferences = context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
    val serverUrl = preferences.getString(SERVER_URL_KEY, DEFAULT_WEB_BASE_URL) ?: DEFAULT_WEB_BASE_URL
    val tracker = fetchTrackerState(serverUrl.toServerConfig().apiBaseUrl)
    preferences.cacheTrackerEvent(tracker.event)
    return tracker.event
}

internal fun SharedPreferences.cacheTrackerEvent(event: EventSummary) {
    edit()
        .putString(CACHED_EVENT_NAME_KEY, event.name)
        .putString(CACHED_EVENT_ACCENT_KEY, event.accentColor)
        .putString(CACHED_EVENT_START_KEY, event.startDate.toString())
        .putString(CACHED_EVENT_END_KEY, event.endDate.toString())
        .putLong(CACHED_FETCH_TIME_KEY, System.currentTimeMillis())
        .apply()
}

internal fun SharedPreferences.cachedTrackerEvent(): EventSummary? {
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

internal fun SharedPreferences.isCachedTrackerEventStale(): Boolean {
    val lastFetch = getLong(CACHED_FETCH_TIME_KEY, 0L)
    return System.currentTimeMillis() - lastFetch >= TRACKER_EVENT_REFETCH_INTERVAL_MS
}
