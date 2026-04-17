package com.offlineinc.dumbdownlauncher.contactsync

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.offlineinc.dumbdownlauncher.contactsync.ui.ContactSyncNav
import com.offlineinc.dumbdownlauncher.ui.components.DumbChipButton
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/**
 * Hosts the Contact Sync UI within the launcher app.
 * Replaces the standalone com.offlineinc.dumbcontactsync app.
 */
class ContactSyncActivity : AppCompatActivity() {

    companion object {
        /** Pass true when launching during onboarding to show a "skip" button. */
        const val EXTRA_ONBOARDING = "extra_onboarding"
    }

    /** Focus state for the skip button — used in dispatchKeyEvent. */
    private var skipFocusRequester: FocusRequester? = null
    private var isSkipFocused = false

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF000000.toInt()

        val isOnboarding = intent.getBooleanExtra(EXTRA_ONBOARDING, false)

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DumbTheme.Colors.Black)
            ) {
                ContactSyncNav(
                    isOnboarding = isOnboarding,
                    onFinish = { finish() }
                )

                // Skip button — only during onboarding, top-right corner.
                // Up from content → skip. Down from skip → back to content.
                // Skip is NOT focusable during initial render so it doesn't
                // steal focus from the content area.
                if (isOnboarding) {
                    val skipFocus = remember { FocusRequester() }
                    var skipFocused by remember { mutableStateOf(false) }
                    var skipReady by remember { mutableStateOf(false) }

                    // Delay making skip available so content focus traps win
                    LaunchedEffect(Unit) {
                        delay(500)
                        skipReady = true
                    }

                    // Expose to dispatchKeyEvent only when ready
                    skipFocusRequester = if (skipReady) skipFocus else null
                    isSkipFocused = skipFocused

                    if (skipReady) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                                            finish()
                                            true
                                        }
                                        else -> false
                                    }
                                }
                        ) {
                            DumbChipButton(
                                text = "skip",
                                focusRequester = skipFocus,
                                isFocused = skipFocused,
                                onFocusChanged = { skipFocused = it }
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Intercept D-pad Up at the Activity level so it moves focus to the
     * skip button — but only when skip is NOT already focused. This lets
     * the default Compose focus traversal handle Down/Back from skip
     * back to the content below.
     *
     * Also intercept Back to ensure we always finish() cleanly back to
     * AllAppsActivity rather than landing on a stale activity in the stack.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!isSkipFocused) {
                        skipFocusRequester?.let {
                            try { it.requestFocus(); return true } catch (_: Throwable) {}
                        }
                    }
                }
                KeyEvent.KEYCODE_BACK -> {
                    finish()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
