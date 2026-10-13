package net.wastu.artha.data.ingestion

import android.content.Context
import android.util.Base64
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only fixed provider paths are accepted. No keys or file contents are logged. */
@android.annotation.SuppressLint("SdCardPath") // These are other apps' root-only sandbox paths.
class RootFileReader(internal val context: Context) {
    fun packageFirstInstallTime(packageName: String): Long =
        runCatching { context.packageManager.getPackageInfo(packageName, 0).firstInstallTime }.getOrDefault(0L)
    suspend fun read(path: String): ByteArray = withContext(Dispatchers.IO) {
        require(path.matches(Regex("/data/(data|system_ce|user)/[a-zA-Z0-9_./-]+")))
        // Android app data isolation hides other sandboxes in inherited namespaces.
        Shell.Builder.create().setContext(context).setTimeout(15).build("su", "-M", "-c", "/system/bin/sh").use { shell ->
            check(shell.isRoot) { "Root access unavailable" }
            // base64 transports binary Hive files without line/character corruption.
            val result = shell.newJob().to(mutableListOf<String>(), mutableListOf<String>()).add("base64 '$path'").exec()
            check(result.isSuccess) { "Provider file unavailable" }
            Base64.decode(result.out.joinToString(""), Base64.DEFAULT)
        }
    }

    suspend fun list(dir: String): List<String> = withContext(Dispatchers.IO) {
        require(dir.matches(Regex("/data/(data|system_ce|user)/[a-zA-Z0-9_./-]+")))
        Shell.Builder.create().setContext(context).setTimeout(15).build("su", "-M", "-c", "/system/bin/sh").use { shell ->
            check(shell.isRoot) { "Root access unavailable" }
            val result = shell.newJob().to(mutableListOf<String>(), mutableListOf<String>()).add("ls '$dir'").exec()
            if (result.isSuccess) result.out else emptyList()
        }
    }
}
