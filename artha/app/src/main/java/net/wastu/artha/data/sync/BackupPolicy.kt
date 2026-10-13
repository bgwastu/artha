package net.wastu.artha.data.sync

import kotlinx.coroutines.CancellationException
import net.wastu.artha.core.model.UnifiedTransaction

/** Backups never delete revisions or automatically restore cloud state. */
object BackupPolicy {
    fun readLatest(
        files: List<DriveBackupFile>,
        password: String,
        download: (String) -> ByteArray,
    ): SyncBundle? {
        if (files.isEmpty()) return null
        var failure: Exception? = null
        for (file in files) {
            try {
                return SyncBundleSerializer.fromJsonBytes(SyncCrypto.decrypt(download(file.id), password))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unauthorized: DriveUnauthorizedException) {
                throw unauthorized
            } catch (unsupported: UnsupportedSyncVersionException) {
                throw unsupported
            } catch (e: Exception) {
                failure = e
            }
        }
        throw CorruptedSyncPayloadException(
            "No readable backup found. Check the password and connection; existing backups have been retained.",
            failure,
        )
    }

    fun requireCompleteLedger(local: List<UnifiedTransaction>, previous: SyncBundle?) {
        if (previous != null && SyncMerger.findMissingTransactionsToAppend(local, previous.transactions).isNotEmpty()) {
            throw IllegalStateException("The backup contains history missing from this phone. Restore it before creating a new backup.")
        }
    }

    fun sameContent(local: SyncBundle, remote: SyncBundle): Boolean =
        canonicalBundle(local) == canonicalBundle(remote)

    private fun canonicalBundle(bundle: SyncBundle): SyncBundle = bundle.copy(
            exportedAtEpochMillis = 0,
            analysis = bundle.analysis.copy(aiConfig = bundle.analysis.aiConfig.copy(apiKey = "")),
            transactions = bundle.transactions.sortedWith(compareBy({ it.provider.name }, { it.providerTransactionId })),
    )
}
