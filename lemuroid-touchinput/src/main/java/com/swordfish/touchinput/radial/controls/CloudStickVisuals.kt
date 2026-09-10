package com.swordfish.touchinput.radial.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.atan2

/** Existing button hit boxes always win over the additional half-screen joystick area. */
internal fun Modifier.cloudStickExclusion(): Modifier =
    composed {
        val controller = LocalCloudStickController.current
        val token = remember { Any() }
        DisposableEffect(controller) {
            onDispose { controller?.removeExclusion(token) }
        }
        onGloballyPositioned { controller?.exclude(token, it.boundsInWindow()) }
    }

@Composable
internal fun CloudStickAnchor(
    modifier: Modifier,
    controller: CloudStickController,
    id: Int,
    diagonals: Boolean,
) {
    DisposableEffect(controller, id) {
        onDispose { controller.removeAnchor() }
    }
    Box(modifier.aspectRatio(1f).onGloballyPositioned { controller.setAnchor(it.boundsInWindow(), id, diagonals) })
}

@Composable
fun CloudStickOverlay(controller: CloudStickController) {
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { controller.setScreen(it.boundsInWindow()) }) {
        // Read the snapshot revision inside drawing, not during composition.
        controller.revision
        val bounds = controller.stickBounds
        if (bounds.width <= 0f || bounds.height <= 0f) return@Canvas
        val offset = controller.screenBounds.topLeft
        val gesture = controller.gesture
        val foreground = Color.White.copy(alpha = 0.48f)
        if (gesture.pointerId == null) {
            val radius = minOf(bounds.width, bounds.height) * 0.41f
            drawCircle(Color.Black.copy(alpha = 0.12f), radius, bounds.center - offset)
            if (gesture.autoRunning) {
                drawRunIndicator(bounds.center - offset, 14f * controller.density, true)
            } else {
                drawCircle(foreground, radius * 0.24f, bounds.center - offset)
            }
        } else if (gesture.value != Offset.Zero) {
            val center = controller.feedbackCenter - offset
            val radius = controller.feedbackRadius
            val angle = atan2(gesture.value.y, gesture.value.x) * 180f / Math.PI.toFloat()
            rotate(angle, center) {
                clipRect(center.x - radius * 0.16f, center.y - radius, center.x + radius, center.y + radius) {
                    drawCircle(
                        Brush.linearGradient(
                            0f to Color.White.copy(alpha = 0f),
                            0.276f to Color.White.copy(alpha = 0.09f),
                            0.621f to Color.White.copy(alpha = 0.318f),
                            1f to Color.White.copy(alpha = 0.6f),
                            start = Offset(center.x - radius * 0.16f, center.y),
                            end = Offset(center.x + radius, center.y),
                        ),
                        radius,
                        center,
                    )
                }
            }
            if (controller.extraFeedback) {
                val dot = center + gesture.position - gesture.origin
                drawCircle(foreground, 3f * controller.density, dot)
                drawCircle(
                    Color.Black.copy(alpha = 0.48f),
                    3f * controller.density,
                    dot,
                    style = Stroke(controller.density),
                )
            }
        }
        if (controller.showRunTarget) {
            drawRunIndicator(controller.runTarget - offset, 14f * controller.density, controller.runTargetReached)
        }
    }
}

private fun DrawScope.drawRunIndicator(
    center: Offset,
    unit: Float,
    highlighted: Boolean,
) {
    val color = Color.White.copy(alpha = if (highlighted) 0.9f else 0.48f)
    val stroke = unit * 0.13f

    fun line(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ) = drawLine(color, center + Offset(x1, y1) * unit, center + Offset(x2, y2) * unit, stroke, StrokeCap.Round)
    drawCircle(color, unit * 0.16f, center + Offset(0.16f, -0.55f) * unit)
    line(0.1f, -0.3f, -0.15f, 0.18f)
    line(0.05f, -0.22f, 0.48f, -0.03f)
    line(0.48f, -0.03f, 0.7f, -0.28f)
    line(0.05f, -0.22f, -0.35f, -0.4f)
    line(-0.35f, -0.4f, -0.62f, -0.1f)
    line(-0.15f, 0.18f, 0.38f, 0.45f)
    line(0.38f, 0.45f, 0.3f, 0.82f)
    line(-0.15f, 0.18f, -0.4f, 0.65f)
    line(-0.4f, 0.65f, -0.8f, 0.65f)
    line(-0.32f, -1f, 0f, -1.28f)
    line(0f, -1.28f, 0.32f, -1f)
}
