package com.simonbrs.autoscreenshot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.simonbrs.autoscreenshot.MainActivity
import com.simonbrs.autoscreenshot.service.ScreenshotService

/**
 * Restores the user's intent to keep screenshot capture active after a reboot.
 *
 * Android does not allow a reboot receiver to silently recreate a MediaProjection
 * token, so we bring the activity forward and let the normal permission flow show
 * the system screen-capture dialog again.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        Log.d(TAG, "Boot completed")

        val prefs: SharedPreferences = context.getSharedPreferences(
            ScreenshotService.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val shouldRestoreCapture = prefs.getBoolean(
            ScreenshotService.KEY_SERVICE_ENABLED,
            prefs.getBoolean(ScreenshotService.KEY_SERVICE_RUNNING, false)
        )

        if (shouldRestoreCapture) {
            Log.d(TAG, "Capture was enabled before shutdown, launching permission flow")

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_AUTO_START_SERVICE, true)
            }
            context.startActivity(launchIntent)
        } else {
            Log.d(TAG, "Capture was not enabled before shutdown")
        }
    }
}
