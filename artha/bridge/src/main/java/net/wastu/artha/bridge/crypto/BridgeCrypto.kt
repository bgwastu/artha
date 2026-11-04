package net.wastu.artha.bridge.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedMessage(
    val iv: String,
    val ciphertext: String,
)

object BridgeCrypto {
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12
    private const val KEY_LENGTH_BYTES = 32

    fun generateKey(): ByteArray {
        val key = ByteArray(KEY_LENGTH_BYTES)
        SecureRandom().nextBytes(key)
        return key
    }

    fun deriveChannelId(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun compress(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(data) }
        return baos.toByteArray()
    }

    fun decompress(data: ByteArray): ByteArray {
        return runCatching {
            GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
        }.getOrDefault(data)
    }

    fun encrypt(plaintext: ByteArray, key: ByteArray): EncryptedMessage {
        require(key.size == KEY_LENGTH_BYTES) { "Key must be 32 bytes for AES-256" }
        val iv = ByteArray(IV_LENGTH_BYTES)
        SecureRandom().nextBytes(iv)

        val compressed = compress(plaintext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val ciphertext = cipher.doFinal(compressed)
        return EncryptedMessage(
            iv = Base64.getEncoder().encodeToString(iv),
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
        )
    }

    fun decrypt(message: EncryptedMessage, key: ByteArray): ByteArray {
        require(key.size == KEY_LENGTH_BYTES) { "Key must be 32 bytes for AES-256" }
        val iv = Base64.getDecoder().decode(message.iv)
        val ciphertext = Base64.getDecoder().decode(message.ciphertext)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        val decrypted = cipher.doFinal(ciphertext)
        return decompress(decrypted)
    }
}
