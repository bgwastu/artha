package net.wastu.artha.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.math.BigInteger
import java.time.Instant
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.iconRes
import net.wastu.artha.ui.GroupRecord
import net.wastu.artha.ui.TransactionTag
import net.wastu.artha.ui.TransactionTagKind
import net.wastu.artha.ui.formatDate
import net.wastu.artha.ui.formatMinorAmount
import net.wastu.artha.ui.formatTime
import net.wastu.artha.ui.groupNetMinor
import net.wastu.artha.ui.theme.expenseColor
import net.wastu.artha.ui.theme.incomeColor
import org.json.JSONObject

/** Providers store item prices as whole rupiah, sometimes with an "Rp" prefix; show them like other amounts. */
internal fun itemPriceText(raw: String): String {
    val digits = raw.trim().removePrefix("Rp").trim()
    val rupiah = digits.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toBigIntegerOrNull() ?: return raw
    return formatMinorAmount(rupiah.multiply(BigInteger.valueOf(100)))
}

internal val StatusTagKinds = setOf(TransactionTagKind.REVERSED, TransactionTagKind.REFUND,
    TransactionTagKind.TRANSFER, TransactionTagKind.PARTIAL_REFUND)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagRow(tags: List<TransactionTag>) {
    if (tags.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { tag -> TransactionTagPill(tag, Modifier.height(20.dp).semantics { contentDescription = tag.description }, compact = false) }
    }
}

@Composable
internal fun SourceHeader(provider: Provider, supporting: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(provider.iconRes), contentDescription = null,
            modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.medium))
        Column {
            Text(provider.displayName, style = MaterialTheme.typography.titleSmall)
            Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The source records behind one reconciled row, each openable, with a net line for refunds. */
@Composable
internal fun GroupRecordsSection(records: List<GroupRecord>, parentTime: Instant, explanation: String?, onOpen: (Int) -> Unit) {
    val parentDay = parentTime.atZone(AccountingTime.zone).toLocalDate()
    Column(Modifier.fillMaxWidth().padding(top = SectionGap), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading("Records", explanation)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
            Column {
                records.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    val at = item.transaction.occurredAt
                    Row(
                        Modifier.fillMaxWidth().clickable(onClickLabel = "Open record") { onOpen(index) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Image(painterResource(item.transaction.provider.iconRes), item.transaction.provider.displayName,
                            Modifier.size(36.dp).clip(MaterialTheme.shapes.small))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.role, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (at.atZone(AccountingTime.zone).toLocalDate() == parentDay) formatTime(at) else formatDate(at),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(item.signedAmount, style = MaterialTheme.typography.titleSmall,
                            color = if (item.transaction.direction == Direction.CREDIT) incomeColor() else MaterialTheme.colorScheme.onSurface)
                    }
                }
                groupNetMinor(records)?.let { net ->
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    val currency = records.first().transaction.money.currencyCode
                    val amount = formatMinorAmount(BigInteger.valueOf(kotlin.math.abs(net)), currency)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Net", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(if (net < 0) "+$amount" else amount, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            color = if (net < 0) incomeColor() else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/** Space above a section, on top of the sheet's line spacing, so sections read as separate groups. */
internal val SectionGap = 14.dp

@Composable
private fun SectionHeading(title: String, supporting: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        supporting?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** One source record on its own: what that source saw, before reconciliation merged it. */
@Composable
internal fun GroupRecordDetails(item: GroupRecord, minHeight: Dp, showBack: Boolean, onBack: () -> Unit) {
    val tx = item.transaction
    val payload = remember(tx.rawPayload) { runCatching { JSONObject(tx.rawPayload) }.getOrNull() }
    Column(Modifier.fillMaxWidth().heightIn(min = minHeight)) {
        // Pinned header; the 12dp start inset puts the arrow glyph on the 24dp content edge.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = if (showBack) 12.dp else 24.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to transaction") }
                Spacer(Modifier.width(4.dp))
            }
            Text(item.role, style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.semantics { heading() })
        }
    SelectionContainer {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SourceHeader(tx.provider, formatDate(tx.occurredAt))
            Text(item.signedAmount, style = MaterialTheme.typography.headlineMediumEmphasized, color = if (tx.direction == Direction.CREDIT) incomeColor() else expenseColor())
            Text(item.title, style = MaterialTheme.typography.titleLargeEmphasized)
            DisableSelection { TagRow(item.tags) }
            DetailRow("Reference", tx.providerTransactionId)
            (payload?.detailText("sourcePaymentMethod") ?: payload?.detailText("paymentMethod"))?.let { DetailRow("Payment method", it) }
            payload?.detailText("counterpart")?.takeUnless { item.title.contains(it, ignoreCase = true) }?.let { DetailRow("Counterpart", it) }
            tx.pocket?.let { DetailRow("Pocket", it.name) }
            payload?.detailText("notes")?.let { DetailRow("Source note", it) }
            tx.description.takeUnless { item.title.equals(it, ignoreCase = true) }?.let { DetailRow("Source description", it) }
            Spacer(Modifier.height(24.dp))
        }
    }
    }
}

@Composable
internal fun DetailCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = SectionGap), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading(title)
        content()
    }
}

@Composable
internal fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun DetailEditRow(
    label: String, value: String, icon: ImageVector,
    enabled: Boolean, supporting: String? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
            if (supportingContent != null) {
                supportingContent()
            } else {
                supporting?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            }
        }
        if (enabled) Icon(Icons.Default.ChevronRight, contentDescription = "Edit $label")
    }
}

@Composable
internal fun SaveFeedback(message: String, error: Boolean) {
    Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
