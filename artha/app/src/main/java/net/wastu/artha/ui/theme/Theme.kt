package net.wastu.artha.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

internal val LightColors = lightColorScheme(
    primary = Color(0xFF006D40),
    surfaceTint = Color(0xFF006D40),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF8CF8BA),
    onPrimaryContainer = Color(0xFF002110),
    secondary = Color(0xFF4F6354),
    secondaryContainer = Color(0xFFD2E8D5),
    onSecondaryContainer = Color(0xFF0C1F13),
    tertiary = Color(0xFF3B6471),
    tertiaryContainer = Color(0xFFBFE9F8),
    onTertiaryContainer = Color(0xFF001F27),
    background = Color(0xFFF6FAF3),
    onBackground = Color(0xFF181D19),
    surface = Color(0xFFF6FAF3),
    surfaceVariant = Color(0xFFDCE5DD),
    surfaceBright = Color(0xFFF6FAF3),
    surfaceDim = Color(0xFFD7DED6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFDCE5DD),
    onSurface = Color(0xFF181D19),
    onSurfaceVariant = Color(0xFF404943),
    surfaceContainer = Color(0xFFEBEFE8),
    surfaceContainerLow = Color(0xFFF0F5EE),
    surfaceContainerHigh = Color(0xFFE5EAE2),
    outline = Color(0xFF707973),
    outlineVariant = Color(0xFFC0C9C1),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF6CDBA0),
    surfaceTint = Color(0xFF6CDBA0),
    onPrimary = Color(0xFF00391E),
    primaryContainer = Color(0xFF00522E),
    onPrimaryContainer = Color(0xFF8CF8BA),
    secondary = Color(0xFFB6CCB9),
    secondaryContainer = Color(0xFF374B3D),
    onSecondaryContainer = Color(0xFFD2E8D5),
    tertiary = Color(0xFFA3CDDC),
    tertiaryContainer = Color(0xFF224C58),
    onTertiaryContainer = Color(0xFFBFE9F8),
    background = Color(0xFF0F1511),
    onBackground = Color(0xFFDFE4DD),
    surface = Color(0xFF0F1511),
    surfaceVariant = Color(0xFF404943),
    surfaceBright = Color(0xFF353B36),
    surfaceDim = Color(0xFF0F1511),
    surfaceContainerLowest = Color(0xFF0A0F0C),
    surfaceContainerHighest = Color(0xFF2F3631),
    onSurface = Color(0xFFDFE4DD),
    onSurfaceVariant = Color(0xFFC0C9C1),
    surfaceContainer = Color(0xFF1B211D),
    surfaceContainerLow = Color(0xFF171D19),
    surfaceContainerHigh = Color(0xFF252C27),
    outline = Color(0xFF8A938C),
    outlineVariant = Color(0xFF404943),
)

@Composable
fun ArthaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val accents = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialExpressiveTheme(
        colorScheme = neutralSurfaces(accents, darkTheme),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/** Keep page backgrounds calm while retaining wallpaper-derived action and selection accents. */
internal fun neutralSurfaces(accents: ColorScheme, dark: Boolean): ColorScheme {
    val neutral = if (dark) DarkColors else LightColors
    return accents.copy(
        background = neutral.background, onBackground = neutral.onBackground,
        surface = neutral.surface, onSurface = neutral.onSurface, surfaceTint = neutral.surfaceTint,
        surfaceVariant = neutral.surfaceVariant, onSurfaceVariant = neutral.onSurfaceVariant,
        surfaceBright = neutral.surfaceBright, surfaceDim = neutral.surfaceDim,
        surfaceContainer = neutral.surfaceContainer, surfaceContainerLow = neutral.surfaceContainerLow,
        surfaceContainerHigh = neutral.surfaceContainerHigh, surfaceContainerLowest = neutral.surfaceContainerLowest,
        surfaceContainerHighest = neutral.surfaceContainerHighest,
        outline = neutral.outline, outlineVariant = neutral.outlineVariant,
    )
}

internal data class PaymentAmountColors(val income: Color, val expense: Color)

internal fun paymentAmountSurfaces(colors: ColorScheme): List<Color> = listOf(
    colors.surface, colors.surfaceContainer, colors.surfaceContainerLow,
    colors.surfaceContainerHigh, colors.surfaceContainerHighest, colors.surfaceContainerLowest,
    colors.surfaceVariant, colors.surfaceDim, colors.surfaceBright,
)

/** Quiet financial text hues. Direction also remains explicit in signs and accessible labels. */
internal fun paymentAmountColors(colors: ColorScheme): PaymentAmountColors {
    val backgrounds = paymentAmountSurfaces(colors)
    fun minimumContrast(foreground: Color): Float = backgrounds.minOf { background ->
        val first = foreground.luminance()
        val second = background.luminance()
        (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
    }
    val base = colors.onSurface.takeIf { minimumContrast(it) >= 4.6f }
        ?: listOf(Color.Black, Color.White).maxBy { minimumContrast(it) }
    val dark = colors.surface.luminance() < .5f
    fun readableTint(reference: Color): Color {
        // Start with the muted hue; blend toward neutral only to recover contrast.
        for (step in 20 downTo 1) {
            val tinted = lerp(base, reference, step * .05f)
            if (minimumContrast(tinted) >= 4.6f) return tinted
        }
        return base
    }
    return PaymentAmountColors(
        income = readableTint(if (dark) Color(0xFF98C9AC) else Color(0xFF2F6A4F)),
        expense = readableTint(if (dark) Color(0xFFD8A6A6) else Color(0xFF8C4545)),
    )
}

@Composable
fun incomeColor(): Color {
    val colors = MaterialTheme.colorScheme
    return remember(colors) { paymentAmountColors(colors).income }
}

@Composable
fun expenseColor(): Color {
    val colors = MaterialTheme.colorScheme
    return remember(colors) { paymentAmountColors(colors).expense }
}
