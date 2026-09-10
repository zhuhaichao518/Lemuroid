package com.swordfish.touchinput.radial.controls

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager.LeftStickMode
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager.Settings
import gg.padkit.inputevents.InputEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudStickTest {
    private val stick = Rect(50f, 500f, 210f, 660f)
    private val screen = Rect(0f, 0f, 1000f, 800f)
    private val emitted = mutableListOf<InputEvent>()
    private val forwarded = mutableListOf<Forwarded>()
    private var time = 100L

    private data class Finger(val id: Int, val x: Float, val y: Float)

    private data class Forwarded(val action: Int, val ids: List<Int>, val downTime: Long)

    private fun controller(mode: LeftStickMode = LeftStickMode.CLOUD_TOUCH_DOWN): CloudStickController =
        CloudStickController().apply {
            configure(Settings(leftStickMode = mode), true, false, 1f) { emitted.addAll(it) }
            setScreen(screen)
            setAnchor(stick, 0, true)
        }

    private fun send(
        controller: CloudStickController,
        action: Int,
        vararg fingers: Finger,
    ) {
        time += 10
        val event =
            MotionEvent.obtain(
                100L, time, action, fingers.size,
                fingers.map {
                    MotionEvent.PointerProperties().apply {
                        id = it.id
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }.toTypedArray(),
                fingers.map {
                    MotionEvent.PointerCoords().apply {
                        x = it.x
                        y = it.y
                        pressure = 1f
                        size = 1f
                    }
                }.toTypedArray(),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
        try {
            controller.dispatchTouchEvent(event) {
                forwarded += Forwarded(it.actionMasked, (0 until it.pointerCount).map(it::getPointerId), it.downTime)
                true
            }
        } finally {
            event.recycle()
        }
    }

    @Test fun touchDownCenterDoesNotFollowAfterFullTravel() {
        val gesture = CloudStickGesture()
        gesture.begin(1, Offset(100f, 200f), Offset.Zero, LeftStickMode.CLOUD_TOUCH_DOWN, 1f)
        assertEquals(Offset.Zero, gesture.value)
        gesture.move(1, Offset(500f, 200f), 1f)
        assertEquals(Offset(1f, 0f), gesture.value)
        assertEquals(Offset(100f, 200f), gesture.origin)
        gesture.move(1, Offset(102f, 200f), 1f)
        assertEquals(1f / 39f, gesture.value.x, 0.0001f)
    }

    @Test fun fixedCenterOutputsOnTouchDownAndUsesDensity() {
        val gesture = CloudStickGesture()
        gesture.begin(1, Offset(180f, 100f), Offset(100f, 100f), LeftStickMode.CLOUD_FIXED, 2f)
        assertEquals(Offset(1f, 0f), gesture.value)
        gesture.move(1, Offset(102f, 100f), 2f)
        assertEquals(Offset.Zero, gesture.value)
    }

    @Test fun digitalDirectionsRespondToSmallNudges() {
        assertEquals(Offset(1f, 0f), CloudStickGesture.digitalDirection(Offset(0.01f, 0f)))
        assertEquals(Offset(1f, -1f), CloudStickGesture.digitalDirection(Offset(0.01f, -0.01f)))
        assertEquals(Offset(-1f, 1f), CloudStickGesture.digitalDirection(Offset(-0.01f, 0.01f)))
        assertEquals(Offset.Zero, CloudStickGesture.digitalDirection(Offset.Zero))
    }

    @Test fun secondFingerCannotStealOrReleaseFirst() {
        val gesture = CloudStickGesture()
        gesture.begin(1, Offset.Zero, Offset.Zero, LeftStickMode.CLOUD_TOUCH_DOWN, 1f)
        assertFalse(gesture.begin(2, Offset(10f, 0f), Offset.Zero, LeftStickMode.CLOUD_TOUCH_DOWN, 1f))
        gesture.move(1, Offset(50f, 0f), 1f)
        gesture.finish(2, false)
        assertEquals(1, gesture.pointerId)
        assertEquals(Offset(1f, 0f), gesture.value)
    }

    @Test fun halfScreenIsUnionWithOriginalBoundsAndButtonsWin() {
        val crossing = Rect(450f, 500f, 610f, 660f)
        assertTrue(CloudStickGesture.canStart(Offset(100f, 10f), crossing, screen, true, emptyList()))
        assertTrue(CloudStickGesture.canStart(Offset(600f, 550f), crossing, screen, true, emptyList()))
        assertFalse(CloudStickGesture.canStart(Offset(600f, 10f), crossing, screen, true, emptyList()))
        assertFalse(CloudStickGesture.canStart(Offset(100f, 10f), crossing, screen, false, emptyList()))
        assertFalse(CloudStickGesture.canStart(crossing.center, crossing, screen, true, listOf(crossing)))
    }

    @Test fun autoRunRequiresFullUpwardPushNearTargetAndStopsOnNextTouch() {
        val gesture = CloudStickGesture()
        gesture.begin(1, Offset(100f, 300f), Offset.Zero, LeftStickMode.CLOUD_TOUCH_DOWN, 1f)
        gesture.move(1, Offset(100f, 290f), 1f)
        assertFalse(gesture.reachesRunTarget(gesture.position, gesture.position, 1f))
        gesture.move(1, Offset(100f, 150f), 1f)
        assertTrue(gesture.reachesRunTarget(Offset(100f, 150f), gesture.position, 1f))
        assertFalse(gesture.reachesRunTarget(Offset(100f, 100f), gesture.position, 1f))
        gesture.finish(1, true)
        assertTrue(gesture.autoRunning)
        assertEquals(Offset(0f, -1f), gesture.value)
        gesture.begin(2, Offset(200f, 200f), Offset.Zero, LeftStickMode.CLOUD_FIXED, 1f)
        assertFalse(gesture.autoRunning)
        assertNull(gesture.pointerId)
        assertEquals(Offset.Zero, gesture.value)
    }

    @Test fun originalModePassesTouchesThrough() {
        val c = controller(LeftStickMode.ORIGINAL)
        send(c, MotionEvent.ACTION_DOWN, Finger(0, 100f, 580f))
        send(c, MotionEvent.ACTION_UP, Finger(0, 100f, 580f))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), forwarded.map { it.action })
        assertTrue(emitted.isEmpty())
    }

    @Test fun oldSettingsKeepOriginalControlAndButtonMapping() {
        val settings = Json.decodeFromString(Settings.serializer(), """{"faceButtonBAction":"NORMAL_B"}""")
        assertEquals(LeftStickMode.ORIGINAL, settings.leftStickMode)
        assertEquals("NORMAL_B", settings.faceButtonBAction.name)
        val changed = settings.copy(leftStickMode = LeftStickMode.CLOUD_FIXED, leftStickHalfScreen = false)
        assertEquals(
            changed,
            Json.decodeFromString(Settings.serializer(), Json.encodeToString(Settings.serializer(), changed)),
        )
    }

    @Test fun leftFirstRightSecondPreservesRightButtonStreamAndDownTime() {
        val c = controller()
        val left = Finger(0, 100f, 580f)
        val right = Finger(1, 800f, 580f)
        send(c, MotionEvent.ACTION_DOWN, left)
        send(c, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), left, right)
        send(c, MotionEvent.ACTION_MOVE, left.copy(x = 800f), right)
        send(c, MotionEvent.ACTION_POINTER_UP, left.copy(x = 800f), right)
        send(c, MotionEvent.ACTION_MOVE, right)
        send(c, MotionEvent.ACTION_UP, right)
        assertEquals(MotionEvent.ACTION_DOWN, forwarded.first().action)
        assertEquals(MotionEvent.ACTION_UP, forwarded.last().action)
        assertTrue(forwarded.all { it.ids == listOf(1) })
        assertEquals(1, forwarded.map { it.downTime }.distinct().size)
        assertEquals(InputEvent.DiscreteDirection(0, Offset.Zero), emitted.last())
    }

    @Test fun rightFirstThenStickLeavesOriginalButtonHeld() {
        val c = controller()
        val right = Finger(5, 800f, 580f)
        val left = Finger(2, 100f, 580f)
        send(c, MotionEvent.ACTION_DOWN, right)
        send(c, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), right, left)
        send(c, MotionEvent.ACTION_POINTER_UP or (1 shl 8), right, left)
        send(c, MotionEvent.ACTION_UP, right)
        assertEquals(
            listOf(
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP,
            ),
            forwarded.map {
                it.action
            },
        )
        assertTrue(forwarded.all { it.ids == listOf(5) })
        assertEquals(1, forwarded.map { it.downTime }.distinct().size)
    }

    @Test fun buttonBoundsTakePriorityInLeftHalf() {
        val c = controller()
        c.exclude("coin", Rect(50f, 100f, 150f, 200f))
        send(c, MotionEvent.ACTION_DOWN, Finger(0, 100f, 150f))
        assertEquals(MotionEvent.ACTION_DOWN, forwarded.single().action)
        assertNull(c.gesture.pointerId)
    }

    @Test fun modeChangeReleasesMovementAndDoesNotLeakExistingFingerToButtons() {
        val c = controller()
        send(c, MotionEvent.ACTION_DOWN, Finger(0, 100f, 580f))
        send(c, MotionEvent.ACTION_MOVE, Finger(0, 150f, 580f))
        c.configure(Settings(), true, false, 1f) { emitted.addAll(it) }
        send(c, MotionEvent.ACTION_MOVE, Finger(0, 800f, 580f))
        send(c, MotionEvent.ACTION_UP, Finger(0, 800f, 580f))
        assertEquals(InputEvent.DiscreteDirection(0, Offset.Zero), emitted.last())
        assertTrue(forwarded.isEmpty())
        send(c, MotionEvent.ACTION_DOWN, Finger(0, 800f, 580f))
        assertEquals(MotionEvent.ACTION_DOWN, forwarded.single().action)
    }

    @Test fun resizeAndCancellationReleaseDirection() {
        val c = controller()
        send(c, MotionEvent.ACTION_DOWN, Finger(0, 100f, 580f))
        send(c, MotionEvent.ACTION_MOVE, Finger(0, 100f, 500f))
        assertEquals(InputEvent.DiscreteDirection(0, Offset(0f, 1f)), emitted.last())
        c.setScreen(Rect(0f, 0f, 800f, 1000f))
        assertEquals(InputEvent.DiscreteDirection(0, Offset.Zero), emitted.last())
        send(c, MotionEvent.ACTION_CANCEL, Finger(0, 100f, 500f))
        assertFalse(c.gesture.autoRunning)
        assertTrue(forwarded.isEmpty())
    }

    @Test fun halfScreenOffOnlyStartsInsideOriginalStick() {
        val c = controller()
        c.configure(
            Settings(leftStickMode = LeftStickMode.CLOUD_TOUCH_DOWN, leftStickHalfScreen = false),
            true,
            false,
            1f,
        ) {
            emitted.addAll(it)
        }
        send(c, MotionEvent.ACTION_DOWN, Finger(0, 20f, 20f))
        send(c, MotionEvent.ACTION_MOVE, Finger(0, 100f, 580f))
        assertNull(c.gesture.pointerId)
        assertEquals(2, forwarded.size)
    }

    @Test fun fixedCenterFeedbackAndRunTargetAreIndependentOfFingerTravel() {
        val c = controller(LeftStickMode.CLOUD_FIXED)
        send(c, MotionEvent.ACTION_DOWN, Finger(0, 130f, 580f))
        val center = c.feedbackCenter
        val target = c.runTarget
        val delta = target - center
        val end = stick.center + delta
        send(c, MotionEvent.ACTION_MOVE, Finger(0, end.x, end.y))
        assertEquals(center, c.feedbackCenter)
        assertTrue(c.runTargetReached)
        send(c, MotionEvent.ACTION_UP, Finger(0, end.x, end.y))
        assertTrue(c.gesture.autoRunning)
        c.cancel()
        assertEquals(InputEvent.DiscreteDirection(0, Offset.Zero), emitted.last())
    }
}
