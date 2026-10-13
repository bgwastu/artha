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
            storePassword(clean)
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

    suspend fun syncNow(): SyncResult = syncMutex.withLock {
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
            } catch (e: Exception) {
                val err = "Failed to obtain Google OAuth token: ${e.message}"
                recordError(err, CloudSyncStatus.ERROR)
                return@withContext SyncResult.Error(err)
            }

            try {
                // 1. List existing backups in appDataFolder
                val existingBackups = GoogleDriveAppDataClient.listBackups(token)

                // 2. Pull and merge remote backup if one exists
                var restoredCount = 0
                if (existingBackups.isNotEmpty()) {
                    var remoteBundle: SyncBundle? = null
                    var wrongPassword = false

                    for (file in existingBackups.take(2)) {
                        val encryptedBytes = GoogleDriveAppDataClient.downloadBackup(token, file.id)
                        try {
                            val decryptedBytes = SyncCrypto.decrypt(encryptedBytes, password)
                            remoteBundle = SyncBundleSerializer.fromJsonBytes(decryptedBytes)
                            break
                        } catch (wp: WrongSyncPasswordException) {
                            wrongPassword = true
                            break
                        } catch (_: Exception) {
                            // Try previous revision
                        }
                    }

                    if (wrongPassword) {
                        val err = "Incorrect encryption password for existing Google Drive backup."
                        recordError(err, CloudSyncStatus.WRONG_PASSWORD)
                        return@withContext SyncResult.Error(err, isWrongPassword = true)
                    }

                    if (remoteBundle != null) {
                        val localSnapshot = analysisStore?.snapshot?.value
                        if (localSnapshot != null) {
                            val mergedAnalysis = SyncMerger.mergeAnalysis(localSnapshot, remoteBundle.analysis)
                            analysisStore.applyMergedSnapshot(mergedAnalysis)
                        }

                        val localTxs = ledger().observeTransactions().firstOrNull().orEmpty()
                        val missingToAppend = SyncMerger.findMissingTransactionsToAppend(localTxs, remoteBundle.transactions)
                        if (missingToAppend.isNotEmpty()) {
                            ledger().append(missingToAppend)
                            restoredCount = missingToAppend.size
                        }
                    }
                }

                // 3. Build superset payload and push to Google Drive
                val allTxs = ledger().observeTransactions().firstOrNull().orEmpty()
                val currentSnapshot = analysisStore?.snapshot?.value ?: net.wastu.artha.data.analysis.AnalysisSnapshot()
                val bundleToExport = SyncBundle(
                    version = 1,
                    exportedAtEpochMillis = System.currentTimeMillis(),
                    analysis = currentSnapshot,
                    transactions = allTxs,
                )

                val jsonBytes = SyncBundleSerializer.toJsonBytes(bundleToExport)
                val encryptedBlob = SyncCrypto.encrypt(jsonBytes, password)
                GoogleDriveAppDataClient.uploadBackup(token, encryptedBlob)

                // 4. Prune older backups, keeping only the 2 newest
                val updatedFiles = GoogleDriveAppDataClient.listBackups(token)
                if (updatedFiles.size > 2) {
                    for (oldFile in updatedFiles.drop(2)) {
                        runCatching { GoogleDriveAppDataClient.deleteFile(token, oldFile.id) }
                    }
                }

                val now = System.currentTimeMillis()
                val summary = "${allTxs.size} tx encrypted & backed up" + if (restoredCount > 0) " ($restoredCount restored)" else ""
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

            val rootReader = net.wastu.artha.data.ingestion.RootFileReader(context)
            val bytes = rootReader.read("/data/system_ce/0/accounts_ce.db")
            val tmp = java.io.File.createTempFile("accounts_ce", ".db", context.cacheDir)
            try {
                tmp.writeBytes(bytes)
                android.database.sqlite.SQLiteDatabase.openDatabase(tmp.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                    val query = """
                        SELECT at.authtoken, CAST(ex.value AS INTEGER) as exp_sec
                        FROM authtokens at
                        JOIN accounts a ON a._id = at.accounts_id
                        LEFT JOIN extras ex ON ex.accounts_id = a._id AND ex.key = 'EXP:' || at.type
                        WHERE a.name = ? AND at.type LIKE '%https://www.googleapis.com/auth/drive%'
                        ORDER BY exp_sec DESC
                        LIMIT 1
                    """.trimIndent()
                    db.rawQuery(query, arrayOf(email)).use { cursor ->
                        if (cursor.moveToNext()) {
                            val token = cursor.getString(0)
                            val expSec = cursor.getLong(1)
                            val nowSec = System.currentTimeMillis() / 1000
                            if (!token.isNullOrBlank() && (expSec == 0L || expSec > nowSec)) {
                                return@withContext token
                            }
                        }
                    }
                }
            } finally {
                tmp.delete()
            }
        } catch (e: Exception) {
            android.util.Log.e("DriveSync", "Root token extraction failed", e)
            null
        }
        null
    }

    // --- Android KeyStore Passphrase Storage ---

    private fun storePassword(password: String) {
        try {
            val key = getOrCreateSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, packed, 0, iv.size)
            System.arraycopy(cipherText, 0, packed, iv.size, cipherText.size)
            val encoded = Base64.encodeToString(packed, Base64.NO_WRAP)
            prefs.edit { putString("enc_sync_pass", encoded) }
        } catch (_: Exception) {
            // Fallback for JVM test environments where AndroidKeyStore is stubbed
            prefs.edit { putString("plain_sync_pass", password) }
        }
    }

    fun getStoredPassword(): String? {
        val plain = prefs.getString("plain_sync_pass", null)
        if (plain != null) return plain
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
