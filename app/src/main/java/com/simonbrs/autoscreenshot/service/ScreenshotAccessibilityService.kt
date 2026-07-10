package com.simonbrs.autoscreenshot.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
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
        private val SCREENSHOT_COMPRESS_FORMAT = Bitmap.CompressFormat.WEBP_LOSSY

        const val DEFAULT_INTERVAL_SECONDS = 10L
        const val PREFS_NAME = "AutoScreenshotPrefs"
        const val KEY_SERVICE_ENABLED = "service_enabled"
        const val KEY_SERVICE_RUNNING = "service_running"
        const val KEY_SCREENSHOT_INTERVAL_SECONDS = "screenshot_interval_seconds"
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
        syncCaptureLoopFromPreferences()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Screenshots are driven by the timer loop, not by UI events.
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.d(TAG, "Accessibility service destroyed")
        stopCaptureLoop()
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

            val dirPath = "/storage/emulated/0/Screenshot/$year/$month/$day"
            val filename = "${hour}_${minute}_${second}.$SCREENSHOT_EXTENSION"
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
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save screenshot", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while saving screenshot", e)
        }
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
}
