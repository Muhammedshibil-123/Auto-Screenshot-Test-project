package com.simonbrs.autoscreenshot.callrecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.simonbrs.autoscreenshot.MainActivity
import com.simonbrs.autoscreenshot.R
import com.simonbrs.autoscreenshot.callrecorder.data.CallDirection

/**
 * Foreground service (type microphone) that keeps the process allowed to use
 * the microphone while a call is being recorded.
 */
class CallRecordingService : Service() {
    companion object {
        private const val TAG = "CallRecordingService"
        private const val CHANNEL_ID = "call_recorder"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_START = "com.simonbrs.autoscreenshot.callrecorder.START"
        private const val EXTRA_DIRECTION = "direction"

        fun start(context: Context, direction: CallDirection): Boolean {
            val intent = Intent(context, CallRecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DIRECTION, direction.name)
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Could not start foreground service", e)
                false
            }
        }

        fun stop(context: Context) {
            CallRecorderEngine.stop(context)
            context.stopService(Intent(context, CallRecordingService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            // Still try to record; some devices allow it from the accessibility process.
            Log.w(TAG, "startForeground failed", e)
        }

        val direction = intent.getStringExtra(EXTRA_DIRECTION)
            ?.let { name -> CallDirection.entries.firstOrNull { it.name == name } }
            ?: CallDirection.Outgoing
        if (!CallRecorderEngine.start(this, direction)) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        CallRecorderEngine.stop(this)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Call recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_recorder)
            .setContentTitle("Recording call")
            .setContentText("The call is being recorded")
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }
}
