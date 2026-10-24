package net.wastu.artha.core.model

import androidx.annotation.DrawableRes
import net.wastu.artha.R

enum class Provider(
    val displayName: String,
    @get:DrawableRes val iconRes: Int,
    /** The source's own app, opened for recovery steps such as signing in. */
    val appPackage: String,
) {
    JAGO("Bank Jago", R.drawable.ic_provider_jago, "com.jago.digitalBanking"),
    GOJEK("Gojek", R.drawable.ic_provider_gojek, "com.gojek.app"),
    SHOPEE("Shopee", R.drawable.ic_provider_shopee, "com.shopee.id"),
    GRAB("Grab", R.drawable.ic_provider_grab, "com.grabtaxi.passenger"),
}
