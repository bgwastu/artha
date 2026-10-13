package net.wastu.artha.data.sync

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class DriveBackupFile(
    val id: String,
    val name: String,
    val modifiedTime: String,
    val sizeBytes: Long,
    val md5Checksum: String,
)

class DriveUnauthorizedException(message: String = "Google Drive OAuth token expired or unauthorized.") : Exception(message)

class DriveApiException(message: String, val httpCode: Int = -1, cause: Throwable? = null) : Exception(message, cause)

object GoogleDriveAppDataClient {
    const val BACKUP_FILE_NAME = "artha_sync_v1.enc"
    const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    private const val FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,modifiedTime,size,md5Checksum"

    fun listBackups(accessToken: String): List<DriveBackupFile> {
        val result = mutableListOf<DriveBackupFile>()
        var pageToken: String? = null
        val seenTokens = mutableSetOf<String>()
        do {
            val query = URLEncoder.encode("name = '$BACKUP_FILE_NAME' and trashed = false", "UTF-8")
            val fields = URLEncoder.encode("nextPageToken,files(id,name,modifiedTime,size,md5Checksum)", "UTF-8")
            val page = pageToken?.let { "&pageToken=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
            val url = URL("$FILES_ENDPOINT?spaces=appDataFolder&q=$query&orderBy=modifiedTime%20desc&fields=$fields&pageSize=1000$page")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code == 401) throw DriveUnauthorizedException()
                if (code !in 200..299) throw DriveApiException("Drive list failed (HTTP $code)", code)
                val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                val files = json.getJSONArray("files")
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    result += DriveBackupFile(f.getString("id"), f.getString("name"),
                        f.getString("modifiedTime"), f.optLong("size"), f.optString("md5Checksum"))
                }
                pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
                if (pageToken != null) check(seenTokens.add(pageToken)) { "Drive returned a repeated page token." }
            } finally {
                conn.disconnect()
            }
        } while (pageToken != null)
        return result
    }

    fun downloadBackup(accessToken: String, fileId: String): ByteArray {
        val url = URL("$FILES_ENDPOINT/$fileId?alt=media")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        return try {
            val code = conn.responseCode
            if (code == 401) throw DriveUnauthorizedException()
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                throw DriveApiException("Drive download failed (HTTP $code): ${err.take(180)}", code)
            }
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    fun uploadBackup(accessToken: String, encryptedBytes: ByteArray): DriveBackupFile {
        val boundary = "artha_sync_boundary_${System.currentTimeMillis()}"
        val metadataJson = JSONObject()
            .put("name", BACKUP_FILE_NAME)
            .put("parents", JSONArray().put("appDataFolder"))
            .toString()

        val multipartBody = ByteArrayOutputStream().apply {
            write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            write("Content-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray(Charsets.UTF_8))
            write(metadataJson.toByteArray(Charsets.UTF_8))
            write("\r\n--$boundary\r\n".toByteArray(Charsets.UTF_8))
            write("Content-Type: application/octet-stream\r\n\r\n".toByteArray(Charsets.UTF_8))
            write(encryptedBytes)
            write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        }.toByteArray()

        val url = URL(UPLOAD_ENDPOINT)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 45_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            setRequestProperty("Content-Length", multipartBody.size.toString())
        }

        return try {
            conn.outputStream.use { it.write(multipartBody) }
            val code = conn.responseCode
            if (code == 401) throw DriveUnauthorizedException()
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                throw DriveApiException("Drive upload failed (HTTP $code): ${err.take(180)}", code)
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            val uploaded = DriveBackupFile(
                id = json.optString("id"),
                name = json.optString("name", BACKUP_FILE_NAME),
                modifiedTime = json.optString("modifiedTime", ""),
                sizeBytes = json.optLong("size", encryptedBytes.size.toLong()),
                md5Checksum = json.optString("md5Checksum", ""),
            )

            // Verify MD5 integrity against what we sent
            val expectedMd5 = md5Hex(encryptedBytes)
            if (uploaded.md5Checksum.isNotBlank() && !uploaded.md5Checksum.equals(expectedMd5, ignoreCase = true)) {
                runCatching { deleteFile(accessToken, uploaded.id) }
                throw DriveApiException("Upload integrity check failed (MD5 mismatch).")
            }

            uploaded
        } finally {
            conn.disconnect()
        }
    }

    fun deleteFile(accessToken: String, fileId: String) {
        val url = URL("$FILES_ENDPOINT/$fileId")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            val code = conn.responseCode
            if (code == 401) throw DriveUnauthorizedException()
        } finally {
            conn.disconnect()
        }
    }

    private fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
