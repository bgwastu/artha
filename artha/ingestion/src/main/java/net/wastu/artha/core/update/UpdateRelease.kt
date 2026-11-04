package net.wastu.artha.core.update

import org.json.JSONObject

/** One app's APK in a release, as listed in the release's `update.json`. */
data class UpdateAsset(val url: String, val sha256: String, val size: Long)

/**
 * A published release of Artha and Artha Bridge. CI attaches `update.json` to every
 * GitHub release; both apps read it from the latest release to learn what to install.
 */
data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val assets: Map<String, UpdateAsset>,
) {
    companion object {
        /** Where both apps look for the newest release. Served as a download, so it has no API rate limit. */
        const val MANIFEST_URL = "https://github.com/bgwastu/artha/releases/latest/download/update.json"

        /** APKs are only ever fetched from this repository's releases. */
        const val DOWNLOAD_PREFIX = "https://github.com/bgwastu/artha/releases/download/"

        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /** Parses `update.json`, rejecting anything that does not point at this repository's releases. */
        fun parse(json: String): UpdateRelease {
            val root = JSONObject(json)
            val apps = root.getJSONObject("apps")
            val assets = apps.keys().asSequence().associateWith { pkg ->
                val app = apps.getJSONObject(pkg)
                UpdateAsset(app.getString("url"), app.getString("sha256").lowercase(), app.getLong("size")).also {
                    require(it.url.startsWith(DOWNLOAD_PREFIX)) { "Update for $pkg is not hosted on Artha's releases" }
                    require(SHA256.matches(it.sha256)) { "Update for $pkg has no valid checksum" }
                    require(it.size > 0) { "Update for $pkg has no size" }
                }
            }
            return UpdateRelease(root.getLong("versionCode"), root.getString("versionName"), assets).also {
                require(it.versionCode > 0) { "Update has no version code" }
            }
        }
    }
}
