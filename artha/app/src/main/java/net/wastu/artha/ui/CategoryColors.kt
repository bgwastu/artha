package net.wastu.artha.ui

import androidx.compose.ui.graphics.Color

val DEFAULT_CATEGORY_COLORS: Map<String, Color> = mapOf(
    "Groceries" to Color(0xFF2B8A64),
    "Dining Out" to Color(0xFFD96B27),
    "Tech & Subscriptions" to Color(0xFF5B5CE5),
    "Utilities & Bills" to Color(0xFFC98218),
    "Transport" to Color(0xFF2575C0),
    "Medical & Health" to Color(0xFFD33A4E),
    "Shopping & Gear" to Color(0xFF0E8A8A),
    "Personal Care" to Color(0xFFB3527D),
    "Travel & Lodging" to Color(0xFF1D87A8),
    "Taxes & Admin" to Color(0xFF546E7A),
    "Investments & Transfers" to Color(0xFF1B8A5A),
    "Others" to Color(0xFF6B7280),
    "Uncategorized" to Color(0xFF9CA3AF),
    // Aliases & legacy categories for backwards compatibility
    "Health & Fitness" to Color(0xFFD33A4E),
    "Shopping & Hardware" to Color(0xFF0E8A8A),
    "Clothing & Apparel" to Color(0xFFB3527D),
    "Taxes & Fees" to Color(0xFF546E7A),
    "Family & Social" to Color(0xFFD96B27),
    "Family & Gifts" to Color(0xFFD96B27),
    "Cash" to Color(0xFF546E7A),
    "Cash & ATM" to Color(0xFF546E7A),
    "Business" to Color(0xFF5B5CE5),
    "Food & Drink" to Color(0xFFD96B27),
    "Bills & Telco" to Color(0xFFC98218),
    "Entertainment & Games" to Color(0xFF5B5CE5),
)

private val FALLBACK_PALETTE: List<Color> = listOf(
    Color(0xFF2B8A64),
    Color(0xFF2575C0),
    Color(0xFFD96B27),
    Color(0xFFD33A4E),
    Color(0xFF5B5CE5),
    Color(0xFFB3527D),
    Color(0xFF0E8A8A),
    Color(0xFFC98218),
)

fun categoryColorFor(category: String, isOther: Boolean = false): Color {
    if (isOther || category.equals("Others", ignoreCase = true)) return Color(0xFF6B7280)
    DEFAULT_CATEGORY_COLORS[category]?.let { return it }
    val hash = kotlin.math.abs(category.hashCode())
    return FALLBACK_PALETTE[hash % FALLBACK_PALETTE.size]
}
