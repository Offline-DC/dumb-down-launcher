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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
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
 * Material Symbols "network_intelligence" (filled, rounded) as an ImageVector.
 * Sourced from Google Material Symbols (Apache 2.0).
 * Viewport: 960×960 (Google's 48px symbol grid).
 */
private val NetworkIntelligenceIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "NetworkIntelligence",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
    ).apply {
        path {
            moveTo(317f, 800f)
            quadToRelative(-8f, 0f, -15f, -4f)
            reflectiveQuadToRelative(-11f, -11f)
            lineToRelative(-84f, -150f)
            horizontalLineToRelative(71f)
            lineToRelative(42f, 80f)
            horizontalLineToRelative(90f)
            verticalLineToRelative(-30f)
            horizontalLineToRelative(-72f)
            lineToRelative(-42f, -80f)
            horizontalLineTo(191f)
            lineToRelative(-63f, -110f)
            quadToRelative(-2f, -4f, -3f, -7.5f)
            reflectiveQuadToRelative(-1f, -7.5f)
            quadToRelative(0f, -2f, 4f, -15f)
            lineToRelative(63f, -110f)
            horizontalLineToRelative(105f)
            lineToRelative(42f, -80f)
            horizontalLineToRelative(72f)
            verticalLineToRelative(-30f)
            horizontalLineToRelative(-90f)
            lineToRelative(-42f, 80f)
            horizontalLineToRelative(-71f)
            lineToRelative(84f, -150f)
            quadToRelative(4f, -7f, 11f, -11f)
            reflectiveQuadToRelative(15f, -4f)
            horizontalLineToRelative(118f)
            quadToRelative(13f, 0f, 21.5f, 8.5f)
            reflectiveQuadTo(465f, 190f)
            verticalLineToRelative(175f)
            horizontalLineToRelative(-85f)
            lineToRelative(-30f, 30f)
            horizontalLineToRelative(115f)
            verticalLineToRelative(130f)
            horizontalLineToRelative(-98f)
            lineToRelative(-39f, -80f)
            horizontalLineToRelative(-98f)
            lineToRelative(-30f, 30f)
            horizontalLineToRelative(108f)
            lineToRelative(40f, 80f)
            horizontalLineToRelative(117f)
            verticalLineToRelative(215f)
            quadToRelative(0f, 13f, -8.5f, 21.5f)
            reflectiveQuadTo(435f, 800f)
            close()
            moveTo(525f, 800f)
            quadToRelative(-13f, 0f, -21.5f, -8.5f)
            reflectiveQuadTo(495f, 770f)
            verticalLineToRelative(-215f)
            horizontalLineToRelative(117f)
            lineToRelative(40f, -80f)
            horizontalLineToRelative(108f)
            lineToRelative(-30f, -30f)
            horizontalLineToRelative(-98f)
            lineToRelative(-39f, 80f)
            horizontalLineToRelative(-98f)
            verticalLineToRelative(-130f)
            horizontalLineToRelative(115f)
            lineToRelative(-30f, -30f)
            horizontalLineToRelative(-85f)
            verticalLineToRelative(-175f)
            quadToRelative(0f, -13f, 8.5f, -21.5f)
            reflectiveQuadTo(525f, 160f)
            horizontalLineToRelative(118f)
            quadToRelative(8f, 0f, 15f, 4f)
            reflectiveQuadToRelative(11f, 11f)
            lineToRelative(84f, 150f)
            horizontalLineToRelative(-71f)
            lineToRelative(-42f, -80f)
            horizontalLineToRelative(-90f)
            verticalLineToRelative(30f)
            horizontalLineToRelative(72f)
            lineToRelative(42f, 80f)
            horizontalLineToRelative(105f)
            lineToRelative(63f, 110f)
            quadToRelative(2f, 4f, 3f, 7.5f)
            reflectiveQuadToRelative(1f, 7.5f)
            quadToRelative(0f, 2f, -4f, 15f)
            lineToRelative(-63f, 110f)
            horizontalLineTo(664f)
            lineToRelative(-42f, 80f)
            horizontalLineToRelative(-72f)
            verticalLineToRelative(30f)
            horizontalLineToRelative(90f)
            lineToRelative(42f, -80f)
            horizontalLineToRelative(71f)
            lineToRelative(-84f, 150f)
            quadToRelative(-4f, 7f, -11f, 11f)
            reflectiveQuadToRelative(-15f, 4f)
            close()
        }
    }.build()
}

/** Grayscale color matrix — desaturates bitmap icons completely. */
private val GrayscaleMatrix = ColorFilter.colorMatrix(
    ColorMatrix().apply { setToSaturation(0f) }
)

/**
 * Known vector icon overrides for the 3×3 grid.
 * All icons render as tinted vectors (inherently monochrome).
 */
private val gridVectorIcons: Map<String, ImageVector> = mapOf(
    "com.openbubbles.messaging" to NetworkIntelligenceIcon,
    GOOGLE_MESSAGES to NetworkIntelligenceIcon,
    "com.ubercab.uberlite" to Icons.Filled.DirectionsCar,
    "com.google.android.apps.mapslite" to Icons.Filled.Map,
    "com.android.contacts" to Icons.Filled.Contacts,
    "com.android.dialer" to Icons.Filled.History,
    "com.tcl.camera" to Icons.Filled.CameraAlt,
)

/**
 * Single cell in the 3×3 main app grid.
 *
 * Sizing targets: 240×320 screen @ 110 dpi.
 *   3 cells across ≈ 72dp each (with 4dp gaps + 8dp padding).
 *   Icon: 58dp, Label: 12sp (2 lines max).
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
                    modifier = Modifier.size(72.dp),
                    colorFilter = ColorFilter.tint(iconTint),
                )
            } else {
                val iconBitmap = remember(item.icon) {
                    item.icon.toBitmapSafely(144, 144)
                }
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap.asImageBitmap(),
                        contentDescription = item.label,
                        modifier = Modifier.size(72.dp),
                        colorFilter = GrayscaleMatrix,
                    )
                } else {
                    // Fallback placeholder
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .background(
                                if (selected) DumbTheme.Colors.Black else DumbTheme.Colors.Gray,
                                shape = RoundedCornerShape(4.dp),
                            )
                    )
                }
            }

            Spacer(Modifier.height(3.dp))

            BasicText(
                text = item.label,
                style = TextStyle(
                    fontFamily = DumbTheme.BioRhyme,
                    fontSize = 12.sp,
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
