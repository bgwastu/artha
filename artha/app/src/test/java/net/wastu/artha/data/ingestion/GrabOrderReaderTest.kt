package net.wastu.artha.data.ingestion

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class GrabOrderReaderTest {

    @Test
    fun parsePreferencesXmlExtractsSessionAndSalt() {
        val xml = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="sessionId">test_session_value</string>
                <string name="salt">test_salt_value</string>
                <string name="KEY_GENERATION_ID">uuid-1234</string>
                <string name="appsFlyerAppId">af-5678</string>
            </map>
        """.trimIndent()

        val parsed = GrabOrderReader.parsePreferencesXml(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

        assertEquals("test_session_value", parsed["sessionId"])
        assertEquals("test_salt_value", parsed["salt"])
        assertEquals("uuid-1234", parsed["KEY_GENERATION_ID"])
        assertEquals("af-5678", parsed["appsFlyerAppId"])
    }

    @Test
    fun decryptSessionCorrectlyDecryptsEncryptedPayload() {
        val installTime = 1790563142673L
        val keyGenId = "test-key-gen-id"
        val saltBytes = ByteArray(16) { 0x42.toByte() }
        val encoder = Base64.getEncoder()
        val saltB64 = encoder.encodeToString(saltBytes)

        val prefix = GRAB_CLIENT_SALT_BYTES.joinToString("") { "%02x".format(it) }
        val kdfMaterial = "$prefix|$installTime|$keyGenId"
        val keySpec = PBEKeySpec(kdfMaterial.toCharArray(), saltBytes, 2000, 384)
        val secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val derived = secretKeyFactory.generateSecret(keySpec).encoded

        val aesKey = SecretKeySpec(derived.copyOfRange(0, 16), "AES")
        val hmacKey = SecretKeySpec(derived.copyOfRange(16, 48), "HmacSHA256")

        val ivBytes = ByteArray(16) { 0x11.toByte() }
        val plaintext = "header.payload.signature"

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, aesKey, IvParameterSpec(ivBytes))
        }
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val mac = Mac.getInstance("HmacSHA256").apply {
            init(hmacKey)
            update(ivBytes)
            update(cipherBytes)
        }
        val macBytes = mac.doFinal()

        val ivB64 = encoder.encodeToString(ivBytes)
        val macB64 = encoder.encodeToString(macBytes)
        val cipherB64 = encoder.encodeToString(cipherBytes)

        val encryptedSession = "$ivB64:$macB64:$cipherB64"

        val decrypted = GrabOrderReader.decryptSession(
            sessionId = encryptedSession,
            salt = saltB64,
            installTime = installTime,
            keyGenId = keyGenId,
            appsFlyerId = null,
        )

        assertEquals(plaintext, decrypted)
    }
}
