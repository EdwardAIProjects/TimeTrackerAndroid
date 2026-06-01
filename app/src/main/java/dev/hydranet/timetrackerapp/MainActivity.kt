package dev.hydranet.timetrackerapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hydranet.timetrackerapp.ui.theme.TimeTrackerAppTheme
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val DEFAULT_WEB_BASE_URL = "https://example.com"
internal const val SETTINGS_NAME = "time_tracker_settings"
internal const val SERVER_URL_KEY = "server_url"
private const val HAS_BOOTED_KEY = "has_booted"
private const val AUTO_REFRESH_KEY = "auto_refresh"
private const val AUTO_REFRESH_INTERVAL_MS = 60_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TimeTrackerAppTheme {
                TimeTrackerApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeTrackerApp() {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
    }
    var loadKey by remember { mutableIntStateOf(0) }
    var serverUrl by remember {
        mutableStateOf(preferences.getString(SERVER_URL_KEY, DEFAULT_WEB_BASE_URL) ?: DEFAULT_WEB_BASE_URL)
    }
    var metricSettings by remember {
        mutableStateOf(preferences.loadMetricSettings())
    }
    var autoRefreshEnabled by remember {
        mutableStateOf(preferences.getBoolean(AUTO_REFRESH_KEY, false))
    }
    var reminderEnabled by remember {
        mutableStateOf(preferences.getBoolean(DAILY_REMINDER_ENABLED_KEY, false))
    }
    var reminderHour by remember {
        mutableIntStateOf(preferences.getInt(DAILY_REMINDER_HOUR_KEY, DEFAULT_REMINDER_HOUR))
    }
    var reminderMinute by remember {
        mutableIntStateOf(preferences.getInt(DAILY_REMINDER_MINUTE_KEY, DEFAULT_REMINDER_MINUTE))
    }
    var notificationsBlocked by remember { mutableStateOf(false) }

    fun enableReminder() {
        reminderEnabled = true
        notificationsBlocked = false
        preferences.edit().putBoolean(DAILY_REMINDER_ENABLED_KEY, true).apply()
        DailyReminder.schedule(context, reminderHour, reminderMinute)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            enableReminder()
        } else {
            notificationsBlocked = true
        }
    }
    val hasBootedBefore = remember {
        preferences.getBoolean(HAS_BOOTED_KEY, false)
    }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var uiState by remember { mutableStateOf<TrackerUiState>(TrackerUiState.Loading) }
    val serverConfig = remember(serverUrl) { serverUrl.toServerConfig() }

    LaunchedEffect(Unit) {
        if (!hasBootedBefore) {
            preferences.edit().putBoolean(HAS_BOOTED_KEY, true).apply()
        }
    }

    LaunchedEffect(loadKey, serverConfig.apiBaseUrl) {
        val isManualRefresh = uiState is TrackerUiState.Ready
        if (isManualRefresh) {
            isRefreshing = true
        } else {
            uiState = TrackerUiState.Loading
        }
        uiState = runCatching { fetchTrackerState(serverConfig.apiBaseUrl) }
            .fold(
                onSuccess = TrackerUiState::Ready,
                onFailure = {
                    if (it is HealthCheckException) {
                        if (hasBootedBefore) {
                            TrackerUiState.HealthUnavailable(it.message ?: "Server health check failed.")
                        } else {
                            TrackerUiState.ServerUnavailable
                        }
                    } else {
                        TrackerUiState.Error(it.message ?: "Could not load tracker.")
                    }
                }
            )
        isRefreshing = false
    }

    val isReady = uiState is TrackerUiState.Ready
    LaunchedEffect(autoRefreshEnabled, isReady) {
        if (autoRefreshEnabled && isReady) {
            while (true) {
                delay(AUTO_REFRESH_INTERVAL_MS)
                loadKey++
            }
        }
    }

    Scaffold { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            if (isSettingsOpen) {
                SettingsScreen(
                    serverUrl = serverConfig.webBaseUrl,
                    metricSettings = metricSettings,
                    autoRefreshEnabled = autoRefreshEnabled,
                    onAutoRefreshChange = { enabled ->
                        autoRefreshEnabled = enabled
                        preferences.edit().putBoolean(AUTO_REFRESH_KEY, enabled).apply()
                    },
                    reminderEnabled = reminderEnabled,
                    reminderHour = reminderHour,
                    reminderMinute = reminderMinute,
                    notificationsBlocked = notificationsBlocked,
                    onReminderEnabledChange = { enabled ->
                        if (enabled) {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                enableReminder()
                            } else {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        } else {
                            reminderEnabled = false
                            notificationsBlocked = false
                            preferences.edit().putBoolean(DAILY_REMINDER_ENABLED_KEY, false).apply()
                            DailyReminder.cancel(context)
                        }
                    },
                    onReminderTimeChange = { hour, minute ->
                        reminderHour = hour
                        reminderMinute = minute
                        preferences.edit()
                            .putInt(DAILY_REMINDER_HOUR_KEY, hour)
                            .putInt(DAILY_REMINDER_MINUTE_KEY, minute)
                            .apply()
                        if (reminderEnabled) {
                            DailyReminder.schedule(context, hour, minute)
                        }
                    },
                    onSave = { nextUrl ->
                        val normalizedUrl = nextUrl.toServerConfig().webBaseUrl
                        preferences.edit().putString(SERVER_URL_KEY, normalizedUrl).apply()
                        serverUrl = normalizedUrl
                        refreshTimeTrackerWidgets(context.applicationContext)
                        isSettingsOpen = false
                    },
                    onMetricSettingChange = { option, enabled ->
                        metricSettings = metricSettings.with(option, enabled)
                        preferences.edit().putBoolean(option.preferenceKey, enabled).apply()
                    }
                )
            } else {
                when (val state = uiState) {
                    TrackerUiState.Loading -> LoadingScreen()
                    TrackerUiState.ServerUnavailable -> ServerUnavailableScreen(
                        onConfigureServer = { isSettingsOpen = true }
                    )

                    is TrackerUiState.HealthUnavailable -> HealthUnavailableScreen(
                        message = state.message,
                        onConfigureServer = { isSettingsOpen = true },
                        onRetry = { loadKey++ }
                    )

                    is TrackerUiState.Error -> ErrorScreen(
                        message = state.message,
                        onRetry = { loadKey++ },
                        onConfigureServer = { isSettingsOpen = true }
                    )

                    is TrackerUiState.Ready -> TrackerDashboard(
                        tracker = state.tracker,
                        webBaseUrl = serverConfig.webBaseUrl,
                        metricSettings = metricSettings,
                        isRefreshing = isRefreshing,
                        onOpenSettings = { isSettingsOpen = true },
                        onRefresh = { loadKey++ }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackerDashboard(
    tracker: TrackerState,
    webBaseUrl: String,
    metricSettings: MetricSettings,
    isRefreshing: Boolean,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit
) {
    var now by remember { mutableStateOf(Instant.now()) }
    val uriHandler = LocalUriHandler.current
    val progress = remember(tracker.event, now) { tracker.event.progressAt(now) }
    val completedCount = tracker.todos.count { it.done }
    val todoProgress = if (tracker.todos.isEmpty()) {
        0f
    } else {
        completedCount.toFloat() / tracker.todos.size.toFloat()
    }
    val accent = tracker.event.accentColor.toComposeColor(MaterialTheme.colorScheme.primary)

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = Instant.now()
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 132.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Header(
                    eventName = tracker.event.name,
                    accent = accent,
                    onOpenSettings = onOpenSettings
                )
            }

            item {
                ProgressSummary(
                    eventName = tracker.event.name,
                    progress = progress,
                    accent = accent
                )
            }

            item {
                MetricGrid(
                    progress = progress,
                    metricSettings = metricSettings
                )
            }

            item {
                TodoPanel(
                    todos = tracker.todos,
                    completedCount = completedCount,
                    todoProgress = todoProgress,
                    accent = accent
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Button(
                onClick = { uriHandler.openUri(webBaseUrl) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = "Open in web to edit",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    painter = painterResource(id = R.drawable.ic_external_link),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun Header(
    eventName: String,
    accent: Color,
    onOpenSettings: () -> Unit
) {
    val density = LocalDensity.current
    var titleWidth by remember { mutableStateOf(0.dp) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = eventName.uppercase(Locale.getDefault()),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Column {
                Text(
                    text = "Time Tracker",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 36.sp,
                    lineHeight = 37.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    onTextLayout = { layoutResult ->
                        titleWidth = with(density) { layoutResult.size.width.toDp() }
                    }
                )
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .width(titleWidth)
                        .height(5.dp)
                        .background(accent)
                )
            }
        }

        Column(
            modifier = Modifier.padding(start = 12.dp),
            horizontalAlignment = Alignment.End
        ) {
            HeaderTextButton(text = "Settings", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun HeaderTextButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun appPanelColor(): Color =
    if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    }

@Composable
private fun appInsetColor(): Color =
    if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    }

@Composable
private fun appTrackColor(): Color =
    if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    }

@Composable
private fun SettingsScreen(
    serverUrl: String,
    metricSettings: MetricSettings,
    autoRefreshEnabled: Boolean,
    onAutoRefreshChange: (Boolean) -> Unit,
    reminderEnabled: Boolean,
    reminderHour: Int,
    reminderMinute: Int,
    notificationsBlocked: Boolean,
    onReminderEnabledChange: (Boolean) -> Unit,
    onReminderTimeChange: (Int, Int) -> Unit,
    onSave: (String) -> Unit,
    onMetricSettingChange: (MetricOption, Boolean) -> Unit
) {
    var draftUrl by remember(serverUrl) { mutableStateOf(serverUrl) }
    val parsedConfig = remember(draftUrl) { draftUrl.toServerConfigOrNull() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Settings",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 36.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                HeaderTextButton(
                    text = "Save",
                    onClick = { onSave(draftUrl) },
                    enabled = parsedConfig != null
                )
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = appPanelColor(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Server",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    TextField(
                        value = draftUrl,
                        onValueChange = { draftUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Web URL") },
                        placeholder = { Text(DEFAULT_WEB_BASE_URL) },
                        isError = parsedConfig == null,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = appInsetColor(),
                            unfocusedContainerColor = appInsetColor(),
                            errorContainerColor = appInsetColor()
                        )
                    )
                    Text(
                        text = parsedConfig?.apiBaseUrl ?: "Enter a valid HTTP or HTTPS URL.",
                        color = if (parsedConfig == null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 14.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = appPanelColor(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "General",
                        modifier = Modifier.padding(bottom = 12.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    SettingToggleRow(
                        label = "Auto-refresh",
                        description = "Reload stats from the server every minute.",
                        checked = autoRefreshEnabled,
                        onCheckedChange = onAutoRefreshChange
                    )
                    SettingToggleRow(
                        label = "Daily reminder",
                        description = "Get a daily notification with your progress.",
                        checked = reminderEnabled,
                        onCheckedChange = onReminderEnabledChange
                    )
                    if (notificationsBlocked) {
                        Text(
                            text = "Notifications are turned off. Enable them for " +
                                "TimeTracker in your system settings to get reminders.",
                            modifier = Modifier.padding(start = 4.dp, end = 2.dp, top = 2.dp),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (reminderEnabled) {
                        var showTimePicker by remember { mutableStateOf(false) }
                        ReminderTimeRow(
                            hour = reminderHour,
                            minute = reminderMinute,
                            onClick = { showTimePicker = true }
                        )
                        if (showTimePicker) {
                            ReminderTimePickerDialog(
                                initialHour = reminderHour,
                                initialMinute = reminderMinute,
                                onConfirm = { hour, minute ->
                                    onReminderTimeChange(hour, minute)
                                    showTimePicker = false
                                },
                                onDismiss = { showTimePicker = false }
                            )
                        }
                    }
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = appPanelColor(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Metrics",
                        modifier = Modifier.padding(bottom = 12.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    MetricOption.entries.forEach { option ->
                        MetricToggleRow(
                            option = option,
                            checked = metricSettings.isEnabled(option),
                            onCheckedChange = { onMetricSettingChange(option, it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricToggleRow(
    option: MetricOption,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(start = 4.dp, top = 0.dp, end = 2.dp, bottom = 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = option.label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingToggleRow(
    label: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(start = 4.dp, top = 4.dp, end = 2.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (description != null) {
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    lineHeight = 17.sp
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun ReminderTimeRow(
    hour: Int,
    minute: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 4.dp, top = 4.dp, end = 2.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Reminder time",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = formatReminderTime(hour, minute),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TimePicker(state = state)
            }
        }
    )
}

private fun formatReminderTime(hour: Int, minute: Int): String =
    LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))

@Composable
private fun ProgressSummary(
    eventName: String,
    progress: Progress,
    accent: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = appPanelColor(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "PROGRESS THROUGH ${eventName.uppercase(Locale.getDefault())}",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Center
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = progress.percent.percentString(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Black,
                    fontSize = 82.sp,
                    lineHeight = 84.sp
                )
                Text(
                    text = "%",
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Black,
                    fontSize = 34.sp
                )
            }
            LinearProgressIndicator(
                progress = { progress.percentFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape),
                color = accent,
                trackColor = appTrackColor()
            )
            Text(
                text = "${progress.start.formatDisplayDate()} to ${progress.end.formatDisplayDate()}",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}

@Composable
private fun MetricGrid(
    progress: Progress,
    metricSettings: MetricSettings
) {
    val metrics = MetricOption.entries.filter { metricSettings.isEnabled(it) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (metrics.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = appPanelColor(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = "No metrics selected",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        } else {
            metrics.forEach { option ->
                val value = option.value(progress)
                val total = option.total(progress)
                MetricCell(
                    value = value,
                    total = total,
                    label = option.label
                )
            }
        }
    }
}

@Composable
private fun MetricCell(
    value: String,
    total: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = appPanelColor(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.uppercase(Locale.getDefault()),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
                maxLines = 1
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Black,
                    fontSize = 42.sp,
                    lineHeight = 44.sp,
                    maxLines = 1
                )
                Text(
                    text = " / $total",
                    modifier = Modifier.padding(start = 3.dp, bottom = 5.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TodoPanel(
    todos: List<Todo>,
    completedCount: Int,
    todoProgress: Float,
    accent: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = appPanelColor(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Key things to do",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 23.sp
                )
                Surface(
                    color = appInsetColor(),
                    shape = CircleShape
                ) {
                    Text(
                        text = "$completedCount/${todos.size} done",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
            LinearProgressIndicator(
                progress = { todoProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(CircleShape),
                color = accent,
                trackColor = appTrackColor()
            )
            if (todos.isEmpty()) {
                Text(
                    text = "No todos yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 14.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    todos.forEach { todo ->
                        TodoRow(todo = todo)
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoRow(todo: Todo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = todo.done,
            onCheckedChange = null,
            modifier = Modifier.size(40.dp)
        )
        Text(
            text = todo.title,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            color = if (todo.done) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontWeight = FontWeight.SemiBold,
            fontSize = 19.sp,
            lineHeight = 23.sp,
            textDecoration = if (todo.done) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Loading tracker...",
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ErrorScreen(
    message: String,
    onRetry: () -> Unit,
    onConfigureServer: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = appPanelColor(),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Could not load Time Tracker",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onConfigureServer) {
                        Text("Server")
                    }
                    Button(onClick = onRetry) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerUnavailableScreen(
    onConfigureServer: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = appPanelColor(),
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Time Tracker",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 38.sp,
                    lineHeight = 42.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Connect to your tracker server to load your progress and tasks.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 17.sp,
                    lineHeight = 23.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(22.dp))
                Button(
                    onClick = onConfigureServer,
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = "Set server",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthUnavailableScreen(
    message: String,
    onConfigureServer: () -> Unit,
    onRetry: () -> Unit
) {
    var showFullError by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = appPanelColor(),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Cannot connect to the\nTime Tracker server",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 25.sp,
                    lineHeight = 30.sp
                )
                Text(
                    text = "Check the server URL or try again.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 16.sp,
                    lineHeight = 22.sp
                )
                Button(
                    onClick = onConfigureServer,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(15.dp)
                ) {
                    Text("Configure server URL", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(13.dp)
                    ) {
                        Text("Retry", fontSize = 16.sp)
                    }
                    OutlinedButton(
                        onClick = { showFullError = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(13.dp)
                    ) {
                        Text("Show error", fontSize = 16.sp)
                    }
                }
            }
        }
    }

    if (showFullError) {
        AlertDialog(
            onDismissRequest = { showFullError = false },
            title = { Text("Connection details") },
            text = {
                Text(
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .verticalScroll(rememberScrollState()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { clipboardManager.setText(AnnotatedString(message)) }) {
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFullError = false }) {
                    Text("Close")
                }
            }
        )
    }
}

internal suspend fun fetchTrackerState(apiBaseUrl: String): TrackerState = withContext(Dispatchers.IO) {
    checkHealth("$apiBaseUrl/health")
    val events = fetchEvents("$apiBaseUrl/events")
    val todos = fetchTodos("$apiBaseUrl/todos")
    val event = events.firstOrNull() ?: throw IOException("The API did not return an event.")
    TrackerState(event = event, todos = todos)
}

private fun checkHealth(url: String) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8_000
        readTimeout = 8_000
    }

    try {
        val body = connection.readBody()
        val statusCode = connection.responseCode
        if (statusCode !in 200..299) {
            throw HealthCheckException(
                buildHealthCheckError(
                    url = url,
                    statusCode = statusCode,
                    body = body
                )
            )
        }
        val isOk = runCatching { JSONObject(body).optBoolean("ok", false) }
            .getOrElse { exception ->
                throw HealthCheckException(
                    buildHealthCheckError(
                        url = url,
                        statusCode = statusCode,
                        body = body,
                        reason = "Server health response was not valid JSON: ${exception.message}"
                    ),
                    exception
                )
            }
        if (!isOk) {
            throw HealthCheckException(
                buildHealthCheckError(
                    url = url,
                    statusCode = statusCode,
                    body = body,
                    reason = "Server health response did not return ok."
                )
            )
        }
    } catch (exception: HealthCheckException) {
        throw exception
    } catch (exception: Exception) {
        throw HealthCheckException(
            buildString {
                append("Server health check failed.")
                append("\n\nURL: ")
                append(url)
                append("\nError: ")
                append(exception::class.java.simpleName)
                exception.message?.let {
                    append(": ")
                    append(it)
                }
            },
            exception
        )
    } finally {
        connection.disconnect()
    }
}

private fun buildHealthCheckError(
    url: String,
    statusCode: Int,
    body: String,
    reason: String = "Server health check returned HTTP $statusCode."
): String = buildString {
    append(reason)
    append("\n\nURL: ")
    append(url)
    append("\nHTTP status: ")
    append(statusCode)
    append("\n\nResponse body:\n")
    append(body.toErrorBodyPreview())
}

private fun String.toErrorBodyPreview(): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) {
        return "(empty response body)"
    }
    return if (trimmed.length <= ERROR_BODY_PREVIEW_LIMIT) {
        trimmed
    } else {
        trimmed.take(ERROR_BODY_PREVIEW_LIMIT) + "\n\n... response body truncated ..."
    }
}

private fun fetchEvents(url: String): List<EventSummary> {
    val array = fetchJsonArray(url)
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                EventSummary(
                    id = item.optString("id", "event-$index"),
                    name = item.optString("name", "Event"),
                    accentColor = item.optString("accentColor", "#6750A4"),
                    startDate = item.optString("startDate", "2026-06-01").toLocalDateOrDefault(),
                    endDate = item.optString("endDate", "2026-08-14").toLocalDateOrDefault()
                )
            )
        }
    }
}

private fun fetchTodos(url: String): List<Todo> {
    val array = fetchJsonArray(url)
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                Todo(
                    id = item.optString("id", "todo-$index"),
                    title = item.optString("title", "Untitled todo"),
                    done = item.optBoolean("done", false)
                )
            )
        }
    }
}

private fun fetchJsonArray(url: String): JSONArray {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8_000
        readTimeout = 8_000
    }

    return try {
        val body = connection.readBody()
        if (connection.responseCode !in 200..299) {
            throw IOException("API returned HTTP ${connection.responseCode}: $body")
        }
        JSONArray(body)
    } finally {
        connection.disconnect()
    }
}

private fun HttpURLConnection.readBody(): String {
    val stream = if (responseCode in 200..299) {
        inputStream
    } else {
        errorStream ?: inputStream
    }
    return stream.bufferedReader().use { it.readText() }
}

internal fun EventSummary.progressAt(now: Instant): Progress {
    val zone = ZoneId.systemDefault()
    val startInstant = startDate.atStartOfDay(zone).toInstant()
    val endExclusive = endDate.plusDays(1)
    val endInstant = endExclusive.atStartOfDay(zone).toInstant()
    val today = now.atZone(zone).toLocalDate()
    val totalMillis = Duration.between(startInstant, endInstant).toMillis().coerceAtLeast(1L)
    val elapsedMillis = Duration.between(startInstant, now).toMillis().coerceIn(0L, totalMillis)
    val remainingMillis = Duration.between(now, endInstant).toMillis().coerceIn(0L, totalMillis)
    val totalDays = ChronoUnit.DAYS.between(startDate, endExclusive).toInt().coerceAtLeast(1)
    val elapsedDays = ChronoUnit.DAYS.between(startDate, today).toInt().coerceIn(0, totalDays)
    val daysLeft = kotlin.math.ceil(remainingMillis.toDouble() / MILLIS_PER_DAY).toInt()
    val totalHours = (totalDays * 24).coerceAtLeast(1)
    val elapsedHours = (elapsedMillis.toDouble() / MILLIS_PER_HOUR).toInt().coerceIn(0, totalHours)
    val hoursLeft = kotlin.math.ceil(remainingMillis.toDouble() / MILLIS_PER_HOUR).toInt()
        .coerceIn(0, totalHours)

    return Progress(
        start = startDate,
        end = endDate,
        totalHours = totalHours,
        elapsedHours = elapsedHours,
        hoursLeft = hoursLeft,
        totalDays = totalDays,
        totalWeeks = totalDays.toDouble() / 7.0,
        elapsedDays = elapsedDays,
        daysLeft = daysLeft,
        weeksDone = elapsedDays.toDouble() / 7.0,
        weeksLeft = daysLeft.toDouble() / 7.0,
        percent = (elapsedMillis.toDouble() / totalMillis.toDouble()) * 100.0
    )
}

private fun String.toLocalDateOrDefault(): LocalDate =
    runCatching { LocalDate.parse(this) }.getOrDefault(LocalDate.of(2026, 6, 1))

internal fun LocalDate.formatDisplayDate(): String =
    format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())).uppercase(Locale.getDefault())

internal fun Double.percentString(): String = percentFormatter.format(this)

internal fun Double.oneDecimalString(): String = oneDecimalFormatter.format(this)

private fun String.toComposeColor(fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(this)) }.getOrDefault(fallback)

internal fun String.toServerConfig(): ServerConfig =
    toServerConfigOrNull() ?: DEFAULT_WEB_BASE_URL.toServerConfigOrNull() ?: ServerConfig(
        webBaseUrl = DEFAULT_WEB_BASE_URL,
        apiBaseUrl = "$DEFAULT_WEB_BASE_URL/api"
    )

private fun String.toServerConfigOrNull(): ServerConfig? {
    val normalized = trim().trimEnd('/')
    if (normalized.isBlank()) {
        return null
    }

    val url = runCatching { URL(normalized) }.getOrNull() ?: return null
    if (url.protocol != "http" && url.protocol != "https") {
        return null
    }

    val webBaseUrl = when {
        normalized.endsWith("/api/mobile") -> normalized.removeSuffix("/api/mobile")
        normalized.endsWith("/api") -> normalized.removeSuffix("/api")
        else -> normalized
    }
    val apiBaseUrl = "$webBaseUrl/api"

    return ServerConfig(webBaseUrl = webBaseUrl, apiBaseUrl = apiBaseUrl)
}

private sealed interface TrackerUiState {
    data object Loading : TrackerUiState
    data object ServerUnavailable : TrackerUiState
    data class HealthUnavailable(val message: String) : TrackerUiState
    data class Ready(val tracker: TrackerState) : TrackerUiState
    data class Error(val message: String) : TrackerUiState
}

private class HealthCheckException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

internal data class TrackerState(
    val event: EventSummary,
    val todos: List<Todo>
)

internal data class ServerConfig(
    val webBaseUrl: String,
    val apiBaseUrl: String
)

internal data class EventSummary(
    val id: String,
    val name: String,
    val accentColor: String,
    val startDate: LocalDate,
    val endDate: LocalDate
)

internal data class Todo(
    val id: String,
    val title: String,
    val done: Boolean
)

internal data class Progress(
    val start: LocalDate,
    val end: LocalDate,
    val totalHours: Int,
    val elapsedHours: Int,
    val hoursLeft: Int,
    val totalDays: Int,
    val totalWeeks: Double,
    val elapsedDays: Int,
    val daysLeft: Int,
    val weeksDone: Double,
    val weeksLeft: Double,
    val percent: Double
) {
    val percentFraction: Float = (percent / 100.0).toFloat().coerceIn(0f, 1f)
}

private enum class MetricOption(
    val preferenceKey: String,
    val label: String,
    val defaultEnabled: Boolean
) {
    HoursLeft(
        preferenceKey = "metric_hours_left",
        label = "Hours left",
        defaultEnabled = false
    ),
    HoursDone(
        preferenceKey = "metric_hours_done",
        label = "Hours done",
        defaultEnabled = false
    ),
    DaysLeft(
        preferenceKey = "metric_days_left",
        label = "Days left",
        defaultEnabled = true
    ),
    DaysDone(
        preferenceKey = "metric_days_done",
        label = "Days done",
        defaultEnabled = true
    ),
    WeeksLeft(
        preferenceKey = "metric_weeks_left",
        label = "Weeks left",
        defaultEnabled = true
    ),
    WeeksDone(
        preferenceKey = "metric_weeks_done",
        label = "Weeks done",
        defaultEnabled = false
    );

    fun value(progress: Progress): String =
        when (this) {
            HoursLeft -> progress.hoursLeft.toString()
            HoursDone -> progress.elapsedHours.toString()
            DaysLeft -> progress.daysLeft.toString()
            DaysDone -> progress.elapsedDays.toString()
            WeeksLeft -> progress.weeksLeft.oneDecimalString()
            WeeksDone -> progress.weeksDone.oneDecimalString()
        }

    fun total(progress: Progress): String =
        when (this) {
            HoursLeft, HoursDone -> progress.totalHours.toString()
            DaysLeft, DaysDone -> progress.totalDays.toString()
            WeeksLeft, WeeksDone -> progress.totalWeeks.oneDecimalString()
        }
}

private data class MetricSettings(
    private val enabledMetrics: Map<MetricOption, Boolean>
) {
    fun isEnabled(option: MetricOption): Boolean =
        enabledMetrics[option] ?: option.defaultEnabled

    fun with(option: MetricOption, enabled: Boolean): MetricSettings =
        copy(enabledMetrics = enabledMetrics + (option to enabled))

    companion object {
        fun default(): MetricSettings =
            MetricSettings(
                enabledMetrics = MetricOption.entries.associateWith { it.defaultEnabled }
            )
    }
}

private fun SharedPreferences.loadMetricSettings(): MetricSettings =
    MetricSettings(
        enabledMetrics = MetricOption.entries.associateWith { option ->
            getBoolean(option.preferenceKey, option.defaultEnabled)
        }
    )

private const val MILLIS_PER_DAY = 1000.0 * 60.0 * 60.0 * 24.0
private const val MILLIS_PER_HOUR = 1000.0 * 60.0 * 60.0
private const val ERROR_BODY_PREVIEW_LIMIT = 6_000

private val percentFormatter: NumberFormat = NumberFormat.getNumberInstance().apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 2
}

private val oneDecimalFormatter: NumberFormat = NumberFormat.getNumberInstance().apply {
    maximumFractionDigits = 1
}

@Preview(showBackground = true)
@Composable
private fun TrackerDashboardPreview() {
    TimeTrackerAppTheme(dynamicColor = false) {
        TrackerDashboard(
            tracker = TrackerState(
                event = EventSummary(
                    id = "main",
                    name = "Summer internship",
                    accentColor = "#f4b400",
                    startDate = LocalDate.of(2026, 5, 11),
                    endDate = LocalDate.of(2026, 8, 28)
                ),
                todos = listOf(
                    Todo(id = "3", title = "Thing 3", done = false),
                    Todo(id = "2", title = "Thing 2", done = false),
                    Todo(id = "1", title = "Thing 1", done = false)
                )
            ),
            webBaseUrl = DEFAULT_WEB_BASE_URL,
            metricSettings = MetricSettings.default(),
            isRefreshing = false,
            onOpenSettings = {},
            onRefresh = {}
        )
    }
}
