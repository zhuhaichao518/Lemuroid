package com.swordfish.lemuroid.app.shared.input

import kotlinx.serialization.Serializable

@Serializable
data class GamePadButtonBinding(
    val retroKey: RetroKey,
    val turbo: Boolean = false,
)
