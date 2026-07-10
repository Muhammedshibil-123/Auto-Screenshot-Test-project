package com.simonbrs.autoscreenshot

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
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
import com.simonbrs.autoscreenshot.service.ScreenshotAccessibilityService
import com.simonbrs.autoscreenshot.ui.theme.AutoScreenshotTheme

class MainActivity : ComponentActivity() {
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var settingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var prefs: SharedPreferences

    private var isCaptureRunning by mutableStateOf(false)
    private var isAccessibilityEnabled by mutableStateOf(false)
    private var hasStorageAccess by mutableStateOf(false)
    private var savedIntervalSeconds by mutableStateOf(ScreenshotAccessibilityService.DEFAULT_INTERVAL_SECONDS)

    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenshotAccessibilityService.ACTION_SERVICE_STATUS_CHANGED) {
                isCaptureRunning = intent.getBooleanExtra(
                    ScreenshotAccessibilityService.EXTRA_IS_RUNNING,
                    false
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = getSharedPreferences(ScreenshotAccessibilityService.PREFS_NAME, MODE_PRIVATE)
        savedIntervalSeconds = prefs.getLong(
            ScreenshotAccessibilityService.KEY_SCREENSHOT_INTERVAL_SECONDS,
            ScreenshotAccessibilityService.DEFAULT_INTERVAL_SECONDS
        )

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            syncState()
        }

        settingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            syncState()
            ScreenshotAccessibilityService.refreshRunningService()
        }

        syncState()

        setContent {
            AutoScreenshotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AccessibilityScreenshotScreen(
                        isCaptureRunning = isCaptureRunning,
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        hasStorageAccess = hasStorageAccess,
                        initialIntervalSeconds = savedIntervalSeconds,
                        onStartCapture = { intervalSeconds ->
                            startCapture(intervalSeconds)
                        },
                        onStopCapture = {
                            stopCapture()
                        },
                        onOpenAccessibilitySettings = {
                            openAccessibilitySettings()
                        },
                        onRequestStorage = {
                            requestStoragePermission()
                        },
                        onRefresh = {
                            syncState()
                            ScreenshotAccessibilityService.refreshRunningService()
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            serviceStatusReceiver,
            IntentFilter(ScreenshotAccessibilityService.ACTION_SERVICE_STATUS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        syncState()
    }

    override fun onResume() {
        super.onResume()
        syncState()
        ScreenshotAccessibilityService.refreshRunningService()
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(serviceStatusReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was not registered.
        }
    }

    private fun syncState() {
        hasStorageAccess = hasStorageAccess()
        isAccessibilityEnabled = isAccessibilityServiceEnabled()
        savedIntervalSeconds = prefs.getLong(
            ScreenshotAccessibilityService.KEY_SCREENSHOT_INTERVAL_SECONDS,
            ScreenshotAccessibilityService.DEFAULT_INTERVAL_SECONDS
        )
        isCaptureRunning = isAccessibilityEnabled &&
            prefs.getBoolean(ScreenshotAccessibilityService.KEY_SERVICE_RUNNING, false)
    }

    private fun startCapture(intervalSeconds: Long) {
        val safeInterval = intervalSeconds.coerceAtLeast(1L)
        savedIntervalSeconds = safeInterval

        prefs.edit()
            .putLong(ScreenshotAccessibilityService.KEY_SCREENSHOT_INTERVAL_SECONDS, safeInterval)
            .putBoolean(ScreenshotAccessibilityService.KEY_SERVICE_ENABLED, true)
            .apply()

        if (!hasStorageAccess()) {
            requestStoragePermission()
            Toast.makeText(this, "Allow storage access before starting capture", Toast.LENGTH_SHORT).show()
            syncState()
            return
        }

        if (!isAccessibilityServiceEnabled()) {
            openAccessibilitySettings()
            Toast.makeText(this, "Turn on AutoScreenshot in Accessibility settings", Toast.LENGTH_LONG).show()
            syncState()
            return
        }

        ScreenshotAccessibilityService.refreshRunningService()
        syncState()
        Toast.makeText(this, "Screenshot capture enabled", Toast.LENGTH_SHORT).show()
    }

    private fun stopCapture() {
        prefs.edit()
            .putBoolean(ScreenshotAccessibilityService.KEY_SERVICE_ENABLED, false)
            .putBoolean(ScreenshotAccessibilityService.KEY_SERVICE_RUNNING, false)
            .apply()

        ScreenshotAccessibilityService.refreshRunningService()
        syncState()
        Toast.makeText(this, "Screenshot capture stopped", Toast.LENGTH_SHORT).show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityEnabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1

        if (!accessibilityEnabled) {
            return false
        }

        val expectedService = ComponentName(
            this,
            ScreenshotAccessibilityService::class.java
        ).flattenToString()

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)

        for (service in splitter) {
            if (service.equals(expectedService, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    private fun openAccessibilitySettings() {
        settingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            settingsLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}

@Composable
fun AccessibilityScreenshotScreen(
    isCaptureRunning: Boolean,
    isAccessibilityEnabled: Boolean,
    hasStorageAccess: Boolean,
    initialIntervalSeconds: Long,
    onStartCapture: (Long) -> Unit,
    onStopCapture: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestStorage: () -> Unit,
    onRefresh: () -> Unit
) {
    var intervalText by rememberSaveable(initialIntervalSeconds) {
        mutableStateOf(initialIntervalSeconds.toString())
    }
    val intervalSeconds = intervalText.trim().toLongOrNull()
    val isIntervalValid = intervalSeconds != null && intervalSeconds >= 1L

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Auto Screenshot",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (isCaptureRunning) {
                            "Capturing every ${intervalSeconds ?: initialIntervalSeconds} seconds"
                        } else {
                            "Enable Accessibility once, then capture runs from the saved interval."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                StatusPanel(
                    isCaptureRunning = isCaptureRunning,
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    hasStorageAccess = hasStorageAccess
                )

                if (!isAccessibilityEnabled) {
                    Button(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text("Open Accessibility Settings")
                    }
                }

                if (!hasStorageAccess) {
                    OutlinedButton(
                        onClick = onRequestStorage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text("Allow Storage Access")
                    }
                }

                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { newValue ->
                        intervalText = newValue.filter { it.isDigit() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isCaptureRunning,
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
                        if (isCaptureRunning) {
                            onStopCapture()
                        } else if (intervalSeconds != null) {
                            onStartCapture(intervalSeconds)
                        }
                    },
                    enabled = isCaptureRunning || isIntervalValid,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(if (isCaptureRunning) "Stop Capture" else "Start Capture")
                }

                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Refresh Status")
                }

                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun StatusPanel(
    isCaptureRunning: Boolean,
    isAccessibilityEnabled: Boolean,
    hasStorageAccess: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (isCaptureRunning) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (isCaptureRunning) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Capture", style = MaterialTheme.typography.titleMedium)
                Text(if (isCaptureRunning) "Running" else "Stopped")
            }

            SetupLine(
                label = "Accessibility service",
                value = if (isAccessibilityEnabled) "On" else "Off"
            )
            SetupLine(
                label = "Storage access",
                value = if (hasStorageAccess) "Allowed" else "Required"
            )
        }
    }
}

@Composable
private fun SetupLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Preview(showBackground = true)
@Composable
fun AccessibilityScreenshotScreenPreview() {
    AutoScreenshotTheme {
        AccessibilityScreenshotScreen(
            isCaptureRunning = false,
            isAccessibilityEnabled = false,
            hasStorageAccess = true,
            initialIntervalSeconds = ScreenshotAccessibilityService.DEFAULT_INTERVAL_SECONDS,
            onStartCapture = {},
            onStopCapture = {},
            onOpenAccessibilitySettings = {},
            onRequestStorage = {},
            onRefresh = {}
        )
    }
}
