package com.offlineinc.dumbdownlauncher.openbubbles

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.notifications.ui.NotificationsActivity
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/**
 * "You must update SmartText (OpenBubbles) before you can use it" gate,
 * shown by [com.offlineinc.dumbdownlauncher.MainAppsGridActivity] instead of
 * launching OpenBubbles when the installed build is below
 * [OpenBubblesOps.MIN_SUPPORTED_VERSION_CODE] AND an update is already
 * waiting in the notifications page.
 *
 * Presented as a **modal**: the activity uses a translucent window theme
 * (see AndroidManifest) so the screen the user came from stays visible,
 * dimmed behind a scrim, with a small centered card on top.
 *
 * Hard block: there is no "open anyway". The only forward action ("update")
 * routes to [NotificationsActivity] with [NotificationsActivity.EXTRA_SCROLL_TO_UPDATE]
 * so it lands focused on the pending update notification — the user taps it
 * there to download/install. Back just dismisses the modal (OpenBubbles
 * stays closed).
 */
class OpenBubblesUpdateRequiredActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            OpenBubblesUpdateRequiredModal(
                onUpdate = {
                    startActivity(
                        Intent(this, NotificationsActivity::class.java)
                            .putExtra(NotificationsActivity.EXTRA_SCROLL_TO_UPDATE, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    )
                    overridePendingTransition(0, 0)
                    finish()
                },
                onDismiss = { finish() },
            )
        }
    }
}

@Composable
private fun OpenBubblesUpdateRequiredModal(
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Scrim dims (but keeps visible) the underlying screen — this is what
    // makes it read as a modal rather than a full page.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        onUpdate()
                        true
                    }
                    Key.Back -> {
                        onDismiss()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .background(DumbTheme.Colors.Black)
                .border(1.dp, DumbTheme.Colors.White)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            BasicText(
                text = "please update smrt txt",
                style = TextStyle(
                    fontFamily = DumbTheme.BioRhyme,
                    fontSize = 20.sp,
                    color = DumbTheme.Colors.White,
                ),
            )
            BasicText(
                text = "to latest version to use",
                style = TextStyle(
                    fontFamily = DumbTheme.BioRhyme,
                    fontSize = 20.sp,
                    color = DumbTheme.Colors.White,
                ),
                modifier = Modifier.padding(bottom = 24.dp),
            )
            BasicText(
                text = "> update",
                style = TextStyle(
                    fontFamily = DumbTheme.BioRhyme,
                    fontSize = 24.sp,
                    color = DumbTheme.Colors.Yellow,
                ),
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}
