package net.wastu.artha.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.wastu.artha.data.attention.NeedsAttention
import net.wastu.artha.ui.components.Segment
import net.wastu.artha.ui.components.SegmentGap
import net.wastu.artha.ui.components.shape

@Composable
internal fun SettingsHeading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .semantics { heading() }
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
internal fun SettingsLink(
    title: String,
    summary: String?,
    icon: ImageVector,
    enabled: Boolean = true,
    attention: List<NeedsAttention> = emptyList(),
    segment: Segment? = null,
    onClick: (() -> Unit)?,
) {
    val attentionText = attentionSummary(attention)
    val grouped = segment != null
    val shape = segment?.shape() ?: RectangleShape
    val color = if (grouped) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface
    val outer = if (grouped) Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = SegmentGap) else Modifier
    val body: @Composable () -> Unit = { ListItem(
            headlineContent = {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            },
            supportingContent = when {
                attentionText != null -> { { Text(attentionText, color = MaterialTheme.colorScheme.error) } }
                summary != null -> { { Text(summary) } }
                else -> null
            },
            leadingContent = {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = when {
                        attentionText != null -> MaterialTheme.colorScheme.errorContainer
                        grouped -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = when {
                                attentionText != null -> MaterialTheme.colorScheme.onErrorContainer
                                grouped -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            trailingContent = when {
                attentionText != null -> { { AttentionIcon() } }
                onClick != null -> { { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
                else -> null
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = if (enabled) Modifier else Modifier.alpha(0.38f),
        ) }
    if (onClick != null) Surface(onClick = onClick, enabled = enabled, shape = shape, color = color, modifier = outer) { body() }
    else Surface(shape = shape, color = color, modifier = outer) { body() }
}

@Composable
internal fun SettingsSwitch(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    sourceName: String? = null,
) {
    ListItem(
        modifier = Modifier
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .semantics { if (sourceName != null) contentDescription = "$sourceName, $title" },
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}
