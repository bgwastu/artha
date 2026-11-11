package net.wastu.artha.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.categoryRunMessage
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.LedgerState
import net.wastu.artha.ui.Page
import net.wastu.artha.ui.PageStack
import net.wastu.artha.ui.components.BannerHost
import net.wastu.artha.ui.components.collectOrDefault

internal enum class Destination(val label: String, val icon: ImageVector) {
    TRANSACTIONS("Transactions", Icons.AutoMirrored.Filled.List),
    ANALYSIS("Analysis", Icons.Default.Analytics),
    SETTINGS("Settings", Icons.Default.Settings);

    companion object {
        fun named(name: String?): Destination = entries.firstOrNull { it.name == name } ?: TRANSACTIONS

        val Saver = Saver<Destination, String>(save = { it.name }, restore = ::named)
    }
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
    val saveableStateHolder = rememberSaveableStateHolder()
    var transactionFilters by rememberSaveable(stateSaver = TransactionFiltersSaver) { mutableStateOf(TransactionFilters()) }
    var destination by rememberSaveable(stateSaver = Destination.Saver) { mutableStateOf(Destination.TRANSACTIONS) }
    val pages = rememberSaveable(saver = PageStack.Saver) { PageStack() }

    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFallback by remember { mutableStateOf<UnifiedTransaction?>(null) }
    val openTransaction = { transaction: UnifiedTransaction -> selectedKey = transaction.key; selectedFallback = transaction }

    val snackbarHost = remember { SnackbarHostState() }
    HomeFeedbackEffects(viewModel, snackbarHost, pages)

    val automaticImport by viewModel?.automaticImportActive.collectOrDefault(false)
    val ledgerLoadFailed by viewModel?.ledgerLoadFailed.collectOrDefault(false)
    val categoryFilter by viewModel?.categoryFilter.collectOrDefault(null)
    val targetDestination by viewModel?.targetDestination.collectOrDefault(null)
    LaunchedEffect(targetDestination) {
        targetDestination?.let {
            destination = Destination.named(it)
            viewModel?.setTargetDestination(null)
        }
    }

    val nothingOpen = selectedKey == null && !showSearch && !showFilter
    BackHandler(enabled = destination != Destination.TRANSACTIONS && nothingOpen && pages.top == null) {
        destination = Destination.TRANSACTIONS
    }
    BackHandler(enabled = destination == Destination.TRANSACTIONS && categoryFilter != null && nothingOpen) {
        viewModel?.closeCategoryTransactions()
    }
    BackHandler(enabled = pages.top != null) { pages.back() }

    val latestTransactions by viewModel?.allTransactions.collectOrDefault(emptyList())
    val selected = latestTransactions.firstOrNull { it.key == selectedKey } ?: selectedFallback?.takeIf { it.key == selectedKey }
    val closeDetails = { selectedKey = null; selectedFallback = null }
    val detailsState = selected?.let { rememberTransactionDetailsState(it.key) }
    val largeNavigationText = LocalDensity.current.fontScale > 1.3f

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val showDetailsPane = maxWidth >= 1080.dp && maxHeight >= 480.dp && destination == Destination.TRANSACTIONS && !showSearch && selected != null
        val showRail = maxWidth >= 600.dp && maxHeight >= 480.dp
        val barBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
            snackbarHost = { BannerHost(snackbarHost) },
            modifier = (if (showSearch) Modifier.clearAndSetSemantics {} else Modifier)
                .nestedScroll(barBehavior.nestedScrollConnection),
            topBar = {
                HomeTopBar(
                    destination = destination,
                    showBackToAnalysis = destination == Destination.TRANSACTIONS && categoryFilter != null,
                    onBackToAnalysis = { viewModel?.closeCategoryTransactions() },
                    scrollBehavior = barBehavior,
                )
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = destination == Destination.TRANSACTIONS,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 },
                ) {
                    LedgerActionsToolbar(
                        refreshing = refreshing,
                        syncEnabled = !refreshing || automaticImport,
                        onSearch = { showSearch = true },
                        onSync = onRefresh,
                        onFilter = { showFilter = true },
                    )
                }
            },
            bottomBar = {
                if (!showRail) HomeNavigationBar(destination, largeNavigationText) { destination = it }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (showRail) HomeNavigationRail(destination, largeNavigationText) { destination = it }
                Column(Modifier.weight(1f)) {
                    if (ledgerLoadFailed && ledgerState is LedgerState.Ready) LedgerLoadFailedBanner(onRetryLedger)
                    // Sync runs in the view model, so every tab shows the same bar for as long as it lasts.
                    AnimatedVisibility(visible = refreshing, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        LinearWavyProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    AnimatedContent(
                        targetState = destination,
                        transitionSpec = { ScreenMotion.fadeThrough().using(null) },
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter,
                        label = "tab",
                    ) { tab ->
                        saveableStateHolder.SaveableStateProvider(tab.name) {
                            when (tab) {
                                Destination.TRANSACTIONS -> Row(Modifier.fillMaxSize()) {
                                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                                        LedgerScreen(
                                            state = ledgerState,
                                            enabledProviders = enabledProviders,
                                            onTransaction = openTransaction,
                                            onRetry = onRetryLedger,
                                            showFilter = showFilter,
                                            onDismissFilter = { showFilter = false },
                                            viewModel = viewModel,
                                            filters = transactionFilters,
                                            onFiltersChanged = { transactionFilters = it },
                                        )
                                    }
                                    if (showDetailsPane) {
                                        Box(Modifier.weight(1f).fillMaxHeight()) {
                                            if (detailsState != null) TransactionDetailsContent(selected, viewModel, onClose = closeDetails, state = detailsState)
                                        }
                                    }
                                }
                                Destination.ANALYSIS -> if (viewModel != null) {
                                    AnalysisScreen(
                                        viewModel = viewModel,
                                        onOpenTransaction = openTransaction,
                                        onOpenCategories = { pages.open(Page.CATEGORIES) },
                                        onOpenCategoryReview = { pages.open(Page.REVIEW) },
                                        onOpenAiCategories = { pages.open(Page.AI) },
                                    )
                                }
                                Destination.SETTINGS -> SettingsScreen(viewModel, onOpen = pages::open)
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(visible = showSearch, enter = ScreenMotion.overlayEnter, exit = ScreenMotion.overlayExit) {
            Surface(Modifier.fillMaxSize()) {
                SearchTransactionsScreen(
                    ledgerState,
                    onBack = { showSearch = false },
                    onTransaction = openTransaction,
                    viewModel = viewModel,
                    onRetry = onRetryLedger,
                )
            }
        }
        HomePages(
            top = pages.top,
            depth = pages.depth,
            viewModel = viewModel,
            enabledProviders = enabledProviders,
            syncingProvider = syncingProvider,
            providerCounts = providerCounts,
            onProviderEnabled = onProviderEnabled,
            onOpen = pages::open,
            onBack = pages::back,
            onOpenTransaction = openTransaction,
        )
        if (!showDetailsPane && selected != null && detailsState != null) {
            TransactionDetailsSheet(selected, viewModel = viewModel, state = detailsState, onDismiss = closeDetails)
        }
    }
}

/** Snackbars for import results and finished categorization runs. */
@Composable
private fun HomeFeedbackEffects(viewModel: ArthaViewModel?, snackbarHost: SnackbarHostState, pages: PageStack) {
    val importFeedback by viewModel?.importFeedback.collectOrDefault(null)
    LaunchedEffect(importFeedback?.sequence) {
        importFeedback?.let { snackbarHost.showSnackbar(it.message) }
    }
    LaunchedEffect(viewModel) {
        viewModel?.categoryRunResults?.collect {
            val result = snackbarHost.showSnackbar(
                categoryRunMessage(it),
                actionLabel = if (it.review > 0 || it.failed > 0) "Review" else null,
            )
            if (result == SnackbarResult.ActionPerformed) pages.open(Page.REVIEW)
        }
    }
}

@Composable
private fun LedgerLoadFailedBanner(onRetry: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Saved history update failed", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}
