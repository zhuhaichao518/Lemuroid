package com.swordfish.lemuroid.app.shared.game.viewmodel

import android.content.Context
import android.graphics.PointF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.input.GamePadButtonBinding
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.shared.input.InputKey
import com.swordfish.lemuroid.app.shared.input.inputclass.getInputClass
import com.swordfish.lemuroid.app.shared.settings.ControllerConfigsManager
import com.swordfish.lemuroid.app.shared.settings.GameShortcutType
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.common.coroutines.safeCollect
import com.swordfish.lemuroid.common.kotlin.NTuple2
import com.swordfish.lemuroid.common.kotlin.NTuple5
import com.swordfish.lemuroid.common.kotlin.filterNotNullValues
import com.swordfish.lemuroid.common.kotlin.toIndexedMap
import com.swordfish.lemuroid.common.kotlin.zipOnKeys
import com.swordfish.lemuroid.lib.controller.ControllerConfig
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.libretrodroid.Controller
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroView.Companion.MOTION_SOURCE_ANALOG_LEFT
import com.swordfish.libretrodroid.GLRetroView.Companion.MOTION_SOURCE_ANALOG_RIGHT
import com.swordfish.libretrodroid.GLRetroView.Companion.MOTION_SOURCE_DPAD
import com.swordfish.touchinput.radial.sensors.TiltConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs

class GameViewModelInput(
    private val appContext: Context,
    private val system: GameSystem,
    private val systemCoreConfig: SystemCoreConfig,
    private val inputDeviceManager: InputDeviceManager,
    private val controllerConfigsManager: ControllerConfigsManager,
    private val retroGameView: GameViewModelRetroGameView,
    private val tilt: GameViewModelTilt,
    private val sideEffects: GameViewModelSideEffects,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    private data class SingleAxisEvent(val axis: Int, val action: Int, val keyCode: Int, val port: Int)

    private data class GamePadSource(val deviceId: Int, val keyCode: Int)

    private data class GamePadOutput(val port: Int, val keyCode: Int)

    private data class ActiveGamePadBinding(val output: GamePadOutput, val turbo: Boolean)

    private val controllerConfigsState = MutableStateFlow<Map<Int, ControllerConfig>>(mapOf())
    private val keyEventsFlow: MutableSharedFlow<KeyEvent?> = MutableSharedFlow()
    private val motionEventsFlow: MutableSharedFlow<MotionEvent> = MutableSharedFlow()
    private val activeNesGamePadBindings = mutableMapOf<GamePadSource, ActiveGamePadBinding>()
    private val normalNesGamePadSources = mutableMapOf<GamePadOutput, MutableSet<GamePadSource>>()
    private val turboNesGamePadSources = mutableMapOf<GamePadOutput, MutableSet<GamePadSource>>()
    private val turboNesGamePadPulses = mutableSetOf<GamePadOutput>()
    private val emittedNesGamePadOutputs = mutableSetOf<GamePadOutput>()
    private val turboNesGamePadJobs = mutableMapOf<GamePadOutput, Job>()

    fun getAllTiltConfigurations(): List<TiltConfiguration> {
        return controllerConfigsState.value[0]
            ?.tiltConfigurations
            ?: emptyList()
    }

    fun getControllerConfigState(): Flow<Map<Int, ControllerConfig>> {
        return controllerConfigsState
    }

    private fun sendStickMotions(
        event: MotionEvent,
        port: Int,
    ) {
        if (port < 0) return
        when (event.source) {
            InputDevice.SOURCE_JOYSTICK -> {
                if (controllerConfigsState.value[port]?.mergeDPADAndLeftStickEvents == true) {
                    sendMergedMotionEvents(event, port)
                } else {
                    sendSeparateMotionEvents(event, port)
                }
            }
        }
    }

    private fun sendMergedMotionEvents(
        event: MotionEvent,
        port: Int,
    ) {
        val events =
            listOf(
                retrieveCoordinates(event, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y),
                retrieveCoordinates(event, MotionEvent.AXIS_X, MotionEvent.AXIS_Y),
            )

        val xVal = events.maxByOrNull { abs(it.x) }?.x ?: 0f
        val yVal = events.maxByOrNull { abs(it.y) }?.y ?: 0f

        retroGameView.retroGameView?.sendMotionEvent(MOTION_SOURCE_DPAD, xVal, yVal, port)
        retroGameView.retroGameView?.sendMotionEvent(MOTION_SOURCE_ANALOG_LEFT, xVal, yVal, port)

        sendStickMotion(
            event,
            MOTION_SOURCE_ANALOG_RIGHT,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
            port,
        )
    }

    private fun sendStickMotion(
        event: MotionEvent,
        source: Int,
        xAxis: Int,
        yAxis: Int,
        port: Int,
    ) {
        val coords = retrieveCoordinates(event, xAxis, yAxis)
        retroGameView.retroGameView?.sendMotionEvent(source, coords.x, coords.y, port)
    }

    private fun sendDPADMotion(
        event: MotionEvent,
        source: Int,
        xAxis: Int,
        yAxis: Int,
        port: Int,
    ) {
        retroGameView.retroGameView?.sendMotionEvent(
            source,
            event.getAxisValue(xAxis),
            event.getAxisValue(yAxis),
            port,
        )
    }

    private fun sendSeparateMotionEvents(
        event: MotionEvent,
        port: Int,
    ) {
        sendDPADMotion(
            event,
            MOTION_SOURCE_DPAD,
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y,
            port,
        )
        sendStickMotion(
            event,
            MOTION_SOURCE_ANALOG_LEFT,
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
            port,
        )
        sendStickMotion(
            event,
            MOTION_SOURCE_ANALOG_RIGHT,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
            port,
        )
    }

    private fun retrieveCoordinates(
        event: MotionEvent,
        xAxis: Int,
        yAxis: Int,
    ): PointF {
        return PointF(event.getAxisValue(xAxis), event.getAxisValue(yAxis))
    }

    fun sendKeyEvent(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (InputKey(keyCode) in event.device.getInputClass().getInputKeys()) {
            scope.launch {
                keyEventsFlow.emit(event)
            }
            return true
        }
        return false
    }

    fun sendMotionEvent(event: MotionEvent): Boolean {
        scope.launch {
            motionEventsFlow.emit(event)
        }
        return true
    }

    fun getEnabledInputDevices(): Flow<List<InputDevice>> {
        return inputDeviceManager.getEnabledInputsObservable()
    }

    private fun updateControllers(controllers: Map<Int, ControllerConfig>) {
        retroGameView.retroGameView
            ?.getControllers()?.toIndexedMap()
            ?.zipOnKeys(controllers, this::findControllerId)
            ?.filterNotNullValues()
            ?.forEach { (port, controllerId) ->
                Timber.i("Controls setting $port to $controllerId")
                retroGameView.retroGameView?.setControllerType(port, controllerId)
            }
    }

    private fun findControllerId(
        supported: Array<Controller>,
        controllerConfig: ControllerConfig,
    ): Int? {
        return supported
            .firstOrNull { controller ->
                sequenceOf(
                    controller.id == controllerConfig.libretroId,
                    controller.description == controllerConfig.libretroDescriptor,
                ).any { it }
            }?.id
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)

        owner.launchOnState(Lifecycle.State.CREATED) {
            initializeControllerConfigsFlow()
        }

        owner.launchOnState(Lifecycle.State.CREATED) {
            initializeGamePadShortcutsFlow()
        }

        owner.launchOnState(Lifecycle.State.CREATED) {
            initializeGamePadKeysFlow()
        }

        owner.launchOnState(Lifecycle.State.CREATED) {
            initializeVirtualGamePadMotionsFlow()
        }

        owner.launchOnState(Lifecycle.State.CREATED) {
            initializeGamePadMotionsFlow()
        }

        owner.launchOnState(Lifecycle.State.RESUMED) {
            initializeControllersConfigFlow()
        }
    }

    private suspend fun initializeControllerConfigsFlow() {
        retroGameView.waitGLEvent<GLRetroView.GLRetroEvents.FrameRendered>()
        controllerConfigsState.safeCollect {
            updateControllers(it)
        }
    }

    private suspend fun initializeGamePadShortcutsFlow() {
        inputDeviceManager.getGameShortcutsObservable()
            .distinctUntilChanged()
            .safeCollect { allShortcuts ->
                allShortcuts.values
                    .firstNotNullOfOrNull { shortcuts ->
                        shortcuts.firstOrNull { it.type == GameShortcutType.MENU }
                    }
                    ?.let {
                        val message =
                            appContext.resources.getString(
                                R.string.game_toast_settings_button_using_gamepad,
                                it.name,
                            )
                        sideEffects.showToast(message)
                    }
            }
    }

    private suspend fun initializeGamePadKeysFlow() {
        val pressedKeys = mutableSetOf<Int>()

        val filteredKeyEvents =
            keyEventsFlow
                .filterNotNull()
                .filter { it.repeatCount == 0 }
                .map { Triple(it.device, it.action, it.keyCode) }
                .distinctUntilChanged()

        val combinedObservable =
            combine(
                inputDeviceManager.getGameShortcutsObservable(),
                inputDeviceManager.getGamePadsPortMapperObservable(),
                inputDeviceManager.getInputBindingsObservable(),
                inputDeviceManager.getNesInputBindingsObservable(),
                filteredKeyEvents,
                ::NTuple5,
            )

        combinedObservable
            .onStart {
                pressedKeys.clear()
                releaseAllNesGamePadButtons()
            }
            .onCompletion {
                pressedKeys.clear()
                releaseAllNesGamePadButtons()
            }
            .safeCollect { (shortcuts, ports, bindings, nesBindings, event) ->
                val (device, action, keyCode) = event
                val port = ports(device)
                val bindKeyCode = bindings(device)[InputKey(keyCode)]?.keyCode ?: keyCode

                if (port == 0) {
                    if (bindKeyCode == KeyEvent.KEYCODE_BUTTON_MODE && action == KeyEvent.ACTION_DOWN) {
                        sideEffects.showMenu(tilt, this)
                        return@safeCollect
                    }

                    if (action == KeyEvent.ACTION_DOWN) {
                        pressedKeys.add(keyCode)
                    } else if (action == KeyEvent.ACTION_UP) {
                        pressedKeys.remove(keyCode)
                    }

                    shortcuts[device]?.forEach { shortcut ->
                        if (shortcut.keys.isNotEmpty() && pressedKeys.containsAll(shortcut.keys)) {
                            when (shortcut.type) {
                                GameShortcutType.MENU -> sideEffects.showMenu(tilt, this)
                                GameShortcutType.QUICK_LOAD -> sideEffects.loadQuickSave()
                                GameShortcutType.QUICK_SAVE -> sideEffects.saveQuickSave()
                                GameShortcutType.TOGGLE_FAST_FORWARD -> sideEffects.toggleFastForward()
                            }
                            return@safeCollect
                        }
                    }
                }

                port?.let {
                    val nesBinding =
                        if (system.id == SystemID.NES) {
                            nesBindings(device)[InputKey(keyCode)]
                        } else {
                            null
                        }
                    if (nesBinding != null) {
                        handleNesGamePadButton(device, action, keyCode, nesBinding, it)
                    } else {
                        retroGameView.retroGameView?.sendKeyEvent(action, bindKeyCode, it)
                    }
                }
            }
    }

    private fun handleNesGamePadButton(
        device: InputDevice,
        action: Int,
        keyCode: Int,
        binding: GamePadButtonBinding,
        port: Int,
    ) {
        val source = GamePadSource(device.id, keyCode)
        removeNesGamePadSource(source)

        if (action != KeyEvent.ACTION_DOWN) return

        val activeBinding = ActiveGamePadBinding(GamePadOutput(port, binding.retroKey.keyCode), binding.turbo)
        activeNesGamePadBindings[source] = activeBinding
        val sources =
            if (binding.turbo) {
                turboNesGamePadSources.getOrPut(activeBinding.output, ::mutableSetOf)
            } else {
                normalNesGamePadSources.getOrPut(activeBinding.output, ::mutableSetOf)
            }
        sources += source

        if (binding.turbo) {
            startNesGamePadTurbo(activeBinding.output)
        } else {
            emitNesGamePadOutputIfChanged(activeBinding.output)
        }
    }

    private fun removeNesGamePadSource(source: GamePadSource) {
        val binding = activeNesGamePadBindings.remove(source) ?: return
        val sourceMap = if (binding.turbo) turboNesGamePadSources else normalNesGamePadSources
        val sources = sourceMap[binding.output] ?: return
        sources -= source
        if (sources.isEmpty()) {
            sourceMap -= binding.output
            if (binding.turbo) {
                turboNesGamePadJobs.remove(binding.output)?.cancel()
                turboNesGamePadPulses -= binding.output
            }
        }
        emitNesGamePadOutputIfChanged(binding.output)
    }

    private fun startNesGamePadTurbo(output: GamePadOutput) {
        if (turboNesGamePadJobs[output] != null) return

        turboNesGamePadJobs[output] =
            scope.launch {
                try {
                    while (isActive && turboNesGamePadSources[output]?.isNotEmpty() == true) {
                        turboNesGamePadPulses += output
                        emitNesGamePadOutputIfChanged(output)
                        delay(NES_GAME_PAD_TURBO_HALF_PERIOD_MILLIS)
                        turboNesGamePadPulses -= output
                        emitNesGamePadOutputIfChanged(output)
                        delay(NES_GAME_PAD_TURBO_HALF_PERIOD_MILLIS)
                    }
                } finally {
                    turboNesGamePadPulses -= output
                    emitNesGamePadOutputIfChanged(output)
                }
            }
    }

    private fun emitNesGamePadOutputIfChanged(output: GamePadOutput) {
        val pressed =
            normalNesGamePadSources[output]?.isNotEmpty() == true ||
                output in turboNesGamePadPulses
        if ((output in emittedNesGamePadOutputs) == pressed) return

        if (pressed) {
            emittedNesGamePadOutputs += output
        } else {
            emittedNesGamePadOutputs -= output
        }
        retroGameView.retroGameView?.sendKeyEvent(
            if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            output.keyCode,
            output.port,
        )
    }

    private fun releaseAllNesGamePadButtons() {
        turboNesGamePadJobs.values.forEach { it.cancel() }
        turboNesGamePadJobs.clear()
        activeNesGamePadBindings.clear()
        normalNesGamePadSources.clear()
        turboNesGamePadSources.clear()
        turboNesGamePadPulses.clear()
        emittedNesGamePadOutputs.toList().forEach { output ->
            retroGameView.retroGameView?.sendKeyEvent(KeyEvent.ACTION_UP, output.keyCode, output.port)
        }
        emittedNesGamePadOutputs.clear()
    }

    private suspend fun initializeVirtualGamePadMotionsFlow() {
        val events =
            combine(
                inputDeviceManager.getGamePadsPortMapperObservable(),
                motionEventsFlow,
                ::NTuple2,
            )

        events
            .mapNotNull { (ports, event) ->
                ports(event.device)?.let { it to event }
            }
            .map { (port, event) ->
                val axes = event.device.getInputClass().getAxesMap().entries

                axes.map { (axis, button) ->
                    val action =
                        if (event.getAxisValue(axis) > 0.5) {
                            KeyEvent.ACTION_DOWN
                        } else {
                            KeyEvent.ACTION_UP
                        }
                    SingleAxisEvent(axis, action, button, port)
                }.toSet()
            }
            .scan(emptySet<SingleAxisEvent>()) { prev, next ->
                next.minus(prev).forEach {
                    retroGameView.retroGameView?.sendKeyEvent(it.action, it.keyCode, it.port)
                }
                next
            }
            .safeCollect { }
    }

    private suspend fun initializeGamePadMotionsFlow() {
        val events =
            combine(
                inputDeviceManager.getGamePadsPortMapperObservable(),
                motionEventsFlow,
                ::NTuple2,
            )

        events
            .safeCollect { (ports, event) ->
                ports(event.device)?.let {
                    sendStickMotions(event, it)
                }
            }
    }

    private suspend fun initializeControllersConfigFlow() {
        try {
            retroGameView.waitRetroGameViewInitialized()
            val controllers = controllerConfigsManager.getControllerConfigs(system.id, systemCoreConfig)
            controllerConfigsState.value = controllers
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    companion object {
        private const val NES_GAME_PAD_TURBO_HALF_PERIOD_MILLIS = 42L
    }
}
