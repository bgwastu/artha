package net.wastu.artha.core.model

import androidx.annotation.DrawableRes
import net.wastu.artha.R

/** Provider logos are app resources, so they live here rather than in the shared ingestion module. */
@get:DrawableRes
val Provider.iconRes: Int
    get() = when (this) {
        Provider.JAGO -> R.drawable.ic_provider_jago
        Provider.GOJEK -> R.drawable.ic_provider_gojek
        Provider.SHOPEE -> R.drawable.ic_provider_shopee
        Provider.GRAB -> R.drawable.ic_provider_grab
    }
