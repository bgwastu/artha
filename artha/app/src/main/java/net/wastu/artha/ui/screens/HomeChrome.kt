package net.wastu.artha.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Collapsing title bar; shows a back arrow only while a category drill-down is open. */
@Composable
internal fun HomeTopBar(
    destination: Destination,
    showBackToAnalysis: Boolean,
    onBackToAnalysis: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    LargeFlexibleTopAppBar(
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            if (showBackToAnalysis) {
                IconButton(onClick = onBackToAnalysis) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Analysis")
                }
            }
        },
        title = { Text(destination.label, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/** Search is the primary action; sync and filter sit beside it in the same toolbar. */
@Composable
internal fun LedgerActionsToolbar(
    refreshing: Boolean,
    syncEnabled: Boolean,
    onSearch: () -> Unit,
    onSync: () -> Unit,
    onFilter: () -> Unit,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        floatingActionButton = {
            FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "Search")
            }
        },
    ) {
        IconButton(onClick = onSync, enabled = syncEnabled) {
            if (refreshing) {
                LoadingIndicator(modifier = Modifier.size(28.dp).semantics { contentDescription = "Syncing" })
            } else {
                Icon(Icons.Default.Sync, contentDescription = "Sync")
            }
        }
        IconButton(onClick = onFilter) {
            Icon(Icons.Default.FilterList, contentDescription = "Filter")
        }
    }
}

/** Phone navigation. [largeText] shortens "Transactions" so it fits at big font scales. */
@Composable
internal fun HomeNavigationBar(selected: Destination, largeText: Boolean, onSelect: (Destination) -> Unit) {
    ShortNavigationBar {
        Destination.entries.forEach { item ->
            ShortNavigationBarItem(
                selected = selected == item,
                onClick = { onSelect(item) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(navigationLabel(item, largeText), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** Tablet and landscape navigation. */
@Composable
internal fun HomeNavigationRail(selected: Destination, largeText: Boolean, onSelect: (Destination) -> Unit) {
    WideNavigationRail(modifier = Modifier.width(if (largeText) 128.dp else 96.dp)) {
        Destination.entries.forEach { item ->
            WideNavigationRailItem(
                railExpanded = false,
                selected = selected == item,
                onClick = { onSelect(item) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(navigationLabel(item, largeText), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

private fun navigationLabel(item: Destination, largeText: Boolean) =
    if (largeText && item == Destination.TRANSACTIONS) "Payments" else item.label
