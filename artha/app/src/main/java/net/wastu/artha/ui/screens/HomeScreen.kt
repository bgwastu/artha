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
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
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

internal enum class Destination(val label: String, val icon: ImageVector) {
    TRANSACTIONS("Expenses", Icons.AutoMirrored.Filled.List),
    ANALYSIS("Analysis", Icons.Default.Analytics),
    SETTINGS("Settings", Icons.Default.Settings);

    companion object {
        val Saver = androidx.compose.runtime.saveable.Saver<Destination, String>(
            save = { it.name },
            restore = { name -> entries.firstOrNull { it.name == name } ?: TRANSACTIONS }
        )
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
    onExitDemo: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val saveableStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var transactionFilters by rememberSaveable(stateSaver = TransactionFiltersSaver) { mutableStateOf(TransactionFilters()) }
    val prefs = remember { context.getSharedPreferences("artha_ui_state", android.content.Context.MODE_PRIVATE) }
    val initialTabName = remember { prefs.getString("active_tab", null) }
    val initialDestination = remember {
        initialTabName?.let { name -> Destination.entries.firstOrNull { it.name == name } }
            ?: Destination.TRANSACTIONS
    }
    var destination by rememberSaveable(stateSaver = Destination.Saver) { mutableStateOf(initialDestination) }
    androidx.compose.runtime.LaunchedEffect(destination) {
        prefs.edit().putString("active_tab", destination.name).apply()
    }

    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    var aiCategoriesPage by rememberSaveable { mutableStateOf("HUB") }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFallback by remember { mutableStateOf<UnifiedTransaction?>(null) }

    val initialShowReview = remember { prefs.getBoolean("show_category_review", false) }
    var showCategoryReview by rememberSaveable { mutableStateOf(initialShowReview) }
    androidx.compose.runtime.LaunchedEffect(showCategoryReview) {
        prefs.edit().putBoolean("show_category_review", showCategoryReview).apply()
    }

    val initialShowAi = remember { prefs.getBoolean("show_ai_categories", false) }
    var showAiCategories by rememberSaveable { mutableStateOf(initialShowAi) }
    androidx.compose.runtime.LaunchedEffect(showAiCategories) {
        prefs.edit().putBoolean("show_ai_categories", showAiCategories).apply()
    }

    val initialShowChart = remember { prefs.getBoolean("show_spending_chart", false) }
    var showSpendingChart by rememberSaveable { mutableStateOf(initialShowChart) }
    androidx.compose.runtime.LaunchedEffect(showSpendingChart) {
        prefs.edit().putBoolean("show_spending_chart", showSpendingChart).apply()
    }

    var categoryDrillFromChart by rememberSaveable { mutableStateOf(false) }
    val snackbarHost = remember { androidx.compose.material3.SnackbarHostState() }
    val importFeedback by viewModel?.importFeedback?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val automaticImport by viewModel?.automaticImportActive?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(importFeedback?.sequence) {
        importFeedback?.let { snackbarHost.showSnackbar(it.message) }
    }
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
    androidx.compose.runtime.LaunchedEffect(categoryFilter) {
        if (categoryFilter == null) {
            categoryDrillFromChart = false
        }
    }

    BackHandler(
        enabled = destination != Destination.TRANSACTIONS &&
            selectedKey == null &&
            !showAiCategories &&
            !showCategoryReview &&
            !showSpendingChart &&
            !showSearch &&
            !showFilter
    ) {
        destination = Destination.TRANSACTIONS
    }

    BackHandler(enabled = destination == Destination.TRANSACTIONS && categoryFilter != null && selectedKey == null && !showSearch && !showFilter) {
        if (categoryDrillFromChart) {
            categoryDrillFromChart = false
            showSpendingChart = true
        }
        viewModel?.closeCategoryTransactions()
    }

    BackHandler(enabled = showSpendingChart) {
        showSpendingChart = false
    }

    BackHandler(enabled = showCategoryReview) {
        showCategoryReview = false
    }

    BackHandler(enabled = showAiCategories) {
        showAiCategories = false
    }

    val latestTransactions by viewModel?.allTransactions?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyList<UnifiedTransaction>()) }
    val selected = latestTransactions.firstOrNull { it.key == selectedKey } ?: selectedFallback?.takeIf { it.key == selectedKey }
    val closeDetails = { selectedKey = null; selectedFallback = null }
    val detailsState = selected?.let { rememberTransactionDetailsState(it.key) }
    val largeNavigationText = LocalDensity.current.fontScale > 1.3f
    var settingsSourceOpen by rememberSaveable { mutableStateOf(false) }
    fun navigationLabel(item: Destination) =
        if (largeNavigationText && item == Destination.TRANSACTIONS) "Payments" else item.label
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val showDetailsPane = maxWidth >= 1080.dp && maxHeight >= 480.dp && destination == Destination.TRANSACTIONS && !showSearch && selected != null
        val showRail = maxWidth >= 600.dp && maxHeight >= 480.dp
        Scaffold(
            snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHost) },
            modifier = if (showSearch) Modifier.clearAndSetSemantics {} else Modifier,
            topBar = {
                if (!settingsSourceOpen || destination != Destination.SETTINGS) {
                TopAppBar(
                    navigationIcon = {
                        if (destination == Destination.TRANSACTIONS && categoryFilter != null) {
                            IconButton(onClick = {
                                if (categoryDrillFromChart) {
                                    categoryDrillFromChart = false
                                    showSpendingChart = true
                                }
                                viewModel?.closeCategoryTransactions()
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = if (categoryDrillFromChart) "Back to Chart" else "Back to Analysis",
                                )
                            }
                        }
                    },
                    title = { Text(destination.label, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        if (destination == Destination.TRANSACTIONS) {
                            IconButton(onClick = onRefresh, enabled = onExitDemo == null && (!refreshing || automaticImport)) {
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
                }
            },
            bottomBar = {
                if (!showRail) {
                    NavigationBar {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(navigationLabel(item), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (showRail) {
                    NavigationRail(modifier = Modifier.width(if (largeNavigationText) 128.dp else 80.dp)) {
                        Destination.entries.forEach { item ->
                            NavigationRailItem(
                                modifier = Modifier.fillMaxWidth(),
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(navigationLabel(item), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                    if (refreshing) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
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
                            Destination.ANALYSIS -> {
                                if (viewModel != null) {
                                    AnalysisScreen(
                                        viewModel = viewModel,
                                        onOpenTransaction = { selectedKey = it.key; selectedFallback = it },
                                        onOpenCategories = { aiCategoriesPage = "CATEGORIES"; showAiCategories = true },
                                        onOpenChart = { showSpendingChart = true },
                                        onOpenCategoryReview = { showCategoryReview = true },
                                        onOpenAiCategories = { aiCategoriesPage = "HUB"; showAiCategories = true },
                                    )
                                }
                            }
                            Destination.SETTINGS -> if (onExitDemo != null) Column(Modifier.padding(24.dp)) {
                                Text("Fictional payments and example AI analysis", style = MaterialTheme.typography.bodyLarge)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Demo mode", modifier = Modifier.weight(1f))
                                    Switch(checked = true, onCheckedChange = { if (!it) onExitDemo() })
                                }
                            } else ProvidersScreen(
                                enabled = enabledProviders,
                                syncingProvider = syncingProvider,
                                providerCounts = providerCounts,
                                onEnabled = onProviderEnabled,
                                viewModel = viewModel,
                                onDetailOpenChanged = { settingsSourceOpen = it },
                                onOpenDemo = { net.wastu.artha.DemoMode.open(context) },
                                onOpenCategoryReview = { showCategoryReview = true },
                                onOpenAiCategories = { aiCategoriesPage = "HUB"; showAiCategories = true },
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
                    viewModel = viewModel,
                    onRetry = onRetryLedger,
                )
            }
        }
        if (showAiCategories && viewModel != null) {
            Surface(Modifier.fillMaxSize()) {
                AiCategoriesScreen(
                    viewModel = viewModel,
                    onBack = { showAiCategories = false },
                    onOpenReview = { showCategoryReview = true },
                    initialPage = aiCategoriesPage,
                )
            }
        }
        if (showSpendingChart && viewModel != null) {
            Surface(Modifier.fillMaxSize()) {
                SpendingChartScreen(
                    viewModel = viewModel,
                    onBack = { showSpendingChart = false },
                    onOpenCategory = { slice ->
                        categoryDrillFromChart = true
                        viewModel.openCategoryTransactions(slice.name, slice.categories)
                        showSpendingChart = false
                    },
                )
            }
        }
        if (showCategoryReview && viewModel != null) {
            Surface(Modifier.fillMaxSize()) {
                CategoryReviewScreen(
                    viewModel = viewModel,
                    onBack = { showCategoryReview = false },
                    onOpenTransaction = { selectedKey = it.key; selectedFallback = it },
                )
            }
        }
        if (!showDetailsPane && selected != null) {
            if (detailsState != null) TransactionDetailsSheet(selected, viewModel = viewModel, state = detailsState, onDismiss = closeDetails)
        }
    }
}
