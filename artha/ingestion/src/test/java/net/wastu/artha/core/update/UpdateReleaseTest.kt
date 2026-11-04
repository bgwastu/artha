package net.wastu.artha.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateReleaseTest {

    private val validSha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun parsesValidRelease() {
        val json = """
            {
                "versionCode": 87,
                "versionName": "0.1.87",
                "apps": {
                    "net.wastu.artha": {
                        "url": "https://github.com/bgwastu/artha/releases/download/v0.1.87/Artha-0.1.87.apk",
                        "sha256": "$validSha",
                        "size": 12345678
                    },
                    "net.wastu.artha.bridge": {
                        "url": "https://github.com/bgwastu/artha/releases/download/v0.1.87/Artha-Bridge-0.1.87.apk",
                        "sha256": "$validSha",
                        "size": 8765432
                    }
                }
            }
        """.trimIndent()

        val release = UpdateRelease.parse(json)
        assertEquals(87L, release.versionCode)
        assertEquals("0.1.87", release.versionName)
        assertEquals(2, release.assets.size)

        val app = release.assets["net.wastu.artha"]
        assertNotNull(app)
        assertEquals(12345678L, app?.size)
        assertEquals(validSha, app?.sha256)

        val bridge = release.assets["net.wastu.artha.bridge"]
        assertNotNull(bridge)
        assertEquals(8765432L, bridge?.size)
    }

    @Test
    fun rejectsNonGitHubUrl() {
        val json = """
            {
                "versionCode": 87,
                "versionName": "0.1.87",
                "apps": {
                    "net.wastu.artha": {
                        "url": "https://evil.example.com/download/v0.1.87/Artha-0.1.87.apk",
                        "sha256": "$validSha",
                        "size": 12345
                    }
                }
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            UpdateRelease.parse(json)
        }
    }

    @Test
    fun rejectsInvalidChecksum() {
        val json = """
            {
                "versionCode": 87,
                "versionName": "0.1.87",
                "apps": {
                    "net.wastu.artha": {
                        "url": "https://github.com/bgwastu/artha/releases/download/v0.1.87/Artha-0.1.87.apk",
                        "sha256": "not-a-valid-sha256",
                        "size": 12345
                    }
                }
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            UpdateRelease.parse(json)
        }
    }

    @Test
    fun rejectsZeroSize() {
        val json = """
            {
                "versionCode": 87,
                "versionName": "0.1.87",
                "apps": {
                    "net.wastu.artha": {
                        "url": "https://github.com/bgwastu/artha/releases/download/v0.1.87/Artha-0.1.87.apk",
                        "sha256": "$validSha",
                        "size": 0
                    }
                }
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            UpdateRelease.parse(json)
        }
    }
}
