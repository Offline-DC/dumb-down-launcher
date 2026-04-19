package com.offlineinc.dumbdownlauncher.ui

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.offlineinc.dumbdownlauncher.AllAppsActivity
import com.offlineinc.dumbdownlauncher.MainAppsGridActivity
import com.offlineinc.dumbdownlauncher.launcher.NetworkUtils
import com.offlineinc.dumbdownlauncher.launcher.PlatformPreferences
import com.offlineinc.dumbdownlauncher.pairing.PairingApiClient
import com.offlineinc.dumbdownlauncher.registration.DeviceRegistrar
import com.offlineinc.dumbdownlauncher.registration.SimInfoReader
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import com.offlineinc.dumbdownlauncher.ui.components.DumbButton
import com.offlineinc.dumbdownlauncher.ui.components.DumbChipButton
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

private const val TAG = "PairingScreen"

/**
 * Pairing screen states:
 *  - LOADING:     reading phone number in background (retries happening)
 *  - ERROR:       retries exhausted — show support number
 *  - READY:       phone number known, show pairing code entry
 *  - PAIRING:     pairing API call in progress
 *  - PAIRED:      success, navigating forward
 */
private enum class ScreenState { LOADING, REGISTERING, REG_ERROR, ERROR, READY, PAIRING, PAIRED }

/**
 * Full-screen pairing code entry — shown during onboarding before the
 * "what is ur smart phone?" question.  Uses D-pad number keys for input,
 * same BioRhyme / yellow-on-black style as the rest of the launcher.
 */
@Composable
fun PairingScreen(
    permissionsReady: Boolean = false,
    onPaired: () -> Unit,
    onSkip: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val pairingStore = remember { PairingStore(ctx) }
    var showLinkedState by remember { mutableStateOf(!pairingStore.sharedSecret.isNullOrEmpty()) }

    // If already paired, show the linked screen with next/unpair options
    if (showLinkedState) {
        DeviceLinkedContent(
            phoneNumber = pairingStore.flipPhoneNumber,
            onNext = onPaired,
            onUnpair = {
                pairingStore.clear()
                PlatformPreferences.saveChoice(ctx, "")
                AllAppsActivity.invalidateCache()
                MainAppsGridActivity.invalidateItemCache()
                showLinkedState = false
            },
            onBack = onSkip
        )
        return
    }

    val focusRequester = remember { FocusRequester() }
    val skipFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var skipFocused by remember { mutableStateOf(false) }

    var screenState by remember { mutableStateOf(ScreenState.LOADING) }
    var phoneNumber by remember { mutableStateOf<String?>(null) }
    // Keep IMEI + ICCID around so we can pass them to registerNow and retry
    // without re-reading via root shell.
    var cachedImei by remember { mutableStateOf<String?>(null) }
    var cachedIccid by remember { mutableStateOf<String?>(null) }
    var pairingCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // Reads phone number, IMEI, and ICCID via SimInfoReader.readAll() — a
    // single root shell call to content://telephony/siminfo that returns all
    // three values. This replaces the old approach of 3 separate reads (each
    // of which tried ~8 failing service-call commands before finding data).
    //
    // Once the SIM info is read, registers the device with the backend BEFORE
    // showing the phone number to the user.
    //
    // IMPORTANT: readAll() spawns a `su` subprocess (ProcessBuilder +
    // waitFor), which would block the main thread and trigger an ANR.
    // Always run on Dispatchers.IO.
    suspend fun readPhoneNumberWithRetry() {
        screenState = ScreenState.LOADING
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

                // Register the device before showing the number.
                screenState = ScreenState.REGISTERING
                val registered = withContext(Dispatchers.IO) {
                    DeviceRegistrar.registerNow(ctx, imei, iccid, phone)
                }
                screenState = if (registered) ScreenState.READY else ScreenState.REG_ERROR
                return
            }
            if (attempt == maxAttempts - 1) {
                screenState = ScreenState.ERROR
                return
            }
            val delayMs = (attempt + 1) * 1000L
            Log.d(TAG, "SIM not ready, retrying (${attempt + 1}/$maxAttempts) in ${delayMs}ms...")
            delay(delayMs)
        }
    }

    // Guard against concurrent launches of readPhoneNumberWithRetry — both the
    // ON_RESUME observer and the permissionsReady effect can fire close together.
    var readInFlight by remember { mutableStateOf(false) }

    fun launchReadIfIdle() {
        if (readInFlight || phoneNumber != null || screenState != ScreenState.LOADING) return
        readInFlight = true
        scope.launch {
            try { readPhoneNumberWithRetry() } finally { readInFlight = false }
        }
    }

    // On resume: kick off the retry loop only if still in the initial loading state.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) launchReadIfIdle()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Re-read phone number once su permission grant completes
    LaunchedEffect(permissionsReady) {
        if (permissionsReady) launchReadIfIdle()
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Navigate forward after successful pairing
    LaunchedEffect(screenState) {
        if (screenState == ScreenState.PAIRED) {
            delay(800)
            onPaired()
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

                // If skip button is focused, handle its keys
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

                // Up arrow → move focus to skip button
                if (event.key == Key.DirectionUp) {
                    skipFocusRequester.requestFocus()
                    return@onPreviewKeyEvent true
                }

                // ── State-specific key handling ─────────────────────────

                when (screenState) {
                    // Normal pairing code entry
                    ScreenState.READY -> {
                        val digit = keyToDigit(event.key)
                        if (digit != null && pairingCode.length < 4) {
                            pairingCode += digit
                            error = null
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent when (event.key) {
                            Key.Back, Key.Backspace, Key.Delete -> {
                                if (pairingCode.isNotEmpty()) {
                                    pairingCode = pairingCode.dropLast(1)
                                    true
                                } else false
                            }
                            Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                                if (pairingCode.length == 4 && phoneNumber != null) {
                                    screenState = ScreenState.PAIRING
                                    error = null
                                    scope.launch {
                                        confirmPairing(ctx, pairingCode, phoneNumber!!) { success, err ->
                                            if (success) {
                                                screenState = ScreenState.PAIRED
                                            } else {
                                                screenState = ScreenState.READY
                                                error = err
                                            }
                                        }
                                    }
                                }
                                true // always consume — don't let OK bubble to skip button
                            }
                            else -> false
                        }
                    }

                    ScreenState.LOADING, ScreenState.REGISTERING, ScreenState.ERROR, ScreenState.PAIRING, ScreenState.PAIRED -> {
                        // Consume OK in these states so it doesn't bubble to the skip button
                        if (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter) {
                            return@onPreviewKeyEvent true
                        }
                    }

                    ScreenState.REG_ERROR -> {
                        // Let the user retry registration by pressing OK.
                        // Uses cached IMEI/ICCID to avoid re-reading via root shell.
                        if (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter) {
                            val imei = cachedImei
                            val iccid = cachedIccid
                            val phone = phoneNumber
                            if (imei != null && iccid != null && phone != null) {
                                screenState = ScreenState.REGISTERING
                                scope.launch {
                                    val registered = withContext(Dispatchers.IO) {
                                        DeviceRegistrar.registerNow(ctx, imei, iccid, phone)
                                    }
                                    screenState = if (registered) ScreenState.READY else ScreenState.REG_ERROR
                                }
                            }
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
                text = "link ur smart phone",
                style = DumbTheme.Text.PageTitle,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            BasicText(
                text = "download Dumb Down on the App Store or Google Play on ur smart phone to link",
                style = DumbTheme.Text.Label,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            when (screenState) {
                // ── Loading: reading phone number in background ──────
                ScreenState.LOADING -> {
                    BasicText(
                        text = "reading phone number...",
                        style = DumbTheme.Text.BodySmall.copy(color = DumbTheme.Colors.Gray),
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }

                // ── Registering: phone number read, registering with backend ──
                ScreenState.REGISTERING -> {
                    BasicText(
                        text = "registering device...",
                        style = DumbTheme.Text.BodySmall.copy(color = DumbTheme.Colors.Gray),
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }

                // ── Registration error — let user retry ─────────────
                ScreenState.REG_ERROR -> {
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
                        text = "did u register ur sim?",
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

                // ── Error: couldn't read number — call support ───────
                ScreenState.ERROR -> {
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

                // ── Ready: phone number known, show code entry ───────
                ScreenState.READY, ScreenState.PAIRING, ScreenState.PAIRED -> {
                    Row(modifier = Modifier.padding(bottom = 10.dp)) {
                        BasicText(
                            text = "ur dumb #: ",
                            style = DumbTheme.Text.Subtitle
                        )
                        BasicText(
                            text = formatDisplay(phoneNumber ?: ""),
                            style = DumbTheme.Text.Subtitle.copy(color = DumbTheme.Colors.Yellow)
                        )
                    }

                    BasicText(
                        text = "enter code from app",
                        style = DumbTheme.Text.BodySmall.copy(color = DumbTheme.Colors.White),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // 4 digit boxes
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 10.dp)
                    ) {
                        for (i in 0 until 4) {
                            val char = if (pairingCode.length > i) pairingCode[i].toString() else ""
                            val isCurrent = i == pairingCode.length && screenState != ScreenState.PAIRED
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .background(
                                        if (isCurrent) DumbTheme.Colors.Yellow.copy(alpha = 0.15f)
                                        else DumbTheme.Colors.White.copy(alpha = 0.1f),
                                        RoundedCornerShape(8.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                BasicText(
                                    text = char,
                                    style = TextStyle(
                                        fontFamily = DumbTheme.BioRhyme,
                                        fontSize = 32.sp,
                                        color = DumbTheme.Colors.Yellow
                                    )
                                )
                            }
                        }
                    }

                    // Error display
                    if (error != null) {
                        BasicText(
                            text = error!!,
                            style = DumbTheme.Text.Subtitle.copy(color = DumbTheme.Colors.Yellow),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    // Status
                    val statusText = when (screenState) {
                        ScreenState.PAIRED -> "paired!"
                        ScreenState.PAIRING -> "pairing..."
                        else -> if (pairingCode.length == 4) "press ok to pair" else null
                    }
                    if (statusText != null) {
                        val statusColor = if (screenState == ScreenState.PAIRING) DumbTheme.Colors.Gray
                                          else DumbTheme.Colors.Yellow
                        BasicText(
                            text = statusText,
                            style = DumbTheme.Text.Subtitle.copy(
                                color = statusColor,
                                textAlign = TextAlign.Center
                            )
                        )
                    }
                }
            }
        }

        // Skip button — top right (shared component)
        DumbChipButton(
            text = "skip",
            focusRequester = skipFocusRequester,
            isFocused = skipFocused,
            onFocusChanged = { skipFocused = it },
            modifier = Modifier.align(Alignment.TopEnd)
        )

    }
}

// ─── "Device linked" screen ──────────────────────────────────────────────

/**
 * Shown when the user opens device setup while already paired.
 * "next" button in center to continue through onboarding again.
 * "unpair" in the top-left corner (red highlight), mirror of the skip button style.
 * D-pad Up moves to unpair, Down returns to next. Enter activates.
 */
@Composable
private fun DeviceLinkedContent(
    phoneNumber: String?,
    onNext: () -> Unit,
    onUnpair: () -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val mainFocus = remember { FocusRequester() }
    val unpairFocus = remember { FocusRequester() }
    var unpairFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { mainFocus.requestFocus() }

    // Check if pairing is still active on the backend.
    // Skip when the network isn't available yet (common on early boot) to
    // avoid a crash before the cellular radio is up.
    LaunchedEffect(Unit) {
        if (phoneNumber != null && NetworkUtils.isNetworkAvailable(ctx)) {
            Log.i(TAG, "[Pairing] Checking backend status for phone=$phoneNumber")
            withContext(Dispatchers.IO) {
                try {
                    val apiClient = PairingApiClient(OkHttpClient())
                    val status = apiClient.getPairingStatus(phoneNumber)
                    Log.i(TAG, "[Pairing] Status response: $status")
                    val paired = status.optBoolean("paired", true)
                    if (!paired) {
                        Log.w(TAG, "[Pairing] Backend says NOT paired — auto-unlinking")
                        withContext(Dispatchers.Main) { onUnpair() }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[Pairing] Status check failed — ${e.message}", e)
                }
            }
        } else if (phoneNumber != null) {
            Log.w(TAG, "[Pairing] Network not available — skipping backend status check")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .focusRequester(mainFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (unpairFocused) {
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            onUnpair(); true
                        }
                        Key.DirectionDown, Key.Back -> {
                            mainFocus.requestFocus(); true
                        }
                        else -> false
                    }
                } else {
                    when (event.key) {
                        Key.DirectionUp -> { unpairFocus.requestFocus(); true }
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            onNext(); true
                        }
                        Key.Back -> { onBack(); true }
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
                text = "link ur smart phone",
                style = DumbTheme.Text.PageTitle,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            BasicText(
                text = "ur device is linked",
                style = DumbTheme.Text.BodySmall.copy(color = DumbTheme.Colors.Yellow),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (phoneNumber != null) {
                Row(modifier = Modifier.padding(bottom = 24.dp)) {
                    BasicText(
                        text = "dumb #: ",
                        style = DumbTheme.Text.Subtitle
                    )
                    BasicText(
                        text = formatDisplay(phoneNumber),
                        style = DumbTheme.Text.Subtitle.copy(color = DumbTheme.Colors.Yellow)
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(24.dp))
            }

            // ── "next" button — yellow highlight when focused ────────
            DumbButton(text = "next", focused = !unpairFocused)
        }

        // ── "unpair" — top-left corner, red highlight ────────────
        DumbChipButton(
            text = "unpair",
            focusRequester = unpairFocus,
            focusedBg = DumbTheme.Colors.Red,
            focusedTextColor = DumbTheme.Colors.White,
            isFocused = unpairFocused,
            onFocusChanged = { unpairFocused = it },
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
}

/**
 * Maps D-pad / hardware keyboard keys to digit characters.
 */
private fun keyToDigit(key: Key): String? = when (key) {
    Key.Zero, Key.NumPad0 -> "0"
    Key.One, Key.NumPad1 -> "1"
    Key.Two, Key.NumPad2 -> "2"
    Key.Three, Key.NumPad3 -> "3"
    Key.Four, Key.NumPad4 -> "4"
    Key.Five, Key.NumPad5 -> "5"
    Key.Six, Key.NumPad6 -> "6"
    Key.Seven, Key.NumPad7 -> "7"
    Key.Eight, Key.NumPad8 -> "8"
    Key.Nine, Key.NumPad9 -> "9"
    else -> null
}

/**
 * Reads the phone number from SIM.
 * Delegates to [com.offlineinc.dumbdownlauncher.launcher.PhoneNumberReader].
 */
private fun readPhoneNumber(ctx: Context): Pair<String?, String?> =
    com.offlineinc.dumbdownlauncher.launcher.PhoneNumberReader.read(ctx)

/** Formats an E.164 number like +15551234567 → 555-123-4567 */
private fun formatDisplay(e164: String): String {
    val digits = e164.filter { it.isDigit() }
    // Strip country code "1" for US numbers
    val local = if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
    return if (local.length == 10) {
        "${local.substring(0, 3)}-${local.substring(3, 6)}-${local.substring(6)}"
    } else {
        e164 // fallback: show as-is
    }
}

private fun formatE164(raw: String): String =
    com.offlineinc.dumbdownlauncher.launcher.PhoneNumberReader.formatE164(raw)

/**
 * Calls the pairing confirm API and stores credentials on success.
 */
private suspend fun confirmPairing(
    ctx: Context,
    code: String,
    phoneNumber: String,
    onResult: (success: Boolean, error: String?) -> Unit
) {
    // Bail early with a friendly message if the network isn't up yet
    // (e.g. right after boot before cellular is ready).
    if (!NetworkUtils.isNetworkAvailable(ctx)) {
        onResult(false, "no internet connection")
        return
    }
    try {
        val apiClient = PairingApiClient(OkHttpClient())
        val result = withContext(Dispatchers.IO) {
            apiClient.confirmPairing(code, phoneNumber, com.offlineinc.dumbdownlauncher.BuildConfig.VERSION_NAME)
        }

        val secret = result.getString("sharedSecret")
        val pairingId = result.optInt("pairingId", 0)
        val smartPlatform = result.optString("smartPlatform", "")
        Log.i(TAG, "confirmPairing: success — pairingId=$pairingId platform=$smartPlatform")

        val store = PairingStore(ctx)
        store.savePairing(phoneNumber, secret, pairingId)

        // Platform is set by IntentChoiceScreen (iMessage / Google Messages),
        // not by the linking step — don't override it here.

        onResult(true, null)
    } catch (e: Exception) {
        Log.e(TAG, "confirmPairing: FAILED", e)
        val friendly = when {
            e is java.net.UnknownHostException ||
            e.message?.contains("Unable to resolve host", ignoreCase = true) == true ->
                "no internet connection"
            e is java.net.SocketTimeoutException ->
                "connection timed out"
            e is java.net.ConnectException ->
                "unable to reach server"
            e.message?.contains("invalid", ignoreCase = true) == true ||
            e.message?.contains("incorrect", ignoreCase = true) == true ->
                "invalid pairing code"
            else -> "pairing failed"
        }
        onResult(false, friendly)
    }
}
