package net.wastu.artha.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the capture field policy: readers need the envelope and response body, never secrets. */
class CaptureRedactionTest {

    @Test fun credentialHeaderValuesAreRedacted() {
        for (name in listOf("Authorization", "Cookie", "set-cookie", "X-Auth-Token", "x-mts-ssid", "Proxy-Authorization")) {
            assertEquals(name, CaptureRedaction.REDACTED, CaptureRedaction.headerValue(name, "secret-value"))
        }
    }

    @Test fun nonCredentialMetadataSurvives() {
        assertEquals("gzip", CaptureRedaction.headerValue("Content-Encoding", "gzip"))
        assertEquals("application/json; charset=utf-8", CaptureRedaction.headerValue("content-type", "application/json; charset=utf-8"))
        assertEquals("1024", CaptureRedaction.headerValue("Content-Length", "1024"))
        assertTrue(CaptureRedaction.safeHeader("Content-Type"))
        assertFalse(CaptureRedaction.safeHeader("Authorization"))
    }

    @Test fun unknownHeaderNamesKeepTheirNameButNotTheirValue() {
        assertEquals(CaptureRedaction.REDACTED, CaptureRedaction.headerValue("x-some-new-credential", "leak"))
    }

    @Test fun tokenQueryValuesAreRedactedAndThePathSurvives() {
        val url = CaptureRedaction.url(
            "https://customer.gopayapi.com/v3/users/order-history?page=2&access_token=abc.def&upper_bound=99")
        assertTrue(url.contains("customer.gopayapi.com/v3/users/order-history"))
        assertFalse(url.contains("abc.def"))
        assertTrue(url.contains("page=2"))
        assertTrue(url.contains("upper_bound=99"))
        assertTrue(url.contains("access_token=${CaptureRedaction.REDACTED}"))
    }

    @Test fun urlsWithoutQueryAreUnchanged() {
        val url = "https://customer.gopayapi.com/v3/users/order-history"
        assertEquals(url, CaptureRedaction.url(url))
        assertEquals("", CaptureRedaction.url(null))
    }
}
