package com.simonbrs.autoscreenshot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
        private const val OVERLAY_PERMISSION_CODE = 101
        private const val PREFS_NAME = "AutoScreenshotPrefs"
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val KEY_SCREENSHOT_INTERVAL_SECONDS = "screenshot_interval_seconds"
        private const val AUTO_START_SERVICE = "AUTO_START_SERVICE"
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var prefs: SharedPreferences

    private var isServiceRunning by mutableStateOf(false)
    private var pendingIntervalSeconds = ScreenshotService.DEFAULT_INTERVAL_SECONDS
    private var shouldAutoStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isServiceRunning = prefs.getBoolean(KEY_SERVICE_RUNNING, false)
        pendingIntervalSeconds = prefs.getLong(
            KEY_SCREENSHOT_INTERVAL_SECONDS,
            ScreenshotService.DEFAULT_INTERVAL_SECONDS
        )
        shouldAutoStart = intent?.getBooleanExtra(AUTO_START_SERVICE, false) ?: false

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        requestManageExternalStoragePermission()
                    } else if (!Settings.canDrawOverlays(this)) {
                        requestOverlayPermission()
                    } else {
                        requestMediaProjection()
                    }
                } else {
                    if (!Settings.canDrawOverlays(this)) {
                        requestOverlayPermission()
                    } else {
                        requestMediaProjection()
                    }
                }
            } else {
                Toast.makeText(this, "Permissions are required to take screenshots", Toast.LENGTH_SHORT).show()
            }
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
                saveServiceRunningState(true)

                Toast.makeText(
                    this,
                    "Screenshot service started - saving to /storage/emulated/0/Screenshot/YYYY/MM/DD/",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "Permission denied, cannot take screenshots", Toast.LENGTH_SHORT).show()
            }
        }

        setContent {
            AutoScreenshotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScreenshotScreen(
                        isServiceRunning = isServiceRunning,
                        initialIntervalSeconds = pendingIntervalSeconds,
                        onStartService = { intervalSeconds ->
                            startScreenshotCapture(intervalSeconds)
                        },
                        onStopService = {
                            stopScreenshotService()
                        }
                    )
                }
            }
        }

        if (shouldAutoStart) {
            startScreenshotCapture(pendingIntervalSeconds)
        }
    }

    override fun onResume() {
        super.onResume()

        // Check if we need to continue the permission flow after external storage or overlay.
        if (shouldAutoStart) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                return
            }

            if (!Settings.canDrawOverlays(this)) {
                return
            }

            requestMediaProjection()
        }
    }

    private fun saveServiceRunningState(running: Boolean) {
        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, running).apply()
    }

    private fun saveScreenshotInterval(intervalSeconds: Long) {
        prefs.edit().putLong(KEY_SCREENSHOT_INTERVAL_SECONDS, intervalSeconds).apply()
    }

    private fun startScreenshotCapture(intervalSeconds: Long) {
        pendingIntervalSeconds = intervalSeconds.coerceAtLeast(1L)
        saveScreenshotInterval(pendingIntervalSeconds)

        if (checkAndRequestPermissions()) {
            requestMediaProjection()
        }
    }

    private fun stopScreenshotService() {
        stopService(Intent(this, ScreenshotService::class.java))
        isServiceRunning = false
        saveServiceRunningState(false)
        Toast.makeText(this, "Screenshot service stopped", Toast.LENGTH_SHORT).show()
    }

    private fun requestMediaProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            requestManageExternalStoragePermission()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        val intent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }

    private fun requestOverlayPermission() {
        Toast.makeText(this, "Please grant overlay permission for service stability", Toast.LENGTH_SHORT).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, OVERLAY_PERMISSION_CODE)
    }

    private fun checkAndRequestPermissions(): Boolean {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                requestManageExternalStoragePermission()
                return false
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return false
        }

        return true
    }

    private fun requestManageExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                Toast.makeText(this, "Please grant storage permission", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == OVERLAY_PERMISSION_CODE) {
            if (Settings.canDrawOverlays(this)) {
                if (shouldAutoStart) {
                    requestMediaProjection()
                }
            } else {
                Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
fun ScreenshotScreen(
    isServiceRunning: Boolean,
    initialIntervalSeconds: Long,
    onStartService: (Long) -> Unit,
    onStopService: () -> Unit
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

                Spacer(modifier = Modifier.height(4.dp))
            }
        }
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
            onStopService = {}
        )
    }
}
