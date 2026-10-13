package net.wastu.artha.core.model

/** Stable lowercase digest encoding, including negative bytes; no per-byte Formatter. */
internal fun ByteArray.toLowerHex(): String {
    val alphabet = "0123456789abcdef"
    val chars = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xff
        chars[i * 2] = alphabet[value ushr 4]
        chars[i * 2 + 1] = alphabet[value and 15]
    }
    return String(chars)
}
