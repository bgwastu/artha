package net.wastu.artha.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

internal val LightColors = lightColorScheme(
    primary = Color(0xFF276749),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3EEDB),
    onPrimaryContainer = Color(0xFF102D1F),
    secondary = Color(0xFF506353),
    secondaryContainer = Color(0xFFD3E8D4),
    onSecondaryContainer = Color(0xFF112715),
    tertiary = Color(0xFF3B6472),
    tertiaryContainer = Color(0xFFC0EAF7),
    onTertiaryContainer = Color(0xFF001F28),
    surface = Color(0xFFF7FAF5),
    onSurface = Color(0xFF18211B),
    onSurfaceVariant = Color(0xFF414A43),
    surfaceContainer = Color(0xFFEBF0E9),
    surfaceContainerLow = Color(0xFFF1F5EE),
    surfaceContainerHigh = Color(0xFFE5EAE3),
    outline = Color(0xFF717A72),
    outlineVariant = Color(0xFFC1CAC1),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF9BD5AE),
    onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF145132),
    onPrimaryContainer = Color(0xFFB6F1C8),
    secondary = Color(0xFFB7CCB8),
    secondaryContainer = Color(0xFF394C3D),
    onSecondaryContainer = Color(0xFFD3E8D4),
    tertiary = Color(0xFFA0CDDB),
    tertiaryContainer = Color(0xFF214C59),
    onTertiaryContainer = Color(0xFFC0EAF7),
    surface = Color(0xFF101510),
    onSurface = Color(0xFFDEE5DB),
    onSurfaceVariant = Color(0xFFC1CAC1),
    surfaceContainer = Color(0xFF1B211B),
    surfaceContainerLow = Color(0xFF171D17),
    surfaceContainerHigh = Color(0xFF252C25),
    outline = Color(0xFF8B958B),
    outlineVariant = Color(0xFF414A43),
)

@Composable
fun ArthaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun incomeColor(): Color = MaterialTheme.colorScheme.primary

@Composable
fun expenseColor(): Color = MaterialTheme.colorScheme.onSurface
