package net.wastu.artha.data.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongSyncPasswordException(
    message: String = "Incorrect encryption password for the cloud backup.",
    cause: Throwable? = null,
) : Exception(message, cause)

class CorruptedSyncPayloadException(
    message: String = "Cloud backup file is corrupted or has an unsupported format.",
    cause: Throwable? = null,
) : Exception(message, cause)

object SyncCrypto {
    private val MAGIC = "ARTHA1".toByteArray(Charsets.US_ASCII) // 6 bytes
    private const val VERSION: Byte = 0x01
    private const val FLAG_GZIP: Byte = 0x01

    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val HEADER_SIZE = 6 + 1 + 1 + SALT_LENGTH + IV_LENGTH // 36 bytes
    private const val MIN_PAYLOAD_SIZE = HEADER_SIZE + GCM_TAG_BYTES // 52 bytes

    private const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_LENGTH_BITS = 256

    private val secureRandom = SecureRandom()

    fun encrypt(plainBytes: ByteArray, password: String): ByteArray {
        require(password.isNotBlank()) { "Encryption password must not be blank." }

        val compressed = gzipCompress(plainBytes)

        val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { secureRandom.nextBytes(it) }
        val secretKey = deriveKey(password.toCharArray(), salt)

        val header = ByteArray(HEADER_SIZE)
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.size)
        header[6] = VERSION
        header[7] = FLAG_GZIP
        System.arraycopy(salt, 0, header, 8, SALT_LENGTH)
        System.arraycopy(iv, 0, header, 8 + SALT_LENGTH, IV_LENGTH)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(header)
        val ciphertext = cipher.doFinal(compressed)

        val output = ByteArray(header.size + ciphertext.size)
        System.arraycopy(header, 0, output, 0, header.size)
        System.arraycopy(ciphertext, 0, output, header.size, ciphertext.size)
        return output
    }

    fun decrypt(encryptedBytes: ByteArray, password: String): ByteArray {
        require(password.isNotBlank()) { "Decryption password must not be blank." }
        if (encryptedBytes.size < MIN_PAYLOAD_SIZE) {
            throw CorruptedSyncPayloadException("Backup file is truncated (${encryptedBytes.size} bytes).")
        }

        for (i in MAGIC.indices) {
            if (encryptedBytes[i] != MAGIC[i]) {
                throw CorruptedSyncPayloadException("Invalid backup file header magic.")
            }
        }

        val version = encryptedBytes[6]
        if (version != VERSION) {
            throw CorruptedSyncPayloadException("Unsupported backup format version: $version.")
        }

        val flags = encryptedBytes[7]
        val salt = encryptedBytes.copyOfRange(8, 8 + SALT_LENGTH)
        val iv = encryptedBytes.copyOfRange(8 + SALT_LENGTH, HEADER_SIZE)
        val header = encryptedBytes.copyOfRange(0, HEADER_SIZE)
        val ciphertext = encryptedBytes.copyOfRange(HEADER_SIZE, encryptedBytes.size)

        val secretKey = deriveKey(password.toCharArray(), salt)
        val decryptedCompressed = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(header)
            cipher.doFinal(ciphertext)
        } catch (badTag: AEADBadTagException) {
            throw WrongSyncPasswordException(cause = badTag)
        } catch (e: Exception) {
            throw CorruptedSyncPayloadException("Failed to decrypt backup payload.", e)
        }

        return if ((flags.toInt() and FLAG_GZIP.toInt()) != 0) {
            try {
                gzipDecompress(decryptedCompressed)
            } catch (e: Exception) {
                throw CorruptedSyncPayloadException("Failed to decompress decrypted backup.", e)
            }
        } else {
            decryptedCompressed
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val keyBytes = factory.generateSecret(spec).encoded
            SecretKeySpec(keyBytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun gzipCompress(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gzip ->
            gzip.write(input)
        }
        return bos.toByteArray()
    }

    private fun gzipDecompress(compressed: ByteArray): ByteArray {
        ByteArrayInputStream(compressed).use { bis ->
            GZIPInputStream(bis).use { gzip ->
                return gzip.readBytes()
            }
        }
    }
}
