package com.simonbrs.autoscreenshot.security

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts screenshots and call recordings at rest with the master key from
 * [MediaVault] (AES-256-GCM). The original file name and extension are kept.
 *
 * File format:
 *   "ZSEC" + version(1) [+ keyId(8) for version 2]
 *   repeated chunks: flag(1: 0 = more, 1 = last) + length(4) + iv(12) + ciphertext
 * Each chunk has its index and flag as associated data, so chunks cannot be
 * reordered, dropped or truncated without detection.
 *
 * Version 1 files (the first encrypted release) used the Keystore key directly.
 * Files written before encryption was added have no header and are read as-is.
 */
object MediaCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val KEY_ID_BYTES = 8
    private const val CHUNK_BYTES = 256 * 1024
    private val MAGIC = byteArrayOf('Z'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte())
    private const val VERSION_KEYSTORE: Byte = 1
    private const val VERSION_MASTER_KEY: Byte = 2

    /** True when the file starts with the encryption header. */
    fun isEncrypted(file: File): Boolean {
        if (!file.isFile || file.length() < MAGIC.size) return false
        return try {
            FileInputStream(file).use { input ->
                val head = ByteArray(MAGIC.size)
                input.read(head) == MAGIC.size && head.contentEquals(MAGIC)
            }
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Opens a stream that writes an encrypted file. Close it to finish the file.
     * Throws [IOException] while the vault is locked (after a reinstall, before
     * the password is entered).
     */
    fun encryptingOutputStream(file: File): OutputStream {
        val key = MediaVault.masterKey() ?: throw IOException("Media vault is locked")
        return EncryptingOutputStream(FileOutputStream(file), key)
    }

    /** Encrypts [source] (plain) into [target]. */
    fun encryptFile(source: File, target: File) {
        FileInputStream(source).use { input ->
            encryptingOutputStream(target).use { output -> input.copyTo(output, CHUNK_BYTES) }
        }
    }

    /** Opens a file for reading, decrypting it if needed. Plain (older) files are returned as-is. */
    fun openInputStream(file: File): InputStream =
        if (isEncrypted(file)) DecryptingInputStream(FileInputStream(file)) else FileInputStream(file)

    fun readBytes(file: File): ByteArray = openInputStream(file).use { it.readBytes() }

    /** Writes the plain contents of [source] to [target]. */
    fun decryptToFile(source: File, target: File) {
        openInputStream(source).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output, CHUNK_BYTES) }
        }
    }

    private fun aad(index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(9).putLong(index).put(if (last) 1 else 0).array()

    private class EncryptingOutputStream(target: OutputStream, private val key: SecretKey) : OutputStream() {
        private val out = DataOutputStream(target.buffered())
        private val buffer = ByteArrayOutputStream(CHUNK_BYTES)
        private var index = 0L
        private var closed = false

        init {
            out.write(MAGIC)
            out.writeByte(VERSION_MASTER_KEY.toInt())
            out.write(MediaVault.keyId(key))
        }

        override fun write(b: Int) {
            buffer.write(b)
            flushFullChunks()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            buffer.write(b, off, len)
            flushFullChunks()
        }

        /** Keeps the last chunk buffered so it can be marked as final on close. */
        private fun flushFullChunks() {
            while (buffer.size() > CHUNK_BYTES) {
                val all = buffer.toByteArray()
                writeChunk(all.copyOfRange(0, CHUNK_BYTES), last = false)
                buffer.reset()
                buffer.write(all, CHUNK_BYTES, all.size - CHUNK_BYTES)
            }
        }

        private fun writeChunk(plain: ByteArray, last: Boolean) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad(index, last))
            val encrypted = cipher.doFinal(plain)
            out.writeByte(if (last) 1 else 0)
            out.writeInt(encrypted.size)
            out.write(cipher.iv)
            out.write(encrypted)
            index += 1
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                writeChunk(buffer.toByteArray(), last = true)
            } finally {
                out.close()
            }
        }
    }

    private class DecryptingInputStream(source: InputStream) : InputStream() {
        private val input = DataInputStream(source.buffered())
        private val key: SecretKey
        private var current = ByteArray(0)
        private var position = 0
        private var index = 0L
        private var finished = false

        init {
            val head = ByteArray(MAGIC.size + 1)
            input.readFully(head)
            if (!head.copyOf(MAGIC.size).contentEquals(MAGIC)) throw IOException("Not an encrypted file")
            key = when (head[MAGIC.size]) {
                VERSION_KEYSTORE -> MediaVault.legacyKeystoreKey()
                    ?: throw IOException("Key for this file no longer exists")

                VERSION_MASTER_KEY -> {
                    val fileKeyId = ByteArray(KEY_ID_BYTES).also { input.readFully(it) }
                    val master = MediaVault.masterKey() ?: throw IOException("Media vault is locked")
                    if (!MediaVault.keyId(master).contentEquals(fileKeyId)) {
                        throw IOException("File was encrypted with a different key")
                    }
                    master
                }

                else -> throw IOException("Unsupported encrypted file")
            }
        }

        private fun nextChunk(): Boolean {
            if (finished) return false
            val flag = try {
                input.readUnsignedByte()
            } catch (_: EOFException) {
                throw IOException("Encrypted file is truncated")
            }
            val last = flag == 1
            val length = input.readInt()
            if (length < TAG_BITS / 8 || length > CHUNK_BYTES + TAG_BITS / 8) {
                throw IOException("Corrupt encrypted chunk")
            }
            val iv = ByteArray(IV_BYTES).also { input.readFully(it) }
            val encrypted = ByteArray(length).also { input.readFully(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(index, last))
            current = cipher.doFinal(encrypted)
            position = 0
            index += 1
            finished = last
            return true
        }

        override fun read(): Int {
            while (position >= current.size) {
                if (!nextChunk()) return -1
            }
            return current[position++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position >= current.size) {
                if (!nextChunk()) return -1
            }
            val count = minOf(len, current.size - position)
            System.arraycopy(current, position, b, off, count)
            position += count
            return count
        }

        override fun close() {
            input.close()
        }
    }
}
