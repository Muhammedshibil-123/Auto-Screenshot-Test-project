package com.simonbrs.autoscreenshot.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.simonbrs.autoscreenshot.data.AppSessionStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ScreenshotAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "ScreenshotA11yService"
        private const val MIN_INTERVAL_SECONDS = 1L
        private const val SCREENSHOT_QUALITY = 65
        private const val SCREENSHOT_EXTENSION = "webp"
        private const val SCREENSHOT_ROOT_PATH = "/storage/emulated/0/Screenshot"
        private const val AUTO_DELETE_CLEANUP_INTERVAL_MILLIS = 60L * 60L * 1000L
        private val SCREENSHOT_COMPRESS_FORMAT = Bitmap.CompressFormat.WEBP_LOSSY
        private val SCREENSHOT_IMAGE_EXTENSIONS = setOf("webp", "jpg", "jpeg", "png")

        const val DEFAULT_INTERVAL_SECONDS = 10L
        const val DEFAULT_AUTO_DELETE_RETENTION_DAYS = 30
        const val PREFS_NAME = "AutoScreenshotPrefs"
        const val KEY_SERVICE_ENABLED = "service_enabled"
        const val KEY_SERVICE_RUNNING = "service_running"
        const val KEY_SCREENSHOT_INTERVAL_SECONDS = "screenshot_interval_seconds"
        const val KEY_AUTO_DELETE_RETENTION_DAYS = "auto_delete_retention_days"
        const val ACTION_SERVICE_STATUS_CHANGED = "com.simonbrs.autoscreenshot.SERVICE_STATUS_CHANGED"
        const val EXTRA_IS_RUNNING = "extra_is_running"

        private val isRunning = AtomicBoolean(false)

        @Volatile
        private var activeService: ScreenshotAccessibilityService? = null

        fun isServiceRunning(): Boolean = isRunning.get()

        fun refreshRunningService() {
            activeService?.syncCaptureLoopFromPreferences()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isCaptureInFlight = AtomicBoolean(false)
    private val screenshotCount = AtomicInteger(0)

    private var captureLoopRunning = false
    private var previousScreenshotPath: String? = null
    private var lastAutoDeleteCleanupMillis = 0L
    @Volatile
    private var currentPackageName: String? = null

    @Volatile
    private var currentAppName: String? = null

    private val appSessionStore by lazy { AppSessionStore(applicationContext) }
    private var isScreenReceiverRegistered = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_SHUTDOWN -> closeCurrentSession(System.currentTimeMillis())
            }
        }
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!captureLoopRunning) {
                return
            }

            if (!isCaptureEnabled()) {
                stopCaptureLoop()
                return
            }

            takeAccessibilityScreenshot()
            mainHandler.postDelayed(this, currentIntervalMillis())
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
        activeService = this
        restoreActiveSession()
        registerScreenStateReceiver()
        syncCaptureLoopFromPreferences()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isForegroundChangeEvent(event)) {
            return
        }

        val foregroundPackage = event.packageName
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: return

        if (shouldIgnoreForegroundPackage(foregroundPackage) || foregroundPackage == currentPackageName) {
            return
        }

        recordForegroundPackageChange(foregroundPackage, event.eventTime.takeIf { it > 0L } ?: System.currentTimeMillis())
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.d(TAG, "Accessibility service destroyed")
        stopCaptureLoop()
        closeCurrentSession(System.currentTimeMillis())
        unregisterScreenStateReceiver()
        activeService = null
        isRunning.set(false)
        captureExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun syncCaptureLoopFromPreferences() {
        if (isCaptureEnabled()) {
            startCaptureLoop()
        } else {
            stopCaptureLoop()
        }
    }

    private fun startCaptureLoop() {
        if (captureLoopRunning) {
            broadcastRuntimeState(true)
            return
        }

        Log.d(TAG, "Starting capture loop")
        captureLoopRunning = true
        isRunning.set(true)
        saveRuntimeState(true)
        broadcastRuntimeState(true)
        mainHandler.removeCallbacks(captureRunnable)
        mainHandler.post(captureRunnable)
    }

    private fun stopCaptureLoop() {
        if (!captureLoopRunning && !isRunning.get()) {
            saveRuntimeState(false)
            broadcastRuntimeState(false)
            return
        }

        Log.d(TAG, "Stopping capture loop")
        captureLoopRunning = false
        isRunning.set(false)
        mainHandler.removeCallbacks(captureRunnable)
        saveRuntimeState(false)
        broadcastRuntimeState(false)
    }

    private fun isCaptureEnabled(): Boolean {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_SERVICE_ENABLED, false)
    }

    private fun currentIntervalMillis(): Long {
        val seconds = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getLong(KEY_SCREENSHOT_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS)
            .coerceAtLeast(MIN_INTERVAL_SECONDS)
        return TimeUnit.SECONDS.toMillis(seconds)
    }

    private fun saveRuntimeState(running: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SERVICE_RUNNING, running)
            .apply()
    }

    private fun broadcastRuntimeState(running: Boolean) {
        val statusIntent = android.content.Intent(ACTION_SERVICE_STATUS_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_RUNNING, running)
        }
        sendBroadcast(statusIntent)
    }

    private fun takeAccessibilityScreenshot() {
        if (isCaptureInFlight.getAndSet(true)) {
            Log.d(TAG, "Screenshot already in progress, skipping this interval")
            return
        }

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            captureExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        saveScreenshotResult(screenshot)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save accessibility screenshot", e)
                    } finally {
                        try {
                            screenshot.hardwareBuffer.close()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to close screenshot hardware buffer", e)
                        }
                        isCaptureInFlight.set(false)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Accessibility screenshot failed with code: $errorCode")
                    isCaptureInFlight.set(false)
                }
            }
        )
    }

    private fun saveScreenshotResult(screenshot: ScreenshotResult) {
        val hardwareBitmap = Bitmap.wrapHardwareBuffer(
            screenshot.hardwareBuffer,
            screenshot.colorSpace
        )

        if (hardwareBitmap == null) {
            Log.e(TAG, "Screenshot hardware buffer could not be converted to a bitmap")
            return
        }

        val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
        try {
            saveBitmapToFile(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap) {
        try {
            val calendar = Calendar.getInstance()
            val year = calendar.get(Calendar.YEAR).toString()
            val month = String.format(Locale.US, "%02d", calendar.get(Calendar.MONTH) + 1)
            val day = String.format(Locale.US, "%02d", calendar.get(Calendar.DAY_OF_MONTH))
            val hour = String.format(Locale.US, "%02d", calendar.get(Calendar.HOUR_OF_DAY))
            val minute = String.format(Locale.US, "%02d", calendar.get(Calendar.MINUTE))
            val second = String.format(Locale.US, "%02d", calendar.get(Calendar.SECOND))

            val dirPath = "$SCREENSHOT_ROOT_PATH/$year/$month/$day"
            val appPrefix = currentAppName
                ?: appSessionStore.readActiveSession()?.appName
                ?: currentPackageName
                ?: "unknown_app"
            val filenamePrefix = appPrefix
                .let { sanitizeAppNameForFilename(it) }
                .takeIf { it.isNotBlank() }
                ?: "unknown_app"
            val filename = "${filenamePrefix}_${hour}_${minute}_${second}.$SCREENSHOT_EXTENSION"
            val fullPath = "$dirPath/$filename"

            val dirFile = File(dirPath)
            if (!dirFile.exists()) {
                val dirsCreated = dirFile.mkdirs()
                Log.d(TAG, "Created screenshot directories: $dirsCreated")

                val nomediaFile = File(dirFile, ".nomedia")
                if (!nomediaFile.exists()) {
                    try {
                        nomediaFile.createNewFile()
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to create .nomedia file", e)
                    }
                }
            }

            val file = File(fullPath)
            var isNewScreenshot = true

            FileOutputStream(file).use { out ->
                val success = bitmap.compress(SCREENSHOT_COMPRESS_FORMAT, SCREENSHOT_QUALITY, out)
                if (!success) {
                    Log.e(TAG, "Failed to compress screenshot to file")
                    return
                }
            }

            previousScreenshotPath?.let { previousPath ->
                val previousFile = File(previousPath)
                if (previousFile.exists() && areFilesIdentical(previousFile, file)) {
                    file.delete()
                    isNewScreenshot = false
                    Log.d(TAG, "Deleted duplicate screenshot: $fullPath")
                }
            }

            if (isNewScreenshot) {
                previousScreenshotPath = fullPath
                val count = screenshotCount.incrementAndGet()
                Log.d(TAG, "Screenshot saved to $fullPath, total: $count")
                cleanupOldScreenshotsIfNeeded()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save screenshot", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while saving screenshot", e)
        }
    }

    private fun recordForegroundPackageChange(packageName: String, openedAt: Long) {
        val appName = appNameForPackage(packageName)
        appSessionStore.startSession(
            packageName = packageName,
            appName = appName,
            openedAt = openedAt
        )
        currentPackageName = packageName
        currentAppName = appName
        Log.d(TAG, "Foreground app changed: $appName ($packageName)")
    }

    private fun closeCurrentSession(closedAt: Long) {
        appSessionStore.closeActiveSession(closedAt)
        currentPackageName = null
        currentAppName = null
    }

    private fun restoreActiveSession() {
        val activeSession = appSessionStore.readActiveSession() ?: return
        currentPackageName = activeSession.packageName
        currentAppName = activeSession.appName
    }

    private fun registerScreenStateReceiver() {
        if (isScreenReceiverRegistered) {
            return
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        isScreenReceiverRegistered = true
    }

    private fun unregisterScreenStateReceiver() {
        if (!isScreenReceiverRegistered) {
            return
        }

        runCatching { unregisterReceiver(screenStateReceiver) }
        isScreenReceiverRegistered = false
    }

    private fun isForegroundChangeEvent(event: AccessibilityEvent): Boolean {
        return event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
    }

    private fun shouldIgnoreForegroundPackage(packageName: String): Boolean {
        return packageName == this.packageName ||
            packageName == "android" ||
            packageName == "com.android.systemui"
    }

    private fun appNameForPackage(packageName: String): String {
        return try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString().ifBlank { packageName }
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private fun sanitizeAppNameForFilename(appName: String): String {
        return appName
            .trim()
            .replace(Regex("""\s+"""), "_")
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .trim('_')
    }

    private fun areFilesIdentical(file1: File, file2: File): Boolean {
        if (!file1.exists() || !file2.exists() || file1.length() != file2.length()) {
            return false
        }

        return try {
            file1.inputStream().use { is1 ->
                file2.inputStream().use { is2 ->
                    val buf1 = ByteArray(8192)
                    val buf2 = ByteArray(8192)
                    var identical = true
                    var done = false

                    while (!done && identical) {
                        val read1 = is1.read(buf1)
                        val read2 = is2.read(buf2)

                        if (read1 != read2) {
                            identical = false
                        } else if (read1 <= 0) {
                            done = true
                        } else {
                            for (index in 0 until read1) {
                                if (buf1[index] != buf2[index]) {
                                    identical = false
                                    break
                                }
                            }
                        }
                    }

                    identical
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error comparing screenshot files", e)
            false
        }
    }

    private fun cleanupOldScreenshotsIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastAutoDeleteCleanupMillis < AUTO_DELETE_CLEANUP_INTERVAL_MILLIS) {
            return
        }

        lastAutoDeleteCleanupMillis = now
        val retentionDays = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_AUTO_DELETE_RETENTION_DAYS, DEFAULT_AUTO_DELETE_RETENTION_DAYS)
            .coerceIn(1, 365)

        try {
            deleteOldScreenshotImages(retentionDays)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean up old screenshots", e)
        }
    }

    private fun deleteOldScreenshotImages(retentionDays: Int) {
        val root = File(SCREENSHOT_ROOT_PATH)
        if (!root.exists() || !root.isDirectory) {
            return
        }

        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
        root.walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.extension.lowercase(Locale.US) in SCREENSHOT_IMAGE_EXTENSIONS &&
                    file.lastModified() < cutoff
            }
            .forEach { file ->
                if (file.delete()) {
                    Log.d(TAG, "Deleted old screenshot: ${file.absolutePath}")
                }
            }

        root.walkBottomUp()
            .filter { file -> file.isDirectory && file != root && file.listFiles()?.isEmpty() == true }
            .forEach { directory ->
                directory.delete()
            }
    }
}
