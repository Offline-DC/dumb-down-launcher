package com.offlineinc.dumbdownlauncher.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.ui.SoftKeyBar
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/**
 * "Storage info" page — reached from [FreeUpSpaceScreen] by pressing the
 * d-pad center / OK key (labeled "info" in the soft key bar).
 *
 * Deliberately plain prose with three short sections:
 *   1. What the launcher cleans up automatically each night.
 *   2. A live storage summary, computed the same way the list screen
 *      computes its "X free of Y" header (free space + addressable pool).
 *   3. Support contact, mirroring the launcher's
 *      [com.offlineinc.dumbdownlauncher.launcher.ResetWarningOverlay].
 *
 * Back / soft-right returns to the list — no other navigation lives here.
 */
@Composable
fun StorageInfoScreen(
    freeBytes: Long,
    addressableTotal: Long,
    onBack: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }

    // Values come down from the parent (FreeUpSpaceScreen), which
    // already computed them via `allSizesBytes` on first composition.
    // Reusing those values means opening the info page doesn't pay
    // another root-shell + multi-section `du` pass — the previous
    // self-contained LaunchedEffect did, which made the info page open
    // feel as slow as the initial "checking storage…" load.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Only "back" gestures dismiss — we don't intercept d-pad
                // up/down etc. because the body scrolls and we want the
                // platform's default scroll handling to take over.
                if (event.key == Key.Back || event.key == Key.SoftRight) {
                    onBack(); true
                } else false
            }
            .focusable(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        ) {
            Spacer(Modifier.height(DumbTheme.Spacing.ScreenPaddingV))

            BasicText(
                text = "storage info",
                style = DumbTheme.Text.PageTitle.copy(
                    color = DumbTheme.Colors.Yellow,
                    fontFamily = DumbTheme.Body,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                ),
            )

            Spacer(Modifier.height(DumbTheme.Spacing.SectionGap))

            // ── Nightly cleanup ──────────────────────────────────────────
            // Section trimmed deliberately tight so the bullets + the
            // single retention-explainer line fit on the TCL Flip's
            // narrow viewport without forcing a scroll. The detail
            // ("4 am", "spotify when it grows past 400 mb", etc.) lives
            // in the source comments of each worker; users who need it
            // can ask support — the on-device page just tells them
            // what's cleaned and what survives.
            SectionHeader("nightly cleanup")
            BulletLine("all whatsapp photos & videos")
            BulletLine("smart txt attachments")
            BulletLine("spotify cache")
            BulletLine("old call log entries")
            Spacer(Modifier.height(6.dp))
            BodyParagraph(
                "messages stay. media is removed from this phone " +
                    "only — originals stay on your other devices."
            )

            Spacer(Modifier.height(DumbTheme.Spacing.SectionGap))

            // ── Storage summary ──────────────────────────────────────────
            SectionHeader("storage summary")
            BodyParagraph(freeOfTotalLine(freeBytes, addressableTotal))
            BodyParagraph(
                "the total above is the free space plus everything " +
                    "this app can clean up — not the whole disk. most " +
                    "of the disk is android itself, which can't be " +
                    "freed from here."
            )

            Spacer(Modifier.height(DumbTheme.Spacing.SectionGap))

            // ── Help ─────────────────────────────────────────────────────
            SectionHeader("need help?")
            BodyParagraph(
                "if something looks wrong — a clear button says zero " +
                    "even though your phone feels full, or the nightly " +
                    "cleanup stopped working — please reach out."
            )
            BodyParagraph("email  support@dumb.co")
            BodyParagraph("call   404 716 3605")

            Spacer(Modifier.height(DumbTheme.Spacing.ScreenPaddingV))
        }

        SoftKeyBar(
            leftLabel = null,
            centerLabel = null,
            rightLabel = "back",
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    BasicText(
        text = text,
        style = DumbTheme.Text.Subtitle.copy(color = DumbTheme.Colors.Yellow),
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun BodyParagraph(text: String) {
    BasicText(
        text = text,
        style = DumbTheme.Text.Body,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun BulletLine(text: String) {
    // Bullet via leading "• " in the body text — keeps the rendering
    // path identical to the rest of the screen instead of pulling in
    // a list composable just for visual indentation.
    BasicText(
        text = "  •  $text",
        style = DumbTheme.Text.Body,
    )
    Spacer(Modifier.height(2.dp))
}

