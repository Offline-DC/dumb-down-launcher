package com.offlineinc.dumbdownlauncher.notifications.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.notifications.isMissedCall
import com.offlineinc.dumbdownlauncher.notifications.model.NotificationItem
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

@Composable
fun NotificationRow(
    item: NotificationItem,
    selected: Boolean,
    fontFamily: FontFamily,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) DumbTheme.Colors.Yellow else androidx.compose.ui.graphics.Color.Transparent)
            .padding(14.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
    ) {
        // Missed-call rows share the title line with an end-aligned
        // timestamp ("2:20 PM", "Yesterday", "Monday", "5/27/26" —
        // iOS-style). Title takes weight(1f) so it truncates rather
        // than pushing the time off-screen on long title strings.
        // Other notification types intentionally omit the timestamp —
        // they already self-describe their recency through ordering,
        // and adding one to every row clutters the d-pad list on a
        // small flip-phone screen.
        val missed = item.isMissedCall()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = item.title,
                style = TextStyle(
                    color = if (selected) DumbTheme.Colors.Black else DumbTheme.Colors.White,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontFamily = fontFamily,
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.None,
                    ),
                ),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (missed) {
                val context = LocalContext.current
                // Re-derive when [item] changes so a dialer that updates
                // the same notification (multiple missed calls from the
                // same contact bumping the timestamp) re-renders the line.
                val timeLabel = remember(item.whenTime) {
                    formatNotificationTime(context, item.whenTime)
                }
                Spacer(Modifier.width(8.dp))
                BasicText(
                    text = timeLabel,
                    style = TextStyle(
                        color = if (selected) DumbTheme.Colors.Black else DumbTheme.Colors.Gray,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontFamily = fontFamily,
                        platformStyle = PlatformTextStyle(includeFontPadding = true),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.None,
                        ),
                    ),
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        BasicText(
            text = item.text,
            style = TextStyle(
                color = if (selected) DumbTheme.Colors.Black else DumbTheme.Colors.Gray,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontFamily = fontFamily,
                platformStyle = PlatformTextStyle(includeFontPadding = true),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.None,
                ),
            ),
            maxLines = 2
        )
    }
}
