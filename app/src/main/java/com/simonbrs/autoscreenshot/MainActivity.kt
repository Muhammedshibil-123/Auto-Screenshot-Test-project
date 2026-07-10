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
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.simonbrs.autoscreenshot.service.ScreenshotAccessibilityService
import com.simonbrs.autoscreenshot.ui.theme.AutoScreenshotTheme
import com.simonbrs.autoscreenshot.ui.navigation.AppNavigation

class MainActivity : ComponentActivity() {
    companion object {
        private const val KEY_AUTOSTART_SETUP_ACKNOWLEDGED = "autostart_setup_acknowledged"
        private const val KEY_RELIABILITY_SETUP_SKIPPED = "reliability_setup_skipped"
    }

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var settingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var prefs: SharedPreferences

    private var isCaptureRunning by mutableStateOf(false)
    private var isAccessibilityEnabled by mutableStateOf(false)
    private var hasStorageAccess by mutableStateOf(false)
    private var isBatteryUnrestricted by mutableStateOf(false)
    private var isAutostartSetupAcknowledged by mutableStateOf(false)
    private var isReliabilitySetupSkipped by mutableStateOf(false)
    private var showSetupFlow by mutableStateOf(true)
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
        showSetupFlow = !isSetupFinished()

        setContent {
            AutoScreenshotTheme {
                AppNavigation(
                    startDestination = if (showSetupFlow) "onboarding" else "main",
                    isCaptureRunning = isCaptureRunning,
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    hasStorageAccess = hasStorageAccess,
                    isBatteryUnrestricted = isBatteryUnrestricted,
                    isAutostartSetupAcknowledged = isAutostartSetupAcknowledged,
                    initialIntervalSeconds = savedIntervalSeconds,
                    onStartCapture = ::startCapture,
                    onStopCapture = ::stopCapture,
                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                    onRequestStorage = ::requestStoragePermission,
                    onRequestBatteryUnrestricted = ::requestIgnoreBatteryOptimizations,
                    onOpenAutostartSettings = ::openAutostartSettings,
                    onSkipReliabilitySetup = ::skipReliabilitySetup,
                    onRefresh = ::syncState
                )
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
        isBatteryUnrestricted = isIgnoringBatteryOptimizations()
        isAutostartSetupAcknowledged = prefs.getBoolean(KEY_AUTOSTART_SETUP_ACKNOWLEDGED, false)
        isReliabilitySetupSkipped = prefs.getBoolean(KEY_RELIABILITY_SETUP_SKIPPED, false)
        savedIntervalSeconds = prefs.getLong(
            ScreenshotAccessibilityService.KEY_SCREENSHOT_INTERVAL_SECONDS,
            ScreenshotAccessibilityService.DEFAULT_INTERVAL_SECONDS
        )
        isCaptureRunning = isAccessibilityEnabled &&
            prefs.getBoolean(ScreenshotAccessibilityService.KEY_SERVICE_RUNNING, false)
    }

    private fun isSetupFinished(): Boolean {
        val optionalDone = isReliabilitySetupSkipped ||
            (isBatteryUnrestricted && isAutostartSetupAcknowledged)
        return isAccessibilityEnabled && hasStorageAccess && optionalDone
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
            showSetupFlow = true
            return
        }

        if (!isAccessibilityServiceEnabled()) {
            openAccessibilitySettings()
            Toast.makeText(this, "Turn on AutoScreenshot in Accessibility settings", Toast.LENGTH_LONG).show()
            syncState()
            showSetupFlow = true
            return
        }

        ScreenshotAccessibilityService.refreshRunningService()
        syncState()
        showSetupFlow = false
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
        launchSettingsIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
        val appStorageIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        if (!launchSettingsIntent(appStorageIntent)) {
            launchSettingsIntent(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (isIgnoringBatteryOptimizations()) {
            syncState()
            Toast.makeText(this, "Battery optimization is already unrestricted", Toast.LENGTH_SHORT).show()
            return
        }

        val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        if (!launchSettingsIntent(requestIntent)) {
            launchSettingsIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun skipReliabilitySetup() {
        prefs.edit().putBoolean(KEY_RELIABILITY_SETUP_SKIPPED, true).apply()
        syncState()
        showSetupFlow = false
    }

    private fun openAutostartSettings() {
        val opened = launchFirstAvailableIntent(autostartIntents() + autostartFallbackIntents())

        if (opened) {
            prefs.edit().putBoolean(KEY_AUTOSTART_SETUP_ACKNOWLEDGED, true).apply()
            syncState()
            Toast.makeText(
                this,
                "Enable Autostart or Background running if your phone shows it",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "Autostart settings are not available on this phone", Toast.LENGTH_LONG).show()
        }
    }

    private fun autostartIntents(): List<Intent> {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()
        val deviceName = "$manufacturer $brand $model"

        return when {
            deviceName.contains("vivo") || deviceName.contains("iqoo") -> listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                    )
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.safeguard.PurviewTabActivity"
                    )
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.PurviewTabActivity"
                    )
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.MainActivity"
                    )
                )
            )
            deviceName.contains("xiaomi") ||
                deviceName.contains("redmi") ||
                deviceName.contains("poco") -> listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity"
                    )
                ).putExtra("extra_pkgname", packageName)
            )
            deviceName.contains("oppo") ||
                deviceName.contains("realme") ||
                deviceName.contains("oneplus") -> listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity"
                    )
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.coloros.oppoguardelf",
                        "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                    )
                )
            )
            deviceName.contains("huawei") || deviceName.contains("honor") -> listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    )
                )
            )
            deviceName.contains("asus") -> listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.asus.mobilemanager",
                        "com.asus.mobilemanager.entry.FunctionActivity"
                    )
                )
            )
            else -> emptyList()
        }
    }

    private fun autostartFallbackIntents(): List<Intent> {
        return listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            },
            Intent(Settings.ACTION_SETTINGS)
        )
    }

    private fun launchFirstAvailableIntent(intents: List<Intent>): Boolean {
        for (intent in intents) {
            if (launchSettingsIntent(intent)) {
                return true
            }
        }
        return false
    }

    private fun launchSettingsIntent(intent: Intent): Boolean {
        return try {
            settingsLauncher.launch(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
