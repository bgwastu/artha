package net.wastu.artha.data.providers

import net.wastu.artha.core.model.Provider

internal val Provider.historyCoverageHint: String
    get() = when (this) {
        Provider.JAGO -> "History saved in the Jago app"
        Provider.SHOPEE -> "Marketplace, food, and digital product purchases"
        Provider.GOJEK -> "Saved GoPay history. Cash and direct card payments are not supported"
        Provider.GRAB -> "GrabPay/OVO payments and recent rides. Full food history, cash and refunds are not supported"
    }

/** Exact previous capability text; never treat an arbitrary provider error as a limitation. */
internal val Provider.legacyCoverageHint: String
    get() = when (this) {
        Provider.JAGO -> "Cached bank history only"
        Provider.SHOPEE -> "Verified marketplace/food payments; bounded history; digital purchases excluded"
        Provider.GOJEK -> "Verified native GoPay cache and exact booking enrichment; live/cash history may be unavailable"
        Provider.GRAB -> "Settled card charges and recent rides; other wallet/cash and food receipts remain unverified"
    }
