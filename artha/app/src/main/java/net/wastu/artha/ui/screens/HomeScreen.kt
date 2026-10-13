package net.wastu.artha.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.Alignment
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import net.wastu.artha.ui.screens.TransactionFilters
import net.wastu.artha.ui.screens.TransactionFiltersSaver

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
    var transactionFilters by rememberSaveable(stateSaver = TransactionFiltersSaver) { mutableStateOf(TransactionFilters()) }
    var destination by rememberSaveable { mutableStateOf(Destination.TRANSACTIONS) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    var showCategoriesSheet by rememberSaveable { mutableStateOf(false) }
    var showKnowledgeEditor by rememberSaveable { mutableStateOf(false) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFallback by remember { mutableStateOf<UnifiedTransaction?>(null) }
    var openBackup by rememberSaveable { mutableStateOf(false) }
    val ledgerLoadFailed by viewModel?.ledgerLoadFailed?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val rootGate by viewModel?.rootGate?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(net.wastu.artha.core.root.RootGateState.LOCKED) }
    val categoryFilter by viewModel?.categoryFilter?.collectAsStateWithLifecycle() ?: remember { mutableStateOf<String?>(null) }
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

    BackHandler(enabled = destination != Destination.TRANSACTIONS && selectedKey == null) {
        destination = Destination.TRANSACTIONS
    }

    BackHandler(enabled = destination == Destination.TRANSACTIONS && categoryFilter != null && selectedKey == null && !showSearch && !showFilter) {
        viewModel?.closeCategoryTransactions()
    }

    val latestTransactions by viewModel?.allTransactions?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyList<UnifiedTransaction>()) }
    val selected = latestTransactions.firstOrNull { it.key == selectedKey } ?: selectedFallback?.takeIf { it.key == selectedKey }
    val closeDetails = { selectedKey = null; selectedFallback = null }
    val detailsState = selected?.let { rememberTransactionDetailsState(it.key) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val showDetailsPane = maxWidth >= 1080.dp && maxHeight >= 480.dp && destination == Destination.TRANSACTIONS && !showSearch && selected != null
        val showRail = maxWidth >= 600.dp && maxHeight >= 480.dp
        Scaffold(
            modifier = if (showSearch) Modifier.clearAndSetSemantics {} else Modifier,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (destination == Destination.TRANSACTIONS && categoryFilter != null) {
                            IconButton(onClick = { viewModel?.closeCategoryTransactions() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Analysis")
                            }
                        }
                    },
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
                                    contentDescription = if (refreshing) "Importing history" else "Import history",
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
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
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
                    if (ledgerLoadFailed && ledgerState is LedgerState.Ready) {
                        Row(Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                            Text("Saved history update failed", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onRetryLedger) { Text("Retry") }
                        }
                    }
                    if (rootGate == net.wastu.artha.core.root.RootGateState.DENIED) {
                        androidx.compose.material3.TextButton(onClick = onRefresh, modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text("Root unavailable. Retry imports.")
                        }
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    saveableStateHolder.SaveableStateProvider(destination.name) {
                        when (destination) {
                            Destination.TRANSACTIONS -> Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                            LedgerScreen(
                                state = ledgerState,
                                enabledProviders = enabledProviders,
                                onTransaction = { selectedKey = it.key; selectedFallback = it },
                                onRetry = onRetryLedger,
                                onImportHistory = { destination = Destination.SETTINGS },
                                onRestoreBackup = { openBackup = true; destination = Destination.SETTINGS },
                                showFilter = showFilter,
                                onDismissFilter = { showFilter = false },
                                viewModel = viewModel,
                                filters = transactionFilters,
                                onFiltersChanged = { transactionFilters = it },
                            )
                            }
                            if (showDetailsPane && selected != null) {
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    if (detailsState != null) TransactionDetailsContent(selected, viewModel, onClose = closeDetails, state = detailsState)
                                }
                            }
                            }
                            Destination.ANALYSIS -> {
                                if (viewModel != null) {
                                    AnalysisScreen(
                                        viewModel = viewModel,
                                        onOpenTransaction = { selectedKey = it.key; selectedFallback = it },
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
                                openBackup = openBackup,
                                onBackupOpened = { openBackup = false },
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
        }
        if (showSearch) {
            Surface(Modifier.fillMaxSize()) {
                SearchTransactionsScreen(
                    ledgerState,
                    onBack = { showSearch = false },
                    onTransaction = { selectedKey = it.key; selectedFallback = it },
                    filters = transactionFilters,
                    viewModel = viewModel,
                    onRetry = onRetryLedger,
                )
            }
        }
        if (!showDetailsPane && selected != null) {
            if (detailsState != null) TransactionDetailsSheet(selected, viewModel = viewModel, state = detailsState, onDismiss = closeDetails)
        }
    }
}
