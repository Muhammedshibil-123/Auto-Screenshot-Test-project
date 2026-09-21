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
    val recordings: List<CallRecording> = emptyList()
)

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
            val recordings = withContext(Dispatchers.IO) {
                RecordingRepository.clearContactCache()
                RecordingRepository.loadRecordings(appContext)
            }
            state = RecordingsUiState(isLoading = false, recordings = recordings)
        }
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
