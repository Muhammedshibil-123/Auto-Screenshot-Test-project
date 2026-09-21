package com.simonbrs.autoscreenshot.callrecorder.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Call recorder settings. Kept in their own preferences file so they never
 * mix with the Auto Screenshot preferences.
 */
object RecorderPrefs {
    const val PREFS_NAME = "call_recorder_prefs"

    const val KEY_ENABLED = "recorder_enabled"
    const val KEY_RETENTION_DAYS = "recorder_retention_days"
    const val KEY_RECORD_INCOMING = "recorder_record_incoming"
    const val KEY_RECORD_OUTGOING = "recorder_record_outgoing"
    const val KEY_ONLY_UNKNOWN = "recorder_only_unknown"
    const val KEY_AUDIO_SOURCE = "recorder_audio_source"

    const val DEFAULT_RETENTION_DAYS = 30
    const val MIN_RETENTION_DAYS = 1
    const val MAX_RETENTION_DAYS = 365

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun retentionDays(context: Context): Int =
        prefs(context).getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
            .coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)

    fun setRetentionDays(context: Context, days: Int) {
        prefs(context).edit()
            .putInt(KEY_RETENTION_DAYS, days.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS))
            .apply()
    }

    fun recordIncoming(context: Context): Boolean = prefs(context).getBoolean(KEY_RECORD_INCOMING, true)

    fun recordOutgoing(context: Context): Boolean = prefs(context).getBoolean(KEY_RECORD_OUTGOING, true)

    fun onlyUnknown(context: Context): Boolean = prefs(context).getBoolean(KEY_ONLY_UNKNOWN, false)

    fun audioSource(context: Context): RecorderAudioSource {
        val stored = prefs(context).getString(KEY_AUDIO_SOURCE, null)
        return RecorderAudioSource.entries.firstOrNull { it.name == stored } ?: RecorderAudioSource.Auto
    }

    fun setBoolean(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    fun setAudioSource(context: Context, source: RecorderAudioSource) {
        prefs(context).edit().putString(KEY_AUDIO_SOURCE, source.name).apply()
    }
}

enum class RecorderAudioSource(val label: String, val description: String) {
    Auto("Auto", "Voice recognition source, falls back to microphone"),
    Microphone("Microphone", "Plain microphone input"),
    VoiceCommunication("Voice call tuned", "Echo-cancelled voice input")
}
