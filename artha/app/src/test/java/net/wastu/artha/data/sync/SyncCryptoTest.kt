package net.wastu.artha.data.sync

import kotlin.experimental.xor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncCryptoTest {

    @Test
    fun encryptAndDecryptRoundTripMatchesOriginalPayload() {
        val original = """{"transactions":[{"id":"tx-123","amount":7650000}],"knowledge":"test"}""".toByteArray(Charsets.UTF_8)
        val password = "MySecretPassphrase2026!"

        val encrypted = SyncCrypto.encrypt(original, password)
        val decrypted = SyncCrypto.decrypt(encrypted, password)

        assertArrayEquals(original, decrypted)
        assertEquals(String(original, Charsets.UTF_8), String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun wrongPasswordThrowsWrongSyncPasswordException() {
        val original = "Sensitive Ledger Content".toByteArray(Charsets.UTF_8)
        val password = "CorrectPassword123"
        val wrongPassword = "WrongPassword456"

        val encrypted = SyncCrypto.encrypt(original, password)
        assertThrows(WrongSyncPasswordException::class.java) {
            SyncCrypto.decrypt(encrypted, wrongPassword)
        }
    }

    @Test
    fun tamperedCiphertextFailsGcmTagVerification() {
        val original = "Financial Data".toByteArray(Charsets.UTF_8)
        val password = "Passphrase"

        val encrypted = SyncCrypto.encrypt(original, password)
        // Tamper with the last byte of ciphertext/tag
        encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1] xor 0x01.toByte())

        assertThrows(WrongSyncPasswordException::class.java) {
            SyncCrypto.decrypt(encrypted, password)
        }
    }

    @Test
    fun tamperedHeaderFailsAadVerification() {
        val original = "Confidential Transactions".toByteArray(Charsets.UTF_8)
        val password = "Passphrase"

        val encrypted = SyncCrypto.encrypt(original, password)
        // Tamper with a salt byte in the AAD header
        encrypted[10] = (encrypted[10] xor 0xFF.toByte())

        assertThrows(WrongSyncPasswordException::class.java) {
            SyncCrypto.decrypt(encrypted, password)
        }
    }

    @Test
    fun truncatedBytesThrowsCorruptedSyncPayloadException() {
        val truncated = ByteArray(20) { 0 }
        assertThrows(CorruptedSyncPayloadException::class.java) {
            SyncCrypto.decrypt(truncated, "Password")
        }
    }

    @Test
    fun invalidMagicBytesThrowsCorruptedSyncPayloadException() {
        val invalidMagic = "WRONG1".toByteArray(Charsets.US_ASCII) + ByteArray(50) { 0 }
        assertThrows(CorruptedSyncPayloadException::class.java) {
            SyncCrypto.decrypt(invalidMagic, "Password")
        }
    }
}
