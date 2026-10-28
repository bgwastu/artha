package net.wastu.artha.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GojekFlashSyncTest {

    @Test fun cooldownOnlyAfterARealLaunch() {
        val now = 1_000_000L
        assertTrue(GojekFlashPolicy.cooldownActive("FLASHED", now - 1_000L, now))
        assertTrue(GojekFlashPolicy.cooldownActive("QUIET", now - 14 * 60_000L, now))
        assertFalse(GojekFlashPolicy.cooldownActive("FLASHED", now - 15 * 60_000L, now))
        assertFalse(GojekFlashPolicy.cooldownActive("SKIPPED_SCREEN_ON", now, now + 1_000L))
        assertFalse(GojekFlashPolicy.cooldownActive("ABORTED", now, now + 1_000L))
        assertFalse(GojekFlashPolicy.cooldownActive("BUSY", now, now + 1_000L))
        assertFalse(GojekFlashPolicy.cooldownActive(null, now, now + 1_000L))
        assertFalse(GojekFlashPolicy.cooldownActive("FLASHED", null, now))
    }

    @Test fun onlySettledLaunchesUpdateTheLastRunStamp() {
        assertTrue(GojekFlashPolicy.recordsAsLastRun(GojekFlashSync.Result.FLASHED))
        assertTrue(GojekFlashPolicy.recordsAsLastRun(GojekFlashSync.Result.QUIET))
        assertFalse(GojekFlashPolicy.recordsAsLastRun(GojekFlashSync.Result.ABORTED))
        assertFalse(GojekFlashPolicy.recordsAsLastRun(GojekFlashSync.Result.SKIPPED_SCREEN_ON))
        assertFalse(GojekFlashPolicy.recordsAsLastRun(GojekFlashSync.Result.SKIPPED_COOLDOWN))
        assertFalse(GojekFlashPolicy.recordsAsLastRun(GojekFlashSync.Result.BUSY))
    }
}
