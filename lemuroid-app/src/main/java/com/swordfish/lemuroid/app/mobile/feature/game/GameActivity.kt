package com.swordfish.lemuroid.app.mobile.feature.game

import android.view.MotionEvent
import androidx.compose.runtime.Composable
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.GameMenuActivity
import com.swordfish.lemuroid.app.shared.game.BaseGameActivity
import com.swordfish.lemuroid.app.shared.game.BaseGameScreenViewModel
import com.swordfish.touchinput.radial.controls.CloudStickController

class GameActivity : BaseGameActivity() {
    private val cloudStickController = CloudStickController()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        cloudStickController.dispatchTouchEvent(event) { super.dispatchTouchEvent(it) }

    override fun onPause() {
        cloudStickController.cancel()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) cloudStickController.cancel()
        super.onWindowFocusChanged(hasFocus)
    }

    @Composable
    override fun GameScreen(viewModel: BaseGameScreenViewModel) {
        MobileGameScreen(viewModel, cloudStickController)
    }

    override fun getDialogClass() = GameMenuActivity::class.java
}
