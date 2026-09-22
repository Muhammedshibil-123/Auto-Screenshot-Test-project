package com.simonbrs.autoscreenshot.callrecorder.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simonbrs.autoscreenshot.callrecorder.data.RecorderPrefs
import com.simonbrs.autoscreenshot.callrecorder.service.CallRecorderEngine
import com.simonbrs.autoscreenshot.security.LocalPasswordGate
import com.simonbrs.autoscreenshot.security.PasswordSettingsPage
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private enum class RecorderSettingsPage { Main, Saved, Storage, Password, Notice }

@Composable
fun RecorderSettingsScreen(
    viewModel: RecorderSettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val resumeTick = rememberResumeTick()
    val recordingsVersion by CallRecorderEngine.recordingsVersion.collectAsState()
    var page by rememberSaveable { mutableStateOf(RecorderSettingsPage.Main) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    val state = viewModel.state
    val passwordGate = LocalPasswordGate.current

    BackHandler(enabled = page != RecorderSettingsPage.Main) {
        page = RecorderSettingsPage.Main
    }

    LaunchedEffect(resumeTick, recordingsVersion) {
        viewModel.refresh(context)
    }

    when (page) {
        RecorderSettingsPage.Main -> LazyColumn(
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
                RecorderMenuItem(
                    icon = Icons.Default.Star,
                    title = "Saved",
                    subtitle = "${state.starredCount} kept recording${if (state.starredCount == 1) "" else "s"}",
                    onClick = { page = RecorderSettingsPage.Saved }
                )
            }
            item {
                RecorderMenuItem(
                    icon = Icons.Default.Storage,
                    title = "Storage",
                    subtitle = "${state.recordingCount} recordings, ${formatRecorderBytes(state.recordingBytes)} · " +
                        "auto delete after ${recorderRetentionLabel(state.retentionDays)}",
                    onClick = { page = RecorderSettingsPage.Storage }
                )
            }
            item {
                RecorderMenuItem(
                    icon = Icons.Default.Lock,
                    title = "Password",
                    subtitle = "Delete / Off password for recordings and screenshots",
                    onClick = { page = RecorderSettingsPage.Password }
                )
            }
            item {
                RecorderMenuItem(
                    icon = Icons.Default.Warning,
                    title = "Notice",
                    subtitle = "Consent, privacy and Android limits",
                    onClick = { page = RecorderSettingsPage.Notice }
                )
            }
        }

        RecorderSettingsPage.Storage -> RecorderStoragePage(
            state = state,
            onBack = { page = RecorderSettingsPage.Main },
            onRefresh = { viewModel.refresh(context) },
            onDeleteClick = {
                passwordGate.guard("Enter the password to delete recordings.") {
                    showDeleteDialog = true
                }
            },
            onRetentionChanged = { days -> viewModel.updateRetentionDays(context, days) }
        )

        RecorderSettingsPage.Saved -> SavedRecordingsPage(
            onBack = {
                page = RecorderSettingsPage.Main
                viewModel.refresh(context)
            }
        )

        RecorderSettingsPage.Password -> PasswordSettingsPage(onBack = { page = RecorderSettingsPage.Main })


        RecorderSettingsPage.Notice -> RecorderDetailPage(
            title = "Notice",
            onBack = { page = RecorderSettingsPage.Main }
        ) {
            NoticeCard(
                title = "Consent",
                body = "Recording a call without telling the other person is illegal in many countries and states. " +
                    "You are responsible for getting consent where the law requires it."
            )
            NoticeCard(
                title = "Android limits",
                body = "Android does not let regular apps record the call line directly. Call Recorder records through the microphone, " +
                    "so your voice is always captured and the other person is captured as well as your phone allows. " +
                    "Use speakerphone for the clearest result."
            )
            NoticeCard(
                title = "Privacy",
                body = "Recordings are saved only on this phone and are encrypted, so other apps cannot play them. " +
                    "Nothing is uploaded. The accessibility service is used only to detect when calls start and end."
            )
        }
    }

    if (showDeleteDialog) {
        DeleteRecordingsDialog(
            state = state,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                viewModel.deleteAll(context) {
                    showDeleteDialog = false
                    Toast.makeText(context, "Recordings deleted", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
private fun RecorderStoragePage(
    state: RecorderStorageUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDeleteClick: () -> Unit,
    onRetentionChanged: (Int) -> Unit
) {
    var sliderDays by rememberSaveable(state.retentionDays) { mutableIntStateOf(state.retentionDays) }
    val usedBytes = (state.deviceTotalBytes - state.deviceFreeBytes).coerceAtLeast(0L)
    val usedProgress = if (state.deviceTotalBytes > 0L) usedBytes.toFloat() / state.deviceTotalBytes else 0f
    val passwordGate = LocalPasswordGate.current

    RecorderDetailPage(title = "Storage", onBack = onBack) {
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            RecorderCard {
                Text(
                    text = "${(usedProgress * 100).roundToInt()}% of device used",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                LinearProgressIndicator(progress = { usedProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                RecorderMetricRow("Device total", formatRecorderBytes(state.deviceTotalBytes))
                RecorderMetricRow("Device free", formatRecorderBytes(state.deviceFreeBytes))
                RecorderMetricRow("Total recordings size", formatRecorderBytes(state.recordingBytes))
                RecorderMetricRow("Recordings", state.recordingCount.toString())
                RecorderMetricRow("Kept (starred)", state.starredCount.toString())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Refresh")
                    }
                    Button(
                        onClick = onDeleteClick,
                        enabled = state.recordingCount > state.starredCount && !state.isDeleting,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Delete")
                    }
                }
            }

            RecorderCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Automatic delete",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = recorderRetentionLabel(sliderDays),
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
                            text = formatRecorderBytes(estimatedRecordingBytes(sliderDays, state.averageBytesPerDay)),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Slider(
                    value = sliderDays.toFloat(),
                    onValueChange = { value ->
                        sliderDays = value.roundToInt()
                            .coerceIn(RecorderPrefs.MIN_RETENTION_DAYS, RecorderPrefs.MAX_RETENTION_DAYS)
                    },
                    valueRange = RecorderPrefs.MIN_RETENTION_DAYS.toFloat()..RecorderPrefs.MAX_RETENTION_DAYS.toFloat(),
                    steps = RecorderPrefs.MAX_RETENTION_DAYS - RecorderPrefs.MIN_RETENTION_DAYS - 1,
                    onValueChangeFinished = {
                        if (sliderDays != state.retentionDays) {
                            passwordGate.guard(
                                reason = "Enter the password to change automatic delete.",
                                onCancel = { sliderDays = state.retentionDays }
                            ) {
                                onRetentionChanged(sliderDays)
                            }
                        }
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("1 day", style = MaterialTheme.typography.labelSmall)
                    Text("365 days", style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    text = "Recordings older than ${recorderRetentionLabel(sliderDays).lowercase(Locale.getDefault())} " +
                        "are deleted automatically. Starred recordings are always kept. " +
                        if (state.averageBytesPerDay > 0L) {
                            "Estimate uses your average of ${formatRecorderBytes(state.averageBytesPerDay)} of calls per day."
                        } else {
                            "Estimate assumes 30 minutes of calls per day (about 0.5 MB per minute)."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
            }
        }
    }
}

@Composable
private fun NoticeCard(title: String, body: String) {
    RecorderCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
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
private fun DeleteRecordingsDialog(
    state: RecorderStorageUiState,
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
        title = { Text("Delete all recordings?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "This permanently deletes every call recording except starred ones.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
                Text(
                    text = "${state.recordingCount - state.starredCount} files",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (secondsLeft > 0) "Delete unlocks in $secondsLeft seconds" else "Delete is unlocked",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = secondsLeft == 0 && !state.isDeleting) {
                if (state.isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (secondsLeft > 0) "Delete (${secondsLeft}s)" else "Confirm Delete")
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

private const val FALLBACK_BYTES_PER_DAY = 30L * 480L * 1024L

private fun estimatedRecordingBytes(days: Int, averageBytesPerDay: Long): Long {
    val perDay = if (averageBytesPerDay > 0L) averageBytesPerDay else FALLBACK_BYTES_PER_DAY
    return days.toLong() * perDay
}
