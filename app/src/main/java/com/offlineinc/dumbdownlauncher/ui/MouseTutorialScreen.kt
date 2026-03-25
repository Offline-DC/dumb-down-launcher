package com.offlineinc.dumbdownlauncher.ui

import android.media.RingtoneManager
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.MouseAccessibilityService
import com.offlineinc.dumbdownlauncher.R
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "MouseTutorial"

/**
 * Mouse tutorial onboarding — teaches the user how to activate and use
 * the FlipMouse cursor on the TCL Flip 2.
 *
 * Steps:
 *  1 → activate mouse (press ✱)
 *  2 → move cursor and click the duck
 *  3 → scroll down and click the duck
 *  4 → scroll up and click the duck
 *  5 → done
 */
@Composable
fun MouseTutorialScreen(
    onComplete: () -> Unit,
    onSkip: () -> Unit
) {
    var step by remember { mutableIntStateOf(1) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
    ) {
        when (step) {
            1 -> ActivateMouseStep(
                onActivated = {
                    Log.d(TAG, "Mouse activated — advancing to click step")
                    step = 2
                },
                onSkip = onSkip
            )
            2 -> DuckGameSteps(onAllDone = {
                Log.d(TAG, "All duck steps done — advancing to done screen")
                step = 5
            })
            5 -> DoneStep(onDismiss = onComplete)
        }
    }
}

// ─── Step 1: Activate Mouse ─────────────────────────────────────────────

@Composable
private fun ActivateMouseStep(
    onActivated: () -> Unit,
    onSkip: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val skipFocusRequester = remember { FocusRequester() }
    var skipFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Poll the FlipMouse daemon status every 500 ms
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val enabled = withContext(Dispatchers.IO) {
                MouseAccessibilityService.queryMouseEnabled()
            }
            if (enabled) {
                onActivated()
                break
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (skipFocused) {
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            onSkip(); true
                        }
                        Key.DirectionDown, Key.Back -> {
                            focusRequester.requestFocus(); true
                        }
                        else -> false
                    }
                } else {
                    when (event.key) {
                        Key.DirectionUp -> {
                            skipFocusRequester.requestFocus(); true
                        }
                        else -> false
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BasicText(
                text = "Getting around ur phone.",
                style = TextStyle(
                    fontFamily = DumbTheme.BioRhyme,
                    fontSize = 18.sp,
                    color = DumbTheme.Colors.White
                ),
                modifier = Modifier.padding(bottom = 16.dp)
            )
            BasicText(
                text = "The dumb mouse helps u navigate advanced apps.",
                style = TextStyle(
                    fontFamily = DumbTheme.BioRhyme,
                    fontSize = 12.sp,
                    color = DumbTheme.Colors.Gray
                ),
                modifier = Modifier.padding(bottom = 16.dp)
            )
            BasicText(
                text = "Press \u2605 to activate it.",
                style = TextStyle(
                    fontFamily = DumbTheme.BioRhyme,
                    fontSize = 14.sp,
                    color = DumbTheme.Colors.White
                )
            )
        }

        // Skip — top right (same pattern as PairingScreen)
        SkipButton(
            modifier = Modifier.align(Alignment.TopEnd),
            focusRequester = skipFocusRequester,
            isFocused = skipFocused,
            onFocusChanged = { skipFocused = it }
        )
    }
}

// ─── Steps 2–4: Duck game (single persistent layout) ────────────────────
//
// The outer Column/GameBox stays mounted across all three sub-steps so the
// duck never re-renders or shifts position between steps.

@Composable
private fun DuckGameSteps(onAllDone: () -> Unit) {
    val context = LocalContext.current
    var gameStep by remember { mutableIntStateOf(2) }
    var quacked by remember { mutableStateOf(false) }

    // Advance after quack with delay
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

    val instruction = when (gameStep) {
        2 -> "Use the direction pad to move left and right and quack the duck."
        3 -> "Press the green call button to scroll down and quack the duck."
        4 -> "Press the top left button to scroll up and quack the duck."
        else -> ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TutorialHeader(instruction = instruction)

        Spacer(modifier = Modifier.height(12.dp))

        GameBox(modifier = Modifier.weight(1f)) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (gameStep) {
                    2 -> {
                        DuckTarget(
                            enabled = !quacked,
                            onClick = { quacked = true; playQuack(context) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 8.dp, end = 8.dp)
                        )
                        QuackBurst(visible = quacked)
                    }
                    3 -> ScrollDownContent(
                        quacked = quacked,
                        onQuack = { quacked = true; playQuack(context) }
                    )
                    4 -> ScrollUpContent(
                        quacked = quacked,
                        onQuack = { quacked = true; playQuack(context) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** Step 3 game content — duck animates down, then user scrolls to find it. */
@Composable
private fun BoxScope.ScrollDownContent(quacked: Boolean, onQuack: () -> Unit) {
    var animating by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()
    val duckOffsetY = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        delay(150)
        duckOffsetY.animateTo(
            targetValue = 500f,
            animationSpec = tween(durationMillis = 700)
        )
        animating = false
    }

    if (animating) {
        Image(
            painter = painterResource(R.drawable.ic_duck),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp)
                .offset(y = duckOffsetY.value.dp)
                .size(56.dp)
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.End
        ) {
            Spacer(modifier = Modifier.height(280.dp))
            DuckTarget(
                enabled = !quacked,
                onClick = onQuack,
                modifier = Modifier.padding(end = 8.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
        QuackBurst(visible = quacked)
    }
}

/** Step 4 game content — duck animates left then up, then user scrolls up to find it. */
@Composable
private fun BoxScope.ScrollUpContent(quacked: Boolean, onQuack: () -> Unit) {
    var animPhase by remember { mutableStateOf("left") }
    val scrollState = rememberScrollState()
    var scrollReady by remember { mutableStateOf(false) }
    val duckOffsetX = remember { Animatable(0f) }
    val duckOffsetY = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // No initial delay — start moving immediately so the position
        // change from step 3's scroll location to BottomEnd is imperceptible.
        // Phase 1: slide left to the left edge of the box
        duckOffsetX.animateTo(
            targetValue = -216f,
            animationSpec = tween(durationMillis = 500)
        )
        animPhase = "up"
        // Phase 2: slide up from bottom to top of the box
        duckOffsetY.animateTo(
            targetValue = -260f,
            animationSpec = tween(durationMillis = 500)
        )
        animPhase = "done"
        delay(400)
        animPhase = "scroll"
    }

    // Scroll to bottom BEFORE showing scrollable content to prevent flash
    LaunchedEffect(animPhase) {
        if (animPhase == "scroll") {
            delay(50)
            scrollState.scrollTo(scrollState.maxValue)
            scrollReady = true
        }
    }

    if (animPhase != "scroll" || !scrollReady) {
        // Show animated duck (frozen at final position while scroll is being set)
        Image(
            painter = painterResource(R.drawable.ic_duck),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 8.dp, end = 8.dp)
                .offset(x = duckOffsetX.value.dp, y = duckOffsetY.value.dp)
                .size(56.dp)
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.Start
        ) {
            DuckTarget(
                enabled = !quacked,
                onClick = onQuack,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp)
            )
            Spacer(modifier = Modifier.height(280.dp))
        }
        QuackBurst(visible = quacked)
    }
}

// ─── Step 5: Done ───────────────────────────────────────────────────────

@Composable
private fun DoneStep(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    // Disable mouse — tutorial is over.  Then grab focus so hardware
    // keys (now un-grabbed by the daemon) reach onPreviewKeyEvent.
    LaunchedEffect(Unit) {
        MouseAccessibilityService.forceDisable(context)
        // Small delay so the daemon releases the keyboard grab before
        // we try to receive key events.
        delay(300)
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    onDismiss()
                    true
                } else false
            }
            // Also support mouse click in case the cursor is still visible
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = "nice quacking!",
                style = TextStyle(
                    fontFamily = DumbTheme.BioRhyme,
                    fontSize = 20.sp,
                    color = DumbTheme.Colors.Yellow,
                    textAlign = TextAlign.Center
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
            BasicText(
                text = "press any key to continue",
                style = TextStyle(
                    fontFamily = DumbTheme.BioRhyme,
                    fontSize = 12.sp,
                    color = DumbTheme.Colors.Gray,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}

// ─── Shared components ──────────────────────────────────────────────────

/** Standard title + instruction text used by steps 2-4.
 *  Uses a fixed min height so the GameBox position stays stable
 *  when instruction text changes between steps. */
@Composable
private fun TutorialHeader(instruction: String) {
    Column(modifier = Modifier.heightIn(min = 70.dp)) {
        BasicText(
            text = "Getting around ur phone.",
            style = TextStyle(
                fontFamily = DumbTheme.BioRhyme,
                fontSize = 18.sp,
                color = DumbTheme.Colors.White
            ),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        BasicText(
            text = instruction,
            style = TextStyle(
                fontFamily = DumbTheme.BioRhyme,
                fontSize = 12.sp,
                color = DumbTheme.Colors.Gray
            )
        )
    }
}

/** White rounded box with a visible border — the "game area". */
@Composable
private fun GameBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .border(2.dp, DumbTheme.Colors.White, RoundedCornerShape(4.dp))
            .background(Color.White, RoundedCornerShape(4.dp))
    ) {
        content()
    }
}

/** Clickable pixel-art duck. */
@Composable
private fun DuckTarget(
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(R.drawable.ic_duck),
        contentDescription = "duck",
        modifier = modifier
            .size(56.dp)
            .then(
                if (enabled) Modifier.clickable(onClick = onClick)
                else Modifier
            )
    )
}

/** Animated "QUACK!" text that scales + fades in over the game area. */
@Composable
private fun QuackBurst(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(initialScale = 0.3f) + fadeIn()
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = "QUACK!",
                style = TextStyle(
                    fontFamily = DumbTheme.BioRhyme,
                    fontSize = 28.sp,
                    color = DumbTheme.Colors.Yellow,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}

/** Skip button — top-right corner, same pattern as PairingScreen. */
@Composable
private fun SkipButton(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit
) {
    Box(
        modifier = modifier
            .padding(top = 6.dp, end = 8.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .focusable()
            .then(
                if (isFocused) Modifier
                    .background(DumbTheme.Colors.Yellow, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                else Modifier
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
    ) {
        BasicText(
            text = "skip",
            style = TextStyle(
                fontFamily = DumbTheme.BioRhyme,
                fontSize = 11.sp,
                color = if (isFocused) DumbTheme.Colors.Black else DumbTheme.Colors.Gray
            )
        )
    }
}

// ─── Utilities ───────────────────────────────────────────────────────────

/** Play the device's default notification sound as a "quack". */
private fun playQuack(context: android.content.Context) {
    try {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val ringtone = RingtoneManager.getRingtone(context, uri)
        ringtone?.play()
    } catch (t: Throwable) {
        Log.w(TAG, "Could not play quack sound: ${t.message}")
    }
}
