package net.wastu.artha

import android.app.Application
import net.wastu.artha.core.root.LibsuRootAccess
import net.wastu.artha.data.ingestion.IngestionCoordinator
import net.wastu.artha.data.ledger.LedgerDatabase
import net.wastu.artha.data.ledger.RoomLedgerRepository

class ArthaApplication : Application() {
    val rootAccess by lazy { LibsuRootAccess(applicationContext) }

    private val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    // Stored history is readable independently of root; imports still require root.
    private val database by lazy { LedgerDatabase.create(applicationContext) }
    val ledger by lazy { RoomLedgerRepository(database.transactions()) }
    val spending by lazy { net.wastu.artha.data.analysis.SpendingRepository(ledger, analysisStore, applicationScope) }
    val providerPreferences by lazy { net.wastu.artha.data.providers.ProviderPreferences(applicationContext) }
    val analysisStore by lazy { net.wastu.artha.data.analysis.AnalysisStore(applicationContext) }
    val driveSyncManager by lazy { net.wastu.artha.data.sync.DriveSyncManager(applicationContext, { ledger }, analysisStore, rootAccess) }
    val ingestion by lazy {
        val files = net.wastu.artha.data.ingestion.RootFileReader(applicationContext)
        val reader = net.wastu.artha.data.ingestion.JagoTransactionReader(files)
        val adapters = listOf(
            net.wastu.artha.data.ingestion.JagoProviderAdapter(reader),
            net.wastu.artha.data.ingestion.ShopeeProviderAdapter(files),
            net.wastu.artha.data.ingestion.TokopediaProviderAdapter(files),
            net.wastu.artha.data.ingestion.GojekProviderAdapter(files),
            net.wastu.artha.data.ingestion.GrabProviderAdapter(files),
        )
        IngestionCoordinator(rootAccess, { ledger }, adapters, reader::invalidate, preferences = providerPreferences)
    }

}
