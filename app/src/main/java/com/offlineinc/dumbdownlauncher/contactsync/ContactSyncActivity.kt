package com.offlineinc.dumbdownlauncher.contactsync

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.contactsync.ui.ContactSyncNav
import com.offlineinc.dumbdownlauncher.contactsync.ui.theme.DumbContactSyncTheme
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
            DumbContactSyncTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Scaffold(modifier = Modifier.fillMaxSize()) {
                        ContactSyncNav(
                            isOnboarding = isOnboarding,
                            onFinish = { finish() }
                        )
                    }

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

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 6.dp, end = 8.dp)
                                .focusRequester(skipFocus)
                                .onFocusChanged { skipFocused = it.isFocused }
                                .focusable(enabled = skipReady)
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                                            finish()
                                            true
                                        }
                                        // Down or Back from skip → release focus so
                                        // default Compose traversal returns to content
                                        else -> false
                                    }
                                }
                                .then(
                                    if (skipFocused) Modifier
                                        .background(DumbTheme.Colors.Yellow, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                    else Modifier
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                        ) {
                            BasicText(
                                text = "skip",
                                style = TextStyle(
                                    fontFamily = DumbTheme.BioRhyme,
                                    fontSize = 11.sp,
                                    color = if (skipFocused) DumbTheme.Colors.Black
                                    else DumbTheme.Colors.Gray
                                )
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
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN
            && event.keyCode == KeyEvent.KEYCODE_DPAD_UP
            && !isSkipFocused
        ) {
            skipFocusRequester?.let {
                try { it.requestFocus(); return true } catch (_: Throwable) {}
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
