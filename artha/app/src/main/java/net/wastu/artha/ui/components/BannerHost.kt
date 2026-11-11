package net.wastu.artha.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Status messages at the bottom as a banner the user can close, instead of a bare snackbar. */
@Composable
fun BannerHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data -> Banner(data) }
}

@Composable
private fun Banner(data: SnackbarData) {
    val action = data.visuals.actionLabel
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 2.dp, bottom = 2.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            if (action != null) {
                TextButton(onClick = data::performAction) {
                    Text(action, color = MaterialTheme.colorScheme.inversePrimary)
                }
            }
            IconButton(onClick = data::dismiss) { Icon(Icons.Default.Close, "Dismiss") }
        }
    }
}
