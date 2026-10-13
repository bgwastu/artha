package net.wastu.artha.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Category
import net.wastu.artha.ui.ArthaViewModel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.ui.LedgerState

private enum class Destination(val label: String, val icon: ImageVector) {
    TRANSACTIONS("Transactions", Icons.AutoMirrored.Filled.List),
    ANALYSIS("Analysis", Icons.Default.Analytics),
    SETTINGS("Settings", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    ledgerState: LedgerState,
    onRetryLedger: () -> Unit,
    enabledProviders: Set<Provider> = Provider.entries.toSet(),
    refreshing: Boolean = false,
    syncingProvider: Provider? = null,
    providerCounts: Map<Provider, Int> = emptyMap(),
    onRefresh: () -> Unit = {},
    onProviderEnabled: (Provider, Boolean) -> Unit = { _, _ -> },
    viewModel: ArthaViewModel? = null,
) {
    val saveableStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var destination by rememberSaveable { mutableStateOf(Destination.TRANSACTIONS) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    var showCategoriesSheet by rememberSaveable { mutableStateOf(false) }
    var showKnowledgeEditor by rememberSaveable { mutableStateOf(false) }
    var selectedTransaction by remember { mutableStateOf<UnifiedTransaction?>(null) }
    val rootGate by viewModel?.rootGate?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(net.wastu.artha.core.root.RootGateState.LOCKED) }
    val targetDest by viewModel?.targetDestination?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    androidx.compose.runtime.LaunchedEffect(targetDest) {
        targetDest?.let {
            when (it) {
                "ANALYSIS" -> destination = Destination.ANALYSIS
                "SETTINGS" -> destination = Destination.SETTINGS
                else -> destination = Destination.TRANSACTIONS
            }
            viewModel?.setTargetDestination(null)
        }
    }

    BackHandler(enabled = destination != Destination.TRANSACTIONS && selectedTransaction == null) {
        destination = Destination.TRANSACTIONS
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val showRail = maxWidth >= 600.dp
        Scaffold(
            modifier = if (showSearch) Modifier.clearAndSetSemantics {} else Modifier,
            topBar = {
                TopAppBar(
                    title = { Text(destination.label) },
                    actions = {
                        if (destination == Destination.TRANSACTIONS) {
                            IconButton(onClick = onRefresh, enabled = !refreshing) {
                                val angle = if (refreshing) {
                                    val transition = rememberInfiniteTransition(label = "Sync")
                                    val rotation by transition.animateFloat(
                                        initialValue = 0f,
                                        targetValue = 360f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(1000, easing = LinearEasing),
                                            repeatMode = RepeatMode.Restart,
                                        ),
                                        label = "Sync rotation",
                                    )
                                    rotation
                                } else {
                                    0f
                                }
                                Icon(
                                    Icons.Default.Sync,
                                    contentDescription = "Sync",
                                    modifier = if (refreshing) {
                                        Modifier.graphicsLayer { rotationZ = angle }
                                    } else Modifier,
                                )
                            }
                            IconButton(onClick = { showFilter = true }) {
                                Icon(Icons.Default.FilterList, contentDescription = "Filter")
                            }
                            IconButton(onClick = { showSearch = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (!showRail) {
                    NavigationBar {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (showRail) {
                    NavigationRail {
                        Destination.entries.forEach { item ->
                            NavigationRailItem(
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    if (rootGate == net.wastu.artha.core.root.RootGateState.DENIED) {
                        androidx.compose.material3.TextButton(onClick = onRefresh, modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text("Root unavailable. Retry imports.")
                        }
                    }
                    saveableStateHolder.SaveableStateProvider(destination.name) {
                        when (destination) {
                            Destination.TRANSACTIONS -> LedgerScreen(
                                state = ledgerState,
                                enabledProviders = enabledProviders,
                                onTransaction = { selectedTransaction = it },
                                onRetry = onRetryLedger,
                                showFilter = showFilter,
                                onDismissFilter = { showFilter = false },
                                viewModel = viewModel,
                            )
                            Destination.ANALYSIS -> {
                                if (viewModel != null) {
                                    AnalysisScreen(
                                        viewModel = viewModel,
                                        onOpenTransaction = { selectedTransaction = it },
                                        showCategoriesSheet = showCategoriesSheet,
                                        onOpenCategoriesSheet = { showCategoriesSheet = true },
                                        onDismissCategoriesSheet = { showCategoriesSheet = false },
                                        showKnowledgeEditor = showKnowledgeEditor,
                                        onDismissKnowledgeEditor = { showKnowledgeEditor = false },
                                        onOpenKnowledgeEditor = { showKnowledgeEditor = true },
                                    )
                                }
                            }
                            Destination.SETTINGS -> ProvidersScreen(
                                enabled = enabledProviders,
                                syncingProvider = syncingProvider,
                                providerCounts = providerCounts,
                                onEnabled = onProviderEnabled,
                                viewModel = viewModel,
                            )
                        }
                    }
                }
            }
        }
        if (showSearch) {
            Surface(Modifier.fillMaxSize()) {
                SearchTransactionsScreen(
                    ledgerState,
                    onBack = { showSearch = false },
                    onTransaction = { selectedTransaction = it },
                )
            }
        }
    }

    val latestTransactions by viewModel?.allTransactions?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyList<UnifiedTransaction>()) }
    selectedTransaction?.let { selected ->
        val transaction = latestTransactions.firstOrNull { it.key == selected.key } ?: selected
        TransactionDetailsSheet(transaction, viewModel = viewModel, onDismiss = { selectedTransaction = null })
    }
}
