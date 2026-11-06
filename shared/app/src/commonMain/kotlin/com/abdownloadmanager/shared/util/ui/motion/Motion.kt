package com.abdownloadmanager.shared.util.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's motion language. Motion is spent on three moments only: a window arriving,
 * progress gliding, and a download landing. Frequent interactions (clicks, hovers, scrolling)
 * stay instant.
 */
object Motion {
    /** Entrances: quick start, long soft landing. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Changes to something that stays on screen. */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val SHORT = 200
    const val MEDIUM = 320
    const val LONG = 480
}

/** True when the OS asks for less motion; every helper below then jumps straight to its end state. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Content arriving on screen: fades in, rises a few dp and settles from 98.5% scale.
 * Plays once when first composed. Animates only the graphics layer, so nothing re-lays out.
 */
@Composable
fun Modifier.arrival(delayMillis: Int = 0, rise: Dp = 14.dp): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduceMotion) {
            progress.animateTo(1f, tween(Motion.LONG, delayMillis, Motion.EmphasizedDecelerate))
        }
    }
    val risePx = with(LocalDensity.current) { rise.toPx() }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * risePx
        val scale = 0.985f + 0.015f * p
        scaleX = scale
        scaleY = scale
    }
}

/** A single confident beat for a finished state, such as the completion check. */
@Composable
fun Modifier.landing(): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduceMotion) {
            progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f))
        }
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        val scale = 0.4f + 0.6f * p
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Progress that glides between the engine's ticks instead of stepping.
 * Critically damped, so it never overshoots past the real value.
 */
@Composable
fun glidingProgress(target: Float): Float {
    val reduceMotion = LocalReduceMotion.current
    return animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 1f, stiffness = 90f),
        label = "progress",
    ).value
}
