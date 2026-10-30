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
import kotlinx.coroutines.currentCoroutineContext
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

    /** Reader for provider files; files above [MAX_INLINE_BYTES] are copied instead of base64'd. */
    suspend fun read(path: String): ByteArray = readBatch(listOf(path))[path]
        ?: throw ProviderReadException(AccountStatus.ERROR, "Provider file missing. Open its app to populate local history.")

    /**
     * Copies one validated provider file into Artha's private sandbox as root, so callers read it
     * once without base64 copies or content re-reads. The returned file belongs to the caller: it
     * must be deleted after use. Null when the source file does not exist.
     */
    suspend fun copyToSandbox(path: String): File? = withContext(Dispatchers.IO) {
        validate(path)
        coroutineContext.ensureActive()
        val size = signatures(listOf(path))[path].sizeOf()
        copyToSandbox(path, sandboxFile(path), size)
    }

    /**
     * Reads small provider files through one shell base64 batch. Larger files are copied to the
     * sandbox and read from there, so their bytes are never held in three in-memory copies.
     */
    suspend fun readBatch(paths: List<String>): Map<String, ByteArray?> = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext emptyMap()
        paths.forEach(::validate)
        coroutineContext.ensureActive()
        val signatures = signatures(paths)
        val inline = paths.filter { (signatures[it].sizeOf() ?: 0L) <= MAX_INLINE_BYTES }
        val large = paths - inline.toSet()
        val result = mutableMapOf<String, ByteArray?>()
        result.putAll(readInlineBatch(inline, signatures))
        for (path in large) {
            coroutineContext.ensureActive()
            val copy = copyToSandbox(path, sandboxFile(path), signatures[path].sizeOf())
            result[path] = try { copy?.readBytes() } finally { copy?.delete() }
        }
        paths.associateWith { result[it] }
    }

    /**
     * Copies [paths] once and validates the copies with `stat -c '%s %Y'` before and after, so a
     * provider app writing mid-copy is detected by size+mtime instead of a second full read.
     * Retries a few times, then fails with the historical changing-data message.
     */
    suspend fun stableBatch(paths: List<String>): Map<String, ByteArray?> {
        if (paths.isEmpty()) return emptyMap()
        val copies = stableCopies(paths) { sandboxFile(it) }
        return try {
            paths.associateWith { path -> copies[path]?.readBytes() }
        } finally {
            copies.values.forEach { it?.delete() }
        }
    }

    suspend fun <T> readSqlite(path: String, block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        validate(path)
        coroutineContext.ensureActive()
        val directory = File(context.cacheDir, "provider-${UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            val copies = stableCopies(listOf(path, "$path-wal")) { source ->
                File(directory, if (source.endsWith("-wal")) "snapshot.db-wal" else "snapshot.db")
            }
            val database = copies[path] ?: error("Provider database missing. Open the provider app first.")
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use(block)
        } finally { directory.deleteRecursively() }
    }

    suspend fun lastModifiedMillis(path: String): Long? = withContext(Dispatchers.IO) {
        validate(path)
        Shell.Builder.create().setContext(context).setTimeout(10).build("su", "-M", "-c", "/system/bin/sh").use { shell ->
            if (!shell.isRoot) return@withContext null
            val output = mutableListOf<String>()
            val result = shell.newJob().to(output).add("if [ -f '$path' ]; then stat -c %Y '$path'; else echo MISSING; fi").exec()
            if (!result.isSuccess) return@withContext null
            when (val line = output.firstOrNull()?.trim()) {
                null, "", "MISSING" -> null
                else -> line.toLongOrNull()?.times(1000L)
            }
        }
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

    suspend fun executeCommandWithOutput(command: String): List<String>? = withContext(Dispatchers.IO) {
        check(Process.myUserHandle() == UserHandle.getUserHandleForUid(0)) { "Provider imports currently support the primary Android profile only." }
        coroutineContext.ensureActive()
        try {
            Shell.Builder.create().setContext(context).setTimeout(15).build("su", "-M", "-c", "/system/bin/sh").use { shell ->
                if (!shell.isRoot) return@use null
                val output = mutableListOf<String>()
                val result = shell.newJob().to(output).add(command).exec()
                if (result.isSuccess) output else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /** Base64 batch for files small enough that one in-memory copy per shell line is cheap. */
    private fun readInlineBatch(paths: List<String>, signatures: Map<String, String?>): Map<String, ByteArray?> {
        if (paths.isEmpty()) return emptyMap()
        val total = paths.sumOf { signatures[it].sizeOf() ?: 0L }
        rootShell(timeoutSeconds(total)).use { shell ->
            check(shell.isRoot) { "Root access unavailable" }
            val commands = paths.mapIndexed { index, path ->
                "if [ -f '$path' ]; then echo __ARTHA_$index; base64 '$path' || exit 1; else echo __ARTHA_${index}_MISSING; fi"
            }
            val result = shell.newJob().to(mutableListOf<String>(), mutableListOf<String>()).add(*commands.toTypedArray()).exec()
            check(result.isSuccess) { "Unable to read provider snapshot" }
            val buffers = mutableMapOf<Int, StringBuilder>()
            var current: Int? = null
            for (line in result.out) {
                if (line.startsWith("__ARTHA_")) {
                    current = line.removePrefix("__ARTHA_").toIntOrNull()
                    current?.let { buffers[it] = StringBuilder() }
                } else current?.let { buffers.getValue(it).append(line) }
            }
            return paths.mapIndexed { index, path -> path to buffers[index]?.let { Base64.decode(it.toString(), Base64.DEFAULT) } }.toMap()
        }
    }

    /**
     * Stat-verified root copies of [paths], retried while a provider app is mid-write. Missing
     * sources map to null; every returned file belongs to the caller's [destination] naming.
     */
    private suspend fun stableCopies(paths: List<String>, destination: (String) -> File): Map<String, File?> {
        paths.forEach(::validate)
        val caller = currentCoroutineContext()
        repeat(STABILITY_ATTEMPTS) {
            caller.ensureActive()
            val before = signatures(paths)
            val copies = copySources(paths, before, destination)
            caller.ensureActive()
            val after = signatures(paths)
            if (before == after) return copies
            copies.values.forEach { it?.delete() }
        }
        error("Provider data is changing. Retry after its app finishes updating.")
    }

    private fun copySources(
        paths: List<String>,
        signatures: Map<String, String?>,
        destination: (String) -> File,
    ): Map<String, File?> {
        val present = paths.filter { signatures[it] != null }
        if (present.isEmpty()) return paths.associateWith { null }
        val destinations = present.associateWith(destination)
        val total = present.sumOf { signatures[it].sizeOf() ?: 0L }
        val copied = copyAll(destinations, total)
        return paths.associateWith { path -> destinations[path]?.takeIf { path in copied } }
    }

    /** Copies [destinations] as root into app-private files; returns the sources that were copied. */
    private fun copyAll(destinations: Map<String, File>, totalBytes: Long): Set<String> {
        rootShell(timeoutSeconds(totalBytes)).use { shell ->
            if (!shell.isRoot) error("Root access unavailable")
            val entries = destinations.entries.toList()
            val uid = Process.myUid()
            val commands = entries.mapIndexed { index, (source, destination) ->
                "echo __ARTHA_COPY_$index; if [ -f '$source' ]; then rm -f '${destination.path}'; " +
                    "cp -f '$source' '${destination.path}' && chown $uid:$uid '${destination.path}' && " +
                    "chmod 600 '${destination.path}' && echo OK; else echo MISSING; fi"
            }
            val output = mutableListOf<String>()
            val result = shell.newJob().to(output).add(*commands.toTypedArray()).exec()
            check(result.isSuccess) { "Unable to read provider snapshot" }
            val values = markedOutput(output, "__ARTHA_COPY_")
            val failed = entries.indices.filter { values[it]?.let { it != "OK" && it != "MISSING" } ?: true }
            check(failed.isEmpty()) { "Unable to read provider snapshot" }
            return entries.indices.filter { values[it] == "OK" }.mapTo(mutableSetOf()) { entries[it].key }
        }
    }

    private suspend fun copyToSandbox(path: String, destination: File, size: Long?): File? {
        if (size == null) return null
        currentCoroutineContext().ensureActive()
        copyAll(mapOf(path to destination), size)
        return destination.takeIf { it.exists() }
    }

    /** `stat -c '%s %Y'` per path as "size mtime", or null when the file is missing. */
    private fun signatures(paths: List<String>): Map<String, String?> {
        if (paths.isEmpty()) return emptyMap()
        rootShell(MIN_TIMEOUT_SECONDS).use { shell ->
            if (!shell.isRoot) error("Root access unavailable")
            val commands = paths.mapIndexed { index, path ->
                "echo __ARTHA_STAT_$index; if [ -f '$path' ]; then stat -c '%s %Y' '$path'; else echo MISSING; fi"
            }
            val output = mutableListOf<String>()
            val result = shell.newJob().to(output).add(*commands.toTypedArray()).exec()
            check(result.isSuccess) { "Unable to read provider snapshot" }
            val values = markedOutput(output, "__ARTHA_STAT_")
            return paths.mapIndexed { index, path ->
                path to values[index]?.takeIf { it.isNotEmpty() && it != "MISSING" }
            }.toMap()
        }
    }

    /** Maps `<marker><index>` lines to the first line that follows each marker. */
    private fun markedOutput(output: List<String>, marker: String): Map<Int, String> {
        val values = mutableMapOf<Int, String>()
        var current = -1
        for (line in output) {
            val trimmed = line.trim()
            if (trimmed.startsWith(marker)) {
                current = trimmed.removePrefix(marker).toIntOrNull() ?: -1
                if (current >= 0) values[current] = ""
                continue
            }
            if (current >= 0 && values[current]?.isEmpty() == true) values[current] = trimmed
        }
        return values
    }

    private fun String?.sizeOf(): Long? = this?.substringBefore(' ')?.toLongOrNull()

    private fun sandboxFile(path: String): File {
        // Snapshots are short-lived; a crash can leave one behind, so roll old ones off cheaply.
        val directory = File(context.filesDir, "root-snapshots")
        directory.mkdirs()
        val now = System.currentTimeMillis()
        directory.listFiles()?.forEach { file ->
            if (now - file.lastModified() > SNAPSHOT_TTL_MILLIS) file.delete()
        }
        return File(directory, "snap-${UUID.randomUUID()}-${File(path).name}")
    }

    private fun rootShell(timeoutSeconds: Int) =
        Shell.Builder.create().setContext(context).setTimeout(timeoutSeconds.toLong()).build("su", "-M", "-c", "/system/bin/sh")

    /** Shell timeout in seconds: a 15 s floor, scaled by bytes copied so large snapshots finish. */
    private fun timeoutSeconds(bytes: Long): Int =
        (MIN_TIMEOUT_SECONDS + bytes / BYTES_PER_EXTRA_TIMEOUT_SECOND)
            .coerceAtMost(MAX_TIMEOUT_SECONDS.toLong())
            .toInt()

    private companion object {
        const val MIN_TIMEOUT_SECONDS = 15
        const val MAX_TIMEOUT_SECONDS = 180
        const val BYTES_PER_EXTRA_TIMEOUT_SECOND = 2L * 1024 * 1024
        const val MAX_INLINE_BYTES = 256L * 1024
        const val STABILITY_ATTEMPTS = 3
        const val SNAPSHOT_TTL_MILLIS = 60L * 60 * 1000
    }
}
