package net.wastu.artha.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

val DEFAULT_CATEGORY_ICON: ImageVector = Icons.AutoMirrored.Filled.Label

val AVAILABLE_CATEGORY_ICONS: List<Pair<String, ImageVector>> = listOf(
    "default" to Icons.AutoMirrored.Filled.Label,
    "restaurant" to Icons.Default.Restaurant,
    "coffee" to Icons.Default.LocalCafe,
    "shopping_cart" to Icons.Default.ShoppingCart,
    "shopping_bag" to Icons.Default.ShoppingBag,
    "clothing" to Icons.Default.Checkroom,
    "car" to Icons.Default.DirectionsCar,
    "flight" to Icons.Default.Flight,
    "home" to Icons.Default.Home,
    "receipt" to Icons.AutoMirrored.Filled.ReceiptLong,
    "devices" to Icons.Default.Devices,
    "fitness" to Icons.Default.FitnessCenter,
    "health" to Icons.Default.Favorite,
    "spa" to Icons.Default.Spa,
    "gift" to Icons.Default.CardGiftcard,
    "games" to Icons.Default.SportsEsports,
    "movie" to Icons.Default.Movie,
    "music" to Icons.Default.MusicNote,
    "pets" to Icons.Default.Pets,
    "school" to Icons.Default.School,
    "work" to Icons.Default.Work,
    "trending_up" to Icons.AutoMirrored.Filled.TrendingUp,
    "wallet" to Icons.Default.AccountBalanceWallet,
    "build" to Icons.Default.Build,
)

val DEFAULT_CATEGORY_ICONS: Map<String, String> = mapOf(
    "Dining Out" to "restaurant",
    "Food & Drink" to "restaurant",
    "Groceries" to "shopping_cart",
    "Transport" to "car",
    "Tech & Subscriptions" to "devices",
    "Shopping & Hardware" to "shopping_bag",
    "Clothing & Apparel" to "clothing",
    "Travel & Lodging" to "flight",
    "Utilities & Bills" to "receipt",
    "Bills & Telco" to "receipt",
    "Health & Fitness" to "fitness",
    "Personal Care" to "spa",
    "Family & Social" to "gift",
    "Family & Gifts" to "gift",
    "Cash" to "wallet",
    "Cash & ATM" to "wallet",
    "Taxes & Fees" to "receipt",
    "Business" to "work",
    "Investments & Transfers" to "trending_up",
    "Entertainment & Games" to "games",
    "Uncategorized" to "default",
)

private val ICON_MAP: Map<String, ImageVector> = AVAILABLE_CATEGORY_ICONS.toMap()

fun categoryIconFor(category: String, customIcons: Map<String, String> = emptyMap()): ImageVector {
    val key = customIcons[category] ?: DEFAULT_CATEGORY_ICONS[category] ?: "default"
    return ICON_MAP[key] ?: DEFAULT_CATEGORY_ICON
}

fun categoryIconLabel(key: String): String = key.replace('_', ' ').replaceFirstChar { it.uppercase() }
