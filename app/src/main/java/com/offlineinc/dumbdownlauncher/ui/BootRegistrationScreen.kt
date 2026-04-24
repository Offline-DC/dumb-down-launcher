package com.offlineinc.dumbdownlauncher.ui

import android.util.Log
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.offlineinc.dumbdownlauncher.R
import com.offlineinc.dumbdownlauncher.pairing.PairingApiClient
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import com.offlineinc.dumbdownlauncher.registration.DeviceRegistrar
import com.offlineinc.dumbdownlauncher.registration.SimInfoReader
import com.offlineinc.dumbdownlauncher.ui.components.DumbChipButton
import com.offlineinc.dumbdownlauncher.ui.components.DumbSpinner
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "BootRegistration"

/**
 * Initial grace window before we start probing the SIM. Gives the modem a
 * few seconds after cold boot to finish initialization — hammering
 * telephony / root-shell immediately after boot sometimes returns a null
 * phone number and forces us into a retry loop. Matches the same intent
 * as [DeviceRegistrar.COLD_BOOT_QUIET_PERIOD_MS] but shorter, because the
 * user is actually staring at this screen and we don't want them to wait
 * 30s before anything visibly happens.
 */
private const val INITIAL_WAIT_MS = 5_000L

/**
 * Screen states — identical flow to [DeviceRegistrationScreen] but with an
 * extra [WAITING] state at the front so the user sees the duck + "quack" +
 * spinner immediately, before any work starts. The passive 30-second
 * [DeviceRegistrar.scheduleOnBoot] path uses the same state machine without
 * a screen.
 *
 *   WAITING         initial grace — "waiting for sim to register..."
 *   LOADING         reading SIM info (phone #, IMEI, ICCID)
 *   REGISTERING     POST /api/v1/register in-flight
 *   CHECKING_BUNDLE GET /contact-sync/bundle-flags in-flight
 *   REG_ERROR       registration failed — OK retries just /register
 *   SIM_ERROR       couldn't read SIM after N retries — OK retries from scratch
 *   DONE            forwarded to [onComplete]
 */
private enum class BootState { WAITING, LOADING, REGISTERING, CHECKING_BUNDLE, REG_ERROR, SIM_ERROR, DONE }

/**
 * First screen every user sees on a fresh phone — and the first screen
 * re-shown when they re-enter Device Setup from the all-apps menu.
 *
 * Single-purpose: get the device registered with the backend and the Gigs
 * bundle flags persisted into [PairingStore] before we show the user any
 * choices. Doing this up-front means:
 *   - Dumbest-tier users skip the "link yr smart phone" / IntentChoice
 *     screens entirely (flags are already in PairingStore by the time
 *     MainActivity consults them for step selection).
 *   - The /register round-trip happens while the user is looking at a duck
 *     and "quack", not as a surprise delay between other screens.
 *
 * State machine mirrors [DeviceRegistrationScreen]: WAITING → LOADING →
 * REGISTERING → CHECKING_BUNDLE → DONE, with REG_ERROR / SIM_ERROR branches
 * that let the user retry without losing progress. [onComplete] is invoked
 * with the registered phone number; the caller is expected to persist it
 * via [PairingStore.saveRegistration] before routing to the next step.
 *
 * [onSkip] is invoked when the user presses the "skip" chip in the top
 * right — only reachable via D-pad while an error state (REG_ERROR /
 * SIM_ERROR) is visible. It's an escape hatch for users who can't reach
 * the network (e.g. no coverage) but still want to use their phone. The
 * caller should route straight to the home screen and skip the remainder
 * of device setup; registration will retry on the next launch because
 * none of the persistent "setup complete" flags get written.
 */
@Composable
fun BootRegistrationScreen(
    permissionsReady: Boolean = false,
    onComplete: (phoneNumber: String) -> Unit,
    onSkip: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    // Skip chip in top-right — only reachable on REG_ERROR / SIM_ERROR.
    // Same pattern as MouseTutorialScreen's skip chip.
    val skipFocusRequester = remember { FocusRequester() }
    var skipFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(BootState.WAITING) }
    var phoneNumber by remember { mutableStateOf<String?>(null) }
    // Cache IMEI + ICCID so REG_ERROR retries don't need another root shell call.
    var cachedImei by remember { mutableStateOf<String?>(null) }
    var cachedIccid by remember { mutableStateOf<String?>(null) }
    // After 30s in an in-flight state we show a "still trying..." sub-line so
    // the user can tell the spinner isn't frozen while the backend's
    // retry-with-exponential-backoff path chews through attempts. Reset to
    // false on every state transition so it doesn't carry over into a new
    // in-flight stage.
    var showStillTrying by remember { mutableStateOf(false) }

    /**
     * Best-effort bundle-flag fetch. Delegates to
     * [DeviceRegistrar.refreshBundleFlags] so we share the one code path
     * that (a) persists flags into [PairingStore] and (b) invalidates
     * AllApps / MainGrid caches when a flag actually changed. Running both
     * a local duplicate here and the background pass would risk the two
     * paths drifting out of sync (it happened once already: the local
     * version forgot cache invalidation).
     *
     * Wrapped in Dispatchers.IO because refreshBundleFlags does a blocking
     * HTTP call; all exceptions are swallowed inside the delegate, so any
     * failure here is picked up on next boot by the passive background
     * pass — safe default (don't hide anything) is strictly worse UX,
     * not broken UX.
     */
    suspend fun fetchBundleFlags(phone: String) {
        withContext(Dispatchers.IO) {
            DeviceRegistrar.refreshBundleFlags(ctx, phone)
        }
    }

    /**
     * One pass: 5s grace window → read SIM with retries → POST /register →
     * GET bundle flags. SIM reads happen on Dispatchers.IO because
     * [SimInfoReader.readAll] spawns a `su` subprocess that would ANR the
     * main thread.
     */
    suspend fun runOnce() {
        // Grace period — duck + "quack" + "waiting for sim..." while the
        // modem finishes init. Always shown (even on re-entry) so the
        // transition into Device Setup feels intentional rather than a
        // flash of spinner.
        state = BootState.WAITING
        delay(INITIAL_WAIT_MS)

        state = BootState.LOADING
        // 15 attempts with linear (attempt+1) * 1000ms delays gives a ~105s
        // total budget in LOADING before SIM_ERROR fires
        // (1+2+3+…+14 = 105s of delays between attempts). Each attempt is
        // cheap — SimInfoReader.readAll() short-circuits in a few ms when
        // the SIM isn't ready — so bumping this count has no wasted work
        // when SIM comes up early (loop exits as soon as all three fields
        // land). Previously 5 (≈15s budget), which routinely tripped on
        // fresh carrier-provisioned SIMs that take 30–90s to attach on
        // first boot. The existing "still trying..." sub-line (appears
        // after 30s in any in-flight state) covers the UX concern of long
        // gaps between polls later in the loop.
        val maxAttempts = 15
        repeat(maxAttempts) { attempt ->
            val delayMs = (attempt + 1) * 1000L
            val simInfo = withContext(Dispatchers.IO) {
                SimInfoReader.readAll(ctx)
            }
            val phone = simInfo.phoneNumber
            val imei = simInfo.imei
            val iccid = simInfo.iccid

            if (phone != null && !imei.isNullOrBlank() && !iccid.isNullOrBlank()) {
                phoneNumber = phone
                cachedImei = imei
                cachedIccid = iccid

                state = BootState.REGISTERING
                // force=true — the user is staring at a spinner, we owe them
                // a real backend round-trip even if SIM state looks cached.
                val registered = withContext(Dispatchers.IO) {
                    DeviceRegistrar.registerNow(ctx, imei, iccid, phone, force = true)
                }
                if (registered) {
                    state = BootState.CHECKING_BUNDLE
                    fetchBundleFlags(phone)
                    state = BootState.DONE
                } else {
                    state = BootState.REG_ERROR
                }
                return
            }
            if (attempt == maxAttempts - 1) {
                state = BootState.SIM_ERROR
                return
            }
            Log.d(TAG, "SIM not ready, retrying (${attempt + 1}/$maxAttempts) in ${delayMs}ms...")
            delay(delayMs)
        }
    }

    // Guard against concurrent launches — both the ON_RESUME observer and
    // the permissionsReady effect can fire close together.
    var inFlight by remember { mutableStateOf(false) }

    fun launchIfIdle() {
        if (inFlight || state !in setOf(BootState.WAITING, BootState.LOADING) || phoneNumber != null) return
        inFlight = true
        scope.launch {
            try { runOnce() } finally { inFlight = false }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) launchIfIdle()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Re-run once su permission grant completes.
    LaunchedEffect(permissionsReady) {
        if (permissionsReady) launchIfIdle()
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // When registration is confirmed, hand the phone number back to the caller.
    LaunchedEffect(state) {
        if (state == BootState.DONE) {
            phoneNumber?.let { onComplete(it) }
        }
    }

    // Drive the "still trying..." sub-line. Keyed on state so re-entering
    // REGISTERING (e.g. via REG_ERROR → retry → REGISTERING) restarts the
    // 30s timer from scratch. LaunchedEffect auto-cancels the delay if state
    // changes before the 30s elapses, so we never flip the flag on a stale
    // stage. Applies to all in-flight states — LOADING usually finishes well
    // under 30s but if a cold-boot SIM read stalls there, the user still
    // deserves visible reassurance.
    LaunchedEffect(state) {
        showStillTrying = false
        if (state == BootState.LOADING ||
            state == BootState.REGISTERING ||
            state == BootState.CHECKING_BUNDLE) {
            delay(30_000L)
            showStillTrying = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                // Skip-chip handling. Mirrors MouseTutorialScreen: when the
                // skip chip is focused, D-pad up is swallowed (nothing above
                // it), Down/Back returns focus to the main content, and
                // Enter/Center invokes onSkip.
                if (skipFocused) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            Log.d(TAG, "user skipped boot registration from error state ($state)")
                            onSkip()
                            true
                        }
                        Key.DirectionDown, Key.Back -> {
                            focusRequester.requestFocus()
                            true
                        }
                        Key.DirectionUp -> true  // no-op, nothing above skip
                        else -> false
                    }
                }

                // From the main content: D-pad up moves focus to the skip
                // chip, but only when an error state is actually rendering
                // the chip. In non-error states the chip isn't composed so
                // focusing it would be a no-op.
                if (event.key == Key.DirectionUp &&
                    (state == BootState.REG_ERROR || state == BootState.SIM_ERROR)
                ) {
                    skipFocusRequester.requestFocus()
                    return@onPreviewKeyEvent true
                }

                when (state) {
                    // OK retries a failed /register call. Re-uses cached
                    // IMEI/ICCID to avoid another root shell read.
                    BootState.REG_ERROR -> {
                        if (event.key == Key.Enter ||
                            event.key == Key.NumPadEnter ||
                            event.key == Key.DirectionCenter) {
                            val imei = cachedImei
                            val iccid = cachedIccid
                            val phone = phoneNumber
                            if (imei != null && iccid != null && phone != null) {
                                state = BootState.REGISTERING
                                scope.launch {
                                    val registered = withContext(Dispatchers.IO) {
                                        DeviceRegistrar.registerNow(ctx, imei, iccid, phone, force = true)
                                    }
                                    if (registered) {
                                        state = BootState.CHECKING_BUNDLE
                                        fetchBundleFlags(phone)
                                        state = BootState.DONE
                                    } else {
                                        state = BootState.REG_ERROR
                                    }
                                }
                            }
                            return@onPreviewKeyEvent true
                        }
                    }
                    // OK retries SIM read from scratch — clears cache and
                    // kicks runOnce() again so the user isn't stuck if the
                    // SIM came up late. We also reset PhoneNumberReader's
                    // su-path backoff: on MediaTek-only-su devices the user's
                    // explicit retry would otherwise hit a silent no-op if
                    // the backoff window from the prior miss was still open.
                    BootState.SIM_ERROR -> {
                        if (event.key == Key.Enter ||
                            event.key == Key.NumPadEnter ||
                            event.key == Key.DirectionCenter) {
                            phoneNumber = null
                            cachedImei = null
                            cachedIccid = null
                            com.offlineinc.dumbdownlauncher.launcher.PhoneNumberReader
                                .invalidateCache()
                            state = BootState.WAITING
                            launchIfIdle()
                            return@onPreviewKeyEvent true
                        }
                    }
                    // Swallow OK in the in-flight states so it doesn't
                    // bubble out and launch the apps grid.
                    BootState.WAITING, BootState.LOADING, BootState.REGISTERING,
                    BootState.CHECKING_BUNDLE, BootState.DONE -> {
                        if (event.key == Key.Enter ||
                            event.key == Key.NumPadEnter ||
                            event.key == Key.DirectionCenter) {
                            return@onPreviewKeyEvent true
                        }
                    }
                }

                false
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Duck icon — uses R.drawable.duck_app_icon, a 1024x1024 PNG of the
            // duck on a black background. (We intentionally do NOT use the
            // vector ic_duck here; product wants the photo-style app-icon duck
            // for the boot screen.)
            Image(
                painter = painterResource(R.drawable.duck_app_icon),
                contentDescription = "duck",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(96.dp)
            )

            Spacer(Modifier.height(12.dp))

            BasicText(
                text = "quack",
                // Device-setup titles use the Helvetica body font — matches
                // LinkingChoiceScreen / DeviceRegistrationScreen.
                style = DumbTheme.Text.PageTitle.copy(fontFamily = DumbTheme.Body),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            when (state) {
                BootState.WAITING -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        DumbSpinner()
                        Spacer(Modifier.height(8.dp))
                        BasicText(
                            text = "waiting for sim to register...",
                            style = DumbTheme.Text.BodySmall.copy(
                                color = DumbTheme.Colors.Gray,
                                textAlign = TextAlign.Center
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                BootState.LOADING, BootState.REGISTERING, BootState.CHECKING_BUNDLE -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        DumbSpinner()
                        Spacer(Modifier.height(8.dp))
                        BasicText(
                            text = when (state) {
                                BootState.LOADING -> "waiting for ur sim..."
                                BootState.REGISTERING -> "activating ur phone..."
                                BootState.CHECKING_BUNDLE -> "checking bundle..."
                                else -> ""
                            },
                            style = DumbTheme.Text.BodySmall.copy(
                                color = DumbTheme.Colors.Gray,
                                textAlign = TextAlign.Center
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        // After 30s in this stage, add a reassurance line so
                        // the user knows the spinner isn't frozen and we're
                        // still inside the retry-with-backoff budget.
                        if (showStillTrying) {
                            Spacer(Modifier.height(4.dp))
                            BasicText(
                                text = "still trying...",
                                style = DumbTheme.Text.BodySmall.copy(
                                    color = DumbTheme.Colors.Gray,
                                    textAlign = TextAlign.Center
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                BootState.REG_ERROR -> {
                    // By the time we're here, SIM IMEI/ICCID/phone have all
                    // been read successfully — the failure is network/DNS,
                    // not SIM activation. Don't show "did u activate ur sim?"
                    // here; that message lives on SIM_ERROR where it's
                    // actually accurate.
                    BasicText(
                        text = "failed to connect to network",
                        style = DumbTheme.Text.BodySmall.copy(
                            color = DumbTheme.Colors.White,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    )
                    BasicText(
                        text = "try restarting ur phone (press and hold red button)",
                        style = DumbTheme.Text.BodySmall.copy(color = DumbTheme.Colors.Yellow),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    BasicText(
                        text = "press ok to retry or call us for help:",
                        style = DumbTheme.Text.BodySmall.copy(color = DumbTheme.Colors.White),
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    BasicText(
                        text = "404-716-3605",
                        style = DumbTheme.Text.Subtitle.copy(color = DumbTheme.Colors.Yellow)
                    )
                }

                BootState.SIM_ERROR -> {
                    BasicText(
                        text = "couldn't read ur phone #. is ur sim activated?",
                        style = DumbTheme.Text.BodySmall.copy(color = DumbTheme.Colors.Yellow),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    BasicText(
                        text = "try restarting ur phone (press and hold red button)",
                        style = DumbTheme.Text.BodySmall.copy(color = DumbTheme.Colors.Yellow),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    BasicText(
                        text = "press ok to retry or call us for help:",
                        style = DumbTheme.Text.BodySmall.copy(color = DumbTheme.Colors.White),
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    BasicText(
                        text = "404-716-3605",
                        style = DumbTheme.Text.Subtitle.copy(color = DumbTheme.Colors.Yellow)
                    )
                }

                BootState.DONE -> {
                    // Intermediate "forwarding" state — the LaunchedEffect
                    // above invokes onComplete() as soon as we land here.
                    BasicText(
                        text = "ready!",
                        style = DumbTheme.Text.BodySmall.copy(color = DumbTheme.Colors.Yellow),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // Skip chip — top right, only while we're in an error state. This
        // is the escape hatch for users who hit a dead-end (no SIM, no
        // network); D-pad up from the main content lands here, Enter fires
        // onSkip which routes them past the rest of device setup to home.
        if (state == BootState.REG_ERROR || state == BootState.SIM_ERROR) {
            DumbChipButton(
                text = "skip",
                focusRequester = skipFocusRequester,
                isFocused = skipFocused,
                onFocusChanged = { skipFocused = it },
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}
