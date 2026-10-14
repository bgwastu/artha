package net.wastu.artha.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.File
import net.wastu.artha.ui.widget.WidgetPalette
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.math.pow

class PresentationContrastTest {
    @Test fun fallbackThemesHaveReadableTextAndActionPairs() {
        listOf(LightColors, DarkColors).forEach { colors ->
            presentationPairs(colors).forEach { (label, foreground, background) ->
                val ratio = contrastRatio(foreground.toArgb(), background.toArgb())
                assertTrue("$label contrast $ratio", ratio >= 4.5)
            }
        }
    }

    @Test fun paymentRolesAreDistinctOpaqueAndReadableAcrossFallbackSurfaces() {
        listOf(LightColors, DarkColors).forEach { colors ->
            val amounts = paymentAmountColors(colors)
            assertNotEquals("Income and expense must have distinct hues", amounts.income.toArgb(), amounts.expense.toArgb())
            assertTrue("Income uses a muted green", amounts.income.green > amounts.income.red)
            assertTrue("Expenses use a muted red", amounts.expense.red > amounts.expense.green)
            assertTrue("Income must look green rather than gray", amounts.income.green - amounts.income.red >= .12f)
            assertTrue("Expenses must look red rather than gray", amounts.expense.red - amounts.expense.green >= .12f)
            listOf(amounts.income, amounts.expense).forEach { foreground ->
                assertTrue("Financial text is opaque", foreground.alpha == 1f)
                paymentAmountSurfaces(colors).forEach { background ->
                    val contrast = contrastRatio(foreground.toArgb(), background.toArgb())
                    assertTrue("Payment text contrast $contrast", contrast >= 4.5)
                }
            }
        }
    }

    @Test fun paymentTintsAdaptToChangedSurfaceAndForegroundRoles() {
        // Dynamic themes change these roles. Exercise the same resolver with contrasting inputs.
        val backgrounds = listOf(Color(0xFFF5EDEA), Color(0xFF282530), Color(0xFF888888))
        backgrounds.forEach { surface ->
            val changed = LightColors.copy(surface = surface, surfaceContainer = surface,
                surfaceContainerLow = surface, surfaceContainerHigh = surface,
                surfaceContainerHighest = surface, surfaceContainerLowest = surface,
                surfaceVariant = surface, surfaceDim = surface, surfaceBright = surface,
                onSurface = Color(0xFF666666))
            val amounts = paymentAmountColors(changed)
            assertNotEquals(amounts.income.toArgb(), amounts.expense.toArgb())
            listOf(amounts.income, amounts.expense).forEach { foreground ->
                paymentAmountSurfaces(changed).forEach { background ->
                    assertTrue(contrastRatio(foreground.toArgb(), background.toArgb()) >= 4.5)
                }
            }
        }
    }

    @Test fun widgetPaletteAndActualLayoutTextColorsContrastWithItsBackground() {
        listOf(WidgetPalette.text, WidgetPalette.supporting, WidgetPalette.income, WidgetPalette.expense).forEach {
            assertTrue(contrastRatio(it, WidgetPalette.background) >= 4.5)
        }
        val resources = listOf(File("src/main/res"), File("app/src/main/res"), File("artha/app/src/main/res"))
            .first { it.isDirectory }
        val drawable = File(resources, "drawable/bg_widget_analysis.xml").readText()
        assertTrue(drawable.contains("#1A1C1E"))
        listOf("widget_analysis.xml", "widget_transactions.xml", "widget_transaction_item.xml").forEach { name ->
            val xml = File(resources, "layout/$name").readText()
            Regex("android:textColor=\"#([0-9A-Fa-f]{6})\"").findAll(xml).forEach { match ->
                val color = (0xff000000L or match.groupValues[1].toLong(16)).toInt()
                assertTrue("$name ${match.value}", contrastRatio(color, WidgetPalette.background) >= 4.5)
            }
            assertTrue("$name has placeholder money", !Regex("android:text=\"[^\"]*Rp").containsMatchIn(xml))
        }
    }
}

internal fun presentationPairs(colors: ColorScheme): List<Triple<String, Color, Color>> = buildList {
    listOf(colors.surface, colors.surfaceContainer, colors.surfaceContainerLow, colors.surfaceContainerHigh).forEach { surface ->
        add(Triple("body", colors.onSurface, surface))
        add(Triple("supporting", colors.onSurfaceVariant, surface))
        add(Triple("income/action", colors.primary, surface))
        add(Triple("error", colors.error, surface))
    }
    add(Triple("primary button", colors.onPrimary, colors.primary))
    add(Triple("tonal button", colors.onSecondaryContainer, colors.secondaryContainer))
    add(Triple("selected icon", colors.onPrimaryContainer, colors.primaryContainer))
    add(Triple("error region", colors.onErrorContainer, colors.errorContainer))
}

internal fun contrastRatio(foreground: Int, background: Int): Double {
    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((argb ushr shift) and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
    val a = luminance(foreground)
    val b = luminance(background)
    return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
}
