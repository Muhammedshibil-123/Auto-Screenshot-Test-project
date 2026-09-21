package com.simonbrs.autoscreenshot.callrecorder.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class CallDirection { Incoming, Outgoing }

data class CallRecording(
    val file: File,
    val direction: CallDirection,
    val number: String?,
    val recordedAt: Long,
    val durationSeconds: Long,
    val sizeBytes: Long,
    val isStarred: Boolean,
    val contactName: String?
) {
    val displayName: String
        get() = contactName ?: number ?: "Unknown number"
}

data class RecordingStorageStats(
    val totalBytes: Long,
    val fileCount: Int,
    val starredCount: Int,
    val oldestRecordedAt: Long?
)

/**
 * File layout:
 *   /storage/emulated/0/CallRecordings/yyyy-MM-dd/IN_+911234567890_20260921_143015_125.m4a
 *   /storage/emulated/0/CallRecordings/Starred/...   (never auto-deleted)
 *   /storage/emulated/0/CallRecordings/.tmp/...      (recording in progress)
 */
object RecordingRepository {
    const val ROOT_PATH = "/storage/emulated/0/CallRecordings"
    const val STARRED_DIR = "Starred"
    const val TEMP_DIR = ".tmp"
    const val EXTENSION = "m4a"
    const val UNKNOWN_NUMBER_TOKEN = "Unknown"

    private val nameCache = ConcurrentHashMap<String, String>()
    private val missingCache: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val root: File get() = File(ROOT_PATH)

    fun tempDir(): File = File(root, TEMP_DIR).apply { mkdirs() }

    fun buildFinalFile(
        direction: CallDirection,
        number: String?,
        startedAt: Long,
        durationSeconds: Long
    ): File {
        val dayFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(startedAt))
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt))
        val prefix = if (direction == CallDirection.Incoming) "IN" else "OUT"
        val safeNumber = sanitizeNumber(number) ?: UNKNOWN_NUMBER_TOKEN
        val dir = File(root, dayFolder).apply { mkdirs() }
        // Keep music/media apps from listing the encrypted files.
        File(root, ".nomedia").takeIf { !it.exists() }?.let { runCatching { it.createNewFile() } }
        return File(dir, "${prefix}_${safeNumber}_${stamp}_${durationSeconds.coerceAtLeast(0L)}.$EXTENSION")
    }

    fun sanitizeNumber(number: String?): String? {
        val cleaned = number?.filter { it.isDigit() || it == '+' }.orEmpty()
        return cleaned.ifEmpty { null }
    }

    /** Clears cached contact names so newly saved contacts are picked up. */
    fun clearContactCache() {
        nameCache.clear()
        missingCache.clear()
    }

    /** Lists and parses recording files. Fast: no contact lookups. */
    fun loadRecordings(): List<CallRecording> {
        val base = root
        if (!base.exists() || !base.isDirectory) return emptyList()

        return base.walkTopDown()
            .onEnter { dir -> dir.name != TEMP_DIR }
            .filter { it.isRecordingFile() }
            .mapNotNull { file -> parse(file) }
            .sortedByDescending { it.recordedAt }
            .toList()
    }

    /** One contact lookup per unique number. Returns number -> contact name. */
    fun resolveNames(context: Context, numbers: Collection<String>): Map<String, String> =
        numbers.toSet().mapNotNull { number ->
            lookupContactName(context, number)?.let { name -> number to name }
        }.toMap()

    fun storageStats(): RecordingStorageStats {
        val base = root
        if (!base.exists() || !base.isDirectory) {
            return RecordingStorageStats(0L, 0, 0, null)
        }
        var bytes = 0L
        var count = 0
        var starred = 0
        var oldest: Long? = null
        base.walkTopDown()
            .onEnter { dir -> dir.name != TEMP_DIR }
            .filter { it.isRecordingFile() }
            .forEach { file ->
                bytes += file.length()
                count += 1
                if (file.parentFile?.name == STARRED_DIR) starred += 1
                val at = parse(file)?.recordedAt ?: file.lastModified()
                oldest = oldest?.let { minOf(it, at) } ?: at
            }
        return RecordingStorageStats(bytes, count, starred, oldest)
    }

    fun delete(recording: CallRecording): Boolean {
        val deleted = recording.file.delete()
        pruneEmptyFolders()
        return deleted
    }

    /** Moves a recording into / out of the Starred folder. Returns the new file. */
    fun toggleStar(recording: CallRecording): File? {
        val target = if (recording.isStarred) {
            val dayFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(recording.recordedAt))
            File(File(root, dayFolder).apply { mkdirs() }, recording.file.name)
        } else {
            File(File(root, STARRED_DIR).apply { mkdirs() }, recording.file.name)
        }
        val moved = recording.file.renameTo(target)
        pruneEmptyFolders()
        return if (moved) target else null
    }

    /** Deletes every recording except starred ones. */
    fun deleteAll() {
        val base = root
        if (!base.exists()) return
        base.walkTopDown()
            .onEnter { dir -> dir.name != STARRED_DIR && dir.name != TEMP_DIR }
            .filter { it.isRecordingFile() }
            .forEach { it.delete() }
        pruneEmptyFolders()
    }

    /** Deletes non-starred recordings older than [retentionDays]. */
    fun deleteOlderThan(retentionDays: Int) {
        val base = root
        if (!base.exists()) return
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
        base.walkTopDown()
            .onEnter { dir -> dir.name != STARRED_DIR && dir.name != TEMP_DIR }
            .filter { it.isRecordingFile() }
            .filter { file -> (parse(file)?.recordedAt ?: file.lastModified()) < cutoff }
            .forEach { it.delete() }
        pruneEmptyFolders()
    }

    fun lookupContactName(context: Context, number: String?): String? {
        if (number.isNullOrBlank()) return null
        nameCache[number]?.let { return it }
        if (number in missingCache) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val name = try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }

        if (name.isNullOrBlank()) {
            missingCache.add(number)
            return null
        }
        nameCache[number] = name
        return name
    }

    private fun parse(file: File): CallRecording? {
        // IN_+911234567890_20260921_143015_125
        val parts = file.nameWithoutExtension.split("_")
        if (parts.size < 4) return null
        val direction = when (parts[0]) {
            "IN" -> CallDirection.Incoming
            "OUT" -> CallDirection.Outgoing
            else -> return null
        }
        val number = parts[1].takeUnless { it == UNKNOWN_NUMBER_TOKEN }
        val recordedAt = try {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse("${parts[2]}_${parts[3]}")?.time
        } catch (_: Exception) {
            null
        } ?: file.lastModified()
        val duration = parts.getOrNull(4)?.toLongOrNull() ?: 0L

        return CallRecording(
            file = file,
            direction = direction,
            number = number,
            recordedAt = recordedAt,
            durationSeconds = duration,
            sizeBytes = file.length(),
            isStarred = file.parentFile?.name == STARRED_DIR,
            contactName = null
        )
    }

    private fun pruneEmptyFolders() {
        val base = root
        if (!base.exists()) return
        base.walkBottomUp()
            .filter { dir ->
                dir.isDirectory && dir != base && dir.name != STARRED_DIR && dir.name != TEMP_DIR &&
                    dir.listFiles()?.isEmpty() == true
            }
            .forEach { it.delete() }
    }

    private fun File.isRecordingFile(): Boolean =
        isFile && extension.lowercase(Locale.US) == EXTENSION
}
