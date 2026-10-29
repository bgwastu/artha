package net.wastu.artha.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalAtm
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material.icons.filled.Wifi
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
    "account_balance" to Icons.Default.AccountBalance,
    "savings" to Icons.Default.Savings,
    "payments" to Icons.Default.Payments,
    "atm" to Icons.Default.LocalAtm,
    "paid" to Icons.Default.Paid,
    "percent" to Icons.Default.Percent,
    "exchange" to Icons.Default.CurrencyExchange,
    "storefront" to Icons.Default.Storefront,
    "sell" to Icons.Default.Sell,
    "volunteer" to Icons.Default.VolunteerActivism,
    "redeem" to Icons.Default.Redeem,
    "handshake" to Icons.Default.Handshake,
    "apartment" to Icons.Default.Apartment,
    "undo" to Icons.Default.Undo,
    "fuel" to Icons.Default.LocalGasStation,
    "bus" to Icons.Default.DirectionsBus,
    "train" to Icons.Default.Train,
    "motorbike" to Icons.Default.TwoWheeler,
    "phone" to Icons.Default.Smartphone,
    "wifi" to Icons.Default.Wifi,
    "electricity" to Icons.Default.Bolt,
    "water" to Icons.Default.WaterDrop,
    "hospital" to Icons.Default.LocalHospital,
    "medication" to Icons.Default.Medication,
    "child" to Icons.Default.ChildCare,
    "laundry" to Icons.Default.LocalLaundryService,
    "hotel" to Icons.Default.Hotel,
    "subscriptions" to Icons.Default.Subscriptions,
    "cloud" to Icons.Default.Cloud,
    "computer" to Icons.Default.Computer,
    "haircut" to Icons.Default.ContentCut,
    "art" to Icons.Default.Brush,
    "book" to Icons.Default.Book,
    "tools" to Icons.Default.Construction,
    "furniture" to Icons.Default.Weekend,
    "celebration" to Icons.Default.Celebration,
    "cake" to Icons.Default.Cake,
    "fastfood" to Icons.Default.Fastfood,
    "bar" to Icons.Default.LocalBar,
    "insurance" to Icons.Default.Security,
    "legal" to Icons.Default.Gavel,
)

val DEFAULT_CATEGORY_ICONS: Map<String, String> = mapOf(
    "Groceries" to "shopping_cart",
    "Dining Out" to "restaurant",
    "Tech & Subscriptions" to "devices",
    "Utilities & Bills" to "receipt",
    "Transport" to "car",
    "Medical & Health" to "health",
    "Shopping & Gear" to "shopping_bag",
    "Personal Care" to "spa",
    "Travel & Lodging" to "flight",
    "Taxes & Admin" to "receipt",
    "Investments & Transfers" to "trending_up",
    "Uncategorized" to "default",
    // Aliases & legacy defaults
    "Food & Drink" to "restaurant",
    "Shopping & Hardware" to "shopping_bag",
    "Clothing & Apparel" to "clothing",
    "Health & Fitness" to "fitness",
    "Family & Social" to "gift",
    "Family & Gifts" to "gift",
    "Bills & Telco" to "receipt",
    "Cash" to "wallet",
    "Cash & ATM" to "wallet",
    "Taxes & Fees" to "receipt",
    "Entertainment & Games" to "games",
    // Income
    "Salary" to "payments",
    "Business" to "storefront",
    "Interest & Returns" to "savings",
    "Gifts" to "redeem",
    "Reimbursements" to "undo",
    "Other Income" to "paid",
)

private val ICON_MAP: Map<String, ImageVector> = AVAILABLE_CATEGORY_ICONS.toMap()

fun categoryIconFor(category: String, customIcons: Map<String, String> = emptyMap()): ImageVector {
    val key = customIcons[category] ?: DEFAULT_CATEGORY_ICONS[category] ?: "default"
    return ICON_MAP[key] ?: DEFAULT_CATEGORY_ICON
}

fun categoryIconLabel(key: String): String = key.replace('_', ' ').replaceFirstChar { it.uppercase() }
