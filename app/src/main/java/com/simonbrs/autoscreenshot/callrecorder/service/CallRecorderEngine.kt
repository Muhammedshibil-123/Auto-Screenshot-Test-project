package com.simonbrs.autoscreenshot.callrecorder.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Environment
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import com.simonbrs.autoscreenshot.callrecorder.data.CallDirection
import com.simonbrs.autoscreenshot.callrecorder.data.RecorderAudioSource
import com.simonbrs.autoscreenshot.callrecorder.data.RecorderPrefs
import com.simonbrs.autoscreenshot.callrecorder.data.RecordingRepository
import com.simonbrs.autoscreenshot.security.MediaCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the single MediaRecorder used for call recording. One call is
 * recorded at a time; start/stop are safe to call more than once.
 */
object CallRecorderEngine {
    private const val TAG = "CallRecorderEngine"
    private const val CALL_LOG_SETTLE_MILLIS = 2_000L
    private const val CALL_LOG_MATCH_WINDOW_MILLIS = 2L * 60L * 1000L
    private const val PENDING_SEPARATOR = "__"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var recorder: MediaRecorder? = null
    private var tempFile: File? = null
    private var startedAt = 0L
    private var guessedDirection = CallDirection.Outgoing

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** Bumped each time a recording is saved or removed so lists can refresh. */
    private val _recordingsVersion = MutableStateFlow(0)
    val recordingsVersion: StateFlow<Int> = _recordingsVersion.asStateFlow()

    fun notifyRecordingsChanged() {
        _recordingsVersion.value += 1
    }

    @Synchronized
    fun start(context: Context, direction: CallDirection): Boolean {
        if (recorder != null) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted, skipping recording")
            return false
        }
        if (!Environment.isExternalStorageManager()) {
            Log.w(TAG, "All files access not granted, skipping recording")
            return false
        }

        // Record into app-private storage; only the encrypted copy goes to the shared folder.
        val privateDir = File(context.applicationContext.noBackupFilesDir, "recording").apply { mkdirs() }
        val file = File(privateDir, "rec_${System.currentTimeMillis()}.${RecordingRepository.EXTENSION}")
        for (source in audioSourcesFor(RecorderPrefs.audioSource(context))) {
            val candidate = MediaRecorder(context)
            try {
                candidate.setAudioSource(source)
                candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                candidate.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                candidate.setAudioEncodingBitRate(64_000)
                candidate.setAudioSamplingRate(44_100)
                candidate.setAudioChannels(1)
                candidate.setOutputFile(file.absolutePath)
                candidate.prepare()
                candidate.start()

                recorder = candidate
                tempFile = file
                startedAt = System.currentTimeMillis()
                guessedDirection = direction
                _isRecording.value = true
                Log.i(TAG, "Recording started with audio source $source")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Audio source $source failed", e)
                candidate.release()
                file.delete()
            }
        }
        return false
    }

    @Synchronized
    fun stop(context: Context) {
        val active = recorder ?: return
        val file = tempFile
        val callStartedAt = startedAt
        val direction = guessedDirection
        val durationSeconds = (System.currentTimeMillis() - callStartedAt) / 1000L

        recorder = null
        tempFile = null
        _isRecording.value = false

        val stoppedCleanly = try {
            active.stop()
            true
        } catch (e: RuntimeException) {
            // Thrown when stop() is called right after start() with no audio data.
            Log.w(TAG, "Recorder stop failed", e)
            false
        } finally {
            active.release()
        }

        if (file == null) return
        if (!stoppedCleanly) {
            file.delete()
            return
        }

        val appContext = context.applicationContext
        scope.launch {
            finalizeRecording(appContext, file, callStartedAt, durationSeconds, direction)
        }
    }

    private suspend fun finalizeRecording(
        context: Context,
        tempFile: File,
        callStartedAt: Long,
        durationSeconds: Long,
        guessedDirection: CallDirection
    ) {
        // The dialer writes the call log entry shortly after the call ends.
        delay(CALL_LOG_SETTLE_MILLIS)
        val logEntry = latestCallLogEntry(context, callStartedAt)
        val number = RecordingRepository.sanitizeNumber(logEntry?.number)
        val direction = logEntry?.direction ?: guessedDirection

        if (RecorderPrefs.onlyUnknown(context) &&
            RecordingRepository.lookupContactName(context, number) != null
        ) {
            tempFile.delete()
            return
        }

        val target = RecordingRepository.buildFinalFile(direction, number, callStartedAt, durationSeconds)
        try {
            MediaCrypto.encryptFile(tempFile, target)
            tempFile.delete()
        } catch (e: Exception) {
            // Usually the vault is locked (reinstalled, password not entered yet).
            // Keep the recording privately and encrypt it after unlocking.
            Log.w(TAG, "Encrypting recording failed, keeping it pending", e)
            target.delete()
            val pendingName = "${target.parentFile?.name}$PENDING_SEPARATOR${target.name}"
            tempFile.renameTo(File(pendingDir(context), pendingName))
        }
        flushPendingNow(context)

        RecordingRepository.deleteOlderThan(RecorderPrefs.retentionDays(context))
        notifyRecordingsChanged()
    }

    private fun pendingDir(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, "recording_pending").apply { mkdirs() }

    /** Encrypts recordings that were kept aside while the vault was locked. */
    fun flushPending(context: Context) {
        val appContext = context.applicationContext
        scope.launch { flushPendingNow(appContext) }
    }

    private fun flushPendingNow(context: Context) {
        val pending = pendingDir(context).listFiles().orEmpty()
        if (pending.isEmpty()) return
        pending.forEach { file ->
            val dayFolder = file.name.substringBefore(PENDING_SEPARATOR)
            val name = file.name.substringAfter(PENDING_SEPARATOR)
            val target = File(File(RecordingRepository.root, dayFolder).apply { mkdirs() }, name)
            try {
                MediaCrypto.encryptFile(file, target)
                file.delete()
            } catch (e: Exception) {
                target.delete()
                return // still locked; try again later
            }
        }
        notifyRecordingsChanged()
    }

    private data class CallLogEntry(val number: String?, val direction: CallDirection?)

    private fun latestCallLogEntry(context: Context, callStartedAt: Long): CallLogEntry? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf((callStartedAt - CALL_LOG_MATCH_WINDOW_MILLIS).toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val direction = when (cursor.getInt(1)) {
                    CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> CallDirection.Incoming
                    CallLog.Calls.OUTGOING_TYPE -> CallDirection.Outgoing
                    else -> null
                }
                CallLogEntry(number = cursor.getString(0), direction = direction)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Call log query failed", e)
            null
        }
    }

    private fun audioSourcesFor(source: RecorderAudioSource): List<Int> = when (source) {
        RecorderAudioSource.Auto -> listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        RecorderAudioSource.Microphone -> listOf(MediaRecorder.AudioSource.MIC)
        RecorderAudioSource.VoiceCommunication -> listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
    }
}
