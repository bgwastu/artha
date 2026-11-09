package net.wastu.artha.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.wastu.artha.ArthaApplication
import net.wastu.artha.data.attention.AttentionAction
import net.wastu.artha.data.attention.NeedsAttention
import net.wastu.artha.ui.components.collectOrDefault

/** Current attention items; empty in previews. */
@Composable
internal fun rememberAttentionItems(): List<NeedsAttention> {
    val repository = (LocalContext.current.applicationContext as? ArthaApplication)?.attention
    val items by repository?.items.collectOrDefault(emptyList())
    return items
}

/** Trailing icon for any row that leads to something needing attention. */
@Composable
internal fun AttentionIcon() {
    Icon(Icons.Outlined.ErrorOutline, contentDescription = "Needs attention", tint = MaterialTheme.colorScheme.error)
}

/** Summary for a row leading to [items]: the item itself, or a count when there are several. */
internal fun attentionSummary(items: List<NeedsAttention>): String? = when (items.size) {
    0 -> null
    1 -> items.single().label
    else -> "${items.size} things need attention"
}

/** M3 Expressive card with clear hierarchy, distinct icon badge, and primary action. */
@Composable
internal fun AttentionCard(item: NeedsAttention, onAction: (AttentionAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = colors.errorContainer.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = colors.error.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = colors.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = item.message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            val action = item.action
            if (action != null && item.actionLabel != null) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { onAction(action) },
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.error,
                        contentColor = colors.onError,
                    ),
                ) {
                    Text(item.actionLabel, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Cards for every item in [items], in order. */
@Composable
internal fun AttentionCards(items: List<NeedsAttention>, onAction: (AttentionAction) -> Unit) {
    items.forEach { AttentionCard(it, onAction) }
}
