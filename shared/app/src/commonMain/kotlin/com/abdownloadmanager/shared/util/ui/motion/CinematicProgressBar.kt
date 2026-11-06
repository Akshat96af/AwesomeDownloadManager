package com.abdownloadmanager.shared.util.ui.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.exp
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Apple system colours (blue, indigo, purple, pink), looping back to blue so the flow tiles seamlessly. */
private val Aurora = listOf(
    Color(0xFF0A84FF), Color(0xFF5E5CE6), Color(0xFFBF5AF2), Color(0xFFFF375F), Color(0xFF0A84FF),
)

/** Gradient flow speed, in gradient periods per second: a slow drift when stalled, brisk at full throughput. */
private const val IDLE_FLOW = 0.10f
private const val FULL_FLOW = 0.55f

/** Progress gained per second (0..1 scale) at which the flow reaches [FULL_FLOW]. */
private const val FULL_RATE = 0.04f

/** How far (in seconds of the measured rate) the bar may run ahead of the last real value between updates. */
private const val MAX_LEAD_SECONDS = 1.2f

/**
 * The download progress bar.
 *
 * Built to make a download *feel* as fast as it is:
 * - **Continuous motion.** The engine reports progress about once a second. Instead of gliding to each
 *   value and stopping (a stop-start rhythm that reads as slow), the bar keeps moving between updates at
 *   the measured rate. It may lead the last real value by at most [MAX_LEAD_SECONDS] of progress, never
 *   moves backwards, and the percentage text always shows the real number.
 * - **Backward flow.** While downloading, the fill is a glossy blue/indigo/purple/pink gradient that flows
 *   against the direction of progress, the colour form of backward-moving ribbing, which makes waits feel
 *   shorter (Harrison, Yeo & Hudson, "Faster Progress Bars", CHI 2010). Its speed follows the real
 *   transfer rate, easing between rates, with a slow drift during stalls so it never looks frozen.
 *
 * Other states (paused, error, finished) cross-fade to the caller's [fill]; finishing blooms once.
 * Motion runs only while [active] via a per-frame loop; [LocalReduceMotion] keeps the static glass only.
 * Nothing is drawn outside the filled part. The caller sizes and clips the capsule.
 */
@Composable
fun CinematicProgressBar(
    progress: Float?,
    fill: Brush,
    active: Boolean,
    modifier: Modifier = Modifier,
    completed: Boolean = false,
    track: Color = Color.Transparent,
) {
    val reduceMotion = LocalReduceMotion.current
    val animate = active && !reduceMotion
    val realProgress by rememberUpdatedState(progress?.coerceIn(0f, 1f))

    // when not animating, a plain glide to the real value
    val settled = progress?.let {
        animateFloatAsState(
            targetValue = it.coerceIn(0f, 1f),
            animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 1f, stiffness = 90f),
            label = "progress",
        )
    }

    // the aurora shows while downloading; other states cross-fade to the caller's status colour
    val auroraMix = remember { Animatable(if (active) 1f else 0f) }
    LaunchedEffect(active) {
        val target = if (active) 1f else 0f
        if (reduceMotion) auroraMix.snapTo(target) else auroraMix.animateTo(target, tween(Motion.MEDIUM + 120, easing = Motion.Standard))
    }
    var previousFill by remember { mutableStateOf(fill) }
    var currentFill by remember { mutableStateOf(fill) }
    val fillMix = remember { Animatable(1f) }
    LaunchedEffect(fill) {
        if (fill != currentFill) {
            previousFill = currentFill
            currentFill = fill
            if (reduceMotion) fillMix.snapTo(1f) else {
                fillMix.snapTo(0f)
                fillMix.animateTo(1f, tween(Motion.MEDIUM + 120, easing = Motion.Standard))
            }
        }
    }

    val rate = remember { RateEstimator() }
    LaunchedEffect(progress) { rate.onProgress(progress) }

    val displayed = remember { mutableFloatStateOf(progress ?: 0f) }
    val flowPhase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var velocity = IDLE_FLOW
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            last = now
            val currentRate = rate.current()

            // continuous progress: aim slightly ahead of the last real value along the measured rate
            val real = realProgress
            if (real != null) {
                val lead = currentRate * minOf(rate.secondsSinceUpdate(), MAX_LEAD_SECONDS)
                val target = minOf(real + lead, 1f)
                var shown = displayed.floatValue
                if (real < shown - 0.05f) {
                    shown = real // a real reset (restart) is the only way to go backwards
                } else {
                    shown += (target - shown) * (1f - exp(-dt / 0.22f))
                    shown = maxOf(shown, displayed.floatValue) // never move backwards
                }
                displayed.floatValue = shown.coerceIn(0f, 1f)
            }

            val targetVelocity = IDLE_FLOW + (FULL_FLOW - IDLE_FLOW) * (currentRate / FULL_RATE).coerceIn(0f, 1f)
            // ease toward the new speed over ~0.6 s so a speed-up feels like acceleration, not a jump
            velocity += (targetVelocity - velocity) * (1f - exp(-dt / 0.6f))
            flowPhase.floatValue = (flowPhase.floatValue + velocity * dt) % 1f
        }
    }
    // keep the continuous value in step when motion stops, so resuming never jumps backwards
    LaunchedEffect(animate, progress) { if (!animate && progress != null) displayed.floatValue = progress.coerceIn(0f, 1f) }

    val bloom = remember { Animatable(0f) }
    var wasCompleted by remember { mutableStateOf(completed) }
    LaunchedEffect(completed) {
        if (completed && !wasCompleted && !reduceMotion) {
            bloom.snapTo(1f)
            bloom.animateTo(0f, tween(900, easing = Motion.EmphasizedDecelerate))
        }
        wasCompleted = completed
    }

    Spacer(
        modifier.drawBehind {
            val w = size.width
            val h = size.height
            if (track != Color.Transparent) drawRect(track)
            // the track is a slight recess
            drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.14f), 0.5f to Color.Transparent), size = size)

            val fraction = when {
                settled == null -> if (animate) 1f else 0f // unknown size: a full bar whose flow carries the motion
                animate -> displayed.floatValue
                else -> settled.value
            }
            val filledWidth = w * fraction
            if (filledWidth <= 0.5f) return@drawBehind
            val fillSize = Size(filledWidth, h)
            val unknownSize = settled == null

            // status colour (paused / error / finished), cross-faded on change
            val statusAlpha = 1f - auroraMix.value
            if (statusAlpha > 0f) {
                if (fillMix.value < 1f) drawRect(previousFill, size = fillSize, alpha = statusAlpha)
                drawRect(currentFill, size = fillSize, alpha = statusAlpha * fillMix.value)
            }
            // aurora, flowing backwards; its period is fixed in pixels so colours don't stretch as the bar grows
            if (auroraMix.value > 0f) {
                val period = maxOf(w * 0.75f, h * 24f)
                val offset = -flowPhase.floatValue * period
                drawRect(
                    Brush.horizontalGradient(Aurora, startX = offset, endX = offset + period, tileMode = TileMode.Repeated),
                    size = fillSize,
                    alpha = auroraMix.value * (if (unknownSize) 0.55f else 1f),
                )
            }
            clipRect(right = filledWidth) {
                // gloss: a soft highlight on top and slight depth below
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.28f), 0.5f to Color.Transparent), size = fillSize)
                drawRect(Brush.verticalGradient(0.62f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.14f)), size = fillSize)
                if (bloom.value > 0f) drawRect(Color.White.copy(alpha = 0.32f * bloom.value), size = fillSize)
            }
        }
    )
}

/** Smoothed progress gained per second, decaying toward zero when updates stop (a stall). */
private class RateEstimator {
    private var smoothed = 0f
    private var lastProgress: Float? = null
    private var lastUpdate: TimeMark = TimeSource.Monotonic.markNow()

    fun onProgress(progress: Float?) {
        val elapsed = secondsSinceUpdate()
        val previous = lastProgress
        if (progress != null && previous != null && elapsed > 0.05f && progress >= previous) {
            smoothed = smoothed * 0.6f + (progress - previous) / elapsed * 0.4f
        }
        lastProgress = progress
        lastUpdate = TimeSource.Monotonic.markNow()
    }

    fun secondsSinceUpdate(): Float = lastUpdate.elapsedNow().inWholeMilliseconds / 1000f

    fun current(): Float {
        val silent = secondsSinceUpdate()
        // no new progress for a while means the transfer slowed; fade the estimate out over ~2 s
        return if (silent < 1.2f) smoothed else smoothed * exp(-(silent - 1.2f) / 2f)
    }
}

/**
 * A number whose digits roll when it changes, like Apple's numeric content transition:
 * rising values roll up, falling values roll down.
 */
@Composable
fun RollingValue(value: Int, content: @Composable (Int) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            if (reduceMotion) {
                fadeIn(snap()) togetherWith fadeOut(snap())
            } else {
                val up = targetState > initialState
                (slideInVertically(tween(Motion.SHORT + 60, easing = Motion.EmphasizedDecelerate)) { if (up) it else -it } +
                        fadeIn(tween(Motion.SHORT))) togetherWith
                        (slideOutVertically(tween(Motion.SHORT, easing = Motion.Standard)) { if (up) -it else it } +
                                fadeOut(tween(Motion.SHORT - 60)))
            }.using(SizeTransform(clip = true))
        },
        label = "rolling",
    ) { content(it) }
}
