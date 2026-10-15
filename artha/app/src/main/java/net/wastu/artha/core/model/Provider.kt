package net.wastu.artha.core.model

import androidx.annotation.DrawableRes
import net.wastu.artha.R

enum class Provider(
    val displayName: String,
    @get:DrawableRes val iconRes: Int,
) {
    JAGO("Bank Jago", R.drawable.ic_provider_jago),
    GOJEK("Gojek", R.drawable.ic_provider_gojek),
    SHOPEE("Shopee", R.drawable.ic_provider_shopee),
    GRAB("Grab", R.drawable.ic_provider_grab),
}
