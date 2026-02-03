// launcher/KeyDispatcher.kt
package com.offlineinc.dumbdownlauncher.launcher

import android.view.KeyEvent

object KeyDispatcher {

    data class Result(
        val consumed: Boolean,
        val dialDigits: String? = null,
        val activateSelected: Boolean = false,
        val openDialerBlank: Boolean = false,
        val resetDialSession: Boolean = false,
        val openNotifications: Boolean = false,
        val openAllApps: Boolean = false,
    )

    fun handle(event: KeyEvent): Result {
        if (event.action != KeyEvent.ACTION_DOWN) return Result(consumed = false)

        when (event.keyCode) {
            KeyEvent.KEYCODE_SOFT_LEFT -> return Result(consumed = true, openNotifications = true)
            KeyEvent.KEYCODE_SOFT_RIGHT -> return Result(consumed = true, openAllApps = true)
        }

        if (event.keyCode == KeyEvent.KEYCODE_ENDCALL) {
            return Result(consumed = true, resetDialSession = true)
        }

        // Prefer unicodeChar so it works across weird keycodes
        val ch = event.unicodeChar
        if (ch != 0) {
            val s = ch.toChar().toString()
            if (s in listOf("0","1","2","3","4","5","6","7","8","9","*","#")) {
                return Result(consumed = true, dialDigits = s)
            }
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER ->
                Result(consumed = true, activateSelected = true)

            KeyEvent.KEYCODE_CALL ->
                Result(consumed = true, openDialerBlank = true)

            // Fallbacks if unicodeChar not populated
            KeyEvent.KEYCODE_STAR ->
                Result(consumed = true, dialDigits = "*")

            KeyEvent.KEYCODE_POUND ->
                Result(consumed = true, dialDigits = "#")

            else -> Result(consumed = false)
        }
    }
}
