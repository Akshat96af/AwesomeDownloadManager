package com.abdownloadmanager.shared.util.ui.motion

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * The app's motion language.
 * - Things that arrive decelerate into place (fast start, long soft landing); things that leave accelerate away
 *   and take less time than arrivals, so the UI never waits on an exit.
 * - Anything the user drives (presses, toggles, hovers, sizes) uses springs: they are interruptible and keep
 *   their velocity when retargeted, so rapid input never snaps or stutters.
 * - Overshoot is reserved for small deliberate beats (a check mark, a menu popping open), never for layout.
 * Every helper here collapses to an instant change when [LocalReduceMotion] is set.
 */
object Motion {
    /** Entrances: quick start, long soft landing. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Exits: gathers speed and leaves. */
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Changes to something that stays on screen. */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val SHORT = 200
    const val MEDIUM = 320
    const val LONG = 480

    /** Presses, hovers, toggles: settles in about 120 ms with no visible wobble. */
    fun <T> snappy(visibilityThreshold: T? = null) = spring(dampingRatio = 0.86f, stiffness = 900f, visibilityThreshold = visibilityThreshold)

    /** Sizes and positions: calm, no overshoot. */
    fun <T> smooth(visibilityThreshold: T? = null) = spring(dampingRatio = 1f, stiffness = 360f, visibilityThreshold = visibilityThreshold)

    /** A deliberate pop with a single soft overshoot. */
    fun <T> lively(visibilityThreshold: T? = null) = spring(dampingRatio = 0.62f, stiffness = 480f, visibilityThreshold = visibilityThreshold)
}

/** [spec], or an instant change when the user asked for less motion. */
@Composable
fun <T> motionSpec(spec: AnimationSpec<T>): AnimationSpec<T> = if (LocalReduceMotion.current) snap() else spec

/**
 * Content appearing in place (an expanded option, an error line, a section): it grows open while fading in
 * and settles up from 97% scale, so it reads as unfolding rather than blinking on.
 */
@Composable
fun reveal(): EnterTransition {
    if (LocalReduceMotion.current) return EnterTransition.None
    return fadeIn(tween(Motion.MEDIUM, 40, Motion.EmphasizedDecelerate)) +
            expandVertically(Motion.smooth(IntSize.VisibilityThreshold), expandFrom = Alignment.Top) +
            scaleIn(tween(Motion.MEDIUM, easing = Motion.EmphasizedDecelerate), initialScale = 0.97f, transformOrigin = TransformOrigin(0.5f, 0f))
}

/** The exit pair of [reveal]: quicker, folds shut while fading. */
@Composable
fun conceal(): ExitTransition {
    if (LocalReduceMotion.current) return ExitTransition.None
    return fadeOut(tween(Motion.SHORT - 60, easing = Motion.EmphasizedAccelerate)) +
            shrinkVertically(spring(dampingRatio = 1f, stiffness = 600f, visibilityThreshold = IntSize.VisibilityThreshold), shrinkTowards = Alignment.Top) +
            scaleOut(tween(Motion.SHORT - 60, easing = Motion.EmphasizedAccelerate), targetScale = 0.98f, transformOrigin = TransformOrigin(0.5f, 0f))
}

/** Floating surfaces (menus, pickers, tooltips): spring out of their anchor with one soft overshoot. */
@Composable
fun popIn(origin: TransformOrigin = TransformOrigin(0.5f, 0f)): EnterTransition {
    if (LocalReduceMotion.current) return EnterTransition.None
    return fadeIn(tween(140, easing = Motion.EmphasizedDecelerate)) +
            scaleIn(spring(dampingRatio = 0.7f, stiffness = 560f), initialScale = 0.9f, transformOrigin = origin)
}

@Composable
fun popOut(origin: TransformOrigin = TransformOrigin(0.5f, 0f)): ExitTransition {
    if (LocalReduceMotion.current) return ExitTransition.None
    return fadeOut(tween(110, easing = Motion.EmphasizedAccelerate)) +
            scaleOut(tween(110, easing = Motion.EmphasizedAccelerate), targetScale = 0.95f, transformOrigin = origin)
}

/** Small markers (the selected-item bar): grow out of their [origin] from nothing with a single overshoot. */
@Composable
fun growIn(origin: TransformOrigin = TransformOrigin.Center): EnterTransition {
    if (LocalReduceMotion.current) return EnterTransition.None
    return scaleIn(Motion.lively(), transformOrigin = origin)
}

@Composable
fun growOut(origin: TransformOrigin = TransformOrigin.Center): ExitTransition {
    if (LocalReduceMotion.current) return ExitTransition.None
    return scaleOut(tween(120, easing = Motion.EmphasizedAccelerate), transformOrigin = origin)
}

/**
 * One piece of content replacing another (a settings page, a step, a status): the old one lifts away quickly,
 * the new one rises a few dp into place, and the container eases to the new size.
 */
@Composable
fun <S> AnimatedContentTransitionScope<S>.crossSlide(): ContentTransform = crossSlide(LocalReduceMotion.current)

/**
 * [crossSlide] packaged for `AnimatedContent(transitionSpec = crossSlideSpec())`: transitionSpec is not a
 * composable scope, so the reduce-motion flag is read here and captured.
 */
@Composable
fun <S> crossSlideSpec(): AnimatedContentTransitionScope<S>.() -> ContentTransform {
    val reduceMotion = LocalReduceMotion.current
    return { crossSlide(reduceMotion) }
}

private fun <S> AnimatedContentTransitionScope<S>.crossSlide(reduceMotion: Boolean): ContentTransform {
    if (reduceMotion) return EnterTransition.None togetherWith ExitTransition.None
    val enter = fadeIn(tween(Motion.MEDIUM, 70, Motion.EmphasizedDecelerate)) +
            slideInVertically(Motion.smooth(IntOffset.VisibilityThreshold)) { it / 16 } +
            scaleIn(tween(Motion.MEDIUM, 70, Motion.EmphasizedDecelerate), initialScale = 0.985f)
    val exit = fadeOut(tween(120, easing = Motion.EmphasizedAccelerate)) +
            scaleOut(tween(120, easing = Motion.EmphasizedAccelerate), targetScale = 0.985f)
    return (enter togetherWith exit) using SizeTransform(clip = false) { _, _ -> Motion.smooth(IntSize.VisibilityThreshold) }
}

/**
 * Tactile press for anything clickable: dips to 96% the instant it is pressed and springs back with a little
 * life on release. Pass the same interaction source the clickable uses.
 */
@Composable
fun Modifier.pressFeedback(interactionSource: InteractionSource, enabled: Boolean = true): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val down = pressed && enabled && !LocalReduceMotion.current
    val scale by animateFloatAsState(
        targetValue = if (down) 0.96f else 1f,
        // in fast, out with a soft rebound
        animationSpec = if (down) spring(dampingRatio = 1f, stiffness = 1500f) else spring(dampingRatio = 0.5f, stiffness = 650f),
        label = "press",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
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
