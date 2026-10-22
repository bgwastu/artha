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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

@Composable
private fun tagLabelStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)

/**
 * Small M3 tag. Filled tags say what happened to the money; outlined tags say how it was paid.
 */
@Composable
fun TransactionTagPill(tag: TransactionTag, modifier: Modifier = Modifier) {
    val outlined = tag.kind == TransactionTagKind.QRIS || tag.kind == TransactionTagKind.CARD
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tag.kind) {
        TransactionTagKind.REVERSED, TransactionTagKind.REFUND, TransactionTagKind.PARTIAL_REFUND ->
            colors.tertiaryContainer to colors.onTertiaryContainer
        TransactionTagKind.TRANSFER, TransactionTagKind.GROUPED -> colors.secondaryContainer to colors.onSecondaryContainer
        TransactionTagKind.QRIS, TransactionTagKind.CARD -> Color.Transparent to colors.onSurfaceVariant
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
            if (tag.kind == TransactionTagKind.CARD) {
                Icon(Icons.Outlined.CreditCard, contentDescription = null, modifier = Modifier.size(TagIconSize))
            }
            Text(tag.label, style = tagLabelStyle(), maxLines = 1)
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
            val labelWidth = measurer.measure(tag.label, labelStyle, maxLines = 1).size.width
            val width = with(density) {
                val icon = if (tag.kind == TransactionTagKind.CARD) (TagIconSize + TagIconGap).toPx() else 0f
                (labelWidth + icon + (TagHorizontalPadding * 2 + TagGap).toPx()).toSp()
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
