package net.wastu.artha.data.attention

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import net.wastu.artha.core.model.Provider
import net.wastu.artha.data.providers.JagoPinCheck
import net.wastu.artha.data.providers.JagoPinStatus
import net.wastu.artha.data.providers.ProviderPreferences

/** Where an item belongs, so each screen can pick the items it owns. */
sealed interface AttentionScope {
    data class Source(val provider: Provider) : AttentionScope
}

/** What the item's button does. Screens decide how to carry it out. */
enum class AttentionAction { SET_JAGO_PIN }

/** Something the user should fix, e.g. a missing PIN. Shown as a card and on the rows leading to it. */
data class NeedsAttention(
    val id: String,
    val scope: AttentionScope,
    /** Short, fits a list row: "Background PIN not set". */
    val title: String,
    /** One or two sentences on what happens if it stays this way. */
    val message: String,
    val action: AttentionAction? = null,
    val actionLabel: String? = null,
) {
    /** Title with its scope, for rows outside that scope (e.g. Settings). */
    val label: String
        get() = when (scope) {
            is AttentionScope.Source -> "${scope.provider.displayName}: ${title.replaceFirstChar { it.lowercase() }}"
        }
}

/** The state every rule reads. Add a field here when a new rule needs more. */
internal data class AttentionInputs(
    val importEnabled: Set<Provider>,
    val jagoPinSet: Boolean,
    val jagoPinCheck: JagoPinCheck = JagoPinCheck(),
)

/** One check; returns an item when something needs the user. */
internal typealias AttentionRule = (AttentionInputs) -> NeedsAttention?

internal val jagoPinRule: AttentionRule = { inputs ->
    if (Provider.JAGO in inputs.importEnabled && !inputs.jagoPinSet) {
        NeedsAttention(
            id = "jago-pin",
            scope = AttentionScope.Source(Provider.JAGO),
            title = "Background PIN not set",
            message = "Without it, Bank Jago only syncs when you open the app on the bridge phone. " +
                "Set the PIN and Artha Bridge fetches new transactions on its own.",
            action = AttentionAction.SET_JAGO_PIN,
            actionLabel = "Set PIN",
        )
    } else null
}

internal val jagoPinWrongRule: AttentionRule = { inputs ->
    if (Provider.JAGO in inputs.importEnabled && inputs.jagoPinSet && inputs.jagoPinCheck.status == JagoPinStatus.WRONG) {
        NeedsAttention(
            id = "jago-wrong-pin",
            scope = AttentionScope.Source(Provider.JAGO),
            title = "Incorrect background PIN",
            message = "Artha Bridge was unable to unlock Bank Jago with this PIN. Update your 6-digit PIN so background syncing can resume.",
            action = AttentionAction.SET_JAGO_PIN,
            actionLabel = "Update PIN",
        )
    } else null
}

internal val attentionRules: List<AttentionRule> = listOf(jagoPinRule, jagoPinWrongRule)

internal fun evaluateAttention(inputs: AttentionInputs, rules: List<AttentionRule> = attentionRules): List<NeedsAttention> =
    rules.mapNotNull { it(inputs) }

/** Everything that currently needs attention, kept up to date as settings change. */
class AttentionRepository(providerPreferences: ProviderPreferences, scope: CoroutineScope) {
    val items: StateFlow<List<NeedsAttention>> = combine(
        providerPreferences.importEnabled,
        providerPreferences.jagoPinSet,
        providerPreferences.jagoPinCheck,
    ) { importEnabled, jagoPinSet, jagoPinCheck ->
        evaluateAttention(AttentionInputs(importEnabled, jagoPinSet, jagoPinCheck))
    }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            evaluateAttention(AttentionInputs(
                providerPreferences.importEnabled.value,
                providerPreferences.jagoPinSet.value,
                providerPreferences.jagoPinCheck.value
            )),
        )
}

fun List<NeedsAttention>.forSource(provider: Provider): List<NeedsAttention> =
    filter { (it.scope as? AttentionScope.Source)?.provider == provider }
