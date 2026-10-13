package net.wastu.artha.data.ingestion

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Jago's Hive type adapters, matching the workspace extraction script. */
internal class HiveReader(private val data: ByteArray) {
    private var position = 0
    private fun bytes(count: Int): ByteArray {
        require(count >= 0 && count <= data.size - position) { "Truncated Hive value" }
        return data.copyOfRange(position, position + count).also { position += count }
    }
    private fun byte() = bytes(1)[0].toInt() and 255
    private fun readLength(): Int = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).int.also {
        require(it in 0..1_000_000) { "Invalid Hive collection length" }
    }
    private fun double() = ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN).double
    private fun string() = bytes(readLength()).toString(Charsets.UTF_8)
    fun value(depth: Int = 0): Any? {
        require(depth < 64) { "Hive nesting limit" }
        return when (val type = byte()) {
            0 -> null
            1 -> double().toLong()
            2 -> double()
            3 -> byte() != 0
            4 -> string()
            5 -> bytes(readLength()).map { it.toInt() and 255 }
            6 -> List(readLength()) { double().toLong() }
            7 -> List(readLength()) { double() }
            8 -> List(readLength()) { byte() != 0 }
            9 -> List(readLength()) { string() }
            10 -> List(readLength()) { value(depth + 1) }
            11 -> buildMap<Any?, Any?> { repeat(readLength()) { put(value(depth + 1), value(depth + 1)) } }
            16 -> mapOf("millis" to double().toLong())
            17 -> bytes(byte()).toString(Charsets.UTF_8).toBigInteger()
            18 -> mapOf("millis" to double().toLong(), "isUtc" to (byte() != 0))
            in 32..255 -> buildMap<String, Any?> {
                put("_typeId", type)
                repeat(byte()) { put("f${byte()}", value(depth + 1)) }
            }
            else -> error("Unsupported Hive type $type")
        }
    }
}

internal object HiveBox {
    fun decrypt(encrypted: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 16 || key.size == 32)
        require(encrypted.size >= 32 && (encrypted.size - 16) % 16 == 0) { "Invalid AES value" }
        return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(encrypted.copyOfRange(0, 16)))
            doFinal(encrypted, 16, encrypted.size - 16)
        }
    }

    fun read(data: ByteArray, key: ByteArray): Map<String, Any?> {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val values = linkedMapOf<String, ByteArray>()
        val keyHash = java.security.MessageDigest.getInstance("SHA-256").digest(key)
        while (buffer.hasRemaining()) {
            val start = buffer.position()
            require(buffer.remaining() >= 4) { "Truncated Hive frame" }
            val length = buffer.int
            require(length >= 10 && length <= data.size - start) { "Invalid Hive frame length" }
            val checksum = java.util.zip.CRC32().apply {
                update(keyHash)
                update(data, start, length - 4)
            }.value
            val stored = buffer.getInt(start + length - 4).toLong() and 0xffffffffL
            require(checksum == stored) { "Hive integrity check failed; retry a stable snapshot." }
            val end = start + length - 4 // trailing Hive CRC
            val name = when (buffer.get().toInt() and 255) {
                0 -> { require(buffer.position() + 4 <= end); (buffer.int.toLong() and 0xffffffffL).toString() }
                1 -> {
                    val size = buffer.get().toInt() and 255
                    require(buffer.position() + size <= end)
                    ByteArray(size).also { buffer.get(it) }.toString(Charsets.ISO_8859_1)
                }
                else -> error("Invalid Hive key type")
            }
            val size = end - buffer.position()
            require(size >= 0)
            if (size == 0) values.remove(name) // deleted frame, last frame wins
            else {
                val encrypted = ByteArray(size).also { buffer.get(it) }
                values[name] = encrypted
            }
            buffer.position(start + length)
        }
        // Obsolete frames can use older adapters; decode only the final active frame.
        return values.mapValues { (_, encrypted) -> HiveReader(decrypt(encrypted, key)).value() }
    }

    fun parseKey(text: String): ByteArray {
        val values = text.trim().removePrefix("[").removeSuffix("]").split(',').map { it.trim().toInt() }
        require(values.size == 32 && values.all { it in 0..255 }) { "Invalid Jago box key" }
        return values.map(Int::toByte).toByteArray()
    }
}
