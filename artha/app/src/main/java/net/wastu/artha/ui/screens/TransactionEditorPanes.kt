package net.wastu.artha.ui.screens

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.ui.ArthaViewModel

/** Category picker that saves on selection and offers to apply the choice to the merchant's other payments. */
@Composable
internal fun CategoryEditorPane(
    record: UnifiedTransaction,
    viewModel: ArthaViewModel?,
    state: TransactionDetailsState,
    snapshot: AnalysisSnapshot?,
    currentCategory: String,
    availableCategories: List<String>,
    isBusy: Boolean,
    snackbarHostState: SnackbarHostState,
    // Owned by the caller: this pane leaves composition as soon as the save closes it, which would
    // cancel the "Category saved" snackbar launched from the save callback.
    scope: CoroutineScope,
    onClose: () -> Unit,
) {
    val selectCategory: (String) -> Unit = { category ->
        state.chooseCategory(
            category, currentCategory, isBusy = isBusy,
            persist = { selected, complete -> persistCategory(viewModel, record, selected, state, scope, snackbarHostState, complete) },
            onDone = onClose,
        )
    }
    CategoryPickerView(
        currentCategory = currentCategory,
        availableCategories = (availableCategories + currentCategory).distinct(),
        categoryIcons = snapshot?.categoryIcons.orEmpty(),
        saving = state.saving,
        error = state.saveError,
        onSelectCategory = selectCategory,
        onBack = onClose,
        onRetry = { selectCategory(state.categoryDraft) },
    )
}

private fun persistCategory(
    viewModel: ArthaViewModel?,
    record: UnifiedTransaction,
    category: String,
    state: TransactionDetailsState,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    complete: (Result<Unit>) -> Unit,
) {
    if (viewModel == null) {
        complete(Result.failure(IllegalStateException("Editor unavailable")))
        return
    }
    viewModel.setTransactionCategory(record, category) { result ->
        complete(result)
        if (result.isSuccess) {
            state.savedFeedback = null
            val peerCount = viewModel.merchantPeerCount(record, category)
            scope.launch {
                val outcome = snackbarHostState.showSnackbar(
                    message = "Category saved",
                    actionLabel = if (peerCount > 0) "Apply to $peerCount more" else null,
                    duration = SnackbarDuration.Short,
                )
                if (outcome == SnackbarResult.ActionPerformed && peerCount > 0) {
                    viewModel.applyToMerchant(record, category) { applied ->
                        val updated = applied.getOrDefault(peerCount)
                        scope.launch {
                            snackbarHostState.showSnackbar(message = "Updated $updated payments", duration = SnackbarDuration.Short)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NoteEditorPane(
    record: UnifiedTransaction,
    viewModel: ArthaViewModel?,
    state: TransactionDetailsState,
    isBusy: Boolean,
    onClose: () -> Unit,
) {
    TransactionNoteEditor(
        text = state.noteDraft,
        onTextChange = { state.noteDraft = it; state.saveError = null },
        saving = state.saving,
        error = state.saveError,
        onDismiss = onClose,
        onSave = {
            if (isBusy) return@TransactionNoteEditor
            state.saving = true
            viewModel?.setUserNote(record, state.noteDraft) { result ->
                state.saving = false
                if (result.isSuccess) {
                    state.savedFeedback = "Note saved"
                    onClose()
                } else state.saveError = "Couldn’t save note. Try again."
            }
        },
    )
}
