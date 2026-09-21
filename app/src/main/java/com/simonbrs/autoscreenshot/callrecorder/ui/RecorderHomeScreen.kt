package com.simonbrs.autoscreenshot.callrecorder.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simonbrs.autoscreenshot.callrecorder.data.RecorderPrefs
import com.simonbrs.autoscreenshot.callrecorder.service.CallRecorderAccessibilityService
import com.simonbrs.autoscreenshot.callrecorder.service.CallRecorderEngine
import com.simonbrs.autoscreenshot.ui.screens.SetupStatusRow
import com.simonbrs.autoscreenshot.ui.screens.StartStopButton
import com.simonbrs.autoscreenshot.ui.screens.StatusCard
import com.simonbrs.autoscreenshot.ui.theme.AccentRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val recorderRuntimePermissions = arrayOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.POST_NOTIFICATIONS
)

private data class RecorderSetupState(
    val microphone: Boolean,
    val phoneState: Boolean,
    val callLog: Boolean,
    val contacts: Boolean,
    val notifications: Boolean,
    val storage: Boolean,
    val accessibility: Boolean
) {
    val runtimeGranted: Boolean
        get() = microphone && phoneState && callLog && contacts && notifications
    val requiredReady: Boolean
        get() = microphone && phoneState && storage && accessibility
}

private fun readSetupState(context: Context): RecorderSetupState {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return RecorderSetupState(
        microphone = granted(Manifest.permission.RECORD_AUDIO),
        phoneState = granted(Manifest.permission.READ_PHONE_STATE),
        callLog = granted(Manifest.permission.READ_CALL_LOG),
        contacts = granted(Manifest.permission.READ_CONTACTS),
        notifications = granted(Manifest.permission.POST_NOTIFICATIONS),
        storage = Environment.isExternalStorageManager(),
        accessibility = CallRecorderAccessibilityService.isEnabled(context)
    )
}

/** Returns a counter that increases every time the screen resumes. */
@Composable
internal fun rememberResumeTick(): Int {
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return tick
}

@Composable
fun RecorderHomeScreen(
    recordingsViewModel: RecordingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val resumeTick = rememberResumeTick()
    var refreshTick by remember { mutableIntStateOf(0) }
    val setup = remember(resumeTick, refreshTick) { readSetupState(context) }
    val isEnabled = remember(resumeTick, refreshTick) { RecorderPrefs.isEnabled(context) }
    val isRecordingNow by CallRecorderEngine.isRecording.collectAsState()
    val recordingsVersion by CallRecorderEngine.recordingsVersion.collectAsState()
    val latest = recordingsViewModel.state.recordings.firstOrNull()

    LaunchedEffect(resumeTick, recordingsVersion) {
        recordingsViewModel.refresh(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshTick += 1
    }

    fun requestNextSetupStep(state: RecorderSetupState) {
        when {
            !state.runtimeGranted -> permissionLauncher.launch(recorderRuntimePermissions)
            !state.storage -> openAllFilesAccess(context)
            !state.accessibility -> {
                Toast.makeText(
                    context,
                    "Turn on \"Call Recorder\" in Accessibility settings",
                    Toast.LENGTH_LONG
                ).show()
                openSettings(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StatusCard(isActive = isEnabled && setup.requiredReady)

        if (isRecordingNow) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.FiberManualRecord,
                    contentDescription = null,
                    tint = AccentRed,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Recording current call",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = AccentRed
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        RecorderCard {
            Text(
                text = "Setup",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Column {
                SetupStatusRow(label = "Microphone", isComplete = setup.microphone)
                SetupStatusRow(label = "Phone state", isComplete = setup.phoneState)
                SetupStatusRow(label = "Call log (numbers)", isComplete = setup.callLog)
                SetupStatusRow(label = "Contacts (names)", isComplete = setup.contacts)
                SetupStatusRow(label = "Notifications", isComplete = setup.notifications)
                SetupStatusRow(label = "Storage access", isComplete = setup.storage)
                SetupStatusRow(label = "Call Recorder accessibility", isComplete = setup.accessibility)
            }
            if (!setup.runtimeGranted || !setup.storage || !setup.accessibility) {
                Button(
                    onClick = { requestNextSetupStep(setup) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant next permission")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        StartStopButton(
            isActive = isEnabled,
            onClick = {
                if (isEnabled) {
                    RecorderPrefs.setEnabled(context, false)
                    Toast.makeText(context, "Call recording turned off", Toast.LENGTH_SHORT).show()
                } else {
                    RecorderPrefs.setEnabled(context, true)
                    if (setup.requiredReady) {
                        Toast.makeText(context, "Calls will be recorded automatically", Toast.LENGTH_SHORT).show()
                    } else {
                        requestNextSetupStep(setup)
                    }
                }
                refreshTick += 1
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        RecorderCard {
            Text(
                text = "Last recording",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (latest == null) {
                Text(
                    text = "No calls recorded yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            } else {
                RecorderMetricRow("Contact", latest.displayName)
                RecorderMetricRow(
                    "When",
                    SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(latest.recordedAt))
                )
                RecorderMetricRow("Duration", formatCallDuration(latest.durationSeconds))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        RecorderCard {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Android limits call audio for regular apps. Your voice is always recorded; " +
                        "how clearly the other person is recorded depends on your phone. Speakerphone gives the best result. " +
                        "Tell the other person when a call is recorded where the law requires it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
            }
        }
    }
}

private fun openAllFilesAccess(context: Context) {
    val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        .setData(Uri.parse("package:${context.packageName}"))
    if (!openSettings(context, appIntent)) {
        openSettings(context, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}

private fun openSettings(context: Context, intent: Intent): Boolean {
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
