package com.offlineinc.dumbdownlauncher.ui.mousetutorial

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

private const val TAG = "MouseTutorial"

/**
 * Mouse tutorial onboarding — teaches the user how to activate and use
 * the FlipMouse cursor on the TCL Flip 2.  All composables live in this
 * subpackage so they're easy to find and modify independently.
 *
 *   1 → press ✱ to activate                  — [ActivateMouseStep]
 *   2 → duck visible below the subtitle,     — [DuckGameSteps]
 *        click it (no scroll needed)
 *   3 → duck drops to bottom-right;          — [DuckGameSteps]
 *        scroll DOWN with the green call
 *        button to find + click it
 *   4 → duck moves L-shape (left then up)    — [DuckGameSteps]
 *        to top-left below the subtitle;
 *        scroll UP with the top-left
 *        button to find + click it
 *   5 → done                                 — [DoneStep]
 *
 * Steps 2–4 share a single tall scrollable page that's twice the
 * viewport height — only the duck's animated position and the per-step
 * subtitle change between them.  A yellow page-level scrollbar appears
 * on the right edge starting at step 3 (when scrolling first matters).
 *
 * Shared helpers in this package:
 *   - [DuckTarget] — the clickable duck with hover halo
 *   - [QuackBurst] — animated "QUACK!" overlay on click
 *   - [playQuack]  — notification-sound utility
 */
@Composable
fun MouseTutorialScreen(
    onComplete: () -> Unit
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
                onSkip = {
                    Log.d(TAG, "Mouse tutorial skipped — advancing to done screen")
                    step = 5
                }
            )
            2 -> DuckGameSteps(onAllDone = {
                Log.d(TAG, "All duck steps done — advancing to done screen")
                step = 5
            })
            5 -> DoneStep(onDismiss = onComplete)
        }
    }
}

// One-shot per-step transition LaunchedEffect lives inside each step's
// own composable — see e.g. [ActivateMouseStep] for the mouse-activation
// poll and [DuckGameSteps] for the post-quack advance.
