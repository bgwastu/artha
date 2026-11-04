package net.wastu.artha.data.sync.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

class BridgeCryptoTest {

    @Test
    fun generatesUniqueKeysAndDerivedChannels() {
        val key1 = BridgeCrypto.generateKey()
        val key2 = BridgeCrypto.generateKey()
        assertEquals(32, key1.size)
        assertEquals(32, key2.size)
        assertNotEquals(key1.toList(), key2.toList())

        val ch1 = BridgeCrypto.deriveChannelId(key1)
        val ch2 = BridgeCrypto.deriveChannelId(key2)
        assertEquals(64, ch1.length)
        assertNotEquals(ch1, ch2)
    }

    @Test
    fun roundtripEncryptionDecryption() {
        val key = BridgeCrypto.generateKey()
        val plaintext = "Hello Artha Bridge Secure Nostr Transport!".toByteArray(Charsets.UTF_8)

        val encrypted = BridgeCrypto.encrypt(plaintext, key)
        val decrypted = BridgeCrypto.decrypt(encrypted, key)

        assertEquals("Hello Artha Bridge Secure Nostr Transport!", decrypted.toString(Charsets.UTF_8))
    }

    @Test
    fun tamperingCiphertextThrowsException() {
        val key = BridgeCrypto.generateKey()
        val plaintext = "Sensitive Financial Data".toByteArray(Charsets.UTF_8)
        val encrypted = BridgeCrypto.encrypt(plaintext, key)

        val tampered = encrypted.copy(ciphertext = encrypted.ciphertext.dropLast(4) + "AAAA")
        assertThrows(AEADBadTagException::class.java) {
            BridgeCrypto.decrypt(tampered, key)
        }
    }
}
