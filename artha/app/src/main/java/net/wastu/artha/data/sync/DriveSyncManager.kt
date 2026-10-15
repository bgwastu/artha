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
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

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

@OptIn(FlowPreview::class)
class DriveSyncManager(
    private val context: Context,
    private val ledger: () -> LedgerRepository,
    private val analysisStore: AnalysisStore?,
    private val rootAccess: net.wastu.artha.core.root.RootAccess? = null,
    private val scope: CoroutineScope? = null,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("artha_drive_sync_prefs", Context.MODE_PRIVATE)
    private val syncMutex = Mutex()

    private val _syncState = MutableStateFlow(loadInitialState())
    val syncState: StateFlow<DriveSyncState> = _syncState.asStateFlow()

    private var autoBackupJob: kotlinx.coroutines.Job? = null

    init {
        scope?.let { autoScope ->
            autoBackupJob = autoScope.launch {
                observeAndAutoBackup()
            }
        }
    }

    fun stopAutoBackup() {
        autoBackupJob?.cancel()
        autoBackupJob = null
    }

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
        if (newStatus == CloudSyncStatus.READY) {
            triggerAutoBackup()
        }
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
        if (newStatus == CloudSyncStatus.READY) {
            triggerAutoBackup()
        }
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
        if (newStatus == CloudSyncStatus.READY) {
            triggerAutoBackup()
        }
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
                val currentSig = computeSignature(allTxs, snapshot)
                prefs.edit {
                    putLong("last_sync_ms", now)
                    putString("last_sync_summary", summary)
                    putString("last_backed_up_signature", currentSig)
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
        val msg = playServicesError.message.orEmpty()
        if (msg.contains("UnregisteredOnApiConsole", ignoreCase = true)) {
            throw IllegalStateException(
                "Google Drive authorization requires root access or a registered Google Cloud client. " +
                "Please grant root permission and ensure Google Drive or Gmail is installed.",
                playServicesError
            )
        }
        throw playServicesError
    }

    private suspend fun getRootAuthToken(email: String): String? = withContext(Dispatchers.IO) {
        try {
            val root = rootAccess ?: net.wastu.artha.core.root.LibsuRootAccess(context)
            if (!root.verify()) return@withContext null

            // First check accounts_ce.db for existing unexpired Google Drive token
            val cached = getCachedRootToken(email, allowExpired = false)
            if (cached != null) return@withContext cached

            // Refresh or acquire token dynamically via DriveAuthHelper running as an installed Google app
            val refreshed = acquireRootTokenViaHelper(email)
            if (refreshed != null) return@withContext refreshed

            // Last resort: check if any token was saved in accounts_ce.db even if expired
            getCachedRootToken(email, allowExpired = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private suspend fun getCachedRootToken(email: String, allowExpired: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            net.wastu.artha.data.ingestion.RootFileReader(context).readSqlite("/data/system_ce/0/accounts_ce.db") { db ->
                val query = """
                    SELECT at.authtoken, CAST(ex.value AS INTEGER) as exp_sec
                    FROM authtokens at JOIN accounts a ON a._id = at.accounts_id
                    LEFT JOIN extras ex ON ex.accounts_id = a._id AND ex.key = 'EXP:' || at.type
                    WHERE a.name = ?
                      AND at.type LIKE '%https://www.googleapis.com/auth/drive%'
                      AND at.type NOT LIKE '%drive.readonly%'
                    ORDER BY exp_sec DESC NULLS LAST LIMIT 1
                """.trimIndent()
                db.rawQuery(query, arrayOf(email)).use { cursor ->
                    if (!cursor.moveToNext()) null else {
                        val token = cursor.getString(0)
                        val expires = cursor.getLong(1)
                        val now = System.currentTimeMillis() / 1000
                        if (token.isNullOrBlank()) null
                        else if (allowExpired || expires == 0L || expires > now + 60L) token
                        else null
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private val candidateGooglePackages = listOf(
        "com.google.android.apps.docs",
        "com.google.android.gm",
        "com.google.android.apps.docs.editors.sheets",
        "com.google.android.apps.docs.editors.slides",
        "com.google.android.apps.docs.editors.docs",
    )

    private fun findCandidateGooglePackage(): Pair<String, Int>? {
        for (pkg in candidateGooglePackages) {
            val uid = runCatching {
                context.packageManager.getPackageInfo(pkg, 0).applicationInfo?.uid
            }.getOrNull()
            if (uid != null) return pkg to uid
        }
        return null
    }

    private fun extractHelperJar(): File? {
        return runCatching {
            val file = File(context.codeCacheDir, "drive_auth_helper.jar")
            if (!file.exists() || file.length() == 0L) {
                context.assets.open("drive_auth_helper.jar").use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            file
        }.getOrNull()
    }

    private suspend fun acquireRootTokenViaHelper(email: String): String? = withContext(Dispatchers.IO) {
        val (pkg, uid) = findCandidateGooglePackage() ?: return@withContext null
        val jar = extractHelperJar() ?: return@withContext null
        val command = "CLASSPATH='${jar.absolutePath}' app_process /system/bin net.wastu.artha.data.sync.DriveAuthHelper '$email' '$uid' '$pkg'"
        val output = net.wastu.artha.data.ingestion.RootFileReader(context).executeCommandWithOutput(command) ?: return@withContext null
        output.firstOrNull { it.startsWith("AUTH_TOKEN:") }
            ?.removePrefix("AUTH_TOKEN:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun observeAndAutoBackup() {
        val txFlow = ledger().observeTransactions()
        val analysisFlow = analysisStore?.snapshot ?: flowOf(net.wastu.artha.data.analysis.AnalysisSnapshot())

        combine(txFlow, analysisFlow, _syncState) { txs, snapshot, state ->
            val isReady = state.isEnabled && !state.accountEmail.isNullOrBlank() && state.hasPassword
            val sig = computeSignature(txs, snapshot)
            isReady to sig
        }
        .distinctUntilChanged()
        .debounce(3000L)
        .collect { (isReady, sig) ->
            if (isReady) {
                val lastBackedUpSig = prefs.getString("last_backed_up_signature", null)
                if (sig != lastBackedUpSig && !_syncState.value.isSyncing) {
                    val result = syncNow()
                    if (result is SyncResult.Success) {
                        prefs.edit { putString("last_backed_up_signature", sig) }
                    }
                }
            }
        }
    }

    fun triggerAutoBackup() {
        val autoScope = scope ?: return
        autoScope.launch {
            val state = _syncState.value
            val isReady = state.isEnabled && !state.accountEmail.isNullOrBlank() && state.hasPassword
            if (isReady && !state.isSyncing) {
                val txs = ledger().observeTransactions().firstOrNull().orEmpty()
                val snapshot = analysisStore?.snapshot?.value ?: net.wastu.artha.data.analysis.AnalysisSnapshot()
                val sig = computeSignature(txs, snapshot)
                val lastBackedUpSig = prefs.getString("last_backed_up_signature", null)
                if (sig != lastBackedUpSig) {
                    val result = syncNow()
                    if (result is SyncResult.Success) {
                        prefs.edit { putString("last_backed_up_signature", sig) }
                    }
                }
            }
        }
    }

    private fun computeSignature(
        txs: List<net.wastu.artha.core.model.UnifiedTransaction>,
        snapshot: net.wastu.artha.data.analysis.AnalysisSnapshot
    ): String {
        val count = txs.size
        val latest = txs.maxOfOrNull { it.occurredAt.toEpochMilli() } ?: 0L
        val earliest = txs.minOfOrNull { it.occurredAt.toEpochMilli() } ?: 0L
        val keyHash = txs.fold(0L) { acc, tx -> acc * 31 + tx.key.hashCode() }
        val notes = snapshot.userNotes.size
        val categories = snapshot.categories.size
        val monthlyNotes = snapshot.monthlyNotes.size
        val knowledge = snapshot.knowledge.hashCode()
        return "tx_${count}_${latest}_${earliest}_${keyHash}_an_${notes}_${categories}_${monthlyNotes}_${knowledge}"
    }

    // --- Android KeyStore Passphrase Storage ---

    @Synchronized
    private fun storePassword(password: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val packed = cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            check(prefs.edit().putString("enc_sync_pass", Base64.encodeToString(packed, Base64.NO_WRAP))
                .remove("plain_sync_pass").commit()) { "Unable to save encryption password securely." }
        } catch (_: Exception) {
            // Fallback for JVM test environments where AndroidKeyStore is unavailable
            prefs.edit { putString("plain_sync_pass", password) }
        }
    }

    fun getStoredPassword(): String? {
        val plain = prefs.getString("plain_sync_pass", null)
        if (plain != null) {
            // One-time migration. Keep legacy recovery material if KeyStore is unavailable.
            return runCatching { storePassword(plain); plain }.getOrDefault(plain)
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
