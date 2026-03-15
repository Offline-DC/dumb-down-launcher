package com.offlineinc.dumbdownlauncher.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.GOOGLE_MESSAGES
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/**
 * Known vector icon overrides for the grid.
 * Matches the map in AppRow.kt for consistency.
 */
private val gridVectorIcons: Map<String, ImageVector> = mapOf(
    "com.openbubbles.messaging" to Icons.Filled.Chat,
    GOOGLE_MESSAGES to Icons.Filled.Chat,
    "com.ubercab.uberlite" to Icons.Filled.DirectionsCar,
)

/**
 * Single cell in the 3×3 main app grid.
 *
 * Sizing targets: 240×320 screen @ 110 dpi.
 *   3 cells across ≈ 72dp each (with 4dp gaps + 8dp padding).
 *   Icon: 48dp, Label: 13sp (2 lines max).
 */
@Composable
fun MainAppGridCell(
    item: AppItem,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (selected) DumbTheme.Colors.Yellow else Color.Transparent
    val textColor = if (selected) DumbTheme.Colors.Black else DumbTheme.Colors.White
    val iconTint = if (selected) DumbTheme.Colors.Black else DumbTheme.Colors.White

    Box(
        modifier = modifier
            .background(
                color = bgColor,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val vectorIcon = gridVectorIcons[item.packageName]

            if (vectorIcon != null) {
                Image(
                    painter = rememberVectorPainter(vectorIcon),
                    contentDescription = item.label,
                    modifier = Modifier.size(48.dp),
                    colorFilter = ColorFilter.tint(iconTint),
                )
            } else {
                val iconBitmap = remember(item.icon) {
                    item.icon.toBitmapSafely(96, 96)
                }
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap.asImageBitmap(),
                        contentDescription = item.label,
                        modifier = Modifier.size(48.dp),
                    )
                } else {
                    // Fallback placeholder
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                if (selected) DumbTheme.Colors.Black else DumbTheme.Colors.Gray,
                                shape = RoundedCornerShape(4.dp),
                            )
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            BasicText(
                text = item.label,
                style = TextStyle(
                    fontFamily = DumbTheme.BioRhyme,
                    fontSize = 13.sp,
                    color = textColor,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(
    name = "Grid Cell – Selected",
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewGridCellSelected() {
    MainAppGridCell(
        item = previewAppItem("WhatsApp"),
        selected = true,
        modifier = Modifier.size(76.dp),
    )
}

@Preview(
    name = "Grid Cell – Unselected",
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewGridCellUnselected() {
    MainAppGridCell(
        item = previewAppItem("Settings"),
        selected = false,
        modifier = Modifier.size(76.dp),
    )
}

@Preview(
    name = "Grid Cell – Long Label",
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewGridCellLongLabel() {
    MainAppGridCell(
        item = previewAppItem("call history"),
        selected = false,
        modifier = Modifier.size(76.dp),
    )
}

/**
 * Creates a minimal [AppItem] for preview purposes.
 * Uses a vector icon stand-in since drawables aren't available in previews.
 */
internal fun previewAppItem(label: String, pkg: String = label): AppItem {
    // In preview we can't get real Drawables, so we create a minimal ColorDrawable
    val dummyDrawable: Drawable = android.graphics.drawable.ColorDrawable(0xFF888888.toInt())
    return AppItem(
        packageName = pkg,
        label = label,
        icon = dummyDrawable,
        launchComponent = null,
    )
}
