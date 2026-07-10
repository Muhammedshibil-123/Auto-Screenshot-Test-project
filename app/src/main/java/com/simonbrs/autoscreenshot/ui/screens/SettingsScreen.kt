package com.simonbrs.autoscreenshot.ui.screens

import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val SETTINGS_SCREENSHOT_ROOT_PATH = "/storage/emulated/0/Screenshot"
private val settingsImageExtensions = setOf("webp", "jpg", "jpeg", "png")

data class SettingsStorageUiState(
    val isLoading: Boolean = true,
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val screenshotBytes: Long = 0L,
    val screenshotCount: Int = 0,
    val isDeleting: Boolean = false
)

class SettingsViewModel : ViewModel() {
    var storageState by mutableStateOf(SettingsStorageUiState())
        private set

    fun refreshStorage(hasStorageAccess: Boolean) {
        if (!hasStorageAccess) {
            storageState = SettingsStorageUiState(isLoading = false)
            return
        }

        viewModelScope.launch {
            storageState = storageState.copy(isLoading = true)
            storageState = loadStorageState().copy(isLoading = false)
        }
    }

    fun deleteAllScreenshots(onDeleted: () -> Unit) {
        viewModelScope.launch {
            storageState = storageState.copy(isDeleting = true)
            withContext(Dispatchers.IO) {
                deleteScreenshotImages()
            }
            storageState = loadStorageState().copy(isLoading = false, isDeleting = false)
            onDeleted()
        }
    }
}

@Composable
fun SettingsScreen(
    hasStorageAccess: Boolean,
    onOpenSetup: () -> Unit,
    onRefresh: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val storageState = viewModel.storageState
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(hasStorageAccess) {
        viewModel.refreshStorage(hasStorageAccess)
    }

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
            SettingsInfoCard(
                icon = Icons.Default.Info,
                title = "About",
                body = "Auto Screenshot captures periodic screenshots through Accessibility and saves them locally on this device. Version 1.0."
            )
        }

        item {
            SettingsInfoCard(
                icon = Icons.Default.Warning,
                title = "Notice",
                body = "Screenshots can include private information from other apps. Review the gallery often and delete captures you no longer need."
            )
        }

        item {
            StorageSettingsCard(
                hasStorageAccess = hasStorageAccess,
                state = storageState,
                onOpenSetup = onOpenSetup,
                onRefresh = {
                    onRefresh()
                    viewModel.refreshStorage(hasStorageAccess)
                },
                onDeleteClick = { showDeleteDialog = true }
            )
        }
    }

    if (showDeleteDialog) {
        DeleteScreenshotsDialog(
            state = storageState,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                viewModel.deleteAllScreenshots {
                    onRefresh()
                    showDeleteDialog = false
                }
            }
        )
    }
}

@Composable
private fun SettingsInfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
private fun StorageSettingsCard(
    hasStorageAccess: Boolean,
    state: SettingsStorageUiState,
    onOpenSetup: () -> Unit,
    onRefresh: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
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
                .clickable { expanded = !expanded }
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        text = "Storage",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (hasStorageAccess) {
                            "${state.screenshotCount} screenshots, ${formatSettingsBytes(state.screenshotBytes)}"
                        } else {
                            "Storage access needed"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when {
                        !hasStorageAccess -> {
                            Text(
                                text = "Allow storage setup to calculate screenshot storage and manage saved captures.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                            )
                            Button(onClick = onOpenSetup) {
                                Text("Open Setup")
                            }
                        }

                        state.isLoading -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            }
                        }

                        else -> {
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            StorageMetricRow("Device total", formatSettingsBytes(state.totalBytes))
                            StorageMetricRow("Device free", formatSettingsBytes(state.freeBytes))
                            StorageMetricRow("Screenshots", formatSettingsBytes(state.screenshotBytes))
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
                }
            }
        }
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

private suspend fun loadStorageState(): SettingsStorageUiState = withContext(Dispatchers.IO) {
    val path = Environment.getExternalStorageDirectory().absolutePath
    val statFs = StatFs(path)
    val totalBytes = statFs.blockCountLong * statFs.blockSizeLong
    val freeBytes = statFs.availableBlocksLong * statFs.blockSizeLong
    val screenshotStats = screenshotFolderStats()

    SettingsStorageUiState(
        isLoading = false,
        totalBytes = totalBytes,
        freeBytes = freeBytes,
        screenshotBytes = screenshotStats.totalBytes,
        screenshotCount = screenshotStats.fileCount
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
        .filter { it.isScreenshotImage() }
        .forEach { file ->
            file.delete()
        }

    root.walkBottomUp()
        .filter { file -> file.isDirectory && file != root && file.listFiles()?.isEmpty() == true }
        .forEach { directory ->
            directory.delete()
        }
}

private fun File.isScreenshotImage(): Boolean {
    return isFile && extension.lowercase(Locale.US) in settingsImageExtensions
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
