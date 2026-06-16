package dev.hydranet.timetrackerapp

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.currentState
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.colorProviders
import androidx.glance.ColorFilter
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TimeTrackerWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TimeTrackerWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        TimeTrackerWidgetUpdater.start(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        TimeTrackerWidgetUpdater.stop(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        TimeTrackerWidgetUpdater.start(context)
        refreshTimeTrackerWidgets(context, WidgetFetchPolicy.NetworkIfStale)
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}

private class TimeTrackerWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        refreshWidgetState(context, id, WidgetFetchPolicy.CacheOnly)

        provideContent {
            GlanceTheme(colors = widgetColors) {
                TimeTrackerWidgetContent()
            }
        }
    }
}

@Composable
private fun TimeTrackerWidgetContent() {
    val context = LocalContext.current
    val preferences = currentState<Preferences>()
    val snapshot = preferences.toWidgetSnapshot()
    val errorMessage = preferences[WidgetStateKeys.ErrorMessage]

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(28.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(14.dp)
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(top = 26.dp, start = 2.dp, end = 2.dp, bottom = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (snapshot == null) {
                WidgetErrorContent(
                    message = errorMessage ?: context.getString(R.string.widget_error_message)
                )
            } else {
                WidgetProgressContent(snapshot)
            }
        }
        Box(
            modifier = GlanceModifier.fillMaxSize(),
            contentAlignment = Alignment.TopStart
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = context.getString(R.string.widget_name),
                    modifier = GlanceModifier
                        .defaultWeight()
                        .padding(start = 6.dp, top = 3.dp),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
                RefreshButton()
            }
        }
    }
}

@Composable
private fun WidgetProgressContent(snapshot: WidgetSnapshot) {
    Text(
        text = snapshot.eventName.uppercase(),
        modifier = GlanceModifier.fillMaxWidth(),
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        ),
        maxLines = 2
    )
    Spacer(modifier = GlanceModifier.height(8.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = snapshot.percentText,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1
        )
        Text(
            text = "%",
            modifier = GlanceModifier.padding(start = 3.dp, bottom = 5.dp),
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
    Spacer(modifier = GlanceModifier.height(14.dp))
    LinearProgressIndicator(
        progress = snapshot.percentFraction,
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(8.dp),
        color = snapshot.accentColor.toWidgetColorProvider() ?: GlanceTheme.colors.primary,
        backgroundColor = GlanceTheme.colors.surfaceVariant
    )
    Spacer(modifier = GlanceModifier.height(12.dp))
    Text(
        text = snapshot.dateRange,
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        ),
        maxLines = 2
    )
}

@Composable
private fun WidgetErrorContent(message: String) {
    Text(
        text = "Cannot load tracker",
        modifier = GlanceModifier.fillMaxWidth(),
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        ),
        maxLines = 2
    )
    Spacer(modifier = GlanceModifier.height(8.dp))
    Text(
        text = message,
        modifier = GlanceModifier.fillMaxWidth(),
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        ),
        maxLines = 2
    )
}

@Composable
private fun RefreshButton() {
    Box(
        modifier = GlanceModifier
            .size(34.dp)
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(17.dp)
            .clickable(actionRunCallback<RefreshTimeTrackerWidgetAction>()),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_refresh),
            contentDescription = "Refresh",
            modifier = GlanceModifier.size(18.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface)
        )
    }
}

internal fun refreshTimeTrackerWidgets(
    context: Context,
    fetchPolicy: WidgetFetchPolicy = WidgetFetchPolicy.ForceNetwork
) {
    CoroutineScope(Dispatchers.IO).launch {
        refreshTimeTrackerWidgetsNow(context.applicationContext, fetchPolicy)
    }
}

private suspend fun refreshTimeTrackerWidgetsNow(
    context: Context,
    fetchPolicy: WidgetFetchPolicy
): Boolean {
    val manager = GlanceAppWidgetManager(context)
    val glanceIds = manager.getGlanceIds(TimeTrackerWidget::class.java)
    glanceIds.forEach { glanceId ->
        refreshWidgetState(context, glanceId, fetchPolicy)
        TimeTrackerWidget().update(context, glanceId)
    }
    return glanceIds.isNotEmpty()
}

internal enum class WidgetFetchPolicy {
    CacheOnly,
    NetworkIfStale,
    ForceNetwork
}

private object TimeTrackerWidgetUpdater {
    private const val WIDGET_UPDATE_INTERVAL_MS = 15 * 60 * 1000L
    private const val WIDGET_UPDATE_REQUEST_CODE = 2003

    fun start(context: Context) {
        val appContext = context.applicationContext
        schedule(appContext)
        refreshTimeTrackerWidgets(appContext, WidgetFetchPolicy.NetworkIfStale)
    }

    fun stop(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(updatePendingIntent(context))
    }

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + WIDGET_UPDATE_INTERVAL_MS,
            updatePendingIntent(context)
        )
    }

    private fun updatePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TimeTrackerWidgetUpdateReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            WIDGET_UPDATE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class TimeTrackerWidgetUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val hasWidgets = refreshTimeTrackerWidgetsNow(
                    appContext,
                    WidgetFetchPolicy.NetworkIfStale
                )
                if (hasWidgets) {
                    TimeTrackerWidgetUpdater.schedule(appContext)
                } else {
                    TimeTrackerWidgetUpdater.stop(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class TimeTrackerWidgetBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = GlanceAppWidgetManager(appContext)
                if (manager.getGlanceIds(TimeTrackerWidget::class.java).isNotEmpty()) {
                    TimeTrackerWidgetUpdater.start(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

internal fun refreshTimeTrackerWidgetsFromCachedEvent(context: Context) {
    CoroutineScope(Dispatchers.IO).launch {
        val manager = GlanceAppWidgetManager(context)
        manager.getGlanceIds(TimeTrackerWidget::class.java).forEach { glanceId ->
            refreshWidgetState(context, glanceId, WidgetFetchPolicy.CacheOnly)
            TimeTrackerWidget().update(context, glanceId)
        }
    }
}

class RefreshTimeTrackerWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        refreshWidgetState(context, glanceId, WidgetFetchPolicy.ForceNetwork)
        TimeTrackerWidget().update(context, glanceId)
    }
}

private suspend fun refreshWidgetState(
    context: Context,
    glanceId: GlanceId,
    fetchPolicy: WidgetFetchPolicy
) {
    val result = runCatching { fetchWidgetSnapshot(context, fetchPolicy) }
    updateAppWidgetState(context, glanceId) { preferences ->
        result.fold(
            onSuccess = { snapshot ->
                preferences[WidgetStateKeys.HasData] = true
                preferences[WidgetStateKeys.EventName] = snapshot.eventName
                preferences[WidgetStateKeys.PercentText] = snapshot.percentText
                preferences[WidgetStateKeys.PercentFraction] = snapshot.percentFraction
                preferences[WidgetStateKeys.AccentColor] = snapshot.accentColor
                preferences[WidgetStateKeys.DateRange] = snapshot.dateRange
                preferences.remove(WidgetStateKeys.ErrorMessage)
            },
            onFailure = { throwable ->
                preferences[WidgetStateKeys.HasData] = false
                preferences[WidgetStateKeys.ErrorMessage] = throwable.toWidgetErrorMessage()
            }
        )
    }
}

private fun Throwable.toWidgetErrorMessage(): String =
    when {
        message?.contains("health", ignoreCase = true) == true -> {
            "Check the server URL, then tap refresh."
        }
        else -> "Tap to open the app, or refresh again."
    }

private fun Preferences.toWidgetSnapshot(): WidgetSnapshot? {
    if (this[WidgetStateKeys.HasData] != true) {
        return null
    }

    return WidgetSnapshot(
        eventName = this[WidgetStateKeys.EventName] ?: return null,
        percentText = this[WidgetStateKeys.PercentText] ?: return null,
        percentFraction = this[WidgetStateKeys.PercentFraction] ?: return null,
        accentColor = this[WidgetStateKeys.AccentColor] ?: return null,
        dateRange = this[WidgetStateKeys.DateRange] ?: return null
    )
}

private suspend fun fetchWidgetSnapshot(
    context: Context,
    fetchPolicy: WidgetFetchPolicy
): WidgetSnapshot {
    val preferences = context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
    val event = resolveWidgetEvent(context, preferences, fetchPolicy)
    val progress = event.progressAt(Instant.now())

    return WidgetSnapshot(
        eventName = event.name,
        percentText = progress.percent.percentString(),
        percentFraction = progress.percentFraction,
        accentColor = event.accentColor,
        dateRange = "${progress.start.formatDisplayDate()} to ${progress.end.formatDisplayDate()}"
    )
}

private suspend fun resolveWidgetEvent(
    context: Context,
    preferences: SharedPreferences,
    fetchPolicy: WidgetFetchPolicy
): EventSummary {
    val cached = preferences.cachedTrackerEvent()
    val shouldFetch = when (fetchPolicy) {
        WidgetFetchPolicy.CacheOnly -> false
        WidgetFetchPolicy.NetworkIfStale -> cached == null || preferences.isCachedTrackerEventStale()
        WidgetFetchPolicy.ForceNetwork -> true
    }

    if (!shouldFetch) {
        return cached ?: throw IOException("No cached tracker data yet.")
    }

    return runCatching { fetchAndCacheTrackerEvent(context) }
        .getOrElse { throwable ->
            cached ?: throw throwable
        }
}

private data class WidgetSnapshot(
    val eventName: String,
    val percentText: String,
    val percentFraction: Float,
    val accentColor: String,
    val dateRange: String
)

private object WidgetStateKeys {
    val HasData = booleanPreferencesKey("has_data")
    val EventName = stringPreferencesKey("event_name")
    val PercentText = stringPreferencesKey("percent_text")
    val PercentFraction = floatPreferencesKey("percent_fraction")
    val AccentColor = stringPreferencesKey("accent_color")
    val DateRange = stringPreferencesKey("date_range")
    val ErrorMessage = stringPreferencesKey("error_message")
}

private fun String.toWidgetColorProvider(): ColorProvider? =
    runCatching { ColorProvider(Color(android.graphics.Color.parseColor(this))) }.getOrNull()

@SuppressLint("RestrictedApi")
private val widgetColors = colorProviders(
    primary = ColorProvider(R.color.widget_accent),
    onPrimary = ColorProvider(R.color.widget_background),
    primaryContainer = ColorProvider(R.color.widget_accent),
    onPrimaryContainer = ColorProvider(R.color.widget_background),
    secondary = ColorProvider(R.color.widget_accent),
    onSecondary = ColorProvider(R.color.widget_background),
    secondaryContainer = ColorProvider(R.color.widget_background),
    onSecondaryContainer = ColorProvider(R.color.widget_text),
    tertiary = ColorProvider(R.color.widget_accent),
    onTertiary = ColorProvider(R.color.widget_background),
    tertiaryContainer = ColorProvider(R.color.widget_background),
    onTertiaryContainer = ColorProvider(R.color.widget_text),
    error = ColorProvider(R.color.widget_text),
    errorContainer = ColorProvider(R.color.widget_background),
    onError = ColorProvider(R.color.widget_background),
    onErrorContainer = ColorProvider(R.color.widget_text),
    background = ColorProvider(R.color.widget_background),
    onBackground = ColorProvider(R.color.widget_text),
    surface = ColorProvider(R.color.widget_background),
    onSurface = ColorProvider(R.color.widget_text),
    surfaceVariant = ColorProvider(R.color.widget_progress_track),
    onSurfaceVariant = ColorProvider(R.color.widget_muted_text),
    outline = ColorProvider(R.color.widget_muted_text),
    inverseOnSurface = ColorProvider(R.color.widget_background),
    inverseSurface = ColorProvider(R.color.widget_text),
    inversePrimary = ColorProvider(R.color.widget_accent),
    widgetBackground = ColorProvider(R.color.widget_background)
)
