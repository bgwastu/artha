package net.wastu.artha.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.filled.AutoAwesomeMotion
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.wastu.artha.ui.TransactionTag
import net.wastu.artha.ui.TransactionTagKind

private val TagHorizontalPadding = 6.dp
private val TagIconSize = 12.dp
private val TagIconGap = 2.dp
private val TagGap = 6.dp

/** Grouping is frequent, so the list shows it as a glyph; the label stays for speech. */
private val TransactionTag.iconOnly: Boolean get() = kind == TransactionTagKind.GROUPED

private val TransactionTag.icon: ImageVector? get() = when (kind) {
    TransactionTagKind.GROUPED -> Icons.Filled.AutoAwesomeMotion
    TransactionTagKind.CARD -> Icons.Outlined.CreditCard
    else -> null
}

@Composable
private fun tagLabelStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)

/**
 * Small M3 tag. Filled tags say what happened to the money; outlined tags say how it was paid.
 */
@Composable
fun TransactionTagPill(tag: TransactionTag, modifier: Modifier = Modifier, compact: Boolean = true) {
    val showLabel = !(compact && tag.iconOnly)
    val outlined = tag.kind == TransactionTagKind.CARD
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tag.kind) {
        TransactionTagKind.REVERSED, TransactionTagKind.REFUND, TransactionTagKind.PARTIAL_REFUND ->
            colors.tertiaryContainer to colors.onTertiaryContainer
        TransactionTagKind.TRANSFER, TransactionTagKind.GROUPED -> colors.secondaryContainer to colors.onSecondaryContainer
        TransactionTagKind.CARD -> Color.Transparent to colors.onSurfaceVariant
    }
    val shape = MaterialTheme.shapes.extraSmall
    Surface(
        color = container, contentColor = content, shape = shape,
        modifier = modifier.then(if (outlined) Modifier.border(1.dp, colors.outlineVariant, shape) else Modifier),
    ) {
        Row(
            Modifier.padding(horizontal = TagHorizontalPadding).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TagIconGap),
        ) {
            tag.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(TagIconSize)) }
            if (showLabel) Text(tag.label, style = tagLabelStyle(), maxLines = 1)
        }
    }
}

/**
 * Title with its tags laid out inline, so a long title wraps after the tags instead of
 * pushing them into a separate row. Each tag's alternate text keeps it in the spoken title.
 */
@Composable
fun TaggedTitle(
    title: String,
    tags: List<TransactionTag>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    highlightQuery: String = "",
    fontWeight: FontWeight? = null,
    maxLines: Int = 2,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = tagLabelStyle()
    val background = MaterialTheme.colorScheme.primaryContainer
    val foreground = MaterialTheme.colorScheme.onPrimaryContainer
    val lineHeight = style.lineHeight
    val inline = remember(tags, labelStyle, density, lineHeight) {
        tags.mapIndexed { index, tag ->
            val width = with(density) {
                val content = when {
                    tag.iconOnly -> TagIconSize.toPx()
                    tag.icon != null -> measurer.measure(tag.label, labelStyle, maxLines = 1).size.width + (TagIconSize + TagIconGap).toPx()
                    else -> measurer.measure(tag.label, labelStyle, maxLines = 1).size.width.toFloat()
                }
                (content + (TagHorizontalPadding * 2 + TagGap).toPx()).toSp()
            }
            val height = with(density) { (labelStyle.lineHeight.toPx() + 2.dp.toPx()).toSp() }
            "tag$index" to InlineTextContent(Placeholder(width, height, PlaceholderVerticalAlign.TextCenter)) {
                Box(Modifier.padding(end = TagGap).clearAndSetSemantics {}) { TransactionTagPill(tag) }
            }
        }.toMap()
    }
    val text = remember(title, tags, highlightQuery, background, foreground) {
        buildAnnotatedString {
            tags.forEachIndexed { index, tag -> appendInlineContent("tag$index", "${tag.description}, ") }
            val start = length
            append(title)
            if (highlightQuery.isNotBlank()) {
                var match = title.indexOf(highlightQuery, ignoreCase = true)
                while (match >= 0) {
                    addStyle(SpanStyle(background = background, color = foreground, fontWeight = FontWeight.Bold),
                        start + match, start + match + highlightQuery.length)
                    match = title.indexOf(highlightQuery, startIndex = match + 1, ignoreCase = true)
                }
            }
        }
    }
    Text(text, modifier = modifier, style = style, fontWeight = fontWeight, inlineContent = inline,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
