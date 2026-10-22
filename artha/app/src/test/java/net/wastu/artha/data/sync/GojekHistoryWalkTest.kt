package net.wastu.artha.data.sync

import net.wastu.artha.data.sync.GojekHistoryWalk
import org.junit.Assert.assertEquals
import java.time.Instant
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val SAMPLE = GojekHistoryWalkTest::class.java
    .getResourceAsStream("/uiautomator_sample.xml")!!.readBytes().decodeToString()

class GojekHistoryWalkTest {

    @Test fun theRealDumpParses() {
        val nodes = GojekHistoryWalk.NODE_REGEX.findAll(SAMPLE).toList()
        assertTrue("parsed=${nodes.size}", nodes.size > 100)
        assertTrue(nodes.any { it.groupValues[1] == "Riwayat transaksi" })
    }

    @Test fun parsesIndonesianDateHeadersFromTheHistoryList() {
        val expected = java.time.LocalDate.of(2026, 7, 24)
            .atStartOfDay(java.time.ZoneId.of("Asia/Jakarta")).toInstant()
        assertEquals(expected.toEpochMilli(), GojekHistoryWalk.dateMillisFromText("Jumat, 24 Jul 2026"))
        assertEquals(
            java.time.LocalDate.of(2025, 12, 3).atStartOfDay(java.time.ZoneId.of("Asia/Jakarta")).toInstant().toEpochMilli(),
            GojekHistoryWalk.dateMillisFromText("Rabu, 03 Des 2025"),
        )
    }

    @Test fun agtAndAguBothMeanAugust() {
        for (month in listOf("Agu", "Agt")) {
            val expected = java.time.LocalDate.of(2026, 8, 1)
                .atStartOfDay(java.time.ZoneId.of("Asia/Jakarta")).toInstant().toEpochMilli()
            assertEquals(expected, GojekHistoryWalk.dateMillisFromText("Sabtu, 1 $month 2026"))
        }
    }

    @Test fun nonDateTextsNeverParse() {
        assertNull(GojekHistoryWalk.dateMillisFromText("Alfamart -UBM Tower"))
        assertNull(GojekHistoryWalk.dateMillisFromText("Rp41.100"))
        assertNull(GojekHistoryWalk.dateMillisFromText("13 payment, synced 1 min ago"))
        assertNull(GojekHistoryWalk.dateMillisFromText(""))
    }

    @Test fun walkFloorKeepsOnlyGenuinelyDeeperHistory() {
        assertEquals(1_000L, GojekHistoryWalk.nextWalkFloor(stored = 1_000L, oldestSeen = 5_000L))
        assertEquals(500L, GojekHistoryWalk.nextWalkFloor(stored = 1_000L, oldestSeen = 500L))
        assertEquals(1_000L, GojekHistoryWalk.nextWalkFloor(stored = 1_000L, oldestSeen = 0L))
        assertEquals(1_000L, GojekHistoryWalk.nextWalkFloor(stored = 1_000L, oldestSeen = Long.MAX_VALUE))
    }
}
