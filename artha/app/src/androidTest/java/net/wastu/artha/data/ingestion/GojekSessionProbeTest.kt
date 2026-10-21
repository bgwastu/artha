package net.wastu.artha.data.ingestion

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.wastu.artha.ArthaApplication
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GojekSessionProbeTest {
    @Test
    fun logGojekHistoryProbe() = kotlinx.coroutines.runBlocking {
        val app = ApplicationProvider.getApplicationContext<ArthaApplication>()
        assertTrue("Root required", app.rootAccess.verify())
        val report = GojekOrderReader(RootFileReader(app)).diagnoseHistoryAccess()
        Log.i("GojekSessionProbe", report)
        assertTrue("Probe must produce a report", report.isNotBlank())
    }
}
