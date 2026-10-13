package net.wastu.artha.data.ingestion

import net.wastu.artha.core.model.Provider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HiveReaderTest {
    private val key = ByteArray(32) { it.toByte() }
    private fun integer(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun text(value: String) = byteArrayOf(4) + integer(value.toByteArray().size) + value.toByteArray()
    private fun encrypt(value: ByteArray): ByteArray {
        val iv = ByteArray(16) { (it + 10).toByte() }
        return iv + Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(value)
        }
    }
    private fun frame(name: String, value: ByteArray?) : ByteArray {
        val body = byteArrayOf(1, name.length.toByte()) + name.toByteArray() + (value?.let(::encrypt) ?: byteArrayOf())
        return integer(body.size + 8) + body + integer(0)
    }

    @Test fun decryptsAdapterFieldsAndLastFrameWinsIncludingDeletion() {
        val fields = byteArrayOf(34, 2, 4) + text("Main Pocket") + byteArrayOf(44) + text("IDR")
        val data = frame("pocket", fields) + frame("history", text("old")) + frame("history", text("new"))
        val result = HiveBox.read(data, key)
        assertEquals("Main Pocket", (result["pocket"] as Map<*, *>)["f4"])
        assertEquals("IDR", (result["pocket"] as Map<*, *>)["f44"])
        assertEquals("new", result["history"])
        assertNull(HiveBox.read(data + frame("history", null), key)["history"])
    }

    @Test fun parsesNestedTransactionListsAndDateTime() {
        val millis = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(123456789.0).array()
        val value = byteArrayOf(40, 1, 2, 10) + integer(1) + byteArrayOf(41, 2, 6) + text("transaction-id") + byteArrayOf(13, 18) + millis + byteArrayOf(1)
        val box = HiveBox.read(frame("history", value), key)
        val tx = (((box["history"] as Map<*, *>)["f2"] as List<*>)[0] as Map<*, *>)
        assertEquals("transaction-id", tx["f6"])
        assertEquals(123456789L, (tx["f13"] as Map<*, *>)["millis"])
    }

    @Test fun mapLengthDoesNotResolveToTheMapBuildersElementCount() {
        val map = byteArrayOf(11) + integer(1) + text("key") + text("value")
        val fields = byteArrayOf(34, 2, 14) + map + byteArrayOf(44) + text("IDR")
        val result = HiveBox.read(frame("pocket", fields), key)["pocket"] as Map<*, *>
        assertEquals(mapOf("key" to "value"), result["f14"])
        assertEquals("IDR", result["f44"])
    }

    @Test fun obsoleteAdapterFramesAreNotParsed() {
        assertEquals("current", HiveBox.read(frame("history", byteArrayOf(24)) + frame("history", text("current")), key)["history"])
    }

    @Test fun truncatedFramesAndWrongKeysFailRatherThanReturningEmptyHistory() {
        val data = frame("history", text("real value"))
        assertTrue(runCatching { HiveBox.read(data.copyOf(data.size - 1), key) }.isFailure)
        assertTrue(runCatching { HiveBox.read(data, ByteArray(32)) }.isFailure)
        assertTrue(runCatching { HiveReader(byteArrayOf(10) + integer(Int.MAX_VALUE)).value() }.isFailure)
        assertTrue(runCatching { HiveBox.parseKey("[1,2]") }.isFailure)
        assertTrue(runCatching { HiveBox.parseKey("[" + List(32) { "256" }.joinToString(",") + "]") }.isFailure)
    }
}
