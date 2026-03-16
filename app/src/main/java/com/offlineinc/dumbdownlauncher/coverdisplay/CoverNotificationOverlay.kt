package com.offlineinc.dumbdownlauncher.coverdisplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

@Composable
internal fun CoverNotificationOverlay(overlay: OverlayState?) {
    val font      = DumbTheme.BioRhyme
    val callColor = White

    // ── Overlay spacing — tweak these two values to adjust icon↔label gap ──
    val overlayIconToLabel = 2.dp   // gap between icon and first line of text
    val overlayLabelToSub  = 2.dp   // gap between label and caller name (calls only)

    val o = overlay ?: return
    Column(
        modifier            = Modifier.fillMaxSize().background(Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // ── Overlay icon size — change this one value ──
        val overlayIconSize = 40.dp
        Icon(
            imageVector        = if (o.kind == OverlayKind.CALL) Icons.Rounded.PhoneInTalk else Icons.Rounded.MailOutline,
            contentDescription = null,
            tint               = if (o.kind == OverlayKind.CALL) callColor else White,
            modifier           = Modifier.size(overlayIconSize),
        )
        Spacer(Modifier.height(overlayIconToLabel))
        if (o.kind != OverlayKind.CALL) {
            BasicText(
                text = o.line1,
                style = TextStyle(
                    fontFamily = font,
                    fontSize = 8.sp,
                    color = if (o.kind == OverlayKind.CALL) callColor else White,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (o.kind == OverlayKind.CALL) {
            Spacer(Modifier.height(overlayLabelToSub))
            BasicText(
                text     = o.line2,
                style    = TextStyle(
                    fontFamily = font,
                    fontSize   = 8.sp,
                    color      = White,
                    textAlign  = TextAlign.Center,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (o.line3.isNotEmpty()) {
                Spacer(Modifier.height(overlayLabelToSub))
                BasicText(
                    text     = o.line3,
                    style    = TextStyle(
                        fontFamily = font,
                        fontSize   = 6.sp,
                        color      = Gray,
                        textAlign  = TextAlign.Center,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "Overlay – call", showBackground = true, backgroundColor = 0xFF000000, device = "spec:width=128px,height=128px,dpi=213")
@Composable
private fun PreviewOverlayCall() = CoverNotificationOverlay(
    overlay = OverlayState(OverlayKind.CALL, "incoming call", "Alex", "Washington, DC"),
)

@Preview(name = "Overlay – message", showBackground = true, backgroundColor = 0xFF000000, device = "spec:width=128px,height=128px,dpi=213")
@Composable
private fun PreviewOverlayMessage() = CoverNotificationOverlay(
    overlay = OverlayState(OverlayKind.MESSAGE, "new message", ""),
)

@Preview(name = "Overlay – none", showBackground = true, backgroundColor = 0xFF000000, device = "spec:width=128px,height=128px,dpi=213")
@Composable
private fun PreviewOverlayNone() = CoverNotificationOverlay(overlay = null)
