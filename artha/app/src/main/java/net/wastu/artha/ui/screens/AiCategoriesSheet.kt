package net.wastu.artha.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.wastu.artha.data.analysis.CategoryAutomationState
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.RecategorizeScope
import net.wastu.artha.data.analysis.categoryProgressLabel
import net.wastu.artha.data.analysis.recategorizeLabel
import java.time.YearMonth

internal object AiCategoriesLogic {
    fun statusSupportingText(progress: CategoryProgress): String? {
        if (progress.paused) return "Paused"
        return categoryProgressLabel(progress)
    }

    fun reviewLine(progress: CategoryProgress): String? {
        return if (progress.review > 0) "${progress.review} to review" else null
    }

    fun failedLine(progress: CategoryProgress): String? {
        return if (progress.failed > 0) "${progress.failed} couldn't be categorized" else null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiCategoriesSheet(
    categoryProgress: CategoryProgress,
    autoCategorize: Boolean,
    selectedMonth: YearMonth,
    onDismiss: () -> Unit,
    onSetAutoCategorize: (Boolean) -> Unit,
    onRecategorize: (RecategorizeScope) -> Unit,
    onRetryAutomaticCategories: () -> Unit,
    onOpenReview: () -> Unit,
) {
    var showConfirmAllDialog by remember { mutableStateOf(false) }
    var inlineFeedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(inlineFeedback) {
        if (inlineFeedback != null) {
            delay(2500L)
            inlineFeedback = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "AI categories",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            val statusText = AiCategoriesLogic.statusSupportingText(categoryProgress)
            val isWorking = categoryProgress.state == CategoryAutomationState.WORKING
            val isError = categoryProgress.state == CategoryAutomationState.ERROR

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (statusText != null) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (isWorking) {
                        val fraction = AiCardLogic.categoryProgressFraction(categoryProgress.labeled, categoryProgress.total)
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (isError) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = onRetryAutomaticCategories) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            inlineFeedback?.let { feedback ->
                Text(
                    text = feedback,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            ListItem(
                headlineContent = { Text("Auto-categorize") },
                supportingContent = { Text("Categorize new payments automatically") },
                trailingContent = {
                    Switch(
                        checked = autoCategorize,
                        onCheckedChange = onSetAutoCategorize,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ListItem(
                    headlineContent = { Text("Categorize now") },
                    modifier = Modifier.clickable {
                        onRecategorize(RecategorizeScope.Uncategorized)
                        onRetryAutomaticCategories()
                        inlineFeedback = "Started"
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )

                ListItem(
                    headlineContent = { Text(recategorizeLabel(RecategorizeScope.Month(selectedMonth))) },
                    modifier = Modifier.clickable {
                        onRecategorize(RecategorizeScope.Month(selectedMonth))
                        inlineFeedback = "Started"
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )

                ListItem(
                    headlineContent = { Text(recategorizeLabel(RecategorizeScope.Uncategorized)) },
                    modifier = Modifier.clickable {
                        onRecategorize(RecategorizeScope.Uncategorized)
                        inlineFeedback = "Started"
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )

                val reviewCount = categoryProgress.review
                val reviewEnabled = reviewCount > 0
                ListItem(
                    headlineContent = {
                        Text(
                            text = "Review $reviewCount",
                            color = if (reviewEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    },
                    modifier = Modifier.clickable(enabled = reviewEnabled) {
                        onOpenReview()
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )

                ListItem(
                    headlineContent = { Text("Re-categorize all") },
                    modifier = Modifier.clickable {
                        showConfirmAllDialog = true
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            Text(
                text = "Your own picks are never changed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    if (showConfirmAllDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmAllDialog = false },
            title = { Text("Re-categorize all") },
            text = { Text("Re-categorize ${categoryProgress.total} payments? Your own picks stay.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmAllDialog = false
                        onRecategorize(RecategorizeScope.All)
                        inlineFeedback = "Started"
                    },
                ) {
                    Text("Re-categorize")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmAllDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
