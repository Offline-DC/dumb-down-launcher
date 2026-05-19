package com.offlineinc.dumbdownlauncher.ui.mousetutorial

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

// ── Dark-mode Win95 palette ─────────────────────────────────────────────
// Same scrollbar geometry as the classic Win95 chrome, just shifted into
// the dark-theme gray range so it sits naturally on the black page.
// The yellow thumb fill (passed through Win95RaisedBox) is the only
// element that keeps a bright color.
private val ScrollTrack          = Color(0xFF2A2A2A) // track fill (very dark gray)
private val ScrollButtonFace     = Color(0xFF3A3A3A) // arrow button face (slightly lighter)
private val ScrollBevelShadow    = Color(0xFF1A1A1A) // bevel bottom/right (nearly black)
private val ScrollBevelHighlight = Color(0xFF6A6A6A) // bevel top/left (medium gray)
private val ScrollArrow          = Color(0xFFCCCCCC) // arrow glyph (light gray)

/** Windows-95-style vertical scrollbar, dark-mode skinned to sit on the
 *  black tutorial page.  The chrome (track + arrow buttons + bevels)
 *  uses a dark-gray palette; the yellow thumb keeps the dumb.co accent
 *  so the scrollbar still reads as part of the brand.
 *
 *  Layout (top → bottom):
 *    Up arrow button     16 dp tall, dark-gray with bevel + light-gray ▲ glyph
 *    Track               fills remaining height, flat dark-gray
 *      Yellow thumb      raised yellow box, vertical position tracks
 *                        [scrollState]; height is half the track
 *                        (the page is laid out as 2× viewport in
 *                        [DuckGamePage], so the viewport-to-content
 *                        ratio is 0.5).
 *    Down arrow button   16 dp tall, dark-gray with bevel + light-gray ▼ glyph
 *
 *  The arrow buttons are visual chrome only — actual scrolling on the
 *  TCL Flip 2 is driven by the green-call / top-left hardware keys, not
 *  by clicks on the scrollbar.
 *
 *  Renders nothing when there's no scrollable range
 *  ([scrollState] `maxValue` is 0). */
@Composable
internal fun Win95Scrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    if (scrollState.maxValue <= 0) return

    Column(modifier = modifier) {
        Win95ArrowButton(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp),
            symbol = "▲"
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(ScrollTrack)
        ) {
            val trackHeight = maxHeight
            // Page is 2× the viewport (see DuckGamePage), so the
            // thumb occupies half the track.
            val thumbHeight = trackHeight / 2f
            val scrollProgress =
                (scrollState.value.toFloat() / scrollState.maxValue.toFloat())
                    .coerceIn(0f, 1f)
            val thumbY = (trackHeight - thumbHeight) * scrollProgress

            Win95RaisedBox(
                fillColor = DumbTheme.Colors.Yellow,
                modifier = Modifier
                    .offset(y = thumbY)
                    .fillMaxWidth()
                    .height(thumbHeight)
            )
        }

        Win95ArrowButton(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp),
            symbol = "▼"
        )
    }
}

/** Single arrow button at top or bottom of the scrollbar. */
@Composable
private fun Win95ArrowButton(
    modifier: Modifier = Modifier,
    symbol: String
) {
    Win95RaisedBox(
        fillColor = ScrollButtonFace,
        modifier = modifier
    ) {
        BasicText(
            text = symbol,
            style = TextStyle(
                color = ScrollArrow,
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            ),
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/** A box rendered with the classic Win95 raised 3D bevel: white
 *  highlights on top + left edges, dark-gray shadows on bottom + right
 *  edges, both 1 dp thick.  The [fillColor] paints the interior. */
@Composable
private fun Win95RaisedBox(
    fillColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {}
) {
    Box(modifier = modifier.background(fillColor)) {
        // Top edge — white
        Spacer(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(1.dp)
                .background(ScrollBevelHighlight)
        )
        // Left edge — white
        Spacer(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxHeight()
                .width(1.dp)
                .background(ScrollBevelHighlight)
        )
        // Bottom edge — dark gray
        Spacer(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(1.dp)
                .background(ScrollBevelShadow)
        )
        // Right edge — dark gray
        Spacer(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(1.dp)
                .background(ScrollBevelShadow)
        )
        content()
    }
}
