package net.wastu.artha.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/** Collects a view-model flow, or holds [default] when previews and tests have no view model. */
@Composable
fun <T> StateFlow<T>?.collectOrDefault(default: T): State<T> =
    this?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(default) }
