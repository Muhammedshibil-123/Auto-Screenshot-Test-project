package com.simonbrs.autoscreenshot.callrecorder.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.Settings
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.simonbrs.autoscreenshot.callrecorder.data.CallDirection
import com.simonbrs.autoscreenshot.callrecorder.data.RecorderPrefs
import com.simonbrs.autoscreenshot.callrecorder.data.RecordingRepository
import java.util.concurrent.Executors

/**
 * Separate accessibility service for the call recorder. It is independent of
 * the Auto Screenshot accessibility service; it only watches call state and
 * starts/stops recording.
 */
class CallRecorderAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "CallRecorderA11y"

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, CallRecorderAccessibilityService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            return splitter.any { it.equals(expected, ignoreCase = true) }
        }
    }

    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private var telephonyManager: TelephonyManager? = null
    private var callStateCallback: TelephonyCallback? = null
    private var sawRinging = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerCallStateCallbackIfPossible()
        callbackExecutor.execute {
            if (Environment.isExternalStorageManager()) {
                RecordingRepository.deleteOlderThan(RecorderPrefs.retentionDays(this))
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Permissions may be granted after the service connected.
        if (callStateCallback == null) {
            registerCallStateCallbackIfPossible()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        unregisterCallStateCallback()
        CallRecordingService.stop(this)
        callbackExecutor.shutdown()
        super.onDestroy()
    }

    private fun registerCallStateCallbackIfPossible() {
        if (callStateCallback != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = getSystemService(TelephonyManager::class.java) ?: return
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                ContextCompat.getMainExecutor(this@CallRecorderAccessibilityService).execute {
                    handleCallState(state)
                }
            }
        }
        try {
            manager.registerTelephonyCallback(callbackExecutor, callback)
            telephonyManager = manager
            callStateCallback = callback
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not register call state callback", e)
        }
    }

    private fun unregisterCallStateCallback() {
        val callback = callStateCallback ?: return
        telephonyManager?.unregisterTelephonyCallback(callback)
        callStateCallback = null
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> sawRinging = true

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (CallRecorderEngine.isRecording.value || !RecorderPrefs.isEnabled(this)) return
                val direction = if (sawRinging) CallDirection.Incoming else CallDirection.Outgoing
                val allowed = when (direction) {
                    CallDirection.Incoming -> RecorderPrefs.recordIncoming(this)
                    CallDirection.Outgoing -> RecorderPrefs.recordOutgoing(this)
                }
                if (!allowed) return
                if (!CallRecordingService.start(this, direction)) {
                    // Foreground service not allowed; record from this service directly.
                    CallRecorderEngine.start(this, direction)
                }
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                sawRinging = false
                CallRecordingService.stop(this)
            }
        }
    }
}
