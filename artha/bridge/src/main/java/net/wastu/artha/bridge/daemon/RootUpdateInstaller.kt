package net.wastu.artha.bridge.daemon

import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.IOException
import net.wastu.artha.core.update.UpdateInstaller

/**
 * Installs updates with root, which never asks for confirmation; the bridge phone sits
 * in a drawer with nobody to tap Install. The APK is streamed to `pm`, so it does not
 * need to be readable outside this app. Android replaces and restarts the bridge, and
 * [BootReceiver] brings the daemon back.
 */
class RootUpdateInstaller : UpdateInstaller {
    override suspend fun install(apk: File) {
        val result = Shell.cmd("cat '${apk.absolutePath}' | pm install -r -S ${apk.length()}").exec()
        val output = (result.out + result.err).joinToString(" ").trim()
        if (!result.isSuccess || "Success" !in output) throw IOException("Install failed: ${output.ifBlank { "no root access" }}")
    }
}
