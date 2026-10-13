package net.wastu.artha.data.sync

import kotlinx.coroutines.CancellationException
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AnalysisSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class BackupPolicyTest {
    private val tx = UnifiedTransaction(Provider.JAGO, "jago:1", Direction.DEBIT, Money(100), Instant.EPOCH, "Payment", "{}")
    private val bundle = SyncBundle(analysis = AnalysisSnapshot(), transactions = listOf(tx))
    private fun file(id: String) = DriveBackupFile(id, "backup", "", 0, "")
    private fun encrypted(bundle: SyncBundle = this.bundle) = SyncCrypto.encrypt(SyncBundleSerializer.toJsonBytes(bundle), "password")

    @Test fun unreadableLatestFallsBackWithoutDeletingHistory() {
        val result = BackupPolicy.readLatest(listOf(file("latest"), file("older")), "password") {
            if (it == "latest") error("Download failed") else encrypted()
        }
        assertEquals(bundle, result)
    }

    @Test fun allCorruptRevisionsAbortInsteadOfReturningAnEmptyBackup() {
        assertThrows(CorruptedSyncPayloadException::class.java) {
            BackupPolicy.readLatest(listOf(file("a"), file("b")), "password") { byteArrayOf(1) }
        }
    }

    @Test fun cancellationDoesNotTriggerFallback() {
        var attempts = 0
        assertThrows(CancellationException::class.java) {
            BackupPolicy.readLatest(listOf(file("a"), file("b")), "password") {
                attempts++; throw CancellationException()
            }
        }
        assertEquals(1, attempts)
    }

    @Test fun newerUnsupportedSchemaIsNeverDowngradedToAnOlderRevision() {
        assertThrows(UnsupportedSyncVersionException::class.java) {
            BackupPolicy.readLatest(listOf(file("new"), file("old")), "password") {
                if (it == "new") encrypted(bundle.copy(version = 999)) else encrypted()
            }
        }
    }

    @Test fun missingHistoryMustBeExplicitlyRestoredBeforeBackup() {
        assertThrows(IllegalStateException::class.java) { BackupPolicy.requireCompleteLedger(emptyList(), bundle) }
        BackupPolicy.requireCompleteLedger(listOf(tx), bundle)
    }

    @Test fun exportTimeAndCredentialDoNotCauseUnchangedUploads() {
        val changedSecret = bundle.copy(exportedAtEpochMillis = 99, analysis = bundle.analysis.copy(aiConfig = bundle.analysis.aiConfig.copy(apiKey = "local")))
        assertTrue(BackupPolicy.sameContent(bundle, changedSecret))
        assertFalse(BackupPolicy.sameContent(bundle, bundle.copy(transactions = emptyList())))
    }

    @Test fun malformedTransactionRejectsTheWholeRestore() {
        val json = String(SyncBundleSerializer.toJsonBytes(bundle))
        assertThrows(Exception::class.java) { SyncBundleSerializer.fromJsonBytes(json.replace("\"amountMinor\":100", "\"amountMinor\":0").toByteArray()) }
        assertThrows(Exception::class.java) { SyncBundleSerializer.fromJsonBytes(json.replace("\"provider\":\"JAGO\"", "\"provider\":\"UNKNOWN\"").toByteArray()) }
        assertThrows(Exception::class.java) { SyncBundleSerializer.fromJsonBytes("{\"version\":1}".toByteArray()) }
    }
}
