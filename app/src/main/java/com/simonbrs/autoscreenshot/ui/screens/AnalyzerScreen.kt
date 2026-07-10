package com.simonbrs.autoscreenshot.ui.screens

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Process
import android.provider.Settings
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simonbrs.autoscreenshot.ui.theme.ElectricBlue
import com.simonbrs.autoscreenshot.ui.theme.NeonPurple
import com.simonbrs.autoscreenshot.ui.theme.VibrantCyan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
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

    Box(modifier = modifier.fillMaxSize()) {
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
                onSelectDay = { viewModel.selectDay(it) }
            )
        }
    }
}

// ── Main content ─────────────────────────────────────────────

@Composable
private fun AnalyzerContent(
    state: AnalyzerUiState,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onSelectDay: (Int) -> Unit
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
            AppUsageRow(app = app)
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

// ── App usage row ────────────────────────────────────────────

@Composable
private fun AppUsageRow(app: AnalyzerAppUsage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App icon
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            if (app.icon == null) {
                Icon(
                    imageVector = Icons.Default.PieChart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Image(
                    bitmap = app.icon.asImageBitmap(),
                    contentDescription = app.appName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                )
            }
        }

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
