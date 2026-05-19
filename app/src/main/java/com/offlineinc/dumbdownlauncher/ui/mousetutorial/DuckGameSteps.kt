package com.offlineinc.dumbdownlauncher.ui.mousetutorial

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Orchestrator for the duck game (tutorial steps 2-4).  Steps 2, 3
 *  and 4 share a single tall scrollable page — only the duck's animated
 *  position and the subtitle text change between them.
 *
 *   Step 2 → duck sits just below the title + subtitle (visible at
 *            scroll = 0).  User clicks the visible duck.
 *   Step 3 → duck drops straight down to the bottom-right corner of the
 *            page.  The yellow page scrollbar fades in here.  User
 *            scrolls down with the green call button to find the duck.
 *   Step 4 → duck moves in an L-shape from the bottom-right corner —
 *            first left along the bottom edge, then up the left edge —
 *            to its final spot just below the title + subtitle on the
 *            left side.  User scrolls up with the top-left button.
 *
 *  Layout details live in [DuckGamePage]. */
@Composable
internal fun DuckGameSteps(onAllDone: () -> Unit) {
    val context = LocalContext.current
    var gameStep by remember { mutableIntStateOf(2) }
    var quacked by remember { mutableStateOf(false) }

    // Advance after quack with delay so QuackBurst has time to play.
    LaunchedEffect(quacked) {
        if (quacked) {
            delay(1000)
            if (gameStep >= 4) {
                onAllDone()
            } else {
                gameStep++
                quacked = false
            }
        }
    }

    val onQuack: () -> Unit = {
        quacked = true
        playQuack(context)
    }

    DuckGamePage(gameStep = gameStep, quacked = quacked, onQuack = onQuack)
}

/** Subtitle copy shown below the title for each duck-game step. */
private fun instructionForStep(gameStep: Int): String = when (gameStep) {
    2 -> "use the direction pad to move the cursor and click the duck."
    3 -> "press the green call button to scroll down, then click the duck."
    4 -> "press the top left button to scroll up, then click the duck."
    else -> ""
}

/** Annotated "scroll down with green call button" text used as the
 *  in-page scroll hint at the bottom of the first viewport in step 3.
 *  Only the word "green" is highlighted in the call button's brand green
 *  (the rest stays white on the black page). */
private fun scrollDownHintText() = buildAnnotatedString {
    withStyle(SpanStyle(color = DumbTheme.Colors.White)) { append("scroll down with ") }
    withStyle(SpanStyle(color = Color(0xFF00AA00), fontWeight = FontWeight.Bold)) {
        append("green")
    }
    withStyle(SpanStyle(color = DumbTheme.Colors.White)) { append(" call button") }
}

/** The shared long-page layout used by steps 2-4.
 *
 *  Black page, twice the viewport height.  Title + per-step subtitle sit
 *  at the very top of the scroll content (offset 0) — the user can
 *  always scroll up to reach them and they stay the topmost items on
 *  the page.  The duck lives inside the scroll content (not absolutely
 *  positioned) and is driven by two animated [Animatable]s keyed off
 *  [gameStep] (see the orchestrator KDoc for the per-step animation
 *  shapes). */
@Composable
private fun DuckGamePage(
    gameStep: Int,
    quacked: Boolean,
    onQuack: () -> Unit
) {
    val scrollState = rememberScrollState()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
    ) {
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        // Page is twice the viewport so there's room to scroll.
        val pageHeight = viewportHeight * 2
        val duckSize = 100.dp
        val edge = 16.dp

        // Per-step duck-position targets, expressed as raw Dp-as-Float
        // so they can drive Animatable<Float>s.  All Y values are
        // measured from the top of the page; X from the left.
        //
        // Step 2 → right side, just below the title + subtitle.
        // Step 3 → same right column, but at the bottom of the page.
        // Step 4 → left side, at the same Y as step 2.
        val step2X = (viewportWidth - duckSize - edge).value
        val step2Y = 140f
        val step3X = step2X
        val step3Y = (pageHeight - duckSize - edge - 24.dp).value
        val step4X = edge.value
        val step4Y = step2Y

        val duckOffsetX = remember { Animatable(step2X) }
        val duckOffsetY = remember { Animatable(step2Y) }

        LaunchedEffect(gameStep) {
            // Brief pause so the QuackBurst has time to fully fade.
            delay(150)
            when (gameStep) {
                2 -> {
                    // Initial mount — snap into place (no animation).
                    duckOffsetX.snapTo(step2X)
                    duckOffsetY.snapTo(step2Y)
                }
                3 -> {
                    // Step 2 → 3: simple vertical drop (X stays on the
                    // right edge, only Y changes).  Animated in parallel
                    // for future-proofing in case X targets diverge.
                    coroutineScope {
                        launch { duckOffsetX.animateTo(step3X, tween(700)) }
                        launch { duckOffsetY.animateTo(step3Y, tween(700)) }
                    }
                }
                4 -> {
                    // Step 3 → 4: L-shape — first slide LEFT along the
                    // bottom edge, then slide UP the left edge.  Done by
                    // awaiting each animateTo sequentially rather than
                    // launching them in parallel.
                    duckOffsetX.animateTo(step4X, tween(500))
                    duckOffsetY.animateTo(step4Y, tween(500))
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pageHeight)
            ) {
                // Title + per-step subtitle — top of scroll content.
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 20.dp, start = 24.dp, end = 24.dp)
                ) {
                    BasicText(
                        text = "Getting around ur phone.",
                        // Device-setup titles use the Helvetica body font
                        // — see LinkingChoiceScreen for rationale.
                        style = DumbTheme.Text.Title.copy(fontFamily = DumbTheme.Body),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    BasicText(
                        text = instructionForStep(gameStep),
                        style = DumbTheme.Text.Hint
                    )
                }

                // Per-step scroll-direction hint, baked into the page
                // so the user scrolls *past* it (not pinned to the
                // viewport).
                //   Step 3 → "scroll down with green call button" near
                //            the bottom of the first viewport, so it's
                //            visible right when the step starts and
                //            scrolls up off the top as the user moves
                //            down toward the duck.
                //   Step 4 → "scroll up with top left button" near the
                //            top of the second viewport, so it's
                //            visible right when the step starts (the
                //            page is pinned to maxScroll for them) and
                //            scrolls down off the bottom as the user
                //            scrolls up toward the duck.
                if (gameStep == 2) {
                    BasicText(
                        text = "use direction buttons to click on duck",
                        // A bit smaller than the step 3 / 4 hints so
                        // this longer string fits on two lines instead
                        // of wrapping to three.
                        style = DumbTheme.Text.Body.copy(
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            // Offset is bumped up from `viewportHeight
                            // - 60.dp` so the wrapped text has a
                            // visible gap below it (effectively a
                            // bottom-padding) instead of butting
                            // against the viewport edge.
                            .offset(y = viewportHeight - 100.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                    )
                }
                if (gameStep == 3) {
                    BasicText(
                        text = scrollDownHintText(),
                        style = DumbTheme.Text.Body.copy(
                            fontSize = 22.sp,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            // Same bottom-padding bump as step 2.
                            .offset(y = viewportHeight - 100.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                    )
                }
                if (gameStep == 4) {
                    BasicText(
                        text = "scroll up with top left button",
                        style = DumbTheme.Text.Body.copy(
                            fontSize = 22.sp,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(y = viewportHeight + 16.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                    )
                }

                // Duck — positioned by animated Dp offsets relative to
                // the page's top-left corner.  Inside the scroll content
                // so it scrolls with the rest of the page.
                DuckTarget(
                    enabled = !quacked,
                    onClick = onQuack,
                    modifier = Modifier.offset(
                        x = duckOffsetX.value.dp,
                        y = duckOffsetY.value.dp
                    )
                )
            }
        }

        // Win95-style scrollbar with a yellow thumb — appears starting
        // in step 3, when the duck has dropped down and there's
        // actually something to scroll to.  Step 2 doesn't need it
        // (the duck is right there).
        if (gameStep >= 3) {
            Win95Scrollbar(
                scrollState = scrollState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(16.dp)
            )
        }

        // Step 3's duck lands at the bottom of the page, so a
        // bottom-anchored QUACK would collide with it — center it on
        // the screen instead.  Steps 2 and 4 keep the duck in the
        // upper half, so the default bottom position is fine there.
        QuackBurst(visible = quacked, centered = gameStep == 3)
    }
}

// ── Previews ────────────────────────────────────────────────────────────
// LaunchedEffect runs in @Preview, so by the time each preview settles
// the duck has snapped/animated to the target position for that step.

@Preview(
    name = "Duck game — step 2 (initial click)",
    widthDp = 240,
    heightDp = 320,
    showBackground = true,
    backgroundColor = 0xFF000000
)
@Composable
private fun DuckGameStep2Preview() {
    DuckGamePage(gameStep = 2, quacked = false, onQuack = {})
}

@Preview(
    name = "Duck game — step 3 (scroll down)",
    widthDp = 240,
    heightDp = 320,
    showBackground = true,
    backgroundColor = 0xFF000000
)
@Composable
private fun DuckGameStep3Preview() {
    DuckGamePage(gameStep = 3, quacked = false, onQuack = {})
}

@Preview(
    name = "Duck game — step 4 (scroll up)",
    widthDp = 240,
    heightDp = 320,
    showBackground = true,
    backgroundColor = 0xFF000000
)
@Composable
private fun DuckGameStep4Preview() {
    DuckGamePage(gameStep = 4, quacked = false, onQuack = {})
}
