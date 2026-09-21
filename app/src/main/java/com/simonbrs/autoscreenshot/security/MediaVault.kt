package com.simonbrs.autoscreenshot.security

import android.content.Context
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Holds the master key that encrypts screenshots and recordings.
 *
 * The master key is stored twice:
 *  1. Locked by the Android Keystore, inside the app's private no-backup folder.
 *     Used every day; no password needed. Lost when the app is uninstalled.
 *  2. Locked by the user's Delete/Off password, in hidden ".zoro_key" files inside
 *     the Screenshot and CallRecordings folders. These survive uninstalling, so after
 *     a reinstall the same password unlocks every file again.
 */
object MediaVault {
    private const val TAG = "MediaVault"
    private const val KEYSTORE_ALIAS = "zoro_media_key"
    private const val LOCAL_KEY_FILE = "media_master_key.bin"
    private const val RECOVERY_FILE_NAME = ".zoro_key"
    private const val PBKDF2_ITERATIONS = 120_000
    private val recoveryDirs = listOf(
        "/storage/emulated/0/Screenshot",
        "/storage/emulated/0/CallRecordings"
    )

    private lateinit var appContext: Context
    private val background = Executors.newSingleThreadExecutor()

    @Volatile
    private var cachedKey: SecretKey? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ---------------------------------------------------------------- state

    /** A recovery file exists but this install has no key yet: the password is needed. */
    fun isLocked(): Boolean = loadLocalKey() == null && readRecoveryJson() != null

    fun hasRecoveryCopy(): Boolean = readRecoveryJson() != null

    /**
     * Master key for encrypting and decrypting files, created on first use.
     * Returns null while locked (after a reinstall, before the password is entered)
     * or when storage access is missing (so a recovery file can't be checked).
     */
    @Synchronized
    fun masterKey(): SecretKey? {
        cachedKey?.let { return it }
        loadLocalKey()?.let {
            cachedKey = it
            return it
        }
        if (!Environment.isExternalStorageManager()) return null
        if (readRecoveryJson() != null) return null // locked: wait for the password

        val created = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        saveLocalKey(created)
        cachedKey = created
        return created
    }

    /** Short fingerprint written into each file so a wrong key is detected quickly. */
    fun keyId(key: SecretKey): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(key.encoded).copyOf(8)

    /** The Keystore key used by the first (version 1) encrypted files. */
    fun legacyKeystoreKey(): SecretKey? = try {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------- password

    /** Unlocks after a reinstall. Returns false if the password is wrong. */
    @Synchronized
    fun unlock(password: String): Boolean {
        val json = readRecoveryJson() ?: return false
        val key = unwrapWithPassword(json, password) ?: return false
        saveLocalKey(key)
        cachedKey = key
        return true
    }

    /** Writes (or rewrites) the password-locked recovery copy. Runs in the background. */
    fun saveRecoveryCopyAsync(password: String) {
        background.execute {
            try {
                val key = masterKey() ?: return@execute
                writeRecoveryJson(wrapWithPassword(key, password))
            } catch (e: Exception) {
                Log.e(TAG, "Saving recovery copy failed", e)
            }
        }
    }

    /** Creates the recovery copy the first time the correct password is entered anywhere. */
    fun saveRecoveryCopyIfMissingAsync(password: String) {
        if (hasRecoveryCopy()) return
        saveRecoveryCopyAsync(password)
    }

    /** Password removed: files can no longer be recovered after uninstalling. */
    fun deleteRecoveryCopies() {
        recoveryDirs.forEach { File(it, RECOVERY_FILE_NAME).delete() }
    }

    /**
     * Gives up on the old files: keeps the old recovery files aside as ".zoro_key.old"
     * and starts a new master key. Files encrypted with the old key stay unreadable.
     */
    @Synchronized
    fun startFresh() {
        recoveryDirs.forEach { dir ->
            val file = File(dir, RECOVERY_FILE_NAME)
            if (file.exists()) file.renameTo(File(dir, "$RECOVERY_FILE_NAME.old.${System.currentTimeMillis()}"))
        }
        cachedKey = null
        File(appContext.noBackupFilesDir, LOCAL_KEY_FILE).delete()
        masterKey()
    }

    // ------------------------------------------------------------ internals

    private fun wrapWithPassword(key: SecretKey, password: String): JSONObject {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, passwordKey(password, salt, PBKDF2_ITERATIONS))
        val wrapped = cipher.doFinal(key.encoded)
        return JSONObject()
            .put("v", 1)
            .put("salt", b64(salt))
            .put("iter", PBKDF2_ITERATIONS)
            .put("iv", b64(cipher.iv))
            .put("key", b64(wrapped))
    }

    private fun unwrapWithPassword(json: JSONObject, password: String): SecretKey? = try {
        val salt = unb64(json.getString("salt"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            passwordKey(password, salt, json.getInt("iter")),
            GCMParameterSpec(128, unb64(json.getString("iv")))
        )
        SecretKeySpec(cipher.doFinal(unb64(json.getString("key"))), "AES")
    } catch (_: Exception) {
        null // wrong password or damaged file
    }

    private fun passwordKey(password: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun readRecoveryJson(): JSONObject? {
        for (dir in recoveryDirs) {
            val file = File(dir, RECOVERY_FILE_NAME)
            val json = try {
                if (file.isFile) JSONObject(file.readText()) else null
            } catch (_: Exception) {
                null
            }
            if (json != null) return json
        }
        return null
    }

    private fun writeRecoveryJson(json: JSONObject) {
        val text = json.toString()
        recoveryDirs.forEach { path ->
            try {
                val dir = File(path).apply { mkdirs() }
                val tmp = File(dir, "$RECOVERY_FILE_NAME.tmp")
                tmp.writeText(text)
                tmp.renameTo(File(dir, RECOVERY_FILE_NAME))
            } catch (e: Exception) {
                Log.e(TAG, "Could not write recovery file in $path", e)
            }
        }
    }

    private fun loadLocalKey(): SecretKey? {
        val file = File(appContext.noBackupFilesDir, LOCAL_KEY_FILE)
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            val iv = bytes.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
            SecretKeySpec(cipher.doFinal(bytes, 12, bytes.size - 12), "AES")
        } catch (e: Exception) {
            Log.e(TAG, "Could not unlock local master key", e)
            null
        }
    }

    private fun saveLocalKey(key: SecretKey) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val encrypted = cipher.doFinal(key.encoded)
        File(appContext.noBackupFilesDir, LOCAL_KEY_FILE).writeBytes(cipher.iv + encrypted)
    }

    private fun keystoreKey(): SecretKey {
        legacyKeystoreKey()?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)
}
