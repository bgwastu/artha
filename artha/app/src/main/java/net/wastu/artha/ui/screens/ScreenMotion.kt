package net.wastu.artha.ui.screens

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.unit.IntOffset

/**
 * Motion for whole screens. The theme's expressive springs overshoot, which suits small components but makes
 * a full-screen surface slide past its final position and flash whatever sits behind it, so screens move with
 * the critically damped standard scheme. The outgoing screen clears first, then the incoming one arrives.
 */
internal object ScreenMotion {
    private val standard = MotionScheme.standard()
    private const val OUT_MILLIS = 90
    private const val IN_MILLIS = 210
    private const val SCALE_FROM = 0.92f

    private fun arrive() = fadeIn(tween(IN_MILLIS, delayMillis = OUT_MILLIS, easing = LinearOutSlowInEasing))
    private fun leave() = fadeOut(tween(OUT_MILLIS, easing = FastOutLinearInEasing))

    /** Shared axis: a screen one level deeper arrives from the end, and going back returns toward the start. */
    fun sharedAxisX(forward: Boolean, distancePx: Int): ContentTransform {
        val direction = if (forward) 1 else -1
        val spatial = standard.defaultSpatialSpec<IntOffset>()
        return (slideInHorizontally(spatial) { direction * distancePx } + arrive())
            .togetherWith(slideOutHorizontally(spatial) { -direction * distancePx } + leave())
    }

    /** Fade through: sibling top-level destinations swap without implying a direction. */
    fun fadeThrough(): ContentTransform =
        (arrive() + scaleIn(tween(IN_MILLIS, delayMillis = OUT_MILLIS, easing = LinearOutSlowInEasing), initialScale = SCALE_FROM))
            .togetherWith(leave())

    /** An overlay such as search grows in from slightly smaller and fades away on dismissal. */
    val overlayEnter: EnterTransition =
        fadeIn(tween(IN_MILLIS, easing = LinearOutSlowInEasing)) +
            scaleIn(tween(IN_MILLIS, easing = LinearOutSlowInEasing), initialScale = 0.96f)
    val overlayExit: ExitTransition = fadeOut(tween(OUT_MILLIS, easing = FastOutLinearInEasing))
}
