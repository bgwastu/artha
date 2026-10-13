package net.wastu.artha.data.sync

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import net.wastu.artha.data.analysis.AnalysisStore
import net.wastu.artha.data.ledger.LedgerRepository
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class CloudSyncStatus {
    DISABLED,
    NEEDS_ACCOUNT,
    NEEDS_PASSWORD,
    READY,
    SYNCING,
    WRONG_PASSWORD,
    AUTH_REQUIRED,
    ERROR,
}

data class DriveSyncState(
    val isEnabled: Boolean = false,
    val accountEmail: String? = null,
    val hasPassword: Boolean = false,
    val status: CloudSyncStatus = CloudSyncStatus.DISABLED,
    val isSyncing: Boolean = false,
    val lastSyncEpochMillis: Long? = null,
    val lastSyncSummary: String? = null,
    val lastError: String? = null,
    val consentIntent: Intent? = null,
)

sealed interface SyncResult {
    data class Success(val totalTransactions: Int, val restoredTransactions: Int) : SyncResult
    data class Error(val message: String, val isWrongPassword: Boolean = false, val consentIntent: Intent? = null) : SyncResult
}

class DriveSyncManager(
    private val context: Context,
    private val ledger: () -> LedgerRepository,
    private val analysisStore: AnalysisStore?,
    private val rootAccess: net.wastu.artha.core.root.RootAccess? = null,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("artha_drive_sync_prefs", Context.MODE_PRIVATE)
    private val syncMutex = Mutex()

    private val _syncState = MutableStateFlow(loadInitialState())
    val syncState: StateFlow<DriveSyncState> = _syncState.asStateFlow()

    private fun loadInitialState(): DriveSyncState {
        val enabled = prefs.getBoolean("sync_enabled", false)
        val email = prefs.getString("sync_account_email", null)
        val hasPass = getStoredPassword() != null
        val lastSyncMs = prefs.getLong("last_sync_ms", 0L).takeIf { it > 0L }
        val lastSummary = prefs.getString("last_sync_summary", null)
        val lastErr = prefs.getString("last_error", null)

        val status = when {
            !enabled -> CloudSyncStatus.DISABLED
            email.isNullOrBlank() -> CloudSyncStatus.NEEDS_ACCOUNT
            !hasPass -> CloudSyncStatus.NEEDS_PASSWORD
            lastErr != null -> CloudSyncStatus.ERROR
            else -> CloudSyncStatus.READY
        }

        return DriveSyncState(
            isEnabled = enabled,
            accountEmail = email,
            hasPassword = hasPass,
            status = status,
            lastSyncEpochMillis = lastSyncMs,
            lastSyncSummary = lastSummary,
            lastError = lastErr,
        )
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("sync_enabled", enabled) }
        val current = _syncState.value
        val newStatus = when {
            !enabled -> CloudSyncStatus.DISABLED
            current.accountEmail.isNullOrBlank() -> CloudSyncStatus.NEEDS_ACCOUNT
            !current.hasPassword -> CloudSyncStatus.NEEDS_PASSWORD
            else -> CloudSyncStatus.READY
        }
        _syncState.value = current.copy(isEnabled = enabled, status = newStatus)
    }

    fun setAccountEmail(email: String?) {
        val clean = email?.trim()?.takeIf { it.isNotBlank() }
        prefs.edit {
            if (clean != null) putString("sync_account_email", clean)
            else remove("sync_account_email")
        }
        val current = _syncState.value
        val newStatus = when {
            !current.isEnabled -> CloudSyncStatus.DISABLED
            clean == null -> CloudSyncStatus.NEEDS_ACCOUNT
            !current.hasPassword -> CloudSyncStatus.NEEDS_PASSWORD
            else -> CloudSyncStatus.READY
        }
        _syncState.value = current.copy(accountEmail = clean, status = newStatus, consentIntent = null)
    }

    fun setEncryptionPassword(password: String) {
        val clean = password.trim()
        if (clean.isBlank()) {
            clearStoredPassword()
        } else {
            try {
                storePassword(clean)
            } catch (_: Exception) {
                recordError("Unable to store the password securely. Please try again.", CloudSyncStatus.ERROR)
                return
            }
        }
        val hasPass = clean.isNotBlank()
        val current = _syncState.value
        val newStatus = when {
            !current.isEnabled -> CloudSyncStatus.DISABLED
            current.accountEmail.isNullOrBlank() -> CloudSyncStatus.NEEDS_ACCOUNT
            !hasPass -> CloudSyncStatus.NEEDS_PASSWORD
            else -> CloudSyncStatus.READY
        }
        _syncState.value = current.copy(hasPassword = hasPass, status = newStatus, lastError = null)
    }

    suspend fun syncNow(): SyncResult = transfer(restore = false)

    suspend fun restoreNow(): SyncResult = transfer(restore = true)

    private suspend fun transfer(restore: Boolean): SyncResult = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val currentState = _syncState.value
            if (!currentState.isEnabled) {
                return@withContext SyncResult.Error("Sync is disabled.")
            }
            val email = currentState.accountEmail
            if (email.isNullOrBlank()) {
                _syncState.value = currentState.copy(status = CloudSyncStatus.NEEDS_ACCOUNT)
                return@withContext SyncResult.Error("Google account is not connected.")
            }
            val password = getStoredPassword()
            if (password.isNullOrBlank()) {
                _syncState.value = currentState.copy(status = CloudSyncStatus.NEEDS_PASSWORD)
                return@withContext SyncResult.Error("Encryption password is not set.")
            }

            _syncState.value = currentState.copy(isSyncing = true, status = CloudSyncStatus.SYNCING, lastError = null)

            val token = try {
                getAuthToken(email)
            } catch (recoverable: UserRecoverableAuthException) {
                val intent = recoverable.intent
                _syncState.value = currentState.copy(
                    isSyncing = false,
                    status = CloudSyncStatus.AUTH_REQUIRED,
                    consentIntent = intent,
                    lastError = "Google Drive authorization required.",
                )
                return@withContext SyncResult.Error("Google Drive authorization required.", consentIntent = intent)
            } catch (cancelled: CancellationException) {
                _syncState.value = _syncState.value.copy(isSyncing = false, status = CloudSyncStatus.READY)
                throw cancelled
            } catch (e: Exception) {
                val err = "Failed to obtain Google OAuth token: ${e.message}"
                recordError(err, CloudSyncStatus.ERROR)
                return@withContext SyncResult.Error(err)
            }

            try {
                val files = GoogleDriveAppDataClient.listBackups(token)
                coroutineContext.ensureActive()
                val previous = BackupPolicy.readLatest(files, password) { id ->
                    coroutineContext.ensureActive()
                    GoogleDriveAppDataClient.downloadBackup(token, id)
                }
                coroutineContext.ensureActive()
                var restoredCount = 0
                if (restore) {
                    checkNotNull(previous) { "No cloud backup exists yet." }
                    val local = ledger().observeTransactions().firstOrNull().orEmpty()
                    val missing = SyncMerger.findMissingTransactionsToAppend(local, previous.transactions)
                    coroutineContext.ensureActive()
                    val result = ledger().append(missing)
                    restoredCount = result.inserted
                    analysisStore?.restoreSnapshot(previous.analysis)
                }
                val allTxs = ledger().observeTransactions().firstOrNull().orEmpty()
                val snapshot = analysisStore?.snapshot?.value ?: net.wastu.artha.data.analysis.AnalysisSnapshot()
                val bundle = SyncBundle(
                    analysis = snapshot.copy(aiConfig = snapshot.aiConfig.copy(apiKey = "")),
                    transactions = allTxs,
                )
                var uploaded = false
                if (!restore) {
                    BackupPolicy.requireCompleteLedger(allTxs, previous)
                    if (previous == null || !BackupPolicy.sameContent(bundle, previous)) {
                        val encrypted = SyncCrypto.encrypt(SyncBundleSerializer.toJsonBytes(bundle), password)
                        coroutineContext.ensureActive()
                        GoogleDriveAppDataClient.uploadBackup(token, encrypted)
                        uploaded = true
                    }
                }
                // Retain all revisions: recovery history must never be silently pruned.
                val now = System.currentTimeMillis()
                val summary = when {
                    restore -> "$restoredCount ledger records restored; existing data kept"
                    uploaded -> "${allTxs.size} ledger records encrypted and backed up"
                    else -> "Backup already up to date"
                }
                prefs.edit {
                    putLong("last_sync_ms", now)
                    putString("last_sync_summary", summary)
                    remove("last_error")
                }

                _syncState.value = _syncState.value.copy(
                    isSyncing = false,
                    status = CloudSyncStatus.READY,
                    lastSyncEpochMillis = now,
                    lastSyncSummary = summary,
                    lastError = null,
                )

                SyncResult.Success(allTxs.size, restoredCount)
            } catch (cancelled: CancellationException) {
                _syncState.value = _syncState.value.copy(isSyncing = false, status = CloudSyncStatus.READY)
                throw cancelled
            } catch (unauthorized: DriveUnauthorizedException) {
                GoogleAuthUtil.clearToken(context, token)
                val err = "Google Drive session expired; please tap Sync again."
                recordError(err, CloudSyncStatus.ERROR)
                SyncResult.Error(err)
            } catch (e: Exception) {
                val err = e.message ?: "Cloud sync failed."
                recordError(err, CloudSyncStatus.ERROR)
                SyncResult.Error(err)
            }
        }
    }

    private fun recordError(message: String, status: CloudSyncStatus) {
        prefs.edit { putString("last_error", message) }
        _syncState.value = _syncState.value.copy(
            isSyncing = false,
            status = status,
            lastError = message,
        )
    }

    private suspend fun getAuthToken(email: String): String = withContext(Dispatchers.IO) {
        // 1. Try Google Play Services standard auth first
        val playServicesError = try {
            val account = Account(email, "com.google")
            val scope = "oauth2:${GoogleDriveAppDataClient.DRIVE_APPDATA_SCOPE}"
            return@withContext GoogleAuthUtil.getToken(context, account, scope)
        } catch (recoverable: UserRecoverableAuthException) {
            throw recoverable
        } catch (e: Exception) {
            e
        }

        // 2. If GoogleAuthUtil failed (e.g. UnregisteredOnApiConsole), try root-privileged token acquisition
        val rootToken = getRootAuthToken(email)
        if (rootToken != null) {
            return@withContext rootToken
        }

        // 3. Re-throw original exception if root fallback wasn't able to extract a valid token
        throw playServicesError
    }

    private suspend fun getRootAuthToken(email: String): String? = withContext(Dispatchers.IO) {
        try {
            val root = rootAccess ?: net.wastu.artha.core.root.LibsuRootAccess(context)
            if (!root.verify()) return@withContext null
            return@withContext net.wastu.artha.data.ingestion.RootFileReader(context).readSqlite("/data/system_ce/0/accounts_ce.db") { db ->
                val query = """
                    SELECT at.authtoken, CAST(ex.value AS INTEGER) as exp_sec
                    FROM authtokens at JOIN accounts a ON a._id = at.accounts_id
                    LEFT JOIN extras ex ON ex.accounts_id = a._id AND ex.key = 'EXP:' || at.type
                    WHERE a.name = ? AND at.type LIKE '%https://www.googleapis.com/auth/drive.appdata%'
                    ORDER BY exp_sec DESC LIMIT 1
                """.trimIndent()
                db.rawQuery(query, arrayOf(email)).use { cursor ->
                    if (!cursor.moveToNext()) null else {
                        val token = cursor.getString(0)
                        val expires = cursor.getLong(1)
                        token?.takeIf { it.isNotBlank() && (expires == 0L || expires > System.currentTimeMillis() / 1000) }
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    // --- Android KeyStore Passphrase Storage ---

    @Synchronized
    private fun storePassword(password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val packed = cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("enc_sync_pass", Base64.encodeToString(packed, Base64.NO_WRAP))
            .remove("plain_sync_pass").commit()) { "Unable to save encryption password securely." }
    }

    fun getStoredPassword(): String? {
        val plain = prefs.getString("plain_sync_pass", null)
        if (plain != null) {
            // One-time migration. Keep legacy recovery material if KeyStore is unavailable.
            return runCatching { storePassword(plain); plain }.getOrNull()
        }
        val enc = prefs.getString("enc_sync_pass", null) ?: return null
        return try {
            val packed = Base64.decode(enc, Base64.NO_WRAP)
            if (packed.size <= 12) return null
            val iv = packed.copyOfRange(0, 12)
            val ciphertext = packed.copyOfRange(12, packed.size)
            val key = getOrCreateSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun clearStoredPassword() {
        prefs.edit {
            remove("enc_sync_pass")
            remove("plain_sync_pass")
        }
    }



    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "artha_sync_pass_key"
        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGenerator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }
}
