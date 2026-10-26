package com.glassos.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Asymptotic rubber-band resistance: the displayed displacement approaches 1 / [dampingFactor] as the
 * raw drag grows without bound, and is additionally hard-clamped to [maxDeformation].
 */
fun rubberBand(delta: Float, dampingFactor: Float, maxDeformation: Float): Float {
    val magnitude = abs(delta)
    val damped = magnitude / (1f + magnitude * dampingFactor)
    return sign(delta) * min(damped, maxDeformation)
}

/**
 * Elastic "gelatin" deformation for any composable.
 *
 * While dragged the element follows the finger with rubber-band resistance, stretches along the drag
 * axis and compresses along the perpendicular axis so its visual area stays constant
 * (scaleX * scaleY == 1). On release (or cancel) the displacement springs back to neutral using a
 * bouncy low-stiffness spring, which makes the element wobble before it settles.
 *
 * Taps are not consumed, so combine with `clickable` for activation.
 */
fun Modifier.squishyDeformation(
    enabled: Boolean = true,
    dampingFactor: Float = 0.012f,
    maxDeformationPx: Float = 72f,
    stretchGain: Float = 1.2f,
    followFactor: Float = 0.55f,
    maxScaleDelta: Float = 0.32f
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val releaseSpec = remember {
        spring<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    }

    this
        .graphicsLayer {
            val width = max(size.width, 1f)
            val height = max(size.height, 1f)
            val stretchX = min(abs(offsetX.value) / width * stretchGain, maxScaleDelta)
            val stretchY = min(abs(offsetY.value) / height * stretchGain, maxScaleDelta)
            // Stretch along the drag axis, compress across it: the product stays exactly 1.
            scaleX = (1f + stretchX) / (1f + stretchY)
            scaleY = (1f + stretchY) / (1f + stretchX)
            translationX = offsetX.value * followFactor
            translationY = offsetY.value * followFactor
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            var rawX = 0f
            var rawY = 0f

            val release: () -> Unit = {
                rawX = 0f
                rawY = 0f
                scope.launch { offsetX.animateTo(0f, releaseSpec) }
                scope.launch { offsetY.animateTo(0f, releaseSpec) }
            }

            detectDragGestures(
                onDragStart = {
                    rawX = 0f
                    rawY = 0f
                },
                onDragEnd = { release() },
                onDragCancel = { release() },
                onDrag = { change, dragAmount ->
                    change.consume()
                    rawX += dragAmount.x
                    rawY += dragAmount.y
                    val dampedX = rubberBand(rawX, dampingFactor, maxDeformationPx)
                    val dampedY = rubberBand(rawY, dampingFactor, maxDeformationPx)
                    scope.launch { offsetX.snapTo(dampedX) }
                    scope.launch { offsetY.snapTo(dampedY) }
                }
            )
        }
}
