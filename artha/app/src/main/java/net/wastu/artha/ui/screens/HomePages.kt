package net.wastu.artha.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.Page

/** Full-screen pages stacked above Home; deeper pages slide in from the end. */
@Composable
internal fun HomePages(
    top: Page?,
    viewModel: ArthaViewModel?,
    enabledProviders: Set<Provider>,
    syncingProvider: Provider?,
    providerCounts: Map<Provider, Int>,
    onProviderEnabled: (Provider, Boolean) -> Unit,
    onOpen: (Page) -> Unit,
    onBack: () -> Unit,
    onOpenTransaction: (UnifiedTransaction) -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = top,
        transitionSpec = {
            val deeper = targetState != null && (initialState == null || targetState!!.ordinal >= initialState!!.ordinal)
            val direction = if (deeper) 1 else -1
            (slideInHorizontally(motion.defaultSpatialSpec()) { direction * it / 4 } + fadeIn(motion.defaultEffectsSpec()))
                .togetherWith(slideOutHorizontally(motion.defaultSpatialSpec()) { -direction * it / 4 } + fadeOut(motion.defaultEffectsSpec()))
        },
        label = "page",
    ) { page ->
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
            }
        }
    }
}
