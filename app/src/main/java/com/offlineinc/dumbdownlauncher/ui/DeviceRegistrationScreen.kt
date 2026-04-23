package com.offlineinc.dumbdownlauncher.ui

import android.util.Log
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

private const val TAG = "DeviceRegistration"

/**
 * Screen states:
 *  - LOADING      reading SIM info (phone #, IMEI, ICCID) in background
 *  - REGISTERING  POST /api/v1/register in-flight
 *  - REG_ERROR    registration failed — pressing OK retries
 *  - ERROR        couldn't read SIM after N retries — show support number
 *  - DONE         phone # known + backend returned 2xx — forward to caller
 */
private enum class RegState { LOADING, REGISTERING, REG_ERROR, ERROR, DONE }

/**
 * Reads SIM info and registers the device with the backend. Shown after
 * the user picks yes/no on [LinkingChoiceScreen] so that every device —
 * regardless of whether the user is linking a smart phone — ends up
 * registered.
 *
 * This is the exact same read-with-retry, register, and error UI that used
 * to live inline inside [PairingScreen]; it was extracted so both branches
 * of the onboarding flow (linking=yes → pair; linking=no → pick messaging
 * platform) can share it. Once the backend confirms registration we invoke
 * [onRegistered] with the resolved phone number — the caller is expected
 * to persist it (e.g. via [PairingStore.saveRegistration]) before moving
 * to the next onboarding step.
 */
@Composable
fun DeviceRegistrationScreen(
    permissionsReady: Boolean = false,
    onRegistered: (phoneNumber: String) -> Unit,
    onSkip: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val skipFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var skipFocused by remember { mutableStateOf(false) }

    var state by remember { mutableStateOf(RegState.LOADING) }
    var phoneNumber by remember { mutableStateOf<String?>(null) }
    // Cache IMEI + ICCID so REG_ERROR retries don't need another root shell call.
    var cachedImei by remember { mutableStateOf<String?>(null) }
    var cachedIccid by remember { mutableStateOf<String?>(null) }

    /**
     * One pass: read SIM info with retries, then POST /register.
     *
     * IMPORTANT: readAll() spawns a `su` subprocess (ProcessBuilder +
     * waitFor), which would block the main thread and trigger an ANR.
     * Always run on Dispatchers.IO.
     */
    suspend fun runOnce() {
        state = RegState.LOADING
        val maxAttempts = 5
        repeat(maxAttempts) { attempt ->
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

                state = RegState.REGISTERING
                // force=true — Device Setup is an explicit user action, so
                // always hit the backend even if we've already registered
                // for this SIM in a previous run.
                val registered = withContext(Dispatchers.IO) {
                    DeviceRegistrar.registerNow(ctx, imei, iccid, phone, force = true)
                }
                if (registered) {
                    state = RegState.DONE
                } else {
                    state = RegState.REG_ERROR
                }
                return
            }
            if (attempt == maxAttempts - 1) {
                state = RegState.ERROR
                return
            }
            val delayMs = (attempt + 1) * 1000L
            Log.d(TAG, "SIM not ready, retrying (${attempt + 1}/$maxAttempts) in ${delayMs}ms...")
            delay(delayMs)
        }
    }

    // Guard against concurrent launches — both the ON_RESUME observer and
    // the permissionsReady effect can fire close together.
    var inFlight by remember { mutableStateOf(false) }

    fun launchIfIdle() {
        if (inFlight || state != RegState.LOADING || phoneNumber != null) return
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
        if (state == RegState.DONE) {
            phoneNumber?.let { onRegistered(it) }
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

                // If skip is focused, its own keys win.
                if (skipFocused) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            onSkip()
                            true
                        }
                        Key.DirectionDown, Key.Back -> {
                            focusRequester.requestFocus()
                            true
                        }
                        else -> false
                    }
                }

                // Up arrow → focus the skip chip.
                if (event.key == Key.DirectionUp) {
                    skipFocusRequester.requestFocus()
                    return@onPreviewKeyEvent true
                }

                when (state) {
                    // OK retries a failed /register call. Re-uses cached
                    // IMEI/ICCID to avoid another root shell read.
                    RegState.REG_ERROR -> {
                        if (event.key == Key.Enter ||
                            event.key == Key.NumPadEnter ||
                            event.key == Key.DirectionCenter) {
                            val imei = cachedImei
                            val iccid = cachedIccid
                            val phone = phoneNumber
                            if (imei != null && iccid != null && phone != null) {
                                state = RegState.REGISTERING
                                scope.launch {
                                    val registered = withContext(Dispatchers.IO) {
                                        DeviceRegistrar.registerNow(ctx, imei, iccid, phone, force = true)
                                    }
                                    state = if (registered) RegState.DONE else RegState.REG_ERROR
                                }
                            }
                            return@onPreviewKeyEvent true
                        }
                    }

                    // In the other states, swallow OK so it doesn't bubble
                    // to the skip button.
                    RegState.LOADING, RegState.REGISTERING, RegState.ERROR, RegState.DONE -> {
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
            BasicText(
                text = "setting up ur phone",
                // Device setup titles use the Helvetica body font — see
                // LinkingChoiceScreen for rationale.
                style = DumbTheme.Text.PageTitle.copy(fontFamily = DumbTheme.Body),
                modifier = Modifier.padding(bottom = 4.dp)
            )

            BasicText(
                text = "activating ur dumb line",
                style = DumbTheme.Text.Label,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            when (state) {
                // Unified status message for both phases — the split between
                // "reading SIM" and "POSTing to backend" is an implementation
                // detail the user doesn't care about.
                RegState.LOADING, RegState.REGISTERING -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        DumbSpinner()
                        Spacer(Modifier.height(8.dp))
                        BasicText(
                            text = "activating ur phone...",
                            style = DumbTheme.Text.BodySmall.copy(color = DumbTheme.Colors.Gray),
                        )
                    }
                }

                RegState.REG_ERROR -> {
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
                        text = "did u activate ur sim?",
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

                RegState.ERROR -> {
                    BasicText(
                        text = "couldn't read ur phone #. is ur sim in?",
                        style = DumbTheme.Text.BodySmall.copy(color = DumbTheme.Colors.Yellow),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    BasicText(
                        text = "call the dumb line for help:",
                        style = DumbTheme.Text.BodySmall.copy(color = DumbTheme.Colors.White),
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    BasicText(
                        text = "404-716-3605",
                        style = DumbTheme.Text.Subtitle.copy(color = DumbTheme.Colors.Yellow)
                    )
                }

                RegState.DONE -> {
                    // Intermediate "forwarding" state — the LaunchedEffect
                    // above invokes onRegistered() as soon as we land here.
                    BasicText(
                        text = "registered!",
                        style = DumbTheme.Text.BodySmall.copy(color = DumbTheme.Colors.Yellow),
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }

        // Skip button — top right (shared component), so the user can bail
        // out the same way they can from PairingScreen.
        DumbChipButton(
            text = "skip",
            focusRequester = skipFocusRequester,
            isFocused = skipFocused,
            onFocusChanged = { skipFocused = it },
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}
