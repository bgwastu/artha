package net.wastu.artha.data.ingestion

import java.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import net.wastu.artha.data.providers.AccountStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TokopediaProviderContractTest {
    private fun envelope(more: Boolean = false, cursor: Any? = null): JSONObject = JSONObject()
        .put("code", 0).put("data", JSONObject()
            .put("facade", JSONObject().put("masonry", org.json.JSONArray()))
            .put("global", JSONObject().put("has_more", more).apply { if (cursor != null) put("next_cursor", cursor) }))

    @Test fun verificationChallengeIsNotReportedAsExpiredLoginOrSchemaFailure() {
        val error = assertThrows(ProviderReadException::class.java) {
            TokopediaProviderParser.requireResponse(200, null, "fictional-check", "fictional-private-parameters")
        }
        assertEquals(AccountStatus.ERROR, error.status)
        assertTrue(error.message!!.contains("verification challenge"))
        assertFalse(error.message!!.contains("fictional-private-parameters"))
        assertFalse(error.message!!.contains("fictional-check"))
    }

    @Test fun documentedServerLoginHeaderAndHttp401RequireLogin() {
        for ((code, state) in listOf(200 to "0", 401 to null)) {
            assertEquals(AccountStatus.NEEDS_LOGIN, assertThrows(ProviderReadException::class.java) {
                TokopediaProviderParser.requireResponse(code, state, null, null)
            }.status)
        }
        // A bare forbidden response does not establish token expiry.
        assertEquals(AccountStatus.ERROR, assertThrows(ProviderReadException::class.java) {
            TokopediaProviderParser.requireResponse(403, null, null, null)
        }.status)
    }

    @Test fun providerRejectionIsCheckedBeforeMissingOrderSchema() {
        val error = assertThrows(ProviderReadException::class.java) {
            TokopediaProviderParser.page(JSONObject("""{"code":10000}"""))
        }
        assertEquals(AccountStatus.ERROR, error.status)
        assertTrue(error.message!!.contains("provider code 10000"))
        assertFalse(error.message!!.contains("schema"))
    }

    @Test fun missingSuccessCodeAndOldPlaceholderArrayAreUnsupported() {
        for (json in listOf("{}", """{"data":{"order_list":[]}}""", """{"code":0,"data":{"order_list":[]}}""")) {
            assertEquals(AccountStatus.ERROR, assertThrows(ProviderReadException::class.java) {
                TokopediaProviderParser.page(JSONObject(json))
            }.status)
        }
    }

    @Test fun sourceCursorIsPreservedAndMissingOrMalformedPaginationFails() {
        for (cursor in listOf<Any>(23L, "fictional-cursor")) {
            val page = TokopediaProviderParser.page(envelope(true, cursor))
            assertTrue(page.hasMore)
            assertEquals(cursor, page.nextCursor)
            val request = TokopediaProviderParser.request(page.nextCursor)
            assertEquals("load_more_data", request.getString("request_type"))
            assertEquals(cursor, request.get("next_cursor"))
        }
        for (cursor in listOf(null, JSONObject(), "", 1.5)) {
            assertThrows(ProviderReadException::class.java) { TokopediaProviderParser.page(envelope(true, cursor)) }
        }
        val malformed = envelope().apply { getJSONObject("data").getJSONObject("global").put("has_more", "false") }
        assertThrows(ProviderReadException::class.java) { TokopediaProviderParser.page(malformed) }
    }

    @Test fun initialRequestUsesAuditedLynxContractAndExplicitEmptyPageIsAllowed() {
        val request = TokopediaProviderParser.request()
        assertEquals("2", request.getString("fe_version"))
        assertEquals(10, request.getInt("page_size"))
        assertEquals("init_data", request.getString("request_type"))
        assertFalse(request.has("next_cursor"))
        val page = TokopediaProviderParser.page(envelope())
        assertFalse(page.hasMore)
        TokopediaProviderParser.requireVerifiedPayments(page)
    }

    @Test fun presentationCardsNeverInventPaymentsOrRefundCredits() {
        // Fictional placeholder fields are intentionally NOT an accepted contract.
        for (status in listOf("paid", "unpaid", "cancelled", "refund_pending", "refunded")) {
            val response = envelope()
            response.getJSONObject("data").getJSONObject("facade").getJSONArray("masonry").put(
                JSONObject().put("order_id", "fictional-order").put("amount_minor", 123_400)
                    .put("currency", "IDR").put("payment_time", "2026-01-02T03:04:05Z").put("status", status))
            assertThrows(ProviderReadException::class.java) {
                TokopediaProviderParser.requireVerifiedPayments(TokopediaProviderParser.page(response))
            }
        }
    }

    private fun encrypted(value: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE,
            SecretKeySpec("ggggtttttujkrrrr".toByteArray(), "AES"),
            IvParameterSpec("tokopedia1234567".toByteArray()))
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray()))
    }

    @Test fun jweIsKeptOpaqueAndAmbiguousTokensFailWithoutExposingThem() {
        val token = "eyJmaWN0aW9uYWwiOiJzdG9yZSJ9..ZmFrZS1pdg.ZmFrZS1jaXBoZXI.ZmFrZS10YWc"
        val bytes = ("ACCESS_TOKEN_v2\u0000" + encrypted(token)).toByteArray()
        assertEquals(token, TokopediaProviderParser.sessionToken(bytes))
        val duplicate = bytes + ("\u0000" + encrypted(token + "x")).toByteArray()
        val error = assertThrows(ProviderReadException::class.java) { TokopediaProviderParser.sessionToken(duplicate) }
        assertEquals(AccountStatus.ERROR, error.status)
        assertFalse(error.message!!.contains(token))
        assertEquals(AccountStatus.NEEDS_LOGIN, assertThrows(ProviderReadException::class.java) {
            TokopediaProviderParser.sessionToken(null)
        }.status)
    }

    @Test fun sourceDeviceSessionMustBeUnambiguousAndHeaderSafe() {
        val bytes = ("gcm_id_v2\u0000" + encrypted("fictional-native-session")).toByteArray()
        assertEquals("fictional-native-session", TokopediaProviderParser.sessionId(bytes))
        assertThrows(ProviderReadException::class.java) {
            TokopediaProviderParser.sessionId(("gcm_id_v2\u0000" + encrypted("fictional\r\nheader")).toByteArray())
        }
        assertThrows(ProviderReadException::class.java) { TokopediaProviderParser.sessionId(null) }
    }

    private fun cookie(
        name: String, value: String, domain: String? = ".tiktokv.com", path: String? = "/",
        ports: String? = null, maxAge: Long = -1, createdMillis: Long = 1_000,
        referenceComment: Boolean = false
    ): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            fun string(value: String?) {
                if (value == null) it.writeByte(0x70) else { it.writeByte(0x74); it.writeUTF(value) }
            }
            it.writeInt(0xaced0005.toInt()); it.writeByte(0x73); it.writeByte(0x72)
            it.writeUTF("X.Um1"); it.writeLong(6374381323722046732L)
            it.writeByte(3); it.writeShort(1); it.writeByte('J'.code); it.writeUTF("LLFFF")
            it.writeByte(0x78); it.writeByte(0x70)
            string(name); string(value)
            if (referenceComment) { it.writeByte(0x71); it.writeInt(0x7e0002) } else string(null)
            string(null); string(domain)
            it.writeByte(0x77); it.writeByte(8); it.writeLong(maxAge)
            string(path); string(ports)
            it.writeByte(0x77); it.writeByte(15); it.writeInt(0)
            it.writeBoolean(true); it.writeBoolean(false); it.writeBoolean(true); it.writeLong(createdMillis)
            it.writeByte(0x78)
        }
        return bytes.toByteArray().joinToString("") { "%02X".format(it.toInt() and 255) }
    }

    private fun cookieXml(hex: String): ByteArray =
        """<map><string name="http://tiktokv.com/|msToken">$hex</string></map>""".toByteArray()

    @Test fun nativeCookieScopeAndExpiryFollowAuditedSourceFields() {
        fun decode(domain: String? = ".tiktokv.com", path: String? = "/", ports: String? = null,
                   maxAge: Long = -1, now: Long = 3_000): String = TokopediaProviderParser.nativeCookies(
            cookieXml(cookie("msToken", "fictional-token", domain, path, ports, maxAge)), now)
        assertEquals("msToken=fictional-token", decode(domain = "OEC-API.TIKTOKV.COM", path = "/api", ports = "443"))
        for (domain in listOf(null, ".com", ".unrelated.invalid", "tiktokv.com.unrelated.invalid")) {
            assertEquals("", decode(domain = domain))
        }
        assertEquals("", decode(path = "/api/v1/trade/bff/order_list/g"))
        assertEquals("", decode(path = "/another"))
        assertEquals("", decode(ports = "80"))
        assertEquals("msToken=fictional-token", decode(maxAge = 1, now = 2_999))
        assertEquals("", decode(maxAge = 1, now = 3_000))
        assertEquals("msToken=fictional-token", decode(maxAge = -1, now = Long.MAX_VALUE))
    }

    @Test fun fullCookieLayoutIsRequiredAndStringReferencesRemainBounded() {
        val valid = cookie("msToken", "fictional-token", referenceComment = true)
        assertEquals("msToken=fictional-token", TokopediaProviderParser.nativeCookies(cookieXml(valid)))
        for (hex in listOf(valid.dropLast(2), valid + "00", valid.replace("007E0002", "007E0000"))) {
            val error = assertThrows(ProviderReadException::class.java) {
                TokopediaProviderParser.nativeCookies(cookieXml(hex))
            }
            assertEquals(AccountStatus.ERROR, error.status)
            assertFalse(error.message!!.contains("fictional-token"))
        }
    }

    @Test fun nativeCookieCodecIsScopedToHistoryHostAndRejectsHeaderInjection() {
        val xml = """<map><string name="http://tiktokv.com/|msToken">${cookie("msToken", "fictional-token")}</string><string name="http://unrelated.invalid/|other">not-a-cookie</string></map>"""
        assertEquals("msToken=fictional-token", TokopediaProviderParser.nativeCookies(xml.toByteArray()))
        assertEquals("", TokopediaProviderParser.nativeCookies(null))
        val bad = """<map><string name="http://tiktokv.com/|msToken">${cookie("msToken", "fictional\r\nHeader")}</string></map>"""
        assertThrows(ProviderReadException::class.java) { TokopediaProviderParser.nativeCookies(bad.toByteArray()) }
        assertThrows(ProviderReadException::class.java) {
            TokopediaProviderParser.nativeCookies("<!DOCTYPE map [<!ENTITY x SYSTEM 'file:///fictional'>]><map/>".toByteArray())
        }
    }
}
