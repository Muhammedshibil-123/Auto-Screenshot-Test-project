package com.simonbrs.autoscreenshot.ui.screens

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simonbrs.autoscreenshot.security.LocalPasswordGate
import com.simonbrs.autoscreenshot.security.PasswordSettingsPage
import com.simonbrs.autoscreenshot.service.ScreenshotAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val SETTINGS_SCREENSHOT_ROOT_PATH = "/storage/emulated/0/Screenshot"
private const val ESTIMATED_SCREENSHOT_BYTES = 100L * 1024L
private const val ESTIMATED_ACTIVE_HOURS_PER_DAY = 8L
private const val MIN_RETENTION_DAYS = 1
private const val MAX_RETENTION_DAYS = 365
private val settingsImageExtensions = setOf("webp", "jpg", "jpeg", "png")

private enum class SettingsPage {
    Main,
    About,
    Notice,
    Storage,
    Saved,
    Terms,
    Password
}

data class SettingsStorageUiState(
    val isLoading: Boolean = true,
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val screenshotBytes: Long = 0L,
    val screenshotCount: Int = 0,
    val autoDeleteDays: Int = ScreenshotAccessibilityService.DEFAULT_AUTO_DELETE_RETENTION_DAYS,
    val isDeleting: Boolean = false,
    val savedCount: Int = 0
)

class SettingsViewModel : ViewModel() {
    var storageState by mutableStateOf(SettingsStorageUiState())
        private set

    fun refreshStorage(context: Context, hasStorageAccess: Boolean) {
        if (!hasStorageAccess) {
            storageState = SettingsStorageUiState(
                isLoading = false,
                autoDeleteDays = readAutoDeleteDays(context)
            )
            return
        }

        viewModelScope.launch {
            storageState = storageState.copy(isLoading = true)
            storageState = loadStorageState(context).copy(isLoading = false)
        }
    }

    fun updateAutoDeleteDays(
        context: Context,
        days: Int,
        hasStorageAccess: Boolean,
        onUpdated: () -> Unit
    ) {
        val safeDays = days.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)
        viewModelScope.launch {
            context.getSharedPreferences(ScreenshotAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(ScreenshotAccessibilityService.KEY_AUTO_DELETE_RETENTION_DAYS, safeDays)
                .apply()

            storageState = storageState.copy(autoDeleteDays = safeDays)

            if (hasStorageAccess) {
                withContext(Dispatchers.IO) {
                    deleteOldScreenshotImages(safeDays)
                }
                storageState = loadStorageState(context).copy(isLoading = false)
            }

            onUpdated()
        }
    }

    fun deleteAllScreenshots(context: Context, onDeleted: () -> Unit) {
        viewModelScope.launch {
            storageState = storageState.copy(isDeleting = true)
            withContext(Dispatchers.IO) {
                deleteScreenshotImages()
            }
            storageState = loadStorageState(context).copy(isLoading = false, isDeleting = false)
            onDeleted()
        }
    }
}

@Composable
fun SettingsScreen(
    hasStorageAccess: Boolean,
    intervalSeconds: Long,
    onOpenSetup: () -> Unit,
    onRefresh: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val storageState = viewModel.storageState
    var page by rememberSaveable { mutableStateOf(SettingsPage.Main) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    val passwordGate = LocalPasswordGate.current

    BackHandler(enabled = page != SettingsPage.Main) {
        page = SettingsPage.Main
    }

    LaunchedEffect(context, hasStorageAccess) {
        viewModel.refreshStorage(context, hasStorageAccess)
    }

    when (page) {
        SettingsPage.Main -> SettingsMainPage(
            hasStorageAccess = hasStorageAccess,
            state = storageState,
            onOpenAbout = { page = SettingsPage.About },
            onOpenNotice = { page = SettingsPage.Notice },
            onOpenStorage = { page = SettingsPage.Storage },
            onOpenSaved = { page = SettingsPage.Saved },
            onOpenTerms = { page = SettingsPage.Terms },
            onOpenPassword = { page = SettingsPage.Password }
        )

        SettingsPage.About -> AboutSettingsPage(onBack = { page = SettingsPage.Main })

        SettingsPage.Notice -> NoticeSettingsPage(onBack = { page = SettingsPage.Main })

        SettingsPage.Storage -> StorageSettingsPage(
            hasStorageAccess = hasStorageAccess,
            intervalSeconds = intervalSeconds,
            state = storageState,
            onBack = { page = SettingsPage.Main },
            onOpenSetup = onOpenSetup,
            onRefresh = {
                onRefresh()
                viewModel.refreshStorage(context, hasStorageAccess)
            },
            onAutoDeleteDaysChanged = { days ->
                viewModel.updateAutoDeleteDays(
                    context = context,
                    days = days,
                    hasStorageAccess = hasStorageAccess,
                    onUpdated = onRefresh
                )
            },
            onDeleteClick = {
                passwordGate.guard("Enter the password to delete screenshots.") {
                    showDeleteDialog = true
                }
            }
        )

        SettingsPage.Saved -> SavedScreenshotsPage(
            onBack = {
                page = SettingsPage.Main
                viewModel.refreshStorage(context, hasStorageAccess)
            }
        )

        SettingsPage.Terms -> TermsSettingsPage(onBack = { page = SettingsPage.Main })

        SettingsPage.Password -> PasswordSettingsPage(onBack = { page = SettingsPage.Main })
    }

    if (showDeleteDialog) {
        DeleteScreenshotsDialog(
            state = storageState,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                viewModel.deleteAllScreenshots(context) {
                    onRefresh()
                    showDeleteDialog = false
                }
            }
        )
    }
}

@Composable
private fun SettingsMainPage(
    hasStorageAccess: Boolean,
    state: SettingsStorageUiState,
    onOpenAbout: () -> Unit,
    onOpenNotice: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenPassword: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 20.dp)
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item {
            SettingsMenuItem(
                icon = Icons.Default.Bookmark,
                title = "Saved",
                subtitle = if (hasStorageAccess) {
                    "${state.savedCount} saved screenshot${if (state.savedCount == 1) "" else "s"}"
                } else {
                    "Storage access needed"
                },
                onClick = onOpenSaved
            )
        }

        item {
            SettingsMenuItem(
                icon = Icons.Default.Storage,
                title = "Storage",
                subtitle = if (hasStorageAccess) {
                    "${state.screenshotCount} screenshots, ${formatSettingsBytes(state.screenshotBytes)}"
                } else {
                    "Storage access needed"
                },
                onClick = onOpenStorage
            )
        }

        item {
            SettingsMenuItem(
                icon = Icons.Default.Lock,
                title = "Password",
                subtitle = "Delete / Off password for screenshots and recordings",
                onClick = onOpenPassword
            )
        }

        item {
            SettingsMenuItem(
                icon = Icons.Default.Description,
                title = "Terms & Conditions",
                subtitle = "App terms of use and compliance",
                onClick = onOpenTerms
            )
        }

        item {
            SettingsMenuItem(
                icon = Icons.Default.Warning,
                title = "Notice",
                subtitle = "Privacy policy & safety guidelines",
                onClick = onOpenNotice
            )
        }

        item {
            SettingsMenuItem(
                icon = Icons.Default.Info,
                title = "About",
                subtitle = "Zoro Engineering credits & version info",
                onClick = onOpenAbout
            )
        }
    }
}

@Composable
private fun AboutSettingsPage(onBack: () -> Unit) {
    SettingsDetailPage(
        title = "About",
        onBack = onBack
    ) {
        SettingsInfoCard(
            icon = Icons.Default.Info,
            title = "Zoro AutoScreenshot",
            body = "AutoScreenshot is a premium screen auditing and productivity utility developed by Zoro Labs. It automatically captures screen content at user-defined intervals, enabling seamless workflow tracking and local backup."
        )
        SettingsInfoCard(
            icon = Icons.Default.Storage,
            title = "Zoro Engineering",
            body = "Designed and engineered with a privacy-first mindset. Version 1.0. All screenshot files and usage stats are kept fully offline under your direct control."
        )
    }
}

@Composable
private fun NoticeSettingsPage(onBack: () -> Unit) {
    SettingsDetailPage(
        title = "Notice",
        onBack = onBack
    ) {
        SettingsInfoCard(
            icon = Icons.Default.Warning,
            title = "Privacy reminder",
            body = "Screenshots may contain sensitive information, including passwords, personal chats, and financial data. We highly recommend reviewing the gallery periodically and securely deleting older captures."
        )
        SettingsInfoCard(
            icon = Icons.Default.Info,
            title = "Accessibility service notice",
            body = "AutoScreenshot uses Android's Accessibility APIs solely to perform periodic screen capture operations in the background. It does not monitor keystrokes, transmit personal inputs, or connect to external servers."
        )
    }
}

@Composable
private fun TermsSettingsPage(onBack: () -> Unit) {
    SettingsDetailPage(
        title = "Terms & Conditions",
        onBack = onBack
    ) {
        SettingsInfoCard(
            icon = Icons.Default.Description,
            title = "Terms of use",
            body = "By using AutoScreenshot, you agree to use this utility responsibly and in compliance with your organization's security policies, local privacy laws, and user consent guidelines. You assume full responsibility for all captured screen data."
        )
        SettingsInfoCard(
            icon = Icons.Default.Warning,
            title = "Limitation of liability",
            body = "This software is provided 'as-is' by the Zoro Team. We are not liable for any data loss, unauthorized access, or regulatory non-compliance resulting from the configuration, storage, or distribution of screenshots captured by this app."
        )
    }
}

@Composable
private fun StorageSettingsPage(
    hasStorageAccess: Boolean,
    intervalSeconds: Long,
    state: SettingsStorageUiState,
    onBack: () -> Unit,
    onOpenSetup: () -> Unit,
    onRefresh: () -> Unit,
    onAutoDeleteDaysChanged: (Int) -> Unit,
    onDeleteClick: () -> Unit
) {
    val usedBytes = (state.totalBytes - state.freeBytes).coerceAtLeast(0L)
    val usedProgress = if (state.totalBytes > 0L) {
        usedBytes.toFloat() / state.totalBytes.toFloat()
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = usedProgress.coerceIn(0f, 1f),
        label = "Storage progress"
    )
    var sliderDays by rememberSaveable(state.autoDeleteDays) {
        mutableIntStateOf(state.autoDeleteDays.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS))
    }
    val estimateBytes = estimatedBackupBytes(sliderDays, intervalSeconds)
    val passwordGate = LocalPasswordGate.current

    SettingsDetailPage(
        title = "Storage",
        onBack = onBack
    ) {
        when {
            !hasStorageAccess -> StorageAccessNeededCard(onOpenSetup = onOpenSetup)

            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            else -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            StorageUsageRing(
                                progress = animatedProgress,
                                modifier = Modifier.size(112.dp)
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "${(animatedProgress * 100).roundToInt()}% used",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${formatSettingsBytes(state.freeBytes)} free",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                                )
                            }
                        }

                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        StorageMetricRow("Device total", formatSettingsBytes(state.totalBytes))
                        StorageMetricRow("Device free", formatSettingsBytes(state.freeBytes))
                        StorageMetricRow("Total screenshot size", formatSettingsBytes(state.screenshotBytes))
                        StorageMetricRow("Captured files", state.screenshotCount.toString())

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onRefresh,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("Refresh")
                            }
                            Button(
                                onClick = onDeleteClick,
                                enabled = state.screenshotCount > 0 && !state.isDeleting,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("Delete")
                            }
                        }
                    }
                }

                AutoDeleteCard(
                    days = sliderDays,
                    intervalSeconds = intervalSeconds,
                    estimateBytes = estimateBytes,
                    onDaysChanged = { days -> sliderDays = days },
                    onDragFinished = {
                        if (sliderDays != state.autoDeleteDays) {
                            passwordGate.guard(
                                reason = "Enter the password to change automatic delete.",
                                onCancel = { sliderDays = state.autoDeleteDays }
                            ) {
                                onAutoDeleteDaysChanged(sliderDays)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AutoDeleteCard(
    days: Int,
    intervalSeconds: Long,
    estimateBytes: Long,
    onDaysChanged: (Int) -> Unit,
    onDragFinished: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Automatic delete",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = retentionLabel(days),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Estimated backup",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
                    )
                    Text(
                        text = formatSettingsBytes(estimateBytes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Slider(
                value = days.toFloat(),
                onValueChange = { value ->
                    onDaysChanged(value.roundToInt().coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS))
                },
                valueRange = MIN_RETENTION_DAYS.toFloat()..MAX_RETENTION_DAYS.toFloat(),
                steps = MAX_RETENTION_DAYS - MIN_RETENTION_DAYS - 1,
                onValueChangeFinished = onDragFinished
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("1 day", style = MaterialTheme.typography.labelSmall)
                Text("365 days", style = MaterialTheme.typography.labelSmall)
            }

            Text(
                text = "Older screenshots are deleted automatically, so the app keeps about ${retentionLabel(days).lowercase(Locale.getDefault())} of backup. Estimate uses 100 KB per screenshot and 8 active hours per day at the current ${intervalSeconds.coerceAtLeast(1L)}s timer. If you change the timer, storage needs will change too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
        }
    }
}

@Composable
private fun SettingsDetailPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun SettingsMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun SettingsInfoCard(
    icon: ImageVector,
    title: String,
    body: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
            }
        }
    }
}

@Composable
private fun StorageAccessNeededCard(onOpenSetup: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Storage access is needed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Allow storage setup to calculate screenshot storage and manage saved captures.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
            Button(onClick = onOpenSetup) {
                Text("Open Setup")
            }
        }
    }
}

@Composable
private fun StorageUsageRing(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 12.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
            val arcSize = Size(diameter, diameter)

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        Text(
            text = "${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StorageMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DeleteScreenshotsDialog(
    state: SettingsStorageUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var secondsLeft by remember { mutableIntStateOf(10) }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete all screenshots?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "This will permanently delete every screenshot image saved by Auto Screenshot.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
                Text(
                    text = "${state.screenshotCount} files - ${formatSettingsBytes(state.screenshotBytes)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (secondsLeft > 0) {
                        "Delete unlocks in $secondsLeft seconds"
                    } else {
                        "Delete is unlocked"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = secondsLeft == 0 && !state.isDeleting
            ) {
                if (state.isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        text = if (secondsLeft > 0) {
                            "Delete (${secondsLeft}s)"
                        } else {
                            "Confirm Delete"
                        }
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isDeleting) {
                Text("Cancel")
            }
        }
    )
}

private suspend fun loadStorageState(context: Context): SettingsStorageUiState = withContext(Dispatchers.IO) {
    val path = Environment.getExternalStorageDirectory().absolutePath
    val statFs = StatFs(path)
    val totalBytes = statFs.blockCountLong * statFs.blockSizeLong
    val freeBytes = statFs.availableBlocksLong * statFs.blockSizeLong
    val screenshotStats = screenshotFolderStats()
    val savedCount = countSavedScreenshots()

    SettingsStorageUiState(
        isLoading = false,
        totalBytes = totalBytes,
        freeBytes = freeBytes,
        screenshotBytes = screenshotStats.totalBytes,
        screenshotCount = screenshotStats.fileCount,
        autoDeleteDays = readAutoDeleteDays(context),
        savedCount = savedCount
    )
}

private data class ScreenshotFolderStats(
    val totalBytes: Long,
    val fileCount: Int
)

private fun screenshotFolderStats(): ScreenshotFolderStats {
    val root = File(SETTINGS_SCREENSHOT_ROOT_PATH)
    if (!root.exists() || !root.isDirectory) {
        return ScreenshotFolderStats(totalBytes = 0L, fileCount = 0)
    }

    var totalBytes = 0L
    var fileCount = 0
    root.walkTopDown()
        .onEnter { dir -> dir.name != "Saved" }
        .filter { it.isScreenshotImage() }
        .forEach { file ->
            totalBytes += file.length()
            fileCount += 1
        }

    return ScreenshotFolderStats(totalBytes = totalBytes, fileCount = fileCount)
}

private fun deleteScreenshotImages() {
    val root = File(SETTINGS_SCREENSHOT_ROOT_PATH)
    if (!root.exists() || !root.isDirectory) {
        return
    }

    root.walkTopDown()
        .onEnter { dir -> dir.name != "Saved" }
        .filter { it.isScreenshotImage() }
        .forEach { file ->
            file.delete()
        }

    pruneEmptyScreenshotFolders(root)
}

private fun deleteOldScreenshotImages(retentionDays: Int) {
    val root = File(SETTINGS_SCREENSHOT_ROOT_PATH)
    if (!root.exists() || !root.isDirectory) {
        return
    }

    val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
    root.walkTopDown()
        .onEnter { dir -> dir.name != "Saved" }
        .filter { file -> file.isScreenshotImage() && file.lastModified() < cutoff }
        .forEach { file ->
            file.delete()
        }

    pruneEmptyScreenshotFolders(root)
}

private fun pruneEmptyScreenshotFolders(root: File) {
    root.walkBottomUp()
        .filter { file -> file.isDirectory && file != root && file.name != "Saved" && file.listFiles()?.isEmpty() == true }
        .forEach { directory ->
            directory.delete()
        }
}

private fun File.isScreenshotImage(): Boolean {
    return isFile && extension.lowercase(Locale.US) in settingsImageExtensions
}

private fun readAutoDeleteDays(context: Context): Int {
    return context.getSharedPreferences(ScreenshotAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
        .getInt(
            ScreenshotAccessibilityService.KEY_AUTO_DELETE_RETENTION_DAYS,
            ScreenshotAccessibilityService.DEFAULT_AUTO_DELETE_RETENTION_DAYS
        )
        .coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)
}

private fun estimatedBackupBytes(days: Int, intervalSeconds: Long): Long {
    val safeInterval = intervalSeconds.coerceAtLeast(1L)
    val activeSeconds = TimeUnit.HOURS.toSeconds(ESTIMATED_ACTIVE_HOURS_PER_DAY)
    val screenshotsPerDay = (activeSeconds / safeInterval).coerceAtLeast(1L)
    return days.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS).toLong() *
        screenshotsPerDay *
        ESTIMATED_SCREENSHOT_BYTES
}

private fun retentionLabel(days: Int): String {
    return when (days) {
        1 -> "1 day"
        30 -> "1 month"
        365 -> "1 year"
        else -> "$days days"
    }
}

private fun formatSettingsBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0

    return when {
        safeBytes >= gb -> String.format(Locale.getDefault(), "%.2f GB", safeBytes / gb)
        safeBytes >= mb -> String.format(Locale.getDefault(), "%.2f MB", safeBytes / mb)
        safeBytes >= kb -> String.format(Locale.getDefault(), "%.1f KB", safeBytes / kb)
        else -> "$safeBytes B"
    }
}

private fun countSavedScreenshots(): Int {
    val savedDir = File(SETTINGS_SCREENSHOT_ROOT_PATH, "Saved")
    if (!savedDir.exists() || !savedDir.isDirectory) {
        return 0
    }
    return savedDir.listFiles()?.count { it.isFile && it.extension.lowercase(Locale.US) in settingsImageExtensions } ?: 0
}

@Composable
private fun SavedScreenshotsPage(
    onBack: () -> Unit
) {
    var savedImages by remember { mutableStateOf<List<ScreenshotImage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedImage by remember { mutableStateOf<ScreenshotImage?>(null) }
    var shown by rememberSaveable { mutableIntStateOf(SCREENSHOT_PAGE_SIZE) }

    LaunchedEffect(Unit) {
        isLoading = true
        savedImages = withContext(Dispatchers.IO) {
            val savedDir = File(SETTINGS_SCREENSHOT_ROOT_PATH, "Saved")
            if (!savedDir.exists() || !savedDir.isDirectory) {
                return@withContext emptyList()
            }
            val root = File(SETTINGS_SCREENSHOT_ROOT_PATH)
            savedDir.listFiles()
                ?.filter { file -> file.isFile && file.extension.lowercase(Locale.US) in settingsImageExtensions }
                ?.map { file -> file.toScreenshotImage(root) }
                ?.sortedByDescending { it.capturedAt }
                ?: emptyList()
        }
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Saved",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (savedImages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "No saved screenshots",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Bookmark screenshots in the gallery to view them here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(savedImages.take(shown), key = { it.file.absolutePath }) { image ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.72f)
                            .clickable { selectedImage = image },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            FileBitmapImage(
                                file = image.file,
                                targetSize = 520,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
                loadMoreFooter(
                    shown = shown,
                    total = savedImages.size,
                    onLoadMore = { shown += SCREENSHOT_PAGE_SIZE }
                )
            }
        }
    }

    selectedImage?.let { image ->
        ScreenshotPreviewDialog(
            images = savedImages,
            initialImage = image,
            onDismiss = {
                selectedImage = null
                // Refresh the list in case the user removed the bookmark inside the preview dialog
                val savedDir = File(SETTINGS_SCREENSHOT_ROOT_PATH, "Saved")
                val root = File(SETTINGS_SCREENSHOT_ROOT_PATH)
                savedImages = savedDir.listFiles()
                    ?.filter { file -> file.isFile && file.extension.lowercase(Locale.US) in settingsImageExtensions }
                    ?.map { file -> file.toScreenshotImage(root) }
                    ?.sortedByDescending { it.capturedAt }
                    ?: emptyList()
            }
        )
    }
}
