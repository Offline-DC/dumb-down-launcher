package com.offlineinc.dumbdownlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.ui.components.DumbChipButton
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/**
 * First onboarding screen — "r u linking smartphone?"
 *
 * Users who link get the full pairing + contact sync flow.
 * Users who don't link still pick their messaging app and go
 * straight to the mouse tutorial (smart txt works, just no sync).
 *
 * [onChoose] is called with true (yes, linking) or false (no, not linking).
 *
 * [onSkipAll] is invoked when the user moves focus to the top-right
 * "skip setup" chip and presses OK. It signals "I don't want to do
 * any of device setup right now" — the caller is expected to persist a
 * flag so onCreate doesn't drag the user back through boot_registration
 * on the next launch. The user can still re-enter setup later from
 * AllAppsActivity → "device setup".
 */
@Composable
fun LinkingChoiceScreen(
    onChoose: (Boolean) -> Unit,
    onSkipAll: () -> Unit = {}
) {
    val options = listOf("yes" to true, "no" to false)

    var selectedIndex by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val skipFocusRequester = remember { FocusRequester() }
    var skipFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Skip-chip handling — mirrors the pattern from
                // PairingScreen / BootRegistrationScreen / MouseTutorialScreen:
                // when the chip is focused, OK fires onSkipAll, Down/Back
                // returns to the main list, Up is a no-op (nothing above it).
                if (skipFocused) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            onSkipAll()
                            true
                        }
                        Key.DirectionDown, Key.Back -> {
                            // Explicitly clear skipFocused before moving focus.
                            // The chip's onFocusChanged callback is unreliable
                            // (sometimes fires late or not at all on this
                            // launcher), so we drive the visual state
                            // ourselves rather than waiting for it.
                            skipFocused = false
                            focusRequester.requestFocus()
                            true
                        }
                        Key.DirectionUp -> true  // nothing above the chip
                        else -> false
                    }
                }
                when (event.key) {
                    Key.DirectionUp -> {
                        // From the first option (yes), Up moves focus to
                        // the skip-setup chip in the top right. From
                        // lower options it just walks back up the list.
                        if (selectedIndex == 0) {
                            // Set skipFocused explicitly before requesting
                            // focus on the chip — see the comment in the
                            // skipFocused handler above for why we don't
                            // rely on the chip's onFocusChanged callback.
                            skipFocused = true
                            skipFocusRequester.requestFocus()
                        } else {
                            selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        selectedIndex = (selectedIndex + 1).coerceAtMost(options.lastIndex)
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        onChoose(options[selectedIndex].second)
                        true
                    }
                    Key.Back -> true // swallow — first screen, nowhere to go back to
                    else -> false
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            BasicText(
                text = "r u linking smartphone?",
                // Device setup titles use the Helvetica body font instead of
                // the Cheltenham header font — keeps the onboarding flow
                // visually distinct from the rest of the launcher.
                style = DumbTheme.Text.PageTitle.copy(fontFamily = DumbTheme.Body),
                modifier = Modifier.padding(bottom = 24.dp)
            )

            options.forEachIndexed { index, (label, _) ->
                // When the skip-setup chip is focused, no option in the
                // list should look selected — otherwise "yes" stays
                // highlighted while focus is up on the chip.
                val isSelected = index == selectedIndex && !skipFocused
                val textColor = if (isSelected) DumbTheme.Colors.Yellow else DumbTheme.Colors.Gray

                BasicText(
                    text = if (isSelected) "> $label" else "  $label",
                    // Device setup options use the Helvetica body font to
                    // match the device setup title — see the title above.
                    style = DumbTheme.Text.AppLabel.copy(
                        fontFamily = DumbTheme.Body,
                        color = textColor,
                        fontSize = 18.sp
                    ),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }

        // "skip setup" chip — top right. Bails out of all of Device Setup;
        // the next launch will go straight to the home screen and the user
        // can re-enter setup from AllAppsActivity.
        DumbChipButton(
            text = "skip setup",
            focusRequester = skipFocusRequester,
            isFocused = skipFocused,
            onFocusChanged = { skipFocused = it },
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}
