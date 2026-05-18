package com.offlineinc.dumbdownlauncher.ui.mousetutorial

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme
import kotlinx.coroutines.launch

/** Animated "QUACK!" overlay that scales + fades in when the user
 *  clicks the duck.
 *
 *  Avoids [androidx.compose.animation.AnimatedVisibility] on purpose
 *  even though it's the obvious choice: with AnimatedVisibility the
 *  composable lingers for one frame on the dismissal (even with
 *  `exit = ExitTransition.None`), which between steps can render a
 *  brief flash of QUACK at the *next* step's `centered` position before
 *  it disappears.  Returning early via `if (visible)` instead removes
 *  the composable from composition the instant `visible` flips false,
 *  with no transition frame.
 *
 *  The entrance scale + fade is reproduced manually with two
 *  [Animatable]s applied via `Modifier.graphicsLayer`, both freshly
 *  initialized every time `visible` flips back to true (the remembers
 *  reset because the conditional block re-enters composition).
 *
 *  [centered] is captured into a `remember`'d local on first
 *  composition so it can't change while QUACK is on screen — that
 *  guards against the same flash if the parent step transitions
 *  before the dismiss recomposition lands.
 *
 *  Position is controlled by [centered]:
 *    - `false` (default): sits 100 dp above the bottom edge.  Used for
 *      steps where the duck lives in the upper half of the page (steps
 *      2 and 4) so QUACK and the duck don't overlap.
 *    - `true`: dead-centered in the viewport.  Used when the duck
 *      itself is in the lower half (step 3) so the bottom position
 *      would collide with it. */
@Composable
internal fun QuackBurst(
    visible: Boolean,
    centered: Boolean = false
) {
    if (visible) {
        // Lock the position the moment QUACK enters composition so a
        // step transition mid-display can't shift it.
        val lockedCentered = remember { centered }

        // Replicates scaleIn(initialScale = 0.3f) + fadeIn() manually.
        val scale = remember { Animatable(0.3f) }
        val alpha = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            launch { scale.animateTo(1f) }
            launch { alpha.animateTo(1f) }
        }

        Box(
            modifier = if (lockedCentered) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxSize()
                    .padding(bottom = 100.dp)
            },
            contentAlignment = if (lockedCentered) Alignment.Center else Alignment.BottomCenter
        ) {
            BasicText(
                text = "QUACK!",
                style = TextStyle(
                    fontFamily = DumbTheme.BioRhyme,
                    fontSize = 28.sp,
                    color = DumbTheme.Colors.Yellow,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier.graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                }
            )
        }
    }
}
