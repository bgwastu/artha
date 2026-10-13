package net.wastu.artha.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.wastu.artha.core.root.RootAccess
import net.wastu.artha.data.analysis.AiConfig
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiConfigSaveFeedbackTest {
    @Test fun unavailableSettingsReportsFailureInsteadOfClosingAsSaved() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val model = ArthaViewModel(RootAccess { false }, { error("Ledger must not be opened to save AI settings") })
            val completed = CompletableDeferred<String?>()
            model.updateAiConfig(AiConfig()) { completed.complete(it) }
            assertEquals("AI settings are unavailable", completed.await())
            assertEquals("AI settings are unavailable", model.settingsError.value)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
