package net.wastu.artha.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.Page

/** A page and how deep in the stack it sits, so the same page opened at another level still animates. */
private data class PageEntry(val page: Page, val depth: Int)

private val PAGE_SLIDE_DISTANCE = 30.dp

/** Full-screen pages stacked above Home; deeper pages slide in from the end. */
@Composable
internal fun HomePages(
    top: Page?,
    depth: Int,
    viewModel: ArthaViewModel?,
    enabledProviders: Set<Provider>,
    syncingProvider: Provider?,
    providerCounts: Map<Provider, Int>,
    onProviderEnabled: (Provider, Boolean) -> Unit,
    onOpen: (Page) -> Unit,
    onBack: () -> Unit,
    onOpenTransaction: (UnifiedTransaction) -> Unit,
) {
    val distancePx = with(LocalDensity.current) { PAGE_SLIDE_DISTANCE.roundToPx() }
    AnimatedContent(
        targetState = top?.let { PageEntry(it, depth) },
        transitionSpec = {
            val forward = targetState != null && (initialState == null || targetState!!.depth >= initialState!!.depth)
            ScreenMotion.sharedAxisX(forward, distancePx).using(null)
        },
        label = "page",
    ) { entry ->
        val page = entry?.page
        if (page != null && viewModel != null) Surface(Modifier.fillMaxSize()) {
            when (page) {
                Page.SOURCES -> SourcesScreen(
                    enabled = enabledProviders,
                    syncingProvider = syncingProvider,
                    providerCounts = providerCounts,
                    onEnabled = onProviderEnabled,
                    viewModel = viewModel,
                    onBack = onBack,
                )
                Page.CATEGORIES -> CategoriesScreen(viewModel, onBack = onBack)
                Page.AI -> AiScreen(viewModel, onBack = onBack, onOpenReview = { onOpen(Page.REVIEW) })
                Page.REMOTE -> RemoteAccessScreen(onBack = onBack)
                Page.DATA -> DataScreen(viewModel, onBack = onBack)
                Page.REVIEW -> CategoryReviewScreen(
                    viewModel = viewModel,
                    onBack = onBack,
                    month = viewModel.selectedMonth.collectAsStateWithLifecycle().value,
                    onOpenTransaction = onOpenTransaction,
                )
                Page.UPDATES -> UpdatesScreen(onBack = onBack)
                Page.LOG -> ActivityLogScreen(onBack = onBack)
            }
        }
    }
}
