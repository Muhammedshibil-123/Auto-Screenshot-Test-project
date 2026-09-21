package com.simonbrs.autoscreenshot.security

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Shared "Delete / Off" password for Auto Screenshot and Call Recorder.
 * Only a salted SHA-256 hash is stored, never the password itself.
 */
object AppLock {
    private const val PREFS_NAME = "app_lock_prefs"
    private const val KEY_SALT = "salt"
    private const val KEY_HASH = "hash"
    const val MIN_LENGTH = 4

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasPassword(context: Context): Boolean = prefs(context).getString(KEY_HASH, null) != null

    fun verify(context: Context, password: String): Boolean {
        val prefs = prefs(context)
        val salt = prefs.getString(KEY_SALT, null) ?: return false
        val hash = prefs.getString(KEY_HASH, null) ?: return false
        return MessageDigest.isEqual(hash.toByteArray(), hash(salt, password).toByteArray())
    }

    fun setPassword(context: Context, password: String) {
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
        prefs(context).edit()
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, hash(salt, password))
            .apply()
    }

    fun clearPassword(context: Context) {
        prefs(context).edit().remove(KEY_SALT).remove(KEY_HASH).apply()
    }

    private fun hash(salt: String, password: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((salt + password).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
