package net.wastu.artha.data.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProvidersTest {
    private val groq = AiProviders.presets.first { it.id == "groq" }
    private val gemini = AiProviders.presets.first { it.id == "gemini" }

    @Test fun everyPresetIsAValidEndpointOnceAKeyIsPasted() {
        AiProviders.presets.forEach { provider ->
            val draft = AiProviders.draftFor(provider, AiConfig()).copy(apiKey = "key", model = "m")
            assertNull(provider.id, aiConfigValidation(draft))
            assertTrue(provider.id, AiClient.endpoint(draft.baseUrl).endsWith("/chat/completions"))
        }
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            AiClient.endpoint(gemini.baseUrl),
        )
        assertEquals("https://api.groq.com/openai/v1/chat/completions", AiClient.endpoint(groq.baseUrl))
    }

    @Test fun presetIdsAndUrlsAreUnique() {
        assertEquals(AiProviders.presets.size, AiProviders.presets.map { it.id }.toSet().size)
        assertEquals(AiProviders.presets.size, AiProviders.presets.map { it.baseUrl.trimEnd('/') }.toSet().size)
    }

    @Test fun savedSettingsAreMatchedIgnoringTrailingSlash() {
        assertEquals(groq, AiProviders.match("https://api.groq.com/openai/v1/"))
        assertEquals(gemini, AiProviders.match(" https://generativelanguage.googleapis.com/v1beta/openai "))
        assertNull(AiProviders.match("https://example.com/v1"))
    }

    @Test fun selectionFollowsTheSavedEndpoint() {
        assertNull(AiProviders.selectionFor(AiConfig()))
        assertEquals("groq", AiProviders.selectionFor(AiConfig(baseUrl = groq.baseUrl)))
        assertEquals(AiProviders.CUSTOM_ID, AiProviders.selectionFor(AiConfig(baseUrl = "http://localhost:11434/v1")))
    }

    @Test fun pickingAPresetFillsOnlyTheUrl() {
        assertEquals(AiConfig(groq.baseUrl), AiProviders.draftFor(groq, AiConfig()))
    }

    @Test fun savedKeyComesBackOnlyForItsOwnProvider() {
        val saved = AiConfig(groq.baseUrl, "secret", "openai/gpt-oss-20b")
        assertEquals(saved, AiProviders.draftFor(groq, saved))
        assertEquals("", AiProviders.draftFor(gemini, saved).apiKey)
        assertEquals("", AiProviders.draftFor(null, saved).apiKey)
    }

    @Test fun customKeepsItsOwnSettingsAndNeverInheritsAPresetKey() {
        val custom = AiConfig("http://localhost:11434/v1", "ollama", "qwen")
        assertEquals(custom, AiProviders.draftFor(null, custom))
        assertEquals(AiConfig(groq.baseUrl), AiProviders.draftFor(groq, custom))
    }

    @Test fun modelListSitsNextToTheChatEndpoint() {
        assertEquals("https://api.groq.com/openai/v1/models", AiClient.modelsEndpoint(groq.baseUrl))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/models", AiClient.modelsEndpoint(gemini.baseUrl))
        assertEquals("https://example.com/v1/models", AiClient.modelsEndpoint("https://example.com/v1/chat/completions"))
        assertEquals("https://example.com/v1/models", AiClient.modelsEndpoint("https://example.com"))
    }

    @Test fun modelListKeepsChatModelsSortedAndUnprefixed() {
        val reply = """{"data":[
            {"id":"models/gemini-3.8-flash"},{"id":"models/gemini-embedding-2"},{"id":"openai/gpt-oss-120b"},
            {"id":"whisper-large-v3"},{"id":"openai/gpt-oss-120b"},{"id":""},{"object":"model"},{"id":"gemini-3.5-flash-lite"}]}"""
        assertEquals(
            listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "openai/gpt-oss-120b"),
            AiClient.parseModelIds(reply),
        )
        assertEquals(emptyList<String>(), AiClient.parseModelIds("{}"))
    }

    @Test fun providerErrorTextIsKeptShortAndOnOneLine() {
        assertEquals("Request too large", AiClient.errorDetail("""{"error":{"message":"Request   too\nlarge","code":"x"}}"""))
        assertEquals("quota", AiClient.errorDetail("""{"error":"quota"}"""))
        assertEquals(160, AiClient.errorDetail("""{"error":{"message":"${"a".repeat(500)}"}}""")!!.length)
        assertNull(AiClient.errorDetail("<html>Bad gateway</html>"))
        assertNull(AiClient.errorDetail(null))
        assertNull(AiClient.errorDetail("""{"error":{}}"""))
    }
}
