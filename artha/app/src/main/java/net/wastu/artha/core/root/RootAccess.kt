package net.wastu.artha.core.root

/** A fresh verification must succeed before accessing the Ledger or any Provider. */
fun interface RootAccess {
    suspend fun verify(): Boolean
}

enum class RootGateState { LOCKED, CHECKING, GRANTED, DENIED }
