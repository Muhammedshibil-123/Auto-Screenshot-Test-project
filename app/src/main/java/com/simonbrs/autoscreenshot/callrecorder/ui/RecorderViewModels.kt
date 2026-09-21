package com.simonbrs.autoscreenshot.callrecorder.ui

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simonbrs.autoscreenshot.callrecorder.data.CallRecording
import com.simonbrs.autoscreenshot.callrecorder.data.RecorderPrefs
import com.simonbrs.autoscreenshot.callrecorder.data.RecordingRepository
import com.simonbrs.autoscreenshot.callrecorder.service.CallRecorderEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecordingsUiState(
    val isLoading: Boolean = true,
    val recordings: List<CallRecording> = emptyList(),
    val approvedNumbers: Set<String> = emptySet(),
    val approvedFiles: Set<String> = emptySet()
) {
    fun isApproved(recording: CallRecording): Boolean {
        val normalized = RecorderPrefs.normalizeNumber(recording.number)
        return if (normalized != null) {
            normalized in approvedNumbers
        } else {
            recording.file.name in approvedFiles
        }
    }
}

class RecordingsViewModel : ViewModel() {
    var state by mutableStateOf(RecordingsUiState())
        private set

    fun refresh(context: Context) {
        val appContext = context.applicationContext
        if (!Environment.isExternalStorageManager()) {
            state = RecordingsUiState(isLoading = false)
            return
        }
        viewModelScope.launch {
            if (state.recordings.isEmpty()) {
                state = state.copy(isLoading = true)
            }
            // Step 1: show the files right away, keeping any names already known.
            val knownNames = state.recordings
                .mapNotNull { r -> r.number?.let { n -> r.contactName?.let { n to it } } }
                .toMap()
            val parsed = withContext(Dispatchers.IO) { RecordingRepository.loadRecordings() }
            state = state.copy(
                isLoading = false,
                recordings = parsed.map { it.copy(contactName = it.number?.let(knownNames::get)) },
                approvedNumbers = RecorderPrefs.approvedNumbers(appContext),
                approvedFiles = RecorderPrefs.approvedFiles(appContext)
            )

            // Step 2: resolve contact names in the background.
            val names = withContext(Dispatchers.IO) {
                RecordingRepository.clearContactCache()
                RecordingRepository.resolveNames(appContext, parsed.mapNotNull { it.number })
            }
            state = state.copy(
                recordings = parsed.map { it.copy(contactName = it.number?.let(names::get)) }
            )
        }
    }

    fun approve(context: Context, recording: CallRecording) {
        val appContext = context.applicationContext
        RecorderPrefs.approve(appContext, recording)
        state = state.copy(
            approvedNumbers = RecorderPrefs.approvedNumbers(appContext),
            approvedFiles = RecorderPrefs.approvedFiles(appContext)
        )
    }

    fun delete(context: Context, recording: CallRecording) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { RecordingRepository.delete(recording) }
            CallRecorderEngine.notifyRecordingsChanged()
            refresh(context)
        }
    }

    fun toggleStar(context: Context, recording: CallRecording) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { RecordingRepository.toggleStar(recording) }
            CallRecorderEngine.notifyRecordingsChanged()
            refresh(context)
        }
    }
}

data class RecorderStorageUiState(
    val isLoading: Boolean = true,
    val deviceTotalBytes: Long = 0L,
    val deviceFreeBytes: Long = 0L,
    val recordingBytes: Long = 0L,
    val recordingCount: Int = 0,
    val starredCount: Int = 0,
    val averageBytesPerDay: Long = 0L,
    val retentionDays: Int = RecorderPrefs.DEFAULT_RETENTION_DAYS,
    val isDeleting: Boolean = false
)

class RecorderSettingsViewModel : ViewModel() {
    var state by mutableStateOf(RecorderStorageUiState())
        private set

    fun refresh(context: Context) {
        val appContext = context.applicationContext
        if (!Environment.isExternalStorageManager()) {
            state = RecorderStorageUiState(
                isLoading = false,
                retentionDays = RecorderPrefs.retentionDays(appContext)
            )
            return
        }
        viewModelScope.launch {
            state = state.copy(isLoading = true)
            state = loadStorage(appContext)
        }
    }

    fun updateRetentionDays(context: Context, days: Int) {
        val appContext = context.applicationContext
        RecorderPrefs.setRetentionDays(appContext, days)
        state = state.copy(retentionDays = RecorderPrefs.retentionDays(appContext))
        if (!Environment.isExternalStorageManager()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                RecordingRepository.deleteOlderThan(RecorderPrefs.retentionDays(appContext))
            }
            CallRecorderEngine.notifyRecordingsChanged()
            state = loadStorage(appContext)
        }
    }

    fun deleteAll(context: Context, onDone: () -> Unit) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            state = state.copy(isDeleting = true)
            withContext(Dispatchers.IO) { RecordingRepository.deleteAll() }
            CallRecorderEngine.notifyRecordingsChanged()
            state = loadStorage(appContext)
            onDone()
        }
    }

    private suspend fun loadStorage(context: Context): RecorderStorageUiState = withContext(Dispatchers.IO) {
        val statFs = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        val stats = RecordingRepository.storageStats()
        val daysCovered = stats.oldestRecordedAt?.let { oldest ->
            ((System.currentTimeMillis() - oldest) / DAY_MILLIS + 1).coerceAtLeast(1L)
        } ?: 1L

        RecorderStorageUiState(
            isLoading = false,
            deviceTotalBytes = statFs.blockCountLong * statFs.blockSizeLong,
            deviceFreeBytes = statFs.availableBlocksLong * statFs.blockSizeLong,
            recordingBytes = stats.totalBytes,
            recordingCount = stats.fileCount,
            starredCount = stats.starredCount,
            averageBytesPerDay = if (stats.fileCount > 0) stats.totalBytes / daysCovered else 0L,
            retentionDays = RecorderPrefs.retentionDays(context)
        )
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
