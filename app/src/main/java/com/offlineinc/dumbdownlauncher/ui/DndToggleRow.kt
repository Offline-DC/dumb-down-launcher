package com.offlineinc.dumbdownlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

const val DND_TOGGLE = "__DND_TOGGLE__"

@Composable
fun DndToggleRow(
    item: AppItem,
    selected: Boolean,
    enabled: Boolean,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) DumbTheme.Colors.Yellow else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TogglePill(
            enabled = enabled,
            selectedRow = selected
        )

        Spacer(Modifier.width(14.dp))

        BasicText(
            text = item.label,
            style = TextStyle(
                fontFamily = fontFamily,
                fontSize = 28.sp,
                color = if (selected) DumbTheme.Colors.Black else DumbTheme.Colors.White
            ),
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TogglePill(
    enabled: Boolean,
    selectedRow: Boolean,
    modifier: Modifier = Modifier,
) {
    val trackColor = when {
        enabled && selectedRow -> DumbTheme.Colors.Black
        enabled -> DumbTheme.Colors.Yellow
        selectedRow -> DumbTheme.Colors.Gray
        else -> DumbTheme.Colors.Gray
    }
    val thumbColor = when {
        selectedRow -> DumbTheme.Colors.Yellow
        else -> DumbTheme.Colors.White
    }

    Box(
        modifier = modifier
            .size(width = 38.dp, height = 22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(trackColor),
        contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(2.dp)
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(thumbColor)
        )
    }
}