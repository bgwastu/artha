package net.wastu.artha.data.analysis

/** A ready-made OpenAI-compatible endpoint, so the user only pastes a key. [catalogId] is the provider's id on models.dev (prices and status only). */
data class AiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val catalogId: String,
)

object AiProviders {
    /** Selection id for "enter the URL yourself". */
    const val CUSTOM_ID = "custom"

    val presets = listOf(
        AiProvider(
            id = "openai", name = "OpenAI",
            baseUrl = "https://api.openai.com/v1", catalogId = "openai",
        ),
        AiProvider(
            id = "gemini", name = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
            catalogId = "google",
        ),
        AiProvider(
            id = "groq", name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1", catalogId = "groq",
        ),
        AiProvider(
            id = "openrouter", name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1", catalogId = "openrouter",
        ),
    )

    private fun normalized(url: String) = url.trim().trimEnd('/')

    fun match(baseUrl: String): AiProvider? =
        presets.firstOrNull { normalized(it.baseUrl) == normalized(baseUrl) }

    /** Which provider the saved settings belong to: a preset id, [CUSTOM_ID], or null when nothing is set up yet. */
    fun selectionFor(saved: AiConfig): String? = when {
        saved.baseUrl.isBlank() -> null
        else -> match(saved.baseUrl)?.id ?: CUSTOM_ID
    }

    /**
     * The fields to show after the user picks [provider] (null means Custom). Saved values come back only when they
     * belong to that provider, so an API key is never carried over to a different host.
     */
    fun draftFor(provider: AiProvider?, saved: AiConfig): AiConfig {
        val selection = provider?.id ?: CUSTOM_ID
        return when {
            selectionFor(saved) == selection -> saved
            provider == null -> AiConfig()
            else -> AiConfig(baseUrl = provider.baseUrl)
        }
    }
}
