package com.abdownloadmanager.shared.ui.widget

import com.abdownloadmanager.shared.util.ui.LocalContentColor
import com.abdownloadmanager.shared.util.ui.widget.MyIcon
import com.abdownloadmanager.shared.util.ui.icon.MyIcons
import com.abdownloadmanager.shared.util.ui.myColors
import ir.amirab.util.ifThen
import com.abdownloadmanager.shared.util.div
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import com.abdownloadmanager.shared.util.ui.motion.LocalReduceMotion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abdownloadmanager.resources.Res
import com.abdownloadmanager.shared.util.ui.WithContentAlpha
import com.abdownloadmanager.shared.util.ui.theme.mySpacings
import ir.amirab.util.compose.modifiers.silentClickable
import ir.amirab.util.compose.resources.myStringResource
import com.abdownloadmanager.shared.util.ui.motion.Motion
import com.abdownloadmanager.shared.util.ui.motion.motionSpec

@Composable
fun CheckBox(
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    uncheckedAlpha: Float = 0.25f,
    shape: Shape = RoundedCornerShape(25)
) {
    val isFocused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier
            .ifThen(!enabled) {
                alpha(0.5f)
            }
            .size(size)
            .clip(shape)
            .triStateToggleable(
                state = ToggleableState(value),
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = interactionSource,
                indication = null,
                onClick = { onValueChange(!value) },
            )
    ) {
        val borderColor = if (isFocused) {
            myColors.focusedBorderColor
        } else {
            LocalContentColor.current / uncheckedAlpha
        }
        Spacer(
            Modifier.matchParentSize()
                .border(1.dp, borderColor, shape)
        )
        AnimatedContent(
            value,
            transitionSpec = togglePopSpec(),
        ) {
            val m = Modifier
                .fillMaxSize()
                .alpha(animateFloatAsState(if (value) 1f else 0f, motionSpec(Motion.snappy())).value)
                .background(myColors.primaryGradient)
            if (it) {
                MyIcon(
                    MyIcons.check,
                    contentDescription = null,
                    modifier = m,
                    tint = myColors.onPrimaryGradient,
                )
            } else {
                Spacer(m)
            }
        }
    }
}

@Composable
fun LabeledCheckbox(
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    description: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .silentClickable(
                enabled = enabled,
            ) {
                onValueChange(!value)
            }
            .heightIn(min = mySpacings.thumbSize),
    ) {
        CheckBox(
            size = mySpacings.iconSize,
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
        )
        Spacer(Modifier.width(mySpacings.mediumSpace))
        WithContentAlpha(
            if (enabled) 1f else 0.5f,
        ) {
            Text(description)
        }
    }
}

/** A check mark or dot: pops in with one soft overshoot and leaves quickly. */
@Composable
internal fun <S> togglePopSpec(): AnimatedContentTransitionScope<S>.() -> ContentTransform {
    val reduceMotion = LocalReduceMotion.current
    return {
        if (reduceMotion) {
            EnterTransition.None togetherWith ExitTransition.None
        } else {
            (fadeIn(tween(120, easing = Motion.EmphasizedDecelerate)) + scaleIn(Motion.lively(), initialScale = 0.6f)) togetherWith
                    (fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.6f))
        }
    }
}
