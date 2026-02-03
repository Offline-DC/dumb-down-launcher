package com.offlineinc.dumbdownlauncher.notifications.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    fontFamily: FontFamily
) {
    BasicText(
        text = "none... ur free!",
        style = TextStyle(
            color = DumbTheme.Colors.Gray,
            fontSize = 16.sp,
            fontFamily = fontFamily
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    )
}
