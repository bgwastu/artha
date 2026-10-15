package net.wastu.artha.data.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.data.analysis.AnalysisStore
import net.wastu.artha.data.ledger.AppendResult
import net.wastu.artha.data.ledger.LedgerRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DriveSyncManagerTest {

    private fun testContext(initialValues: Map<String, Any?> = emptyMap()): Pair<Context, MutableMap<String, Any?>> {
        val values = initialValues.toMutableMap()
        lateinit var preferences: SharedPreferences

        lateinit var editor: SharedPreferences.Editor
        editor = proxy { name, args ->
            when (name) {
                "putString", "putLong", "putBoolean" -> {
                    values[args[0] as String] = args[1]
                    editor
                }
                "remove" -> {
                    values.remove(args[0] as String)
                    editor
                }
                "apply" -> null
                "commit" -> true
                else -> error("Unexpected editor call: $name")
            }
        }

        preferences = proxy { name, args ->
            when (name) {
                "getString" -> values[args[0]] as? String ?: args[1]
                "getLong" -> values[args[0]] as? Long ?: args[1]
                "getBoolean" -> values[args[0]] as? Boolean ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preference call: $name")
            }
        }

        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
            override fun getApplicationContext(): Context = this
        }

        return context to values
    }

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args.orEmpty())
        } as T

    private class FakeLedger(initial: List<UnifiedTransaction> = emptyList()) : LedgerRepository {
        val flow = MutableStateFlow(initial)
        override fun observeTransactions() = flow.asStateFlow()
        override suspend fun append(transactions: List<UnifiedTransaction>): AppendResult {
            flow.value = flow.value + transactions
            return AppendResult(transactions.size, 0)
        }
        override suspend fun appendProviderImport(transactions: List<UnifiedTransaction>): AppendResult = append(transactions)
    }

    @Test
    fun initialStatusMatchesConfiguration() {
        val (ctxDisabled, _) = testContext(mapOf("sync_enabled" to false))
        val managerDisabled = DriveSyncManager(ctxDisabled, { FakeLedger() }, null)
        assertEquals(CloudSyncStatus.DISABLED, managerDisabled.syncState.value.status)

        val (ctxNoAccount, _) = testContext(mapOf("sync_enabled" to true))
        val managerNoAccount = DriveSyncManager(ctxNoAccount, { FakeLedger() }, null)
        assertEquals(CloudSyncStatus.NEEDS_ACCOUNT, managerNoAccount.syncState.value.status)

        val (ctxNoPassword, _) = testContext(mapOf(
            "sync_enabled" to true,
            "sync_account_email" to "test@example.com"
        ))
        val managerNoPassword = DriveSyncManager(ctxNoPassword, { FakeLedger() }, null)
        assertEquals(CloudSyncStatus.NEEDS_PASSWORD, managerNoPassword.syncState.value.status)

        val (ctxReady, _) = testContext(mapOf(
            "sync_enabled" to true,
            "sync_account_email" to "test@example.com",
            "plain_sync_pass" to "secret123"
        ))
        val managerReady = DriveSyncManager(ctxReady, { FakeLedger() }, null)
        assertEquals(CloudSyncStatus.READY, managerReady.syncState.value.status)
        assertTrue(managerReady.syncState.value.isEnabled)
        assertEquals("test@example.com", managerReady.syncState.value.accountEmail)
        assertTrue(managerReady.syncState.value.hasPassword)
    }

    @Test
    fun setAccountEmailCleansInputAndUpdatesState() {
        val (ctx, values) = testContext(mapOf("sync_enabled" to true, "plain_sync_pass" to "pass"))
        val manager = DriveSyncManager(ctx, { FakeLedger() }, null)

        manager.setAccountEmail("   user@domain.com   ")
        assertEquals("user@domain.com", manager.syncState.value.accountEmail)
        assertEquals(CloudSyncStatus.READY, manager.syncState.value.status)
        assertEquals("user@domain.com", values["sync_account_email"])

        manager.setAccountEmail("   ")
        assertEquals(null, manager.syncState.value.accountEmail)
        assertEquals(CloudSyncStatus.NEEDS_ACCOUNT, manager.syncState.value.status)
        assertFalse(values.containsKey("sync_account_email"))
    }

    @Test
    fun setEnabledTransitionsBetweenDisabledAndReady() {
        val (ctx, values) = testContext(mapOf(
            "sync_account_email" to "user@domain.com",
            "plain_sync_pass" to "pass"
        ))
        val manager = DriveSyncManager(ctx, { FakeLedger() }, null)
        assertEquals(CloudSyncStatus.DISABLED, manager.syncState.value.status)

        manager.setEnabled(true)
        assertEquals(CloudSyncStatus.READY, manager.syncState.value.status)
        assertTrue(values["sync_enabled"] as Boolean)

        manager.setEnabled(false)
        assertEquals(CloudSyncStatus.DISABLED, manager.syncState.value.status)
        assertFalse(values["sync_enabled"] as Boolean)
    }
    @Test
    fun triggerAutoBackupSkipsWhenNotConfigured() = runTest {
        val (ctx, _) = testContext(mapOf("sync_enabled" to false))
        val manager = DriveSyncManager(ctx, { FakeLedger() }, null, scope = backgroundScope)
        manager.triggerAutoBackup()
        advanceTimeBy(4000L)
        assertEquals(CloudSyncStatus.DISABLED, manager.syncState.value.status)
    }

    @Test
    fun setEncryptionPasswordUpdatesState() {
        val (ctx, values) = testContext(mapOf(
            "sync_enabled" to true,
            "sync_account_email" to "user@domain.com"
        ))
        val manager = DriveSyncManager(ctx, { FakeLedger() }, null)
        assertEquals(CloudSyncStatus.NEEDS_PASSWORD, manager.syncState.value.status)

        manager.setEncryptionPassword("mypassword")
        assertEquals(CloudSyncStatus.READY, manager.syncState.value.status)
        assertTrue(manager.syncState.value.hasPassword)

        manager.setEncryptionPassword("   ")
        assertEquals(CloudSyncStatus.NEEDS_PASSWORD, manager.syncState.value.status)
        assertFalse(manager.syncState.value.hasPassword)
    }
}
