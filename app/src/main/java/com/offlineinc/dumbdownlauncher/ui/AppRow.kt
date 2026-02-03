package com.offlineinc.dumbdownlauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

@Composable
fun AppRow(
    item: AppItem,
    selected: Boolean,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) DumbTheme.Colors.Yellow else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val iconBitmap = remember(item.icon) { item.icon.toBitmapSafely(96, 96) }

        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap.asImageBitmap(),
                contentDescription = item.label,
                modifier = Modifier.size(38.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(if (selected) DumbTheme.Colors.Black else DumbTheme.Colors.Gray)
            )
        }

        Spacer(Modifier.width(14.dp))

        BasicText(
            text = item.label,
            style = TextStyle(
                fontFamily = fontFamily,
                fontSize = 32.sp,
                color = if (selected) DumbTheme.Colors.Black else DumbTheme.Colors.White
            ),
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
    }
}
