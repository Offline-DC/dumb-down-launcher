package com.offlineinc.dumbdownlauncher.snake

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * Hosts the Snake game within the launcher app.
 * Uses the traditional View system (not Compose) because SnakeGameView
 * is a custom Canvas-based View ported from the standalone snake APK.
 * Follows the same virtual-app pattern as QuackActivity and WeatherActivity.
 */
class SnakeActivity : AppCompatActivity() {

    private lateinit var snakeGameView: SnakeGameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF000000.toInt()

        // Keep screen on while playing
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        snakeGameView = SnakeGameView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(snakeGameView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }

        setContentView(container)
        snakeGameView.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                snakeGameView.handleKey(Direction.UP)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                snakeGameView.handleKey(Direction.DOWN)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                snakeGameView.handleKey(Direction.LEFT)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                snakeGameView.handleKey(Direction.RIGHT)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                snakeGameView.handleKeyCenter()
                true
            }
            // Also handle number keys as alternative controls
            KeyEvent.KEYCODE_2 -> { snakeGameView.handleKey(Direction.UP); true }
            KeyEvent.KEYCODE_8 -> { snakeGameView.handleKey(Direction.DOWN); true }
            KeyEvent.KEYCODE_4 -> { snakeGameView.handleKey(Direction.LEFT); true }
            KeyEvent.KEYCODE_6 -> { snakeGameView.handleKey(Direction.RIGHT); true }
            KeyEvent.KEYCODE_5 -> { snakeGameView.handleKeyCenter(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onPause() {
        super.onPause()
        snakeGameView.pauseGame()
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
        snakeGameView.resumeIfNeeded()
    }
}
