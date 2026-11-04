package net.wastu.artha.data.sync.bridge

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

object Bip340 {
    private val p = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val n = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val Gx = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val Gy = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)

    data class Point(val x: BigInteger, val y: BigInteger)

    private fun pointAdd(p1: Point?, p2: Point?): Point? {
        if (p1 == null) return p2
        if (p2 == null) return p1
        if (p1.x == p2.x && p1.y != p2.y) return null
        val m = if (p1.x == p2.x) {
            (THREE * p1.x.multiply(p1.x) * (TWO * p1.y).modInverse(p)).mod(p)
        } else {
            ((p2.y - p1.y) * (p2.x - p1.x).modInverse(p)).mod(p)
        }
        val x3 = (m.multiply(m) - p1.x - p2.x).mod(p)
        val y3 = (m * (p1.x - x3) - p1.y).mod(p)
        return Point(x3, y3)
    }

    private fun pointMul(k: BigInteger, point: Point): Point? {
        var r: Point? = null
        var p = point
        for (i in 0 until 256) {
            if (k.testBit(i)) {
                r = pointAdd(r, p)
            }
            p = pointAdd(p, p) ?: break
        }
        return r
    }

    private fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val tagHash = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray(Charsets.UTF_8))
        val md = MessageDigest.getInstance("SHA-256")
        md.update(tagHash)
        md.update(tagHash)
        md.update(msg)
        return md.digest()
    }

    fun pubkeyGen(seckey: ByteArray): ByteArray {
        val d0 = BigInteger(1, seckey)
        val pPoint = pointMul(d0, Point(Gx, Gy)) ?: error("Invalid point")
        return toBytes32(pPoint.x)
    }

    fun sign(msg: ByteArray, seckey: ByteArray, auxRand: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }): ByteArray {
        val d0 = BigInteger(1, seckey)
        require(d0 >= BigInteger.ONE && d0 < n) { "Invalid secret key" }
        var pPoint = pointMul(d0, Point(Gx, Gy)) ?: error("Invalid point")
        val d = if (pPoint.y.testBit(0)) n - d0 else d0
        pPoint = pointMul(d, Point(Gx, Gy)) ?: error("Invalid point")
        val px = toBytes32(pPoint.x)

        val t = d.xor(BigInteger(1, taggedHash("BIP0340/aux", auxRand)))
        val rand = taggedHash("BIP0340/nonce", toBytes32(t) + px + msg)
        val k0 = BigInteger(1, rand).mod(n)
        require(k0 != BigInteger.ZERO) { "k is zero" }
        val rPoint = pointMul(k0, Point(Gx, Gy)) ?: error("Invalid point")
        val k = if (rPoint.y.testBit(0)) n - k0 else k0
        val rx = toBytes32(rPoint.x)

        val e = BigInteger(1, taggedHash("BIP0340/challenge", rx + px + msg)).mod(n)
        val s = (k + e * d).mod(n)
        return rx + toBytes32(s)
    }

    private fun toBytes32(bi: BigInteger): ByteArray {
        val raw = bi.toByteArray()
        val result = ByteArray(32)
        if (raw.size >= 32) {
            System.arraycopy(raw, raw.size - 32, result, 0, 32)
        } else {
            System.arraycopy(raw, 0, result, 32 - raw.size, raw.size)
        }
        return result
    }
}
