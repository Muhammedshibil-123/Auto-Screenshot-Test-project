package com.simonbrs.autoscreenshot.data

import android.content.Context
import org.json.JSONObject
import java.io.File

data class AppSession(
    val id: Long,
    val packageName: String,
    val appName: String,
    val timeOpened: Long,
    val timeClosed: Long?
)

class AppSessionStore(context: Context) {
    private companion object {
        const val ACTIVE_SESSION_PREFS = "ActiveAppSessionPrefs"
        const val KEY_ACTIVE_PACKAGE = "active_package"
        const val KEY_ACTIVE_APP_NAME = "active_app_name"
        const val KEY_ACTIVE_OPENED_AT = "active_opened_at"

        val fileLock = Any()
    }

    private val appContext = context.applicationContext
    private val sessionsFile = File(appContext.filesDir, "app_sessions.jsonl")
    private val activeSessionPrefs = appContext.getSharedPreferences(ACTIVE_SESSION_PREFS, Context.MODE_PRIVATE)

    fun startSession(packageName: String, appName: String, openedAt: Long) {
        val activeSession = readActiveSession()
        if (activeSession?.packageName == packageName) {
            return
        }

        activeSession?.let { session ->
            append(session.copy(timeClosed = openedAt.coerceAtLeast(session.timeOpened)))
        }

        activeSessionPrefs.edit()
            .putString(KEY_ACTIVE_PACKAGE, packageName)
            .putString(KEY_ACTIVE_APP_NAME, appName)
            .putLong(KEY_ACTIVE_OPENED_AT, openedAt)
            .apply()
    }

    fun closeActiveSession(closedAt: Long) {
        val activeSession = readActiveSession() ?: return
        append(activeSession.copy(timeClosed = closedAt.coerceAtLeast(activeSession.timeOpened)))
        clearActiveSession()
    }

    fun readSessions(includeActiveSession: Boolean = true): List<AppSession> {
        val completedSessions = readCompletedSessions()
        val activeSession = readActiveSession().takeIf { includeActiveSession }
        return (completedSessions + listOfNotNull(activeSession)).sortedByDescending { it.timeOpened }
    }

    fun readActiveSession(): AppSession? {
        val packageName = activeSessionPrefs.getString(KEY_ACTIVE_PACKAGE, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val openedAt = activeSessionPrefs.getLong(KEY_ACTIVE_OPENED_AT, 0L).takeIf { it > 0L }
            ?: return null
        val appName = activeSessionPrefs.getString(KEY_ACTIVE_APP_NAME, packageName).orEmpty().ifBlank { packageName }

        return AppSession(
            id = openedAt,
            packageName = packageName,
            appName = appName,
            timeOpened = openedAt,
            timeClosed = null
        )
    }

    private fun append(session: AppSession) {
        synchronized(fileLock) {
            if (!sessionsFile.exists()) {
                sessionsFile.parentFile?.mkdirs()
                sessionsFile.createNewFile()
            }
            sessionsFile.appendText("${session.toJson()}\n")
        }
    }

    private fun readCompletedSessions(): List<AppSession> = synchronized(fileLock) {
        if (!sessionsFile.exists()) {
            return@synchronized emptyList()
        }

        sessionsFile.readLines()
            .mapNotNull { line ->
                runCatching {
                    val json = JSONObject(line)
                    AppSession(
                        id = json.optLong("id"),
                        packageName = json.getString("packageName"),
                        appName = json.optString("appName", json.getString("packageName")),
                        timeOpened = json.getLong("timeOpened"),
                        timeClosed = json.takeUnless { it.isNull("timeClosed") }?.getLong("timeClosed")
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.timeOpened }
    }

    private fun clearActiveSession() {
        activeSessionPrefs.edit()
            .remove(KEY_ACTIVE_PACKAGE)
            .remove(KEY_ACTIVE_APP_NAME)
            .remove(KEY_ACTIVE_OPENED_AT)
            .apply()
    }

    private fun AppSession.toJson(): String {
        return JSONObject()
            .put("id", id)
            .put("packageName", packageName)
            .put("appName", appName)
            .put("timeOpened", timeOpened)
            .put("timeClosed", timeClosed)
            .toString()
    }
}
