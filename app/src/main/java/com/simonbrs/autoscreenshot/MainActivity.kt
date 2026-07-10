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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AutoScreenshotApp(
                        showSetupFlow = showSetupFlow,
                        isCaptureRunning = isCaptureRunning,
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        hasStorageAccess = hasStorageAccess,
                        isBatteryUnrestricted = isBatteryUnrestricted,
                        isAutostartSetupAcknowledged = isAutostartSetupAcknowledged,
                        isReliabilitySetupSkipped = isReliabilitySetupSkipped,
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
                        onRequestBatteryUnrestricted = {
                            requestIgnoreBatteryOptimizations()
                        },
                        onOpenAutostartSettings = {
                            openAutostartSettings()
                        },
                        onSkipReliabilitySetup = {
                            skipReliabilitySetup()
                        },
                        onFinishSetup = {
                            syncState()
                            if (isAccessibilityEnabled && hasStorageAccess) {
                                if (!isBatteryUnrestricted || !isAutostartSetupAcknowledged) {
                                    prefs.edit()
                                        .putBoolean(KEY_RELIABILITY_SETUP_SKIPPED, true)
                                        .apply()
                                    syncState()
                                }
                                showSetupFlow = false
                            }
                        },
                        onOpenSetup = {
                            showSetupFlow = true
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

private enum class SetupPage {
    Accessibility,
    Storage,
    Battery,
    Autostart,
    Finish
}

private data class SetupPageContent(
    val page: SetupPage,
    val title: String,
    val subtitle: String,
    val status: String,
    val primaryAction: String,
    val iconRes: Int,
    val accent: Color,
    val complete: Boolean,
    val optional: Boolean = false
)

@Composable
fun AutoScreenshotApp(
    showSetupFlow: Boolean,
    isCaptureRunning: Boolean,
    isAccessibilityEnabled: Boolean,
    hasStorageAccess: Boolean,
    isBatteryUnrestricted: Boolean,
    isAutostartSetupAcknowledged: Boolean,
    isReliabilitySetupSkipped: Boolean,
    initialIntervalSeconds: Long,
    onStartCapture: (Long) -> Unit,
    onStopCapture: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestStorage: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onOpenAutostartSettings: () -> Unit,
    onSkipReliabilitySetup: () -> Unit,
    onFinishSetup: () -> Unit,
    onOpenSetup: () -> Unit,
    onRefresh: () -> Unit
) {
    if (showSetupFlow) {
        SetupFlowScreen(
            isAccessibilityEnabled = isAccessibilityEnabled,
            hasStorageAccess = hasStorageAccess,
            isBatteryUnrestricted = isBatteryUnrestricted,
            isAutostartSetupAcknowledged = isAutostartSetupAcknowledged,
            isReliabilitySetupSkipped = isReliabilitySetupSkipped,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onRequestStorage = onRequestStorage,
            onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
            onOpenAutostartSettings = onOpenAutostartSettings,
            onSkipReliabilitySetup = onSkipReliabilitySetup,
            onFinishSetup = onFinishSetup,
            onRefresh = onRefresh
        )
    } else {
        CaptureDashboardScreen(
            isCaptureRunning = isCaptureRunning,
            isAccessibilityEnabled = isAccessibilityEnabled,
            hasStorageAccess = hasStorageAccess,
            isBatteryUnrestricted = isBatteryUnrestricted,
            isAutostartSetupAcknowledged = isAutostartSetupAcknowledged,
            initialIntervalSeconds = initialIntervalSeconds,
            onStartCapture = onStartCapture,
            onStopCapture = onStopCapture,
            onOpenSetup = onOpenSetup,
            onRefresh = onRefresh
        )
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun SetupFlowScreen(
    isAccessibilityEnabled: Boolean,
    hasStorageAccess: Boolean,
    isBatteryUnrestricted: Boolean,
    isAutostartSetupAcknowledged: Boolean,
    isReliabilitySetupSkipped: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestStorage: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onOpenAutostartSettings: () -> Unit,
    onSkipReliabilitySetup: () -> Unit,
    onFinishSetup: () -> Unit,
    onRefresh: () -> Unit
) {
    val pages = setupPages(
        isAccessibilityEnabled = isAccessibilityEnabled,
        hasStorageAccess = hasStorageAccess,
        isBatteryUnrestricted = isBatteryUnrestricted,
        isAutostartSetupAcknowledged = isAutostartSetupAcknowledged,
        isReliabilitySetupSkipped = isReliabilitySetupSkipped
    )
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(isAccessibilityEnabled, hasStorageAccess, isBatteryUnrestricted, isAutostartSetupAcknowledged) {
        pageIndex = pageIndex.coerceAtMost(pages.lastIndex)
    }

    val currentPage = pages[pageIndex]
    val progress by animateFloatAsState(
        targetValue = (pageIndex + 1).toFloat() / pages.size.toFloat(),
        label = "setup-progress"
    )

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Setup",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Step ${pageIndex + 1} of ${pages.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = if (currentPage.optional) "Optional" else "Required",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (currentPage.optional) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                AnimatedContent(
                    targetState = pageIndex,
                    transitionSpec = {
                        val direction = if (targetState > initialState) 1 else -1
                        (
                            slideInHorizontally { fullWidth -> fullWidth * direction } + fadeIn()
                            ).togetherWith(
                            slideOutHorizontally { fullWidth -> -fullWidth * direction } + fadeOut()
                        ).using(SizeTransform(clip = false))
                    },
                    label = "setup-page"
                ) { targetIndex ->
                    SetupStepPage(page = pages[targetIndex])
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        if (currentPage.complete && currentPage.page != SetupPage.Finish && pageIndex < pages.lastIndex) {
                            pageIndex += 1
                        } else {
                            when (currentPage.page) {
                                SetupPage.Accessibility -> onOpenAccessibilitySettings()
                                SetupPage.Storage -> onRequestStorage()
                                SetupPage.Battery -> onRequestBatteryUnrestricted()
                                SetupPage.Autostart -> onOpenAutostartSettings()
                                SetupPage.Finish -> onFinishSetup()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(if (currentPage.complete) "Continue" else currentPage.primaryAction)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (pageIndex > 0) {
                        OutlinedButton(
                            onClick = { pageIndex -= 1 },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Back")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            if (currentPage.page == SetupPage.Finish) {
                                onFinishSetup()
                            } else if (pageIndex < pages.lastIndex) {
                                pageIndex += 1
                            }
                        },
                        enabled = currentPage.complete || currentPage.optional,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (currentPage.optional && !currentPage.complete) "Skip" else "Next")
                    }
                }

                if (currentPage.optional && !currentPage.complete) {
                    OutlinedButton(
                        onClick = onSkipReliabilitySetup,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Skip recommended setup")
                    }
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
}

@Composable
private fun SetupStepPage(page: SetupPageContent) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(page.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(page.iconRes),
                contentDescription = null,
                tint = page.accent,
                modifier = Modifier.size(52.dp)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = page.subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = if (page.complete) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (page.complete) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Status", style = MaterialTheme.typography.labelLarge)
                Text(page.status, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun CaptureDashboardScreen(
    isCaptureRunning: Boolean,
    isAccessibilityEnabled: Boolean,
    hasStorageAccess: Boolean,
    isBatteryUnrestricted: Boolean,
    isAutostartSetupAcknowledged: Boolean,
    initialIntervalSeconds: Long,
    onStartCapture: (Long) -> Unit,
    onStopCapture: () -> Unit,
    onOpenSetup: () -> Unit,
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
                .padding(horizontal = 24.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                DashboardHeader(isCaptureRunning = isCaptureRunning)

                StatusPanel(
                    isCaptureRunning = isCaptureRunning,
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    hasStorageAccess = hasStorageAccess,
                    isBatteryUnrestricted = isBatteryUnrestricted,
                    isAutostartSetupAcknowledged = isAutostartSetupAcknowledged
                )

                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { newValue ->
                        intervalText = newValue.filter { it.isDigit() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isCaptureRunning,
                    singleLine = true,
                    label = { Text("Interval seconds") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !isIntervalValid,
                    supportingText = if (!isIntervalValid) {
                        { Text("Minimum is 1 second") }
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenSetup,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Setup")
                    }
                    OutlinedButton(
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Refresh")
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun DashboardHeader(isCaptureRunning: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (isCaptureRunning) 1f else 0.72f,
        label = "capture-alpha"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha),
        shape = MaterialTheme.shapes.large,
        color = if (isCaptureRunning) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_capture),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Auto Screenshot",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isCaptureRunning) {
                        "Capture is active"
                    } else {
                        "Ready when you are"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun StatusPanel(
    isCaptureRunning: Boolean,
    isAccessibilityEnabled: Boolean,
    hasStorageAccess: Boolean,
    isBatteryUnrestricted: Boolean,
    isAutostartSetupAcknowledged: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SetupLine(
                label = "Capture",
                value = if (isCaptureRunning) "Running" else "Stopped",
                strong = true
            )
            SetupLine(
                label = "Accessibility",
                value = if (isAccessibilityEnabled) "On" else "Required"
            )
            SetupLine(
                label = "Storage",
                value = if (hasStorageAccess) "Allowed" else "Required"
            )
            SetupLine(
                label = "Battery",
                value = if (isBatteryUnrestricted) "Unrestricted" else "Optional"
            )
            SetupLine(
                label = "Autostart",
                value = if (isAutostartSetupAcknowledged) "Checked" else "Optional"
            )
        }
    }
}

@Composable
private fun SetupLine(label: String, value: String, strong: Boolean = false) {
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
            style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun setupPages(
    isAccessibilityEnabled: Boolean,
    hasStorageAccess: Boolean,
    isBatteryUnrestricted: Boolean,
    isAutostartSetupAcknowledged: Boolean,
    isReliabilitySetupSkipped: Boolean
): List<SetupPageContent> {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val error = MaterialTheme.colorScheme.error

    return listOf(
        SetupPageContent(
            page = SetupPage.Accessibility,
            title = "Enable Accessibility",
            subtitle = "This is the screenshot engine. Android keeps the enabled service available after screen off and reboot.",
            status = if (isAccessibilityEnabled) "Enabled" else "Needed",
            primaryAction = "Open Accessibility Settings",
            iconRes = R.drawable.ic_accessibility,
            accent = primary,
            complete = isAccessibilityEnabled
        ),
        SetupPageContent(
            page = SetupPage.Storage,
            title = "Allow Storage",
            subtitle = "Screenshots are saved into /storage/emulated/0/Screenshot with year, month, and day folders.",
            status = if (hasStorageAccess) "Allowed" else "Needed",
            primaryAction = "Allow Storage Access",
            iconRes = R.drawable.ic_storage,
            accent = secondary,
            complete = hasStorageAccess
        ),
        SetupPageContent(
            page = SetupPage.Battery,
            title = "Relax Battery Limits",
            subtitle = "Optional. Helpful on strict phones if capture stops after long idle time or battery saver changes.",
            status = if (isBatteryUnrestricted) "Unrestricted" else "Optional",
            primaryAction = "Allow Unrestricted Battery",
            iconRes = R.drawable.ic_battery,
            accent = tertiary,
            complete = isBatteryUnrestricted || isReliabilitySetupSkipped,
            optional = true
        ),
        SetupPageContent(
            page = SetupPage.Autostart,
            title = "Check Autostart",
            subtitle = "Optional. Some brands hide a separate switch named Autostart, Background start, or Auto launch.",
            status = if (isAutostartSetupAcknowledged) "Checked" else "Optional",
            primaryAction = "Open Autostart Settings",
            iconRes = R.drawable.ic_autostart,
            accent = error,
            complete = isAutostartSetupAcknowledged || isReliabilitySetupSkipped,
            optional = true
        ),
        SetupPageContent(
            page = SetupPage.Finish,
            title = "Ready to Capture",
            subtitle = "Required setup is complete. You can start capture from the main screen and change the interval anytime.",
            status = "Ready",
            primaryAction = "Go to Capture",
            iconRes = R.drawable.ic_capture,
            accent = primary,
            complete = isAccessibilityEnabled && hasStorageAccess
        )
    )
}

@Preview(showBackground = true)
@Composable
fun AutoScreenshotAppPreview() {
    AutoScreenshotTheme {
        AutoScreenshotApp(
            showSetupFlow = true,
            isCaptureRunning = false,
            isAccessibilityEnabled = false,
            hasStorageAccess = true,
            isBatteryUnrestricted = false,
            isAutostartSetupAcknowledged = false,
            isReliabilitySetupSkipped = false,
            initialIntervalSeconds = ScreenshotAccessibilityService.DEFAULT_INTERVAL_SECONDS,
            onStartCapture = {},
            onStopCapture = {},
            onOpenAccessibilitySettings = {},
            onRequestStorage = {},
            onRequestBatteryUnrestricted = {},
            onOpenAutostartSettings = {},
            onSkipReliabilitySetup = {},
            onFinishSetup = {},
            onOpenSetup = {},
            onRefresh = {}
        )
    }
}
