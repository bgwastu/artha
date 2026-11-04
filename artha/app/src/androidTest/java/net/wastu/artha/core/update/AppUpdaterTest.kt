package net.wastu.artha.core.update

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdaterTest {

    @Test
    fun verifiesInstalledVersion() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val updater = AppUpdater(context, { })
        assertTrue(updater.installedVersionCode > 0)
        assertNotNull(updater.installedVersionName)
    }

    @Test
    fun togglesAutoUpdatePreference() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val updater = AppUpdater(context, { })

        updater.setAutoUpdate(false)
        assertFalse(updater.autoUpdate.value)

        updater.setAutoUpdate(true)
        assertTrue(updater.autoUpdate.value)
    }

    @Test
    fun checksDueLogic() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val updater = AppUpdater(context, { })

        if (updater.lastCheckedAt == null) {
            assertTrue(updater.isCheckDue(60_000L))
        }
    }
}
