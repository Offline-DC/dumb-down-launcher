package com.offlineinc.dumbdownlauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.CHANGE_PLATFORM
import com.offlineinc.dumbdownlauncher.CHECK_UPDATES
import com.offlineinc.dumbdownlauncher.GOOGLE_MESSAGES
import com.offlineinc.dumbdownlauncher.WEB_KEYBOARD
import com.offlineinc.dumbdownlauncher.DEVICE_PAIRING
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

private val vectorIcons: Map<String, ImageVector> = mapOf(
    "com.openbubbles.messaging" to Icons.Filled.Chat,
    GOOGLE_MESSAGES to Icons.Filled.Chat,
    "com.ubercab.uberlite" to Icons.Filled.DirectionsCar,
    CHANGE_PLATFORM to Icons.Filled.Psychology,
    CHECK_UPDATES to Icons.Filled.SystemUpdate,
    WEB_KEYBOARD to Icons.Filled.Keyboard,
    DEVICE_PAIRING to Icons.Filled.Link,
)

@Composable
fun AppRow(
    item: AppItem,
    selected: Boolean,
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

        // ---- App Icon ----
        val vectorIcon = vectorIcons[item.packageName]

        if (vectorIcon != null) {
            Image(
                painter = rememberVectorPainter(vectorIcon),
                contentDescription = item.label,
                modifier = Modifier.size(38.dp),
                colorFilter = ColorFilter.tint(
                    if (selected) DumbTheme.Colors.Black else DumbTheme.Colors.White
                )
            )
        } else {
            val iconBitmap = remember(item.icon) {
                item.icon.toBitmapSafely(96, 96)
            }

            val grayscaleFilter = remember {
                ColorFilter.colorMatrix(
                    ColorMatrix().apply { setToSaturation(0f) }
                )
            }

            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap.asImageBitmap(),
                    contentDescription = item.label,
                    modifier = Modifier.size(38.dp),
                    colorFilter = if (item.isMuted) grayscaleFilter else null
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(
                            if (selected)
                                DumbTheme.Colors.Black
                            else
                                DumbTheme.Colors.Gray
                        )
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        BasicText(
            text = item.label,
            style = TextStyle(
                fontFamily = fontFamily,
                // Slightly smaller when an ON/OFF badge is present so the label fits
                fontSize = if (item.isToggleOn != null) 26.sp else 32.sp,
                color = if (selected)
                    DumbTheme.Colors.Black
                else
                    DumbTheme.Colors.White
            ),
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )

        // Toggle indicator for items that act as toggles (e.g. Type Sync)
        if (item.isToggleOn != null) {
            Spacer(Modifier.width(10.dp))
            val toggleOn = item.isToggleOn
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(28.dp)
                    .background(
                        color = if (toggleOn)
                            if (selected) DumbTheme.Colors.Black else DumbTheme.Colors.Yellow
                        else
                            if (selected) DumbTheme.Colors.Black.copy(alpha = 0.3f)
                            else DumbTheme.Colors.Gray,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = if (toggleOn) "ON" else "OFF",
                    style = TextStyle(
                        fontFamily = fontFamily,
                        fontSize = 13.sp,
                        color = if (toggleOn)
                            if (selected) DumbTheme.Colors.Yellow else DumbTheme.Colors.Black
                        else
                            DumbTheme.Colors.White.copy(alpha = 0.5f)
                    )
                )
            }
        }

        if (item.isMuted) {
            Spacer(Modifier.width(10.dp))

            Image(
                painter = painterResource(
                    android.R.drawable.ic_lock_silent_mode
                ),
                contentDescription = "Muted",
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
