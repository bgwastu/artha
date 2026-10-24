package net.wastu.artha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal data class AnalysisSection(val title: String?, val items: List<String>)

/** Persisted Markdown stays compatible; displayed findings always form actual list rows. */
internal fun analysisSections(text: String): List<AnalysisSection> {
    val sections = mutableListOf<AnalysisSection>()
    var title: String? = null
    val items = mutableListOf<String>()
    fun finish() { if (items.isNotEmpty()) sections += AnalysisSection(title, items.toList()); items.clear() }
    for (block in parseMarkdownBlocks(text)) {
        when (block) {
            is MarkdownBlock.Heading -> { finish(); title = block.text }
            is MarkdownBlock.Paragraph -> if (block.text.trimEnd(':').equals("recommendations", true)) {
                finish(); title = "Recommendations"
            } else items += block.text
            is MarkdownBlock.BulletItem -> items += block.text
            is MarkdownBlock.NumberedItem -> items += block.text
            else -> Unit
        }
    }
    finish()
    return sections
}

@Composable
internal fun AnalysisListContent(text: String) {
    val sections = remember(text) { analysisSections(text) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sections.forEach { section ->
            if (section.title != null) {
                val displayTitle = if (section.title.equals("Recommendations", ignoreCase = true)) "Next steps" else section.title
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            section.items.forEach { item ->
                Text(
                    text = parseInlineMarkdown(item),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
