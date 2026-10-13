package net.wastu.artha.core.model

import java.security.MessageDigest
import java.util.HexFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class DigestEncodingTest {
    @Test fun encodingKeepsExistingObservationAndSummaryDigestIdentity() {
        val allByteValues = ByteArray(256) { it.toByte() }
        assertEquals(HexFormat.of().formatHex(allByteValues), allByteValues.toLowerHex())
        val digest = MessageDigest.getInstance("SHA-256").digest("Fictional immutable payment".toByteArray())
        assertEquals(digest.joinToString("") { "%02x".format(it) }, digest.toLowerHex())
        assertEquals("", byteArrayOf().toLowerHex())
    }
}
