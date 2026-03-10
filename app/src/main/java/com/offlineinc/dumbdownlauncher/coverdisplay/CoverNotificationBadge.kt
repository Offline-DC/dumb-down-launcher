package com.offlineinc.dumbdownlauncher.coverdisplay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

@Composable
internal fun CoverNotificationBadge(
    badgeCount: Int,
    hasNew:     Boolean,
    modifier:   Modifier = Modifier,
) {
    val font = DumbTheme.BioRhyme
    AnimatedVisibility(
        visible  = badgeCount > 0,
        modifier = modifier,
        enter    = fadeIn(),
        exit     = fadeOut(),
    ) {
        Box(
            modifier         = Modifier
                .requiredSize(12.dp)
                .background(
                    color = if (hasNew) Yellow else Color(0xFF444444),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text  = if (badgeCount > 9) "9+" else badgeCount.toString(),
                style = TextStyle(
                    fontFamily    = font,
                    fontSize      = 5.sp,
                    lineHeight    = 5.sp,
                    color         = if (hasNew) Black else Gray,
                    textAlign     = TextAlign.Center,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "Badge – new", showBackground = true, backgroundColor = 0xFF000000, device = "spec:width=128px,height=128px,dpi=213")
@Composable
private fun PreviewBadgeNew() {
    Box(Modifier.fillMaxSize().background(Black), contentAlignment = Alignment.TopStart) {
        CoverNotificationBadge(badgeCount = 3, hasNew = true)
    }
}

@Preview(name = "Badge – seen", showBackground = true, backgroundColor = 0xFF000000, device = "spec:width=128px,height=128px,dpi=213")
@Composable
private fun PreviewBadgeSeen() {
    Box(Modifier.fillMaxSize().background(Black), contentAlignment = Alignment.TopStart) {
        CoverNotificationBadge(badgeCount = 5, hasNew = false)
    }
}

@Preview(name = "Badge – overflow", showBackground = true, backgroundColor = 0xFF000000, device = "spec:width=128px,height=128px,dpi=213")
@Composable
private fun PreviewBadgeOverflow() {
    Box(Modifier.fillMaxSize().background(Black), contentAlignment = Alignment.TopStart) {
        CoverNotificationBadge(badgeCount = 12, hasNew = true)
    }
}

@Preview(name = "Badge – none", showBackground = true, backgroundColor = 0xFF000000, device = "spec:width=128px,height=128px,dpi=213")
@Composable
private fun PreviewBadgeNone() {
    Box(Modifier.fillMaxSize().background(Black), contentAlignment = Alignment.TopStart) {
        CoverNotificationBadge(badgeCount = 0, hasNew = false)
    }
}
