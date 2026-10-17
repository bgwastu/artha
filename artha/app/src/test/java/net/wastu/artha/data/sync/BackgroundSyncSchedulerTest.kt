package net.wastu.artha.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundSyncSchedulerTest {
    @Test
    fun backgroundJobIdIsConsistent() {
        assertEquals(20261017, BackgroundSyncScheduler.JOB_ID)
    }
}
