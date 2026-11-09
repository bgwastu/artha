package net.wastu.artha.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.core.model.iconRes
import net.wastu.artha.data.analysis.PaymentCategoryState
import net.wastu.artha.data.analysis.paymentCategoryStateLabel
import net.wastu.artha.ui.GroupBadge
import net.wastu.artha.ui.TransactionTagKind
import net.wastu.artha.ui.formatTime
import net.wastu.artha.ui.groupBadgeInfo
import net.wastu.artha.ui.paymentRowAmount
import net.wastu.artha.ui.theme.expenseColor
import net.wastu.artha.ui.theme.incomeColor
import net.wastu.artha.ui.transactionHumanLine
import net.wastu.artha.ui.transactionTags

@Composable
fun HighlightedText(
    text: String,
    query: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
) {
    val background = MaterialTheme.colorScheme.primaryContainer
    val foreground = MaterialTheme.colorScheme.onPrimaryContainer
    val highlighted = remember(text, query, background, foreground) {
        buildAnnotatedString {
            append(text)
            if (query.isNotBlank()) {
                var start = text.indexOf(query, ignoreCase = true)
                while (start >= 0) {
                    addStyle(
                        SpanStyle(background = background, color = foreground, fontWeight = FontWeight.Bold),
                        start, start + query.length,
                    )
                    start = text.indexOf(query, startIndex = start + 1, ignoreCase = true)
                }
            }
        }
    }
    Text(highlighted, modifier = modifier, style = style, fontWeight = fontWeight,
        color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionRow(
    transaction: UnifiedTransaction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlightQuery: String = "",
    categoryState: PaymentCategoryState = PaymentCategoryState.IDLE,
) {
    val reversed = transaction.isReversedRecord
    val tags = remember(transaction) { transactionTags(transaction) }
    // A readable group drops the Grouped pill for an icon overlay; unreadable keeps the pill.
    val groupBadge = remember(transaction) { groupBadgeInfo(transaction) }.takeIf { tags.any { it.kind == TransactionTagKind.GROUPED } }
    val rowTags = if (groupBadge != null) tags.filter { it.kind != TransactionTagKind.GROUPED } else tags
    val movedMoney = tags.any { it.kind == TransactionTagKind.REVERSED || it.kind == TransactionTagKind.REFUND || it.kind == TransactionTagKind.TRANSFER }
    val title = remember(transaction) { transactionHumanLine(transaction) }
    val amount = remember(transaction) { paymentRowAmount(transaction) }
    // Reversed charges and own transfers aren't spending, so their amounts are not shown as expenses.
    val amountColor = if (reversed || tags.any { it.kind == TransactionTagKind.TRANSFER }) MaterialTheme.colorScheme.onSurfaceVariant else if (transaction.direction == Direction.CREDIT) incomeColor() else expenseColor()
    val payloadValues by produceState(initialValue = emptyList<String>(), transaction.rawPayload, highlightQuery.isNotBlank()) {
        value = if (highlightQuery.isBlank()) emptyList() else withContext(Dispatchers.Default) { payloadTextValues(transaction.rawPayload) }
    }
    val snippet = remember(transaction, highlightQuery, payloadValues) {
        if (highlightQuery.isBlank()) null else (payloadValues + transaction.provider.displayName + transaction.cardLast4.orEmpty())
            .firstOrNull { it.contains(highlightQuery, true) && !title.contains(it, true) }
    }
    val fontScale = LocalDensity.current.fontScale
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface, modifier = modifier.semantics { stateDescription = if (transaction.direction == Direction.CREDIT) "Incoming payment" else "Outgoing payment" }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clearAndSetSemantics {
                contentDescription = transaction.provider.displayName + if (groupBadge != null) ", Grouped" else ""
            }) {
                Image(painterResource(transaction.provider.iconRes), contentDescription = null,
                    Modifier.matchParentSize().clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop)
                groupBadge?.let { badge ->
                    GroupBadgeOverlay(badge, Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stackAmount = fontScale >= 1.3f || maxWidth < 300.dp || amount.length > 16
                    if (stackAmount) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TaggedTitle(title, rowTags, MaterialTheme.typography.bodyLarge, highlightQuery = highlightQuery,
                                fontWeight = FontWeight.Medium)
                            HighlightedText(amount, highlightQuery, MaterialTheme.typography.titleSmall,
                                color = amountColor,
                                fontWeight = FontWeight.SemiBold, maxLines = Int.MAX_VALUE)
                        }
                    } else Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TaggedTitle(title, rowTags, MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                            highlightQuery = highlightQuery, fontWeight = FontWeight.Medium)
                        HighlightedText(amount, highlightQuery, MaterialTheme.typography.titleSmall,
                                color = amountColor, fontWeight = FontWeight.SemiBold)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(formatTime(transaction.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!movedMoney) {
                        val busyLabel = paymentCategoryStateLabel(categoryState)
                        if (categoryState != PaymentCategoryState.IDLE && busyLabel != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                LoadingIndicator(
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = busyLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (!transaction.category.isNullOrBlank()) {
                            HighlightedText(transaction.category.orEmpty(), highlightQuery, MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
                transaction.userNote?.takeIf { it.isNotBlank() }?.let {
                    HighlightedText(it, highlightQuery, MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                snippet?.let { HighlightedText(it, highlightQuery, MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
            }
        }
    }
}

@Composable
fun LedgerFeedback(
    title: String? = null,
    loading: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            LoadingIndicator(Modifier.size(64.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                "Loading transactions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Reading encrypted transactions from local storage",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(68.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            title?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Something went wrong while accessing your local ledger.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            onRetry?.let {
                Spacer(Modifier.height(20.dp))
                Button(onClick = it, shape = MaterialTheme.shapes.medium) {
                    Text("Retry")
                }
            }
        }
    }
}

/** The tiny bottom-right overlay on a grouped row: the 2nd member's icon, or "+n" for bigger groups. */
@Composable
private fun GroupBadgeOverlay(badge: GroupBadge, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(16.dp)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .padding(1.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (badge.extraCount > 0) {
            Text("+${badge.extraCount}", style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        } else {
            Image(painterResource(badge.secondProvider.iconRes), contentDescription = null,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
