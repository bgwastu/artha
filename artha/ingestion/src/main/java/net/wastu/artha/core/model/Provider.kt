package net.wastu.artha.core.model

enum class Provider(
    val displayName: String,
    /** The source's own app, opened for recovery steps such as signing in. */
    val appPackage: String,
) {
    JAGO("Bank Jago", "com.jago.digitalBanking"),
    GOJEK("Gojek", "com.gojek.app"),
    SHOPEE("Shopee", "com.shopee.id"),
    GRAB("Grab", "com.grabtaxi.passenger"),
}
