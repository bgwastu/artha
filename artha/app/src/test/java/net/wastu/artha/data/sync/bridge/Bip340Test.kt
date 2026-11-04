package net.wastu.artha.data.sync.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/** Official BIP-340 test vectors 0 and 1. */
class Bip340Test {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

    @Test
    fun vector0() {
        val sk = hex("0000000000000000000000000000000000000000000000000000000000000003")
        assertEquals("F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9", Bip340.pubkeyGen(sk).hex())
        val sig = Bip340.sign(ByteArray(32), sk, ByteArray(32))
        assertEquals(
            "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0",
            sig.hex(),
        )
    }

    @Test
    fun vector1() {
        val sk = hex("B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF")
        assertEquals("DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659", Bip340.pubkeyGen(sk).hex())
        val sig = Bip340.sign(
            hex("243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89"),
            sk,
            hex("0000000000000000000000000000000000000000000000000000000000000001"),
        )
        assertEquals(
            "6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE33418906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A",
            sig.hex(),
        )
    }
}
