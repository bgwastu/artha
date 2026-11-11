package net.wastu.artha.data.analysis

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    private val file = """
        {"skipped":{"id":"skipped","models":{"x":{"cost":{"input":9}}},"note":"a } and \" and { inside a string"},
         "groq":{"id":"groq","models":{
           "openai/gpt-oss-120b":{"cost":{"input":0.15,"output":0.6},"limit":{"context":131072,"output":8192},"structured_output":true},
           "old-model":{"cost":{"input":0.05,"output":0.08},"limit":{"context":131072},"structured_output":true,"status":"deprecated"},
           "tiny":{"cost":{"input":0.01,"output":0.01},"limit":{"context":8192},"structured_output":true},
           "whisper":{"limit":{"context":0}}}},
         "openrouter":{"id":"openrouter","models":{"vendor/free:free":{"cost":{"input":0,"output":0},"limit":{"context":200000},"structured_output":true}}},
         "list":[{"groq":{"id":"nested key is not a provider"}}]}
    """.trimIndent()
    private val wanted = setOf("groq", "openrouter", "google")

    private fun catalog() = ModelCatalog.parse(ModelCatalog.extractProviders(StringReader(file), wanted), wanted)

    @Test fun onlyWantedProvidersAreExtracted() {
        val found = ModelCatalog.extractProviders(StringReader(file), wanted)
        assertEquals(setOf("groq", "openrouter"), found.keys)
        assertTrue(found.getValue("groq").startsWith("{\"id\":\"groq\"") && found.getValue("groq").endsWith("}}}"))
    }

    @Test fun scanningSurvivesTinyReads() {
        val slow = object : java.io.Reader() {
            val source = StringReader(file)
            override fun read(buffer: CharArray, offset: Int, length: Int) = source.read(buffer, offset, minOf(length, 3))
            override fun close() = source.close()
        }
        assertEquals(setOf("groq", "openrouter"), ModelCatalog.extractProviders(slow, wanted).keys)
    }

    @Test fun modelsKeepPriceContextAndStatusAndMissingProvidersAreEmpty() {
        val parsed = catalog()
        assertEquals(ModelInfo(0.15, 0.6, 131072, true, false), parsed.getValue("groq").getValue("openai/gpt-oss-120b"))
        assertTrue(parsed.getValue("groq").getValue("old-model").deprecated)
        assertNull(parsed.getValue("groq").getValue("whisper").inputPerM)
        assertTrue(parsed.getValue("openrouter").getValue("vendor/free:free").free)
        assertEquals(emptyMap<String, ModelInfo>(), parsed.getValue("google"))
    }

    @Test fun cacheRoundTrips() {
        val saved = ModelCatalog.Saved("\"abc\"", 42L, catalog())
        val back = ModelCatalog.decode(ModelCatalog.encode(saved))
        assertEquals("\"abc\"", back.etag)
        assertEquals(42L, back.at)
        assertEquals(saved.models, back.models)
        assertNull(ModelCatalog.decode(ModelCatalog.encode(ModelCatalog.Saved(null, 1L, emptyMap()))).etag)
    }

    @Test fun deprecatedModelsAreHiddenButUnknownOnesStay() {
        val groq = catalog().getValue("groq")
        assertEquals(listOf("openai/gpt-oss-120b", "brand-new"), ModelCatalog.visible(listOf("openai/gpt-oss-120b", "old-model", "brand-new"), groq))
    }

    @Test fun priceLabels() {
        assertEquals("$0.15 in · $0.60 out per 1M tokens", ModelCatalog.priceLabel(ModelInfo(0.15, 0.6, null, true, false)))
        assertEquals("$0.20 in · $1.20 out per 1M tokens", ModelCatalog.priceLabel(ModelInfo(0.2, 1.2, null, true, false)))
        assertEquals("$3.00 in · $15.00 out per 1M tokens", ModelCatalog.priceLabel(ModelInfo(3.0, 15.0, null, true, false)))
        assertEquals("$0.075 in · $0.30 out per 1M tokens", ModelCatalog.priceLabel(ModelInfo(0.075, 0.3, null, true, false)))
        assertEquals("Free", ModelCatalog.priceLabel(ModelInfo(0.0, 0.0, null, true, false)))
        assertNull(ModelCatalog.priceLabel(ModelInfo(null, 1.0, null, true, false)))
        assertNull(ModelCatalog.priceLabel(null))
    }
}
