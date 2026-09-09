package com.swordfish.lemuroid.app.shared.game.viewmodel

import android.view.KeyEvent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.swordfish.lemuroid.app.mobile.feature.settings.SettingsManager
import com.swordfish.lemuroid.app.shared.settings.HapticFeedbackMode
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.common.coroutines.safeCollect
import com.swordfish.lemuroid.lib.controller.ControllerConfig
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroView.Companion.MOTION_SOURCE_ANALOG_LEFT
import com.swordfish.libretrodroid.GLRetroView.Companion.MOTION_SOURCE_ANALOG_RIGHT
import com.swordfish.libretrodroid.GLRetroView.Companion.MOTION_SOURCE_DPAD
import com.swordfish.touchinput.radial.layouts.shared.ComposeTouchLayouts
import com.swordfish.touchinput.radial.settings.TouchControllerID
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import gg.padkit.inputevents.InputEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTouchControls(
    private val settingsManager: SettingsManager,
    private val touchControllerSettingsManager: TouchControllerSettingsManager,
    private val retroGameView: GameViewModelRetroGameView,
    private val inputs: GameViewModelInput,
    private val tilt: GameViewModelTilt,
    private val sideEffects: GameViewModelSideEffects,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    private val touchControlId = MutableStateFlow(TouchControllerID.GB)
    private val screenOrientation = MutableStateFlow(TouchControllerSettingsManager.Orientation.PORTRAIT)
    private val menuPressed = MutableStateFlow(false)
    private val showEditControls = MutableStateFlow(false)
    private val hapticFeedbackMode = MutableStateFlow(HapticFeedbackMode.NONE)

    private var activeTouchSettings = TouchControllerSettingsManager.Settings()
    private val pressedFaceButtons = mutableSetOf<Int>()
    private val latchedFaceButtons = mutableSetOf<Int>()
    private val normalFaceKeys = mutableSetOf<Int>()
    private val turboFaceKeys = mutableSetOf<Int>()
    private val turboPulseKeys = mutableSetOf<Int>()
    private val emittedFaceKeys = mutableSetOf<Int>()
    private val turboJobs = mutableMapOf<Int, Job>()

    private var loadingMenuJob: Job? = null

    override fun onCreate(owner: LifecycleOwner) {
        owner.launchOnState(Lifecycle.State.CREATED) {
            getTouchControllerConfig().safeCollect {
                if (touchControlId.value != it.touchControllerID) releaseAllFaceButtons()
                touchControlId.value = it.touchControllerID
            }
        }
        owner.launchOnState(Lifecycle.State.CREATED) {
            withContext(Dispatchers.IO) {
                hapticFeedbackMode.value = HapticFeedbackMode.parse(settingsManager.hapticFeedbackMode())
            }
        }
    }

    fun getTouchControlsSettings(
        density: Density,
        insets: WindowInsets,
    ): Flow<TouchControllerSettingsManager.Settings?> {
        return combine(
            touchControlId,
            screenOrientation,
        ) { touchControlId, orientation -> touchControlId to orientation }
            .flatMapLatest { (touchControlId, orientation) ->
                touchControllerSettingsManager.observeSettings(touchControlId, orientation, density, insets)
            }
            .onEach { settings ->
                activeTouchSettings = settings
                if (!settings.slideLatchEnabled) latchedFaceButtons.clear()
                refreshFaceButtonOutput()
            }
    }

    fun getTouchHapticFeedbackMode(): Flow<HapticFeedbackMode> {
        return hapticFeedbackMode
    }

    fun updateTouchControllerSettings(touchControllerSettings: TouchControllerSettingsManager.Settings) {
        scope.launch {
            touchControllerSettingsManager.storeSettings(
                touchControlId.value,
                screenOrientation.value,
                touchControllerSettings,
            )
        }
    }

    fun resetTouchControls() {
        scope.launch {
            touchControllerSettingsManager.resetSettings(
                touchControlId.value,
                screenOrientation.value,
            )
        }
    }

    fun updateScreenOrientation(orientation: TouchControllerSettingsManager.Orientation) {
        screenOrientation.value = orientation
    }

    fun isTouchControllerVisible(): Flow<Boolean> {
        return inputs.getEnabledInputDevices()
            .map { it.isEmpty() }
    }

    fun getTouchControllerConfig(): Flow<ControllerConfig> {
        return inputs.getControllerConfigState()
            .map { it[0] }
            .filterNotNull()
            .distinctUntilChanged()
    }

    fun handleVirtualInputEvent(events: List<InputEvent>) {
        val menuEvent = events.firstOrNull { it is InputEvent.Button && it.id == KeyEvent.KEYCODE_BUTTON_MODE }
        if (menuEvent != null) {
            onMenuPressed((menuEvent as InputEvent.Button).pressed)
        }

        val nesFaceButtonEvents =
            if (touchControlId.value == TouchControllerID.NES) {
                events.filterIsInstance<InputEvent.Button>().filter { it.id in NES_FACE_BUTTON_IDS }
            } else {
                emptyList()
            }

        if (nesFaceButtonEvents.isNotEmpty()) {
            handleNESFaceButtonEvents(nesFaceButtonEvents)
        }

        events.filterNot { it is InputEvent.Button && it in nesFaceButtonEvents }.forEach { event ->
            when (event) {
                is InputEvent.Button -> {
                    handleVirtualInputButton(event)
                }

                is InputEvent.DiscreteDirection -> {
                    handleVirtualInputDirection(event.id, event.direction.x, -event.direction.y)
                }

                is InputEvent.ContinuousDirection -> {
                    handleVirtualInputDirection(event.id, event.direction.x, -event.direction.y)
                }
            }
        }
    }

    private fun handleNESFaceButtonEvents(events: List<InputEvent.Button>) {
        val buttonsBeforeEvent = pressedFaceButtons.toSet()
        val newlyPressed = events.filter { it.pressed }.mapTo(mutableSetOf()) { it.id }
        val newlyReleased = events.filterNot { it.pressed }.mapTo(mutableSetOf()) { it.id }

        if (activeTouchSettings.slideLatchEnabled && newlyPressed.isNotEmpty() && buttonsBeforeEvent.isNotEmpty()) {
            latchedFaceButtons.addAll(buttonsBeforeEvent)
        }

        events.forEach { event ->
            if (event.pressed) {
                pressedFaceButtons += event.id
            } else {
                pressedFaceButtons -= event.id
            }
        }

        if (activeTouchSettings.slideLatchEnabled) {
            // PadKit may report a direct A -> B transition in one event batch, or pass
            // through an A+B composite region first. Cover both paths so the original
            // button remains held until the finger leaves the whole face-button control.
            if (newlyPressed.isNotEmpty() && newlyReleased.isNotEmpty()) {
                latchedFaceButtons.addAll(newlyReleased)
            }
            if (pressedFaceButtons.size > 1) {
                latchedFaceButtons.addAll(pressedFaceButtons)
            }
        } else {
            latchedFaceButtons.clear()
        }

        if (pressedFaceButtons.isEmpty()) {
            latchedFaceButtons.clear()
        }

        refreshFaceButtonOutput()
    }

    private fun refreshFaceButtonOutput() {
        if (touchControlId.value != TouchControllerID.NES) return

        val effectiveButtons = pressedFaceButtons + latchedFaceButtons
        val requestedNormalKeys = mutableSetOf<Int>()
        val requestedTurboKeys = mutableSetOf<Int>()

        effectiveButtons.forEach { physicalButton ->
            when (faceButtonAction(physicalButton)) {
                TouchControllerSettingsManager.FaceButtonAction.NORMAL_A ->
                    requestedNormalKeys += KeyEvent.KEYCODE_BUTTON_A

                TouchControllerSettingsManager.FaceButtonAction.NORMAL_B ->
                    requestedNormalKeys += KeyEvent.KEYCODE_BUTTON_B

                TouchControllerSettingsManager.FaceButtonAction.TURBO_A ->
                    requestedTurboKeys += KeyEvent.KEYCODE_BUTTON_A

                TouchControllerSettingsManager.FaceButtonAction.TURBO_B ->
                    requestedTurboKeys += KeyEvent.KEYCODE_BUTTON_B
            }
        }

        normalFaceKeys.clear()
        normalFaceKeys.addAll(requestedNormalKeys)
        turboFaceKeys.clear()
        turboFaceKeys.addAll(requestedTurboKeys)

        NES_LOGICAL_BUTTON_IDS.forEach { keyCode ->
            updateTurboJob(keyCode, keyCode in turboFaceKeys)
            emitFaceKeyIfChanged(keyCode)
        }
    }

    private fun faceButtonAction(physicalButton: Int): TouchControllerSettingsManager.FaceButtonAction {
        return when (physicalButton) {
            KeyEvent.KEYCODE_BUTTON_A -> activeTouchSettings.faceButtonAAction
            KeyEvent.KEYCODE_BUTTON_B -> activeTouchSettings.faceButtonBAction
            KeyEvent.KEYCODE_BUTTON_X -> activeTouchSettings.faceButtonXAction
            KeyEvent.KEYCODE_BUTTON_Y -> activeTouchSettings.faceButtonYAction
            else -> error("Unsupported NES face button: $physicalButton")
        }
    }

    private fun updateTurboJob(
        keyCode: Int,
        requested: Boolean,
    ) {
        if (requested && turboJobs[keyCode] == null) {
            turboJobs[keyCode] =
                scope.launch {
                    while (isActive && keyCode in turboFaceKeys) {
                        turboPulseKeys += keyCode
                        emitFaceKeyIfChanged(keyCode)
                        delay(turboHalfPeriodMillis())
                        turboPulseKeys -= keyCode
                        emitFaceKeyIfChanged(keyCode)
                        delay(turboHalfPeriodMillis())
                    }
                }
        } else if (!requested) {
            turboJobs.remove(keyCode)?.cancel()
            turboPulseKeys -= keyCode
        }
    }

    private fun turboHalfPeriodMillis(): Long {
        val rate =
            activeTouchSettings.turboRateHz.coerceIn(
                TouchControllerSettingsManager.MIN_TURBO_RATE_HZ,
                TouchControllerSettingsManager.MAX_TURBO_RATE_HZ,
            )
        return (500f / rate).toLong().coerceAtLeast(1L)
    }

    private fun emitFaceKeyIfChanged(keyCode: Int) {
        val pressed = keyCode in normalFaceKeys || keyCode in turboPulseKeys
        if ((keyCode in emittedFaceKeys) == pressed) return

        if (pressed) {
            emittedFaceKeys += keyCode
        } else {
            emittedFaceKeys -= keyCode
        }
        retroGameView.retroGameView?.sendKeyEvent(
            if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            keyCode,
        )
    }

    private fun releaseAllFaceButtons() {
        turboJobs.values.forEach { it.cancel() }
        turboJobs.clear()
        pressedFaceButtons.clear()
        latchedFaceButtons.clear()
        normalFaceKeys.clear()
        turboFaceKeys.clear()
        turboPulseKeys.clear()
        emittedFaceKeys.toList().forEach { keyCode ->
            retroGameView.retroGameView?.sendKeyEvent(KeyEvent.ACTION_UP, keyCode)
        }
        emittedFaceKeys.clear()
    }

    private fun onMenuPressed(pressed: Boolean) {
        menuPressed.value = pressed

        if (pressed) {
            loadingMenuJob?.cancel()
            loadingMenuJob =
                scope.launch {
                    delay(MENU_LOADING_ANIMATION_MILLIS.toLong())
                    sideEffects.showMenu(tilt, inputs)
                }
        } else {
            loadingMenuJob?.cancel()
            loadingMenuJob = null
        }
    }

    fun isMenuPressed(): Flow<Boolean> {
        return menuPressed
    }

    fun isEditControlsShown(): Flow<Boolean> {
        return showEditControls
    }

    fun showEditControls(show: Boolean) {
        showEditControls.value = show
    }

    private fun handleVirtualInputButton(event: InputEvent.Button) {
        val action = if (event.pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        retroGameView.retroGameView?.sendKeyEvent(action, event.id)
    }

    private fun handleVirtualInputDirection(
        id: Int,
        xAxis: Float,
        yAxis: Float,
    ) {
        when (id) {
            ComposeTouchLayouts.MOTION_SOURCE_DPAD -> {
                retroGameView.retroGameView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, xAxis, yAxis)
            }

            ComposeTouchLayouts.MOTION_SOURCE_LEFT_STICK -> {
                retroGameView.retroGameView?.sendMotionEvent(
                    MOTION_SOURCE_ANALOG_LEFT,
                    xAxis,
                    yAxis,
                )
            }

            ComposeTouchLayouts.MOTION_SOURCE_RIGHT_STICK -> {
                retroGameView.retroGameView?.sendMotionEvent(
                    MOTION_SOURCE_ANALOG_RIGHT,
                    xAxis,
                    yAxis,
                )
            }

            ComposeTouchLayouts.MOTION_SOURCE_DPAD_AND_LEFT_STICK -> {
                retroGameView.retroGameView?.sendMotionEvent(
                    MOTION_SOURCE_ANALOG_LEFT,
                    xAxis,
                    yAxis,
                )
                retroGameView.retroGameView?.sendMotionEvent(MOTION_SOURCE_DPAD, xAxis, yAxis)
            }

            ComposeTouchLayouts.MOTION_SOURCE_RIGHT_DPAD -> {
                retroGameView.retroGameView?.sendMotionEvent(
                    MOTION_SOURCE_ANALOG_RIGHT,
                    xAxis,
                    yAxis,
                )
            }
        }
    }

    companion object {
        const val MENU_LOADING_ANIMATION_MILLIS = 500

        private val NES_FACE_BUTTON_IDS =
            setOf(
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y,
            )
        private val NES_LOGICAL_BUTTON_IDS =
            setOf(
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
            )
    }
}
