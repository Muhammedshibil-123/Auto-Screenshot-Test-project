package com.simonbrs.autoscreenshot

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.simonbrs.autoscreenshot.service.ScreenshotService
import com.simonbrs.autoscreenshot.ui.theme.AutoScreenshotTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_AUTO_START_SERVICE = "AUTO_START_SERVICE"

        private const val LEGACY_KEY_SERVICE_RUNNING = "service_running"
        private const val KEY_ONBOARDING_SKIPPED = "onboarding_skipped"
        private const val KEY_BATTERY_SETUP_ACKNOWLEDGED = "battery_setup_acknowledged"
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var settingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var prefs: SharedPreferences

    private var isServiceRunning by mutableStateOf(false)
    private var permissionUiState by mutableStateOf(PermissionUiState())
    private var showOnboarding by mutableStateOf(true)
    private var pendingIntervalSeconds = ScreenshotService.DEFAULT_INTERVAL_SECONDS
    private var pendingCaptureAfterPermissions = false
    private var shouldAutoStart = false

    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenshotService.ACTION_SERVICE_STATUS_CHANGED) {
                val running = intent.getBooleanExtra(ScreenshotService.EXTRA_IS_RUNNING, false)
                isServiceRunning = running
                saveRuntimeServiceState(running)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
        migrateLegacyServicePreference()

        isServiceRunning = ScreenshotService.isServiceRunning()
        pendingIntervalSeconds = prefs.getLong(
            ScreenshotService.KEY_SCREENSHOT_INTERVAL_SECONDS,
            ScreenshotService.DEFAULT_INTERVAL_SECONDS
        )
        shouldAutoStart = intent?.getBooleanExtra(EXTRA_AUTO_START_SERVICE, false) ?: false

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            syncPermissionState()

            if (allGranted) {
                continuePendingCaptureIfReady(requestMissingPermissions = true)
            } else {
                pendingCaptureAfterPermissions = false
                Toast.makeText(this, "Required permissions were not granted", Toast.LENGTH_SHORT).show()
            }
        }

        settingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            syncPermissionState()
            continuePendingCaptureIfReady(requestMissingPermissions = false)
        }

        mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenshotService::class.java).apply {
                    putExtra(ScreenshotService.EXTRA_RESULT_DATA, result.data)
                    putExtra(ScreenshotService.EXTRA_INTERVAL_SECONDS, pendingIntervalSeconds)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                isServiceRunning = true
                saveTrackingEnabled(true)
                saveRuntimeServiceState(true)

                Toast.makeText(
                    this,
                    "Screenshot service started - saving to /storage/emulated/0/Screenshot/YYYY/MM/DD/",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                saveTrackingEnabled(false)
                saveRuntimeServiceState(false)
                Toast.makeText(this, "Permission denied, cannot take screenshots", Toast.LENGTH_SHORT).show()
            }
        }

        syncPermissionState()
        showOnboarding = !prefs.getBoolean(KEY_ONBOARDING_SKIPPED, false) &&
            !permissionUiState.allRecommendedComplete &&
            !isServiceRunning

        setContent {
            AutoScreenshotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showOnboarding && !isServiceRunning) {
                        PermissionOnboardingScreen(
                            permissionUiState = permissionUiState,
                            onRequestStorage = { requestStoragePermission() },
                            onRequestNotifications = { requestNotificationPermission() },
                            onRequestBattery = { requestIgnoreBatteryOptimizations() },
                            onContinue = { skipOnboarding() },
                            onStartAnyway = {
                                skipOnboarding()
                                startScreenshotCapture(pendingIntervalSeconds)
                            },
                            onRefresh = {
                                syncPermissionState()
                                syncServiceRunningState()
                            }
                        )
                    } else {
                        ScreenshotScreen(
                            isServiceRunning = isServiceRunning,
                            initialIntervalSeconds = pendingIntervalSeconds,
                            onStartService = { intervalSeconds ->
                                startScreenshotCapture(intervalSeconds)
                            },
                            onStopService = {
                                stopScreenshotService()
                            },
                            onOpenSetup = {
                                prefs.edit().putBoolean(KEY_ONBOARDING_SKIPPED, false).apply()
                                syncPermissionState()
                                showOnboarding = true
                            }
                        )
                    }
                }
            }
        }

        if (shouldAutoStart) {
            startScreenshotCapture(pendingIntervalSeconds)
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            serviceStatusReceiver,
            IntentFilter(ScreenshotService.ACTION_SERVICE_STATUS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        syncServiceRunningState()
    }

    override fun onResume() {
        super.onResume()
        syncPermissionState()
        syncServiceRunningState()
        continuePendingCaptureIfReady(requestMissingPermissions = false)
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(serviceStatusReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was not registered.
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (intent.getBooleanExtra(EXTRA_AUTO_START_SERVICE, false)) {
            shouldAutoStart = true
            startScreenshotCapture(pendingIntervalSeconds)
        }
    }

    private fun migrateLegacyServicePreference() {
        if (!prefs.contains(ScreenshotService.KEY_SERVICE_ENABLED)) {
            val legacyRunning = prefs.getBoolean(LEGACY_KEY_SERVICE_RUNNING, false)
            prefs.edit()
                .putBoolean(ScreenshotService.KEY_SERVICE_ENABLED, legacyRunning)
                .apply()
        }
    }

    private fun saveTrackingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(ScreenshotService.KEY_SERVICE_ENABLED, enabled).apply()
    }

    private fun saveRuntimeServiceState(running: Boolean) {
        prefs.edit().putBoolean(ScreenshotService.KEY_SERVICE_RUNNING, running).apply()
    }

    private fun saveScreenshotInterval(intervalSeconds: Long) {
        prefs.edit()
            .putLong(ScreenshotService.KEY_SCREENSHOT_INTERVAL_SECONDS, intervalSeconds)
            .apply()
    }

    private fun startScreenshotCapture(intervalSeconds: Long) {
        pendingIntervalSeconds = intervalSeconds.coerceAtLeast(1L)
        saveScreenshotInterval(pendingIntervalSeconds)
        pendingCaptureAfterPermissions = true
        continuePendingCaptureIfReady(requestMissingPermissions = true)
    }

    private fun continuePendingCaptureIfReady(requestMissingPermissions: Boolean) {
        if (!pendingCaptureAfterPermissions && !shouldAutoStart) {
            return
        }

        val ready = if (requestMissingPermissions) {
            checkAndRequestPermissions()
        } else {
            hasRequiredPermissions()
        }

        if (ready) {
            pendingCaptureAfterPermissions = false
            shouldAutoStart = false
            requestMediaProjection()
        }
    }

    private fun stopScreenshotService() {
        pendingCaptureAfterPermissions = false
        shouldAutoStart = false
        saveTrackingEnabled(false)
        saveRuntimeServiceState(false)
        stopService(Intent(this, ScreenshotService::class.java))
        isServiceRunning = false
        Toast.makeText(this, "Screenshot service stopped", Toast.LENGTH_SHORT).show()
    }

    private fun syncServiceRunningState() {
        val running = ScreenshotService.isServiceRunning()
        isServiceRunning = running
        saveRuntimeServiceState(running)
    }

    private fun syncPermissionState() {
        val batterySetupAcknowledged = prefs.getBoolean(KEY_BATTERY_SETUP_ACKNOWLEDGED, false)
        permissionUiState = PermissionUiState(
            storageGranted = hasStorageAccess(),
            notificationsGranted = hasNotificationPermission(),
            batteryUnrestricted = isIgnoringBatteryOptimizations() || batterySetupAcknowledged
        )
    }

    private fun hasRequiredPermissions(): Boolean {
        syncPermissionState()
        return permissionUiState.canAttemptCapture
    }

    private fun checkAndRequestPermissions(): Boolean {
        syncPermissionState()

        if (!hasStorageAccess()) {
            requestStoragePermission()
            return false
        }

        return true
    }

    private fun requestMediaProjection() {
        if (!hasStorageAccess()) {
            requestStoragePermission()
            return
        }

        val intent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            syncPermissionState()
        }
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestManageExternalStoragePermission()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun requestManageExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                settingsLauncher.launch(intent)
                Toast.makeText(this, "Please grant storage permission", Toast.LENGTH_SHORT).show()
            } catch (_: ActivityNotFoundException) {
                settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (isIgnoringBatteryOptimizations()) {
            syncPermissionState()
            return
        }

        prefs.edit().putBoolean(KEY_BATTERY_SETUP_ACKNOWLEDGED, true).apply()
        syncPermissionState()

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            settingsLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            settingsLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun requestOverlayPermission() {
        Toast.makeText(this, "Please grant overlay permission for service stability", Toast.LENGTH_SHORT).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        settingsLauncher.launch(intent)
    }

    private fun skipOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDING_SKIPPED, true).apply()
        showOnboarding = false
    }
}

data class PermissionUiState(
    val storageGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val batteryUnrestricted: Boolean = false
) {
    val canAttemptCapture: Boolean
        get() = storageGranted

    val allRecommendedComplete: Boolean
        get() = storageGranted && notificationsGranted && batteryUnrestricted
}

@Composable
fun PermissionOnboardingScreen(
    permissionUiState: PermissionUiState,
    onRequestStorage: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBattery: () -> Unit,
    onContinue: () -> Unit,
    onStartAnyway: () -> Unit,
    onRefresh: () -> Unit
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Finish Setup",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Allow the essentials so screenshots can be saved reliably while the app runs in the background.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            PermissionStep(
                title = "Storage access",
                description = "Saves screenshot files into dated folders so you can inspect them later.",
                granted = permissionUiState.storageGranted,
                required = true,
                actionText = "Allow storage",
                onAction = onRequestStorage
            )

            PermissionStep(
                title = "Notifications",
                description = "Shows the required foreground service notification while capture is active.",
                granted = permissionUiState.notificationsGranted,
                required = false,
                actionText = "Allow notifications",
                onAction = onRequestNotifications
            )

            PermissionStep(
                title = "Battery background access",
                description = "Opens Android battery settings. Keep background usage allowed, or choose Unrestricted if your phone shows that option.",
                granted = permissionUiState.batteryUnrestricted,
                required = false,
                actionText = "Open battery settings",
                onAction = onRequestBattery
            )

            Button(
                onClick = onStartAnyway,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Start anyway")
            }

            OutlinedButton(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip setup")
            }

            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh status")
            }
        }
    }
}

@Composable
fun PermissionStep(
    title: String,
    description: String,
    granted: Boolean,
    required: Boolean,
    actionText: String,
    onAction: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (granted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (granted) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = when {
                        granted -> "Done"
                        required -> "Required"
                        else -> "Recommended"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium
            )
            if (!granted) {
                Button(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(actionText)
                }
            }
        }
    }
}

@Composable
fun ScreenshotScreen(
    isServiceRunning: Boolean,
    initialIntervalSeconds: Long,
    onStartService: (Long) -> Unit,
    onStopService: () -> Unit,
    onOpenSetup: () -> Unit
) {
    var intervalText by rememberSaveable { mutableStateOf(initialIntervalSeconds.toString()) }
    val intervalSeconds = intervalText.trim().toLongOrNull()
    val isIntervalValid = intervalSeconds != null && intervalSeconds >= 1L
    val activeInterval = intervalSeconds ?: initialIntervalSeconds

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Auto Screenshot",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (isServiceRunning) {
                            "Capturing every $activeInterval seconds"
                        } else {
                            "Set an interval and start capture"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = if (isServiceRunning) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (isServiceRunning) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Status",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = if (isServiceRunning) "Running" else "Idle",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        Text(
                            text = if (isServiceRunning) "Active" else "Ready",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { newValue ->
                        intervalText = newValue.filter { it.isDigit() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isServiceRunning,
                    singleLine = true,
                    label = {
                        Text("Interval seconds")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !isIntervalValid,
                    supportingText = if (!isIntervalValid) {
                        {
                            Text("Minimum is 1 second")
                        }
                    } else {
                        null
                    }
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        if (isServiceRunning) {
                            onStopService()
                        } else if (intervalSeconds != null) {
                            onStartService(intervalSeconds)
                        }
                    },
                    enabled = isServiceRunning || isIntervalValid,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(if (isServiceRunning) "Stop" else "Start")
                }

                if (!isServiceRunning) {
                    OutlinedButton(
                        onClick = onOpenSetup,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Setup permissions")
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PermissionOnboardingScreenPreview() {
    AutoScreenshotTheme {
        PermissionOnboardingScreen(
            permissionUiState = PermissionUiState(
                storageGranted = true,
                notificationsGranted = false,
                batteryUnrestricted = false
            ),
            onRequestStorage = {},
            onRequestNotifications = {},
            onRequestBattery = {},
            onContinue = {},
            onStartAnyway = {},
            onRefresh = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ScreenshotScreenPreview() {
    AutoScreenshotTheme {
        ScreenshotScreen(
            isServiceRunning = false,
            initialIntervalSeconds = ScreenshotService.DEFAULT_INTERVAL_SECONDS,
            onStartService = {},
            onStopService = {},
            onOpenSetup = {}
        )
    }
}
