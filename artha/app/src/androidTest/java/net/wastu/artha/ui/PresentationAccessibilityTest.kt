package net.wastu.artha.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.wastu.artha.core.model.*
import net.wastu.artha.ui.screens.IconPickerDialog
import net.wastu.artha.ui.screens.TransactionRow
import net.wastu.artha.ui.theme.ArthaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.math.pow

@RunWith(AndroidJUnit4::class)
class PresentationAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullPaymentAmountAtNormalFont() = amountAtScale(1f)
    @Test fun fullPaymentAmountAt150Percent() = amountAtScale(1.5f)
    @Test fun fullPaymentAmountAt200Percent() = amountAtScale(2f)

    private fun amountAtScale(scale: Float) {
        val tx = UnifiedTransaction(Provider.JAGO, "fictional-accessibility", Direction.DEBIT,
            Money(12_345_678_900), Instant.parse("2026-10-13T02:00:00Z"), "Fictional long merchant payment", "{}", isConfirmed = true)
        var pixelsPerDp = 1f
        compose.setContent {
            val density = LocalDensity.current.density
            pixelsPerDp = density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                ArthaTheme(dynamicColor = false) { Column { TransactionRow(tx, {}) } }
            }
        }
        val amount = compose.onNodeWithText("−${formatMoney(tx.money)}", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(amount.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
        assertTrue("Amount must have a measured layout", layouts.isNotEmpty())
        assertFalse("Full amount must not overflow at scale $scale", layouts.single().hasVisualOverflow)
        val row = compose.onNode(hasClickAction()).fetchSemanticsNode()
        assertTrue(row.boundsInRoot.height >= 48 * pixelsPerDp - 1)
    }

    @Test fun iconSelectionHasNamedNonoverlappingTargets() {
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            ArthaTheme(dynamicColor = false) { IconPickerDialog("coffee", {}, {}) }
        }
        compose.onNodeWithContentDescription("Coffee").assertIsSelected().assertIsDisplayed()
        val targets = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
        targets.forEach { node ->
            assertTrue("Control width", node.boundsInRoot.width >= 48 * density - 1)
            assertTrue("Control height", node.boundsInRoot.height >= 48 * density - 1)
        }
        targets.forEachIndexed { index, node -> targets.drop(index + 1).forEach { other ->
            assertFalse("Control bounds overlap", node.boundsInRoot.overlaps(other.boundsInRoot))
        } }
    }

    @Test fun actualDeviceDynamicThemeTextPairsMeetContrast() {
        if (Build.VERSION.SDK_INT < 31) return
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        listOf(dynamicLightColorScheme(context), dynamicDarkColorScheme(context)).forEach { colors ->
            val surfaces = listOf(colors.surface, colors.surfaceContainer, colors.surfaceContainerLow, colors.surfaceContainerHigh)
            val pairs = surfaces.flatMap { bg -> listOf(colors.onSurface, colors.onSurfaceVariant, colors.primary, colors.error).map { it to bg } } +
                listOf(colors.onPrimary to colors.primary, colors.onSecondaryContainer to colors.secondaryContainer,
                    colors.onPrimaryContainer to colors.primaryContainer, colors.onErrorContainer to colors.errorContainer)
            val minimum = pairs.minOf { contrast(it.first, it.second) }
            android.util.Log.i("ArthaAccessibility", "Dynamic theme minimum tested contrast=$minimum")
            assertTrue("Actual dynamic theme minimum contrast $minimum", minimum >= 4.5)
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        fun luminance(color: Color): Double {
            val argb = color.toArgb()
            fun channel(shift: Int): Double {
                val value = ((argb ushr shift) and 255) / 255.0
                return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
            }
            return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
        }
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
}
