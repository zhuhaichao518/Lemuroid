package com.swordfish.touchinput.radial.layouts

import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager

internal fun TouchControllerSettingsManager.FaceButtonAction.shortLabel(): String {
    return when (this) {
        TouchControllerSettingsManager.FaceButtonAction.TURBO_A -> "A连"
        TouchControllerSettingsManager.FaceButtonAction.TURBO_B -> "B连"
        TouchControllerSettingsManager.FaceButtonAction.TURBO_X -> "X连"
        TouchControllerSettingsManager.FaceButtonAction.TURBO_Y -> "Y连"
        TouchControllerSettingsManager.FaceButtonAction.TURBO_L1 -> "L1连"
        TouchControllerSettingsManager.FaceButtonAction.TURBO_R1 -> "R1连"
        TouchControllerSettingsManager.FaceButtonAction.NORMAL_A -> "A"
        TouchControllerSettingsManager.FaceButtonAction.NORMAL_B -> "B"
        TouchControllerSettingsManager.FaceButtonAction.NORMAL_X -> "X"
        TouchControllerSettingsManager.FaceButtonAction.NORMAL_Y -> "Y"
        TouchControllerSettingsManager.FaceButtonAction.NORMAL_L1 -> "L1"
        TouchControllerSettingsManager.FaceButtonAction.NORMAL_R1 -> "R1"
    }
}
