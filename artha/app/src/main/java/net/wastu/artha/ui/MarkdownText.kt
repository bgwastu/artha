package net.wastu.artha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class BulletItem(val indentLevel: Int, val text: String) : MarkdownBlock
    data class NumberedItem(val number: String, val text: String) : MarkdownBlock
    data class Blockquote(val text: String) : MarkdownBlock
    data class CodeBlock(val code: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data object Divider : MarkdownBlock
}

fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    if (markdown.isBlank()) return emptyList()
    val lines = markdown.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0
    while (i < lines.size) {
        val rawLine = lines[i]
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // Code block ```
        if (trimmed.startsWith("```")) {
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MarkdownBlock.CodeBlock(codeLines.joinToString("\n")))
            i++ // skip closing ```
            continue
        }

        // Horizontal divider
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            blocks.add(MarkdownBlock.Divider)
            i++
            continue
        }

        // Headings
        val headingMatch = HEADING_REGEX.matchEntire(trimmed)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length.coerceIn(1, 4)
            val content = headingMatch.groupValues[2].trim()
            blocks.add(MarkdownBlock.Heading(level, content))
            i++
            continue
        }

        // Blockquote
        if (trimmed.startsWith(">")) {
            val quoteText = trimmed.removePrefix(">").trim()
            if (quoteText.isNotEmpty()) {
                blocks.add(MarkdownBlock.Blockquote(quoteText))
            }
            i++
            continue
        }

        // Bullet list item (-, *, +)
        val bulletMatch = BULLET_REGEX.matchEntire(rawLine)
        if (bulletMatch != null) {
            val leadingSpaces = bulletMatch.groupValues[1].length
            val indentLevel = (leadingSpaces / 2).coerceIn(0, 3)
            val content = bulletMatch.groupValues[3].trim()
            blocks.add(MarkdownBlock.BulletItem(indentLevel, content))
            i++
            continue
        }

        // Numbered list item (1., 2., etc.)
        val numberedMatch = NUMBERED_REGEX.matchEntire(trimmed)
        if (numberedMatch != null) {
            val number = numberedMatch.groupValues[1]
            val content = numberedMatch.groupValues[2].trim()
            blocks.add(MarkdownBlock.NumberedItem(number, content))
            i++
            continue
        }

        // Normal paragraph
        blocks.add(MarkdownBlock.Paragraph(trimmed))
        i++
    }
    return blocks
}

private val HEADING_REGEX = Regex("^(#{1,4})\\s+(.+)$")
private val BULLET_REGEX = Regex("^(\\s*)([-*+])\\s+(.+)$")
private val NUMBERED_REGEX = Regex("^(\\d+)\\.\\s+(.+)$")

private val INLINE_TOKEN_REGEX = Regex(
    "(\\*\\*\\*(.+?)\\*\\*\\*)" + // 1,2: ***bold italic***
        "|(\\*\\*(.+?)\\*\\*)" + // 3,4: **bold**
        "|(__(.+?)__)" + // 5,6: __bold__
        "|(\\*(.+?)\\*)" + // 7,8: *italic*
        "|(`([^`]+)`)" + // 9,10: `code`
        "|(~~(.+?)~~)" + // 11,12: ~~strikethrough~~
        "|(\\[([^\\]]+)\\]\\(([^)]+)\\))" // 13,14,15: [link](url)
)

fun parseInlineMarkdown(
    text: String,
    primaryColor: Color = Color.Unspecified,
    codeBackground: Color = Color.Unspecified,
    codeColor: Color = Color.Unspecified,
): AnnotatedString = buildAnnotatedString {
    var lastIndex = 0
    for (match in INLINE_TOKEN_REGEX.findAll(text)) {
        if (match.range.first > lastIndex) {
            append(text.substring(lastIndex, match.range.first))
        }
        when {
            match.groups[1] != null -> {
                val content = match.groupValues[2]
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                append(content)
                pop()
            }
            match.groups[3] != null -> {
                val content = match.groupValues[4]
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(content)
                pop()
            }
            match.groups[5] != null -> {
                val content = match.groupValues[6]
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(content)
                pop()
            }
            match.groups[7] != null -> {
                val content = match.groupValues[8]
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(content)
                pop()
            }
            match.groups[9] != null -> {
                val content = match.groupValues[10]
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                        color = codeColor,
                        fontWeight = FontWeight.Medium,
                    )
                )
                append(" $content ")
                pop()
            }
            match.groups[11] != null -> {
                val content = match.groupValues[12]
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(content)
                pop()
            }
            match.groups[13] != null -> {
                val label = match.groupValues[14]
                pushStyle(
                    SpanStyle(
                        color = primaryColor,
                        fontWeight = FontWeight.Medium,
                        textDecoration = TextDecoration.Underline,
                    )
                )
                append(label)
                pop()
            }
        }
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        append(text.substring(lastIndex))
    }
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.onSurface,
    bulletColor: Color = MaterialTheme.colorScheme.primary,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Heading -> {
                    val headingStyle = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        2 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        else -> MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    }
                    val annotated = remember(block.text, primaryColor, codeBg, codeColor) {
                        parseInlineMarkdown(block.text, primaryColor, codeBg, codeColor)
                    }
                    Text(
                        text = annotated,
                        style = headingStyle,
                        color = color,
                        modifier = Modifier.semantics { heading() }.padding(top = 2.dp),
                    )
                }

                is MarkdownBlock.BulletItem -> {
                    val annotated = remember(block.text, primaryColor, codeBg, codeColor) {
                        parseInlineMarkdown(block.text, primaryColor, codeBg, codeColor)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (block.indentLevel * 12).dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "-",
                            modifier = Modifier.clearAndSetSemantics {},
                            style = style,
                            fontWeight = FontWeight.Bold,
                            color = bulletColor,
                        )
                        Text(
                            text = annotated,
                            style = style,
                            color = color,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MarkdownBlock.NumberedItem -> {
                    val annotated = remember(block.text, primaryColor, codeBg, codeColor) {
                        parseInlineMarkdown(block.text, primaryColor, codeBg, codeColor)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "${block.number}.",
                            style = style,
                            fontWeight = FontWeight.Bold,
                            color = bulletColor,
                        )
                        Text(
                            text = annotated,
                            style = style,
                            color = color,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MarkdownBlock.Blockquote -> {
                    val annotated = remember(block.text, primaryColor, codeBg, codeColor) {
                        parseInlineMarkdown(block.text, primaryColor, codeBg, codeColor)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(bulletColor.copy(alpha = 0.7f)),
                        )
                        Text(
                            text = annotated,
                            style = style.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MarkdownBlock.CodeBlock -> {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = block.code,
                            style = style.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                is MarkdownBlock.Paragraph -> {
                    val annotated = remember(block.text, primaryColor, codeBg, codeColor) {
                        parseInlineMarkdown(block.text, primaryColor, codeBg, codeColor)
                    }
                    Text(
                        text = annotated,
                        style = style,
                        color = color,
                    )
                }

                MarkdownBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}
