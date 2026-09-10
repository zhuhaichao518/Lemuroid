package com.swordfish.touchinput.radial.controls

import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.swordfish.touchinput.radial.layouts.shared.ComposeTouchLayouts
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager.LeftStickMode
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager.Settings
import gg.padkit.inputevents.InputEvent

val LocalCloudStickController = staticCompositionLocalOf<CloudStickController?> { null }
val LocalCloudStickEligible = staticCompositionLocalOf { false }

/** Splits Android touch streams before PadKit so each finger keeps its original owner. */
class CloudStickController {
    internal val gesture = CloudStickGesture()
    internal var revision by mutableStateOf(0)
        private set
    internal var settings by mutableStateOf(Settings())
        private set
    internal var stickBounds by mutableStateOf(Rect.Zero)
        private set
    internal var screenBounds by mutableStateOf(Rect.Zero)
        private set
    internal var density = 1f
    private var active = false
    private var analog = false
    private var directionId = ComposeTouchLayouts.MOTION_SOURCE_DPAD
    private var diagonals = true
    private var onInput: (List<InputEvent>) -> Unit = {}
    private var lastOutput = Offset.Zero
    private val excludedBounds = mutableMapOf<Any, Rect>()
    private val capturedPointers = mutableSetOf<Int>()
    private var forwardedDownTime = 0L
    private var splitGesture = false

    fun configure(
        settings: Settings,
        enabled: Boolean,
        analog: Boolean,
        density: Float,
        onInput: (List<InputEvent>) -> Unit,
    ) {
        if (this.settings != settings || active != enabled || this.analog != analog || this.density != density) cancel()
        this.settings = settings
        this.active = enabled
        this.analog = analog
        this.density = density
        this.onInput = onInput
    }

    fun replacesDirection(
        id: Int,
        continuous: Boolean,
    ): Boolean =
        settings.leftStickMode != LeftStickMode.ORIGINAL &&
            if (analog) {
                continuous && id == ComposeTouchLayouts.MOTION_SOURCE_LEFT_STICK
            } else {
                !continuous && id in
                    setOf(
                        ComposeTouchLayouts.MOTION_SOURCE_DPAD,
                        ComposeTouchLayouts.MOTION_SOURCE_DPAD_AND_LEFT_STICK,
                    )
            }

    internal fun setAnchor(
        bounds: Rect,
        id: Int,
        allowDiagonals: Boolean,
    ) {
        if (stickBounds != bounds || directionId != id || diagonals != allowDiagonals) cancel()
        stickBounds = bounds
        directionId = id
        diagonals = allowDiagonals
    }

    internal fun removeAnchor() {
        cancel()
        stickBounds = Rect.Zero
    }

    internal fun setScreen(bounds: Rect) {
        if (screenBounds != bounds) cancel()
        screenBounds = bounds
    }

    internal fun exclude(
        key: Any,
        bounds: Rect,
    ) {
        excludedBounds[key] = bounds
    }

    internal fun removeExclusion(key: Any) {
        excludedBounds.remove(key)
    }

    internal val extraFeedback: Boolean
        get() = settings.leftStickMode == LeftStickMode.CLOUD_FIXED && settings.leftStickPositionFeedback

    internal val feedbackRadius: Float
        get() = minOf(stickBounds.width, stickBounds.height) * 0.82f * 0.34f

    internal val feedbackCenter: Offset
        get() {
            if (!extraFeedback) return gesture.origin
            val diameter = minOf(stickBounds.width, stickBounds.height) * 0.82f
            val desired = stickBounds.center + Offset(diameter * 0.9f, -diameter * 1.4f)
            val margin = feedbackRadius + 8f * density
            return clampToScreen(desired, margin)
        }

    internal val runTarget: Offset
        get() = clampToScreen(feedbackCenter - Offset(0f, feedbackRadius + 100f * density), 24f * density)

    private fun clampToScreen(
        point: Offset,
        margin: Float,
    ): Offset {
        val mx = minOf(margin, screenBounds.width / 2)
        val my = minOf(margin, screenBounds.height / 2)
        return Offset(
            point.x.coerceIn(screenBounds.left + mx, screenBounds.right - mx),
            point.y.coerceIn(screenBounds.top + my, screenBounds.bottom - my),
        )
    }

    internal val showRunTarget: Boolean get() = settings.leftStickAutoRun && gesture.aimingUp()
    internal val runTargetReached: Boolean
        get() =
            showRunTarget &&
                gesture.reachesRunTarget(
                    runTarget,
                    if (extraFeedback) feedbackCenter + gesture.position - gesture.origin else gesture.position,
                    density,
                )

    fun cancel() {
        gesture.cancel()
        publish()
        // Captured pointer IDs remain excluded until UP, even after a settings change.
    }

    private fun publish() {
        revision++
        val output = if (analog) gesture.value else CloudStickGesture.digitalDirection(gesture.value, diagonals)
        if (output == lastOutput) return
        lastOutput = output
        // PadKit uses positive Y up; the view model flips it back for libretro.
        val direction = if (output == Offset.Zero) Offset.Zero else Offset(output.x, -output.y)
        onInput(
            listOf(
                if (analog) {
                    InputEvent.ContinuousDirection(
                        directionId,
                        direction,
                    )
                } else {
                    InputEvent.DiscreteDirection(directionId, direction)
                },
            ),
        )
    }

    fun dispatchTouchEvent(
        event: MotionEvent,
        forward: (MotionEvent) -> Boolean,
    ): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            capturedPointers.clear()
            splitGesture = false
            forwardedDownTime = event.downTime
            if (!gesture.autoRunning) cancel()
        }
        if (settings.leftStickMode == LeftStickMode.ORIGINAL && capturedPointers.isEmpty() && !splitGesture) {
            return forward(
                event,
            )
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val index = event.actionIndex
            val id = event.getPointerId(index)
            val point = Offset(event.getX(index), event.getY(index))
            if (active && settings.leftStickMode != LeftStickMode.ORIGINAL && stickBounds != Rect.Zero &&
                CloudStickGesture.canStart(
                    point, stickBounds, screenBounds, settings.leftStickHalfScreen, excludedBounds.values,
                )
            ) {
                capturedPointers += id
                splitGesture = true
                gesture.begin(id, point, stickBounds.center, settings.leftStickMode, density)
            }
        }
        val pointer = gesture.pointerId
        if (pointer != null) {
            val index = event.findPointerIndex(pointer)
            if (index >= 0) gesture.move(pointer, Offset(event.getX(index), event.getY(index)), density)
            val pointerUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
            if (pointerUp && event.getPointerId(event.actionIndex) == pointer) {
                gesture.finish(pointer, runTargetReached)
            }
        }
        if (action == MotionEvent.ACTION_CANCEL) gesture.cancel()
        publish()

        val indices = (0 until event.pointerCount).filter { event.getPointerId(it) !in capturedPointers }
        val handled =
            if (!splitGesture) {
                forward(event)
            } else if (indices.isNotEmpty()) {
                forwardFiltered(event, indices, forward)
            } else {
                true
            }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            capturedPointers -= event.getPointerId(event.actionIndex)
        }
        if (action == MotionEvent.ACTION_CANCEL) capturedPointers.clear()
        return handled || splitGesture
    }

    private fun forwardFiltered(
        event: MotionEvent,
        indices: List<Int>,
        forward: (MotionEvent) -> Boolean,
    ): Boolean {
        val changedIndex = indices.indexOf(event.actionIndex)
        val action =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                    if (changedIndex < 0) {
                        MotionEvent.ACTION_MOVE
                    } else if (indices.size == 1) {
                        MotionEvent.ACTION_DOWN
                    } else {
                        MotionEvent.ACTION_POINTER_DOWN or (changedIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                    if (changedIndex < 0) {
                        MotionEvent.ACTION_MOVE
                    } else if (indices.size == 1) {
                        MotionEvent.ACTION_UP
                    } else {
                        MotionEvent.ACTION_POINTER_UP or (changedIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    }
                else -> event.actionMasked
            }
        if (action == MotionEvent.ACTION_DOWN) forwardedDownTime = event.eventTime
        val properties =
            indices.map {
                    index ->
                MotionEvent.PointerProperties().also { event.getPointerProperties(index, it) }
            }.toTypedArray()
        val coordinates =
            indices.map {
                    index ->
                MotionEvent.PointerCoords().also { event.getPointerCoords(index, it) }
            }.toTypedArray()
        val filtered =
            MotionEvent.obtain(
                if (forwardedDownTime == 0L) event.downTime else forwardedDownTime,
                event.eventTime, action, indices.size,
                properties, coordinates, event.metaState, event.buttonState, event.xPrecision, event.yPrecision,
                event.deviceId, event.edgeFlags, event.source, event.flags,
            )
        return try {
            forward(filtered)
        } finally {
            filtered.recycle()
        }
    }
}
