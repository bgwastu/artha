package net.wastu.artha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        sections.forEach { section ->
            Column(Modifier.semantics { collectionInfo = CollectionInfo(section.items.size, 1) }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(section.title ?: "Findings", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
                section.items.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { collectionItemInfo = CollectionItemInfo(index, 1, 0, 1) }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.widthIn(min = 20.dp).alignByBaseline())
                        Text(parseInlineMarkdown(item), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).alignByBaseline())
                    }
                }
            }
        }
    }
}
