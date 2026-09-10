package com.swordfish.touchinput.radial.controls

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager.LeftStickMode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/** CloudPlayPlus PR #687 semantics, expressed in screen coordinates (positive Y is down). */
internal class CloudStickGesture {
    var pointerId: Int? = null
        private set
    var origin = Offset.Zero
        private set
    var position = Offset.Zero
        private set
    var value = Offset.Zero
        private set
    var autoRunning = false
        private set

    fun begin(
        id: Int,
        point: Offset,
        center: Offset,
        mode: LeftStickMode,
        density: Float,
    ): Boolean {
        if (pointerId != null) return false
        if (autoRunning) {
            cancel()
            // The tap that stops automatic movement is swallowed until its own up.
            return true
        }
        pointerId = id
        origin = if (mode == LeftStickMode.CLOUD_FIXED) center else point
        move(id, point, density)
        return true
    }

    fun move(
        id: Int,
        point: Offset,
        density: Float,
    ) {
        if (id != pointerId) return
        position = point
        val delta = point - origin
        val distance = delta.getDistance()
        val force = ((distance / density - 1f) / 39f).coerceIn(0f, 1f)
        value = if (force <= 0f) Offset.Zero else delta / distance * force
    }

    fun finish(
        id: Int,
        lockForward: Boolean,
    ) {
        if (id != pointerId) return
        pointerId = null
        autoRunning = lockForward
        value = if (lockForward) Offset(0f, -1f) else Offset.Zero
    }

    fun cancel() {
        pointerId = null
        autoRunning = false
        value = Offset.Zero
    }

    fun aimingUp(): Boolean = pointerId != null && value.y < 0 && abs(value.x) <= -value.y * 0.5773503f

    fun reachesRunTarget(
        target: Offset,
        displayedPoint: Offset,
        density: Float,
    ): Boolean = aimingUp() && value.getDistance() >= 0.99f && (displayedPoint - target).getDistance() <= 30f * density

    companion object {
        fun canStart(
            point: Offset,
            stick: Rect,
            screen: Rect,
            halfScreen: Boolean,
            buttons: Collection<Rect>,
        ): Boolean {
            if (buttons.any { it.contains(point) }) return false
            return stick.contains(point) || (halfScreen && screen.contains(point) && point.x < screen.center.x)
        }

        // Digital systems need eight full-strength directions even for a light nudge.
        fun digitalDirection(
            value: Offset,
            diagonals: Boolean = true,
        ): Offset {
            if (value == Offset.Zero) return Offset.Zero
            val sectors = if (diagonals) 8 else 4
            val sector = (atan2(value.y, value.x) * sectors / (2 * Math.PI)).roundToInt()
            val angle = sector * 2 * Math.PI / sectors
            return Offset(kotlin.math.cos(angle).roundToInt().toFloat(), kotlin.math.sin(angle).roundToInt().toFloat())
        }
    }
}
