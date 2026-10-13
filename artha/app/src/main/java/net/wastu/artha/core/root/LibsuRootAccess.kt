package net.wastu.artha.core.root

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LibsuRootAccess(private val context: Context) : RootAccess {
    private val mutex = Mutex()

    override suspend fun verify(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                // A new su process detects revocation even if libsu's main shell was cached.
                Shell.Builder.create().setContext(context).setTimeout(10).build("su").use { shell ->
                    if (!shell.isRoot) return@use false
                    // newJob discards output unless an explicit destination is provided.
                    val result = shell.newJob().to(mutableListOf<String>()).add("id -u").exec()
                    result.isSuccess && result.out.singleOrNull()?.trim() == "0"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
    }
}
