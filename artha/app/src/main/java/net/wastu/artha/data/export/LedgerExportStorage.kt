package net.wastu.artha.data.export

import android.content.Context
import android.net.Uri
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object LedgerExportStorage {
    private val nameFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.of("UTC"))

    fun fileName(exportedAt: Instant): String = "artha-export-${nameFormat.format(exportedAt)}.jsonl"

    fun writeToUri(context: Context, uri: Uri, body: String) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(body.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Unable to write the export file.")
    }
}
