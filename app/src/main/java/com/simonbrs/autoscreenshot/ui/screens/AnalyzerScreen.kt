package com.simonbrs.autoscreenshot.ui.screens

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simonbrs.autoscreenshot.ui.theme.ElectricBlue
import com.simonbrs.autoscreenshot.ui.theme.NeonPurple
import com.simonbrs.autoscreenshot.ui.theme.VibrantCyan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// ── Data models ──────────────────────────────────────────────

data class AnalyzerAppUsage(
    val packageName: String,
    val appName: String,
    val usageMillis: Long,
    val icon: Bitmap?
)

data class DayUsageData(
    val date: LocalDate,
    val totalMillis: Long,
    val apps: List<AnalyzerAppUsage>
)

data class AnalyzerUiState(
    val isLoading: Boolean = true,
    val hasUsageAccess: Boolean = false,
    val weekOffset: Int = 0, // 0 = current week, -1 = last week, etc.
    val selectedDayIndex: Int = -1, // 0=Sun..6=Sat, -1 = auto (today)
    val weekData: List<DayUsageData> = emptyList() // 7 entries, Sun–Sat
)

// ── ViewModel ────────────────────────────────────────────────

class AnalyzerViewModel : ViewModel() {
    var uiState by mutableStateOf(AnalyzerUiState())
        private set

    fun refresh(context: Context) {
        val appContext = context.applicationContext
        val hasAccess = hasUsageStatsPermission(appContext)
        if (!hasAccess) {
            uiState = AnalyzerUiState(isLoading = false, hasUsageAccess = false)
            return
        }
        uiState = uiState.copy(isLoading = true, hasUsageAccess = true)
        viewModelScope.launch {
            val weekData = withContext(Dispatchers.IO) {
                loadWeekUsage(appContext, uiState.weekOffset)
            }
            uiState = uiState.copy(isLoading = false, weekData = weekData)
        }
    }

    fun navigateWeek(context: Context, delta: Int) {
        val newOffset = uiState.weekOffset + delta
        if (newOffset > 0) return // can't go into the future
        uiState = uiState.copy(weekOffset = newOffset, selectedDayIndex = -1)
        refresh(context)
    }

    fun selectDay(index: Int) {
        uiState = uiState.copy(selectedDayIndex = index)
    }
}

// ── Root composable ──────────────────────────────────────────

@Composable
fun AnalyzerScreen(
    modifier: Modifier = Modifier,
    viewModel: AnalyzerViewModel = viewModel()
) {
    val context = LocalContext.current
    val state = viewModel.uiState
    val usageAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refresh(context)
    }

    LaunchedEffect(context) {
        viewModel.refresh(context)
    }

    var activeAppForScreenshots by remember { mutableStateOf<Pair<AnalyzerAppUsage, LocalDate>?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        val appScreenshots = activeAppForScreenshots
        if (appScreenshots != null) {
            BackHandler {
                activeAppForScreenshots = null
            }
            AppScreenshotsDetail(
                app = appScreenshots.first,
                date = appScreenshots.second,
                onBack = { activeAppForScreenshots = null }
            )
        } else {
            when {
                !state.hasUsageAccess && !state.isLoading -> UsageAccessPrompt(
                    onOpenSettings = {
                        usageAccessLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                )
                state.isLoading -> LoadingAnalyzer()
                else -> AnalyzerContent(
                    state = state,
                    onPrevWeek = { viewModel.navigateWeek(context, -1) },
                    onNextWeek = { viewModel.navigateWeek(context, 1) },
                    onSelectDay = { viewModel.selectDay(it) },
                    onAppClick = { app, date ->
                        activeAppForScreenshots = Pair(app, date)
                    }
                )
            }
        }
    }
}

// ── Main content ─────────────────────────────────────────────

@Composable
private fun AnalyzerContent(
    state: AnalyzerUiState,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onSelectDay: (Int) -> Unit,
    onAppClick: (AnalyzerAppUsage, LocalDate) -> Unit
) {
    val today = LocalDate.now()
    val todayDayIndex = (today.dayOfWeek.value % 7) // Sun=0..Sat=6

    // Determine which day is selected
    val effectiveIndex = if (state.selectedDayIndex < 0) {
        if (state.weekOffset == 0) todayDayIndex else 6 // default to Saturday for past weeks
    } else {
        state.selectedDayIndex
    }

    val selectedDay = state.weekData.getOrNull(effectiveIndex)
    val maxMillis = state.weekData.maxOfOrNull { it.totalMillis } ?: 1L

    // Calculate average
    val avgMillis = state.weekData.map { it.totalMillis }.filter { it > 0 }.let { list ->
        if (list.isEmpty()) 0L else list.sum() / list.size
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(0.dp)
    ) {
        // ── Chart card ───────────────────────────────────
        item {
            ChartCard(
                state = state,
                effectiveIndex = effectiveIndex,
                selectedDay = selectedDay,
                maxMillis = maxMillis,
                avgMillis = avgMillis,
                todayDayIndex = todayDayIndex,
                isCurrentWeek = state.weekOffset == 0,
                onPrevWeek = onPrevWeek,
                onNextWeek = onNextWeek,
                onSelectDay = onSelectDay
            )
        }

        // ── App usage list ───────────────────────────────
        val apps = selectedDay?.apps ?: emptyList()

        if (apps.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No app usage recorded for this day.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        items(apps, key = { it.packageName }) { app ->
            AppUsageRow(
                app = app,
                onClick = {
                    selectedDay?.date?.let { date ->
                        onAppClick(app, date)
                    }
                }
            )
        }

        // Bottom spacer
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

// ── Chart card ───────────────────────────────────────────────

@Composable
private fun ChartCard(
    state: AnalyzerUiState,
    effectiveIndex: Int,
    selectedDay: DayUsageData?,
    maxMillis: Long,
    avgMillis: Long,
    todayDayIndex: Int,
    isCurrentWeek: Boolean,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onSelectDay: (Int) -> Unit
) {
    val canGoNext = state.weekOffset < 0

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // ── Header: arrows + total usage + date ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onPrevWeek) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous week",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = formatUsageDuration(selectedDay?.totalMillis ?: 0L),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 36.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = selectedDay?.date?.let { formatDateLabel(it) } ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                IconButton(
                    onClick = onNextWeek,
                    enabled = canGoNext
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next week",
                        tint = if (canGoNext)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Bar chart ────────────────────────────────
            BarChart(
                weekData = state.weekData,
                selectedIndex = effectiveIndex,
                maxMillis = maxMillis,
                avgMillis = avgMillis,
                todayDayIndex = todayDayIndex,
                isCurrentWeek = isCurrentWeek,
                onSelectDay = onSelectDay
            )
        }
    }
}

// ── Bar chart composable ─────────────────────────────────────

@Composable
private fun BarChart(
    weekData: List<DayUsageData>,
    selectedIndex: Int,
    maxMillis: Long,
    avgMillis: Long,
    todayDayIndex: Int,
    isCurrentWeek: Boolean,
    onSelectDay: (Int) -> Unit
) {
    val dayLabels = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    val chartHeight = 180.dp
    val maxBarHeight = chartHeight - 24.dp // leave room for labels

    // Hour labels on the right side
    val maxHours = ((maxMillis / 3_600_000.0).coerceAtLeast(1.0)).let { raw ->
        // Round up to nice values
        when {
            raw <= 1.0 -> 1.0
            raw <= 2.0 -> 2.0
            raw <= 4.0 -> 4.0
            raw <= 6.0 -> 6.0
            raw <= 8.0 -> 8.0
            else -> (Math.ceil(raw / 2) * 2)
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            // ── Bars area ──
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(chartHeight),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                weekData.forEachIndexed { index, dayData ->
                    val fraction = if (maxMillis > 0) {
                        (dayData.totalMillis.toFloat() / (maxHours * 3_600_000).toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    val animatedFraction by animateFloatAsState(
                        targetValue = fraction,
                        animationSpec = tween(durationMillis = 400),
                        label = "bar_$index"
                    )

                    val isSelected = index == selectedIndex
                    val isToday = isCurrentWeek && index == todayDayIndex

                    val barColor by animateColorAsState(
                        targetValue = when {
                            isSelected -> NeonPurple
                            else -> ElectricBlue.copy(alpha = 0.55f)
                        },
                        label = "barColor_$index"
                    )

                    val barHighlightColor by animateColorAsState(
                        targetValue = when {
                            isSelected -> VibrantCyan
                            else -> ElectricBlue.copy(alpha = 0.35f)
                        },
                        label = "barHighlight_$index"
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onSelectDay(index) }
                    ) {
                        // Bar
                        Box(
                            modifier = Modifier
                                .width(24.dp)
                                .height(maxBarHeight * animatedFraction.coerceAtLeast(0.02f))
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(barHighlightColor, barColor)
                                    )
                                )
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // Day label
                        Text(
                            text = dayLabels[index],
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (isSelected)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // ── Y-axis labels ──
            Column(
                modifier = Modifier
                    .width(32.dp)
                    .height(chartHeight),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                val steps = listOf(maxHours, maxHours * 2 / 3, maxHours / 3, 0.0)
                steps.forEach { hours ->
                    Text(
                        text = when {
                            hours >= 1.0 -> "${hours.toInt()}h"
                            hours > 0 -> "${(hours * 60).toInt()}m"
                            else -> "0m"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }
                // Spacer for day label area
                Spacer(modifier = Modifier.height(18.dp))
            }
        }

        // ── Average line ──
        if (avgMillis > 0 && maxMillis > 0) {
            val avgFraction = (avgMillis.toFloat() / (maxHours * 3_600_000).toFloat()).coerceIn(0f, 1f)
            val avgOffset = maxBarHeight * (1f - avgFraction) + 0.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = avgOffset, end = 32.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                    )
                    Text(
                        text = "Avg",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

// ── App Icon Composable ──────────────────────────────────────

@Composable
private fun AppIcon(
    icon: Bitmap?,
    appName: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        if (icon == null) {
            Icon(
                imageVector = Icons.Default.PieChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
        } else {
            Image(
                bitmap = icon.asImageBitmap(),
                contentDescription = appName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            )
        }
    }
}

// ── App usage row ────────────────────────────────────────────

@Composable
private fun AppUsageRow(
    app: AnalyzerAppUsage,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(icon = app.icon, appName = app.appName)

        Spacer(modifier = Modifier.width(14.dp))

        // App name + usage
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = formatUsageDuration(app.usageMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "View screenshots",
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
            modifier = Modifier.size(24.dp)
        )
    }

    // Subtle divider
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    )
}

// ── Permission prompt ────────────────────────────────────────

@Composable
private fun UsageAccessPrompt(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(54.dp),
            tint = NeonPurple
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Usage access is needed",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Allow usage access in settings to see your daily app usage and weekly breakdown.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(18.dp))
        Button(onClick = onOpenSettings) {
            Text("Open Usage Access")
        }
    }
}

@Composable
private fun LoadingAnalyzer() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = NeonPurple)
    }
}

// ── Data loading ─────────────────────────────────────────────

/**
 * Loads usage data for each day (Sun–Sat) of the week at [weekOffset].
 *
 * Strategy:
 * 1. Try queryEvents() first — gives precise per-session foreground time
 *    by pairing ACTIVITY_RESUMED ↔ ACTIVITY_PAUSED events.  This is the
 *    same source Digital Wellbeing and usage-tracker apps rely on.
 * 2. queryEvents() only keeps detailed data for ~5-7 days (device-
 *    dependent).  If it returns nothing for a given day, fall back to
 *    queryUsageStats(INTERVAL_DAILY) which has data going back weeks.
 */
private fun loadWeekUsage(context: Context, weekOffset: Int): List<DayUsageData> {
    val today = LocalDate.now()
    val currentSunday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    val targetSunday = currentSunday.plusWeeks(weekOffset.toLong())
    val pm = context.packageManager
    val zone = ZoneId.systemDefault()

    return (0..6).map { dayIndex ->
        val date = targetSunday.plusDays(dayIndex.toLong())
        if (date.isAfter(today)) {
            return@map DayUsageData(date = date, totalMillis = 0L, apps = emptyList())
        }

        val startMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            .coerceAtMost(System.currentTimeMillis())

        // Primary: precise events-based calculation
        var apps = computeForegroundTimeFromEvents(context, pm, startMillis, endMillis)

        // Fallback: if events returned nothing (data expired), use daily stats
        if (apps.isEmpty()) {
            apps = loadDayFromDailyStats(context, pm, startMillis, endMillis)
        }

        DayUsageData(
            date = date,
            totalMillis = apps.sumOf { it.usageMillis },
            apps = apps
        )
    }
}

/**
 * Walks the raw UsageEvents stream between [startMillis] and [endMillis],
 * pairing ACTIVITY_RESUMED ↔ ACTIVITY_PAUSED to compute accurate
 * per-app foreground durations.
 *
 * Edge cases handled:
 * • App already in foreground at window start (e.g. left open overnight):
 *   The first event for that package will be ACTIVITY_PAUSED without a
 *   prior ACTIVITY_RESUMED in our window.  We count from [startMillis].
 * • App still in foreground at window end (today, right now):
 *   No ACTIVITY_PAUSED yet — we close the interval at [endMillis].
 */
private fun computeForegroundTimeFromEvents(
    context: Context,
    pm: PackageManager,
    startMillis: Long,
    endMillis: Long
): List<AnalyzerAppUsage> {
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val events = usm.queryEvents(startMillis, endMillis)
    val event = UsageEvents.Event()

    // Per-package: timestamp when it most recently moved to foreground
    val foregroundSince = mutableMapOf<String, Long>()
    // Per-package: accumulated foreground milliseconds
    val totalForeground = mutableMapOf<String, Long>()
    // Track which packages have had at least one RESUMED event in this window
    val hadResumed = mutableSetOf<String>()

    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        val ts = event.timeStamp.coerceIn(startMillis, endMillis)
        val pkg = event.packageName

        when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> {
                foregroundSince[pkg] = ts
                hadResumed.add(pkg)
            }
            UsageEvents.Event.ACTIVITY_PAUSED -> {
                val resumedAt = foregroundSince.remove(pkg)
                val effectiveStart = when {
                    // Normal case: we have a matching RESUMED
                    resumedAt != null -> resumedAt
                    // Edge case: PAUSED is the FIRST event for this package
                    // in our window → app was already in foreground at
                    // startMillis (e.g. left open overnight)
                    pkg !in hadResumed -> {
                        hadResumed.add(pkg)
                        startMillis
                    }
                    // Duplicate PAUSED without RESUMED — skip
                    else -> null
                }
                if (effectiveStart != null) {
                    val duration = (ts - effectiveStart).coerceAtLeast(0L)
                    totalForeground[pkg] =
                        (totalForeground[pkg] ?: 0L) + duration
                }
            }
        }
    }

    // Close still-open intervals (app currently in foreground)
    for ((pkg, resumedAt) in foregroundSince) {
        val duration = (endMillis - resumedAt).coerceAtLeast(0L)
        totalForeground[pkg] = (totalForeground[pkg] ?: 0L) + duration
    }

    // Resolve names & icons; drop unresolvable system packages
    return totalForeground.entries
        .filter { it.value >= 1_000L } // include apps used ≥ 1 second
        .mapNotNull { (packageName, millis) ->
            val appName = resolveAppLabel(pm, packageName) ?: return@mapNotNull null
            val icon = resolveAppIcon(pm, packageName)
            AnalyzerAppUsage(
                packageName = packageName,
                appName = appName,
                usageMillis = millis,
                icon = icon
            )
        }
        .sortedByDescending { it.usageMillis }
}

/**
 * Fallback for days where queryEvents() returns no data (events expired,
 * typically > 5-7 days old).  Uses queryUsageStats(INTERVAL_DAILY) which
 * retains data for much longer (~4 weeks on most devices).
 *
 * Less precise than events but still gives reasonable daily totals.
 */
private fun loadDayFromDailyStats(
    context: Context,
    pm: PackageManager,
    startMillis: Long,
    endMillis: Long
): List<AnalyzerAppUsage> {
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val stats = usm.queryUsageStats(
        UsageStatsManager.INTERVAL_DAILY,
        startMillis,
        endMillis
    )

    // queryUsageStats can return multiple entries per package across
    // different buckets — aggregate by package name.
    val perPackage = mutableMapOf<String, Long>()
    for (stat in stats) {
        if (stat.totalTimeInForeground > 0L) {
            perPackage[stat.packageName] =
                (perPackage[stat.packageName] ?: 0L) + stat.totalTimeInForeground
        }
    }

    return perPackage.entries
        .filter { it.value >= 1_000L }
        .mapNotNull { (packageName, millis) ->
            val appName = resolveAppLabel(pm, packageName) ?: return@mapNotNull null
            val icon = resolveAppIcon(pm, packageName)
            AnalyzerAppUsage(
                packageName = packageName,
                appName = appName,
                usageMillis = millis,
                icon = icon
            )
        }
        .sortedByDescending { it.usageMillis }
}

/**
 * Returns the human-readable app label (e.g. "Instagram"), or null if
 * the package is not installed / cannot be resolved — so we skip it
 * instead of showing raw package names like com.instagram.android.
 */
private fun resolveAppLabel(pm: PackageManager, packageName: String): String? {
    return try {
        val appInfo = pm.getApplicationInfo(
            packageName,
            PackageManager.ApplicationInfoFlags.of(0)
        )
        val label = pm.getApplicationLabel(appInfo).toString()
        label.ifBlank { null }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}

/**
 * Returns the app's launcher icon as a Bitmap, or null.
 */
private fun resolveAppIcon(pm: PackageManager, packageName: String): Bitmap? {
    return try {
        pm.getApplicationIcon(packageName).toIconBitmap()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}

// ── Utilities ────────────────────────────────────────────────

private fun hasUsageStatsPermission(context: Context): Boolean {
    val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOpsManager.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun Drawable.toIconBitmap(): Bitmap {
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}

private fun formatUsageDuration(millis: Long): String {
    val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L -> String.format(Locale.getDefault(), "%dh %02dm", hours, minutes)
        minutes > 0L -> String.format(Locale.getDefault(), "%dm", minutes)
        else -> "<1m"
    }
}

private fun formatDateLabel(date: LocalDate): String {
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)
    val dayName = when (date) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> date.dayOfWeek.name.lowercase()
            .replaceFirstChar { it.uppercase() }
    }
    val formatter = DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault())
    return "$dayName, ${date.format(formatter)}"
}

// ── App screenshots detailed page ────────────────────────────

data class AppScreenshot(
    val file: File,
    val time: LocalTime,
    val timeLabel: String,
    val hour: Int
)

@Composable
private fun AppScreenshotsDetail(
    app: AnalyzerAppUsage,
    date: LocalDate,
    onBack: () -> Unit
) {
    var screenshots by remember { mutableStateOf<List<AppScreenshot>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedHour by remember { mutableStateOf<Int?>(null) }
    var previewScreenshot by remember { mutableStateOf<AppScreenshot?>(null) }

    LaunchedEffect(app.packageName, app.appName, date) {
        isLoading = true
        screenshots = loadAppScreenshotsForDay(app.packageName, app.appName, date)
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // --- Header ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            AppIcon(icon = app.icon, appName = app.appName)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = formatDateLabel(date),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NeonPurple)
            }
        } else if (screenshots.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(54.dp),
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "No screenshots captured for this app today.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // Horizontal scrollable hours
            val availableHours = remember(screenshots) {
                screenshots.map { it.hour }.distinct().sorted()
            }

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    val isSelected = selectedHour == null
                    HourChip(
                        label = "All Hours",
                        count = screenshots.size,
                        isSelected = isSelected,
                        onClick = { selectedHour = null }
                    )
                }
                items(availableHours) { hour ->
                    val isSelected = selectedHour == hour
                    val count = remember(hour, screenshots) {
                        screenshots.count { it.hour == hour }
                    }
                    HourChip(
                        label = hourLabel(hour),
                        count = count,
                        isSelected = isSelected,
                        onClick = { selectedHour = hour }
                    )
                }
            }

            val filteredScreenshots = remember(selectedHour, screenshots) {
                if (selectedHour == null) screenshots else screenshots.filter { it.hour == selectedHour }
            }

            if (filteredScreenshots.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No screenshots captured in this hour.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filteredScreenshots, key = { it.file.absolutePath }) { screenshot ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.72f)
                                .clickable { previewScreenshot = screenshot },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                FileBitmapImage(
                                    file = screenshot.file,
                                    targetSize = 350,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.58f))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = screenshot.timeLabel,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = Color.White,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    previewScreenshot?.let { screenshot ->
        val filtered = remember(selectedHour, screenshots) {
            if (selectedHour == null) screenshots else screenshots.filter { it.hour == selectedHour }
        }
        AppScreenshotPreviewDialog(
            screenshots = filtered,
            initialScreenshot = screenshot,
            onDismiss = { previewScreenshot = null }
        )
    }
}

@Composable
private fun HourChip(
    label: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) NeonPurple else MaterialTheme.colorScheme.surface,
        label = "chipBg"
    )
    val contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onClick
        ),
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor
            )
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                shape = CircleShape,
                color = if (isSelected) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(18.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreenshotPreviewDialog(
    screenshots: List<AppScreenshot>,
    initialScreenshot: AppScreenshot,
    onDismiss: () -> Unit
) {
    val initialIndex = remember(screenshots, initialScreenshot) {
        screenshots.indexOfFirst { it.file.absolutePath == initialScreenshot.file.absolutePath }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialIndex) { screenshots.size }
    val currentScreenshot = screenshots.getOrNull(pagerState.currentPage) ?: initialScreenshot

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = screenshots.size > 1
                ) { page ->
                    val item = screenshots[page]
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        FileBitmapImage(
                            file = item.file,
                            targetSize = 1600,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                    color = Color.Black.copy(alpha = 0.72f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentScreenshot.timeLabel,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 1
                            )
                            if (screenshots.size > 1) {
                                Text(
                                    text = "${pagerState.currentPage + 1}/${screenshots.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.72f),
                                    maxLines = 1
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileBitmapImage(
    file: File,
    targetSize: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale
) {
    var bitmap by remember(file.absolutePath, targetSize) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(file.absolutePath, targetSize) {
        bitmap = withContext(Dispatchers.IO) {
            decodeSampledBitmap(file, targetSize)
        }
    }

    val currentBitmap = bitmap
    if (currentBitmap == null) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.36f),
                modifier = Modifier.size(32.dp)
            )
        }
    } else {
        Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = file.name,
            modifier = modifier,
            contentScale = contentScale
        )
    }
}

// ── Private Helper Functions ──────────────────────────────────

private suspend fun loadAppScreenshotsForDay(
    packageName: String,
    appName: String,
    date: LocalDate
): List<AppScreenshot> = withContext(Dispatchers.IO) {
    val year = date.year.toString()
    val month = String.format(Locale.US, "%02d", date.monthValue)
    val day = String.format(Locale.US, "%02d", date.dayOfMonth)
    
    val rootDir = File("/storage/emulated/0/Screenshot/$year/$month/$day")
    if (!rootDir.exists() || !rootDir.isDirectory) {
        return@withContext emptyList()
    }

    val sanitizedAppName = sanitizeAppNameForFilename(appName)
    val timeFormatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault())
    val filenameTimeRegex = Regex("""(\d{2})_(\d{2})_(\d{2})""")

    rootDir.listFiles()?.filter { file ->
        file.isFile && 
        file.extension.lowercase(Locale.US) in setOf("webp", "jpg", "jpeg", "png") &&
        (file.name.startsWith(sanitizedAppName + "_", ignoreCase = true) || 
         file.name.startsWith(packageName + "_", ignoreCase = true) ||
         file.name.startsWith(sanitizeAppNameForFilename(packageName) + "_", ignoreCase = true))
    }?.map { file ->
        val filenameMatch = filenameTimeRegex.find(file.nameWithoutExtension)
        val hour = filenameMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minute = filenameMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        val second = filenameMatch?.groupValues?.getOrNull(3)?.toIntOrNull() ?: 0
        
        val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59), second.coerceIn(0, 59))
        val timeLabel = timeFormatter.format(time)
        
        AppScreenshot(
            file = file,
            time = time,
            timeLabel = timeLabel,
            hour = hour
        )
    }?.sortedByDescending { it.time } ?: emptyList()
}

private fun sanitizeAppNameForFilename(appName: String): String {
    return appName
        .trim()
        .replace(Regex("""\s+"""), "_")
        .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        .trim('_')
}

private fun decodeSampledBitmap(file: File, targetSize: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(file.absolutePath, bounds)

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetSize)
    }

    return BitmapFactory.decodeFile(file.absolutePath, options)
}

private fun calculateInSampleSize(width: Int, height: Int, targetSize: Int): Int {
    var sampleSize = 1
    val halfWidth = width / 2
    val halfHeight = height / 2

    while (halfWidth / sampleSize >= targetSize && halfHeight / sampleSize >= targetSize) {
        sampleSize *= 2
    }

    return sampleSize.coerceAtLeast(1)
}

private fun hourLabel(hour: Int): String {
    val formatter = DateTimeFormatter.ofPattern("h a", Locale.getDefault())
    return formatter.format(LocalTime.of(hour.coerceIn(0, 23), 0))
}

