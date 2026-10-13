package net.wastu.artha.core.root

/** Imports require root; saved ledger viewing is independent of this gate. */
fun interface RootAccess {
    suspend fun verify(): Boolean
}

enum class RootGateState { LOCKED, CHECKING, GRANTED, DENIED }
