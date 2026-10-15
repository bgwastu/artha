package net.wastu.artha.data.ingestion

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.os.UserHandle
import android.util.Base64
import com.topjohnwu.superuser.Shell
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.wastu.artha.data.providers.AccountStatus

/** Fixed paths, read-only source access, and no logging of provider contents or credentials. */
@android.annotation.SuppressLint("SdCardPath")
class RootFileReader(internal val context: Context) {
    fun packageFirstInstallTime(packageName: String): Long =
        runCatching { context.packageManager.getPackageInfo(packageName, 0).firstInstallTime }.getOrDefault(0L)

    private fun validate(path: String) {
        require(path.matches(Regex("/data/(data|system_ce|system|user)/[a-zA-Z0-9_./-]+")) && ".." !in path.split('/'))
        if (path.startsWith("/data/data/")) {
            val packageName = path.removePrefix("/data/data/").substringBefore('/')
            try { context.packageManager.getPackageInfo(packageName, 0) }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                throw ProviderReadException(AccountStatus.APP_NOT_INSTALLED, "Provider app is not installed.")
            }
        }
        check(Process.myUserHandle() == UserHandle.getUserHandleForUid(0)) { "Provider imports currently support the primary Android profile only." }
    }

    suspend fun read(path: String): ByteArray = readBatch(listOf(path))[path]
        ?: throw ProviderReadException(AccountStatus.ERROR, "Provider file missing. Open its app to populate local history.")

    suspend fun readBatch(paths: List<String>): Map<String, ByteArray?> = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext emptyMap()
        paths.forEach(::validate)
        coroutineContext.ensureActive()
        Shell.Builder.create().setContext(context).setTimeout(15).build("su", "-M", "-c", "/system/bin/sh").use { shell ->
            check(shell.isRoot) { "Root access unavailable" }
            val commands = paths.mapIndexed { index, path ->
                "if [ -f '$path' ]; then echo __ARTHA_$index; base64 '$path' || exit 1; else echo __ARTHA_${index}_MISSING; fi"
            }
            val result = shell.newJob().to(mutableListOf<String>(), mutableListOf<String>()).add(*commands.toTypedArray()).exec()
            check(result.isSuccess) { "Unable to read provider snapshot" }
            coroutineContext.ensureActive()
            val buffers = mutableMapOf<Int, StringBuilder>()
            var current: Int? = null
            for (line in result.out) {
                if (line.startsWith("__ARTHA_")) {
                    current = line.removePrefix("__ARTHA_").toIntOrNull()
                    current?.let { buffers[it] = StringBuilder() }
                } else current?.let { buffers.getValue(it).append(line) }
            }
            paths.mapIndexed { index, path -> path to buffers[index]?.let { Base64.decode(it.toString(), Base64.DEFAULT) } }.toMap()
        }
    }

    /** Two identical reads avoid importing torn snapshots while a provider app writes. */
    suspend fun stableBatch(paths: List<String>): Map<String, ByteArray?> {
        var previous = readBatch(paths)
        repeat(3) {
            val current = readBatch(paths)
            if (paths.all { path -> previous[path]?.contentEquals(current[path] ?: byteArrayOf()) ?: (current[path] == null) }) return current
            previous = current
        }
        error("Provider data is changing. Retry after its app finishes updating.")
    }

    suspend fun <T> readSqlite(path: String, block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        val snapshot = stableBatch(listOf(path, "$path-wal"))
        val bytes = snapshot[path] ?: error("Provider database missing. Open the provider app first.")
        val directory = File(context.cacheDir, "provider-${UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            val database = File(directory, "snapshot.db").apply { writeBytes(bytes) }
            snapshot["$path-wal"]?.let { File(directory, "snapshot.db-wal").writeBytes(it) }
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use(block)
        } finally { directory.deleteRecursively() }
    }

    suspend fun list(dir: String): List<String> = withContext(Dispatchers.IO) {
        validate(dir)
        Shell.Builder.create().setContext(context).setTimeout(15).build("su", "-M", "-c", "/system/bin/sh").use { shell ->
            check(shell.isRoot) { "Root access unavailable" }
            val result = shell.newJob().to(mutableListOf<String>(), mutableListOf<String>()).add("ls '$dir'").exec()
            check(result.isSuccess) { "Provider cache missing. Open its app to populate history." }
            coroutineContext.ensureActive()
            result.out
        }
    }

    suspend fun executeCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        check(Process.myUserHandle() == UserHandle.getUserHandleForUid(0)) { "Provider imports currently support the primary Android profile only." }
        coroutineContext.ensureActive()
        try {
            Shell.Builder.create().setContext(context).setTimeout(10).build("su", "-M", "-c", "/system/bin/sh").use { shell ->
                if (!shell.isRoot) return@use false
                shell.newJob().add(command).exec().isSuccess
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}
