package com.offlineinc.dumbdownlauncher.ui

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.offlineinc.dumbdownlauncher.launcher.PlatformPreferences
import com.offlineinc.dumbdownlauncher.pairing.PairingApiClient
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import com.offlineinc.dumbdownlauncher.ui.components.DumbButton
import com.offlineinc.dumbdownlauncher.ui.components.DumbChipButton
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import okhttp3.OkHttpClient

private const val TAG = "PairingScreen"

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
    var showLinkedState by remember { mutableStateOf(pairingStore.isPaired) }

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

    var phoneNumber by remember { mutableStateOf<String?>(null) }
    var phoneError by remember { mutableStateOf<String?>(null) }
    var pairingCode by remember { mutableStateOf("") }
    var isPairing by remember { mutableStateOf(false) }
    var isPaired by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Retry-aware phone number reader — SIM may not be ready immediately,
    // so retry with increasing delays before showing an error.
    // Uses 8 attempts with back-off (1s, 1s, 2s, 2s, 3s, 3s, 4s) ≈ 16s total,
    // giving the SIM and root fallback plenty of time.
    suspend fun readPhoneNumberWithRetry() {
        phoneError = null
        val maxAttempts = 8
        repeat(maxAttempts) { attempt ->
            val result = readPhoneNumber(ctx)
            if (result.first != null) {
                phoneNumber = result.first
                phoneError = null
                return
            }
            // On last attempt, surface the error
            if (attempt == maxAttempts - 1) {
                phoneError = result.second
                return
            }
            val delayMs = ((attempt / 2) + 1) * 1000L
            Log.d(TAG, "SIM not ready, retrying (${attempt + 1}/$maxAttempts) in ${delayMs}ms...")
            delay(delayMs)
        }
    }

    // Try reading the phone number on every resume — covers the case where
    // the permission dialog was up and the user just granted it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && phoneNumber == null) {
                scope.launch { readPhoneNumberWithRetry() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Re-read phone number once su permission grant completes
    LaunchedEffect(permissionsReady) {
        if (permissionsReady && phoneNumber == null) {
            readPhoneNumberWithRetry()
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Navigate forward after successful pairing
    LaunchedEffect(isPaired) {
        if (isPaired) {
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

                // Number key input (0-9) via D-pad numpad
                val digit = keyToDigit(event.key)
                if (digit != null && pairingCode.length < 4 && !isPairing && !isPaired) {
                    pairingCode += digit
                    error = null
                    return@onPreviewKeyEvent true
                }

                when (event.key) {
                    // Back / backspace / delete → remove last digit
                    Key.Back, Key.Backspace, Key.Delete -> {
                        if (pairingCode.isNotEmpty() && !isPairing) {
                            pairingCode = pairingCode.dropLast(1)
                            true
                        } else false
                    }
                    // Enter / center → confirm pairing
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        if (pairingCode.length == 4 && phoneNumber != null && !isPairing && !isPaired) {
                            isPairing = true
                            error = null
                            scope.launch {
                                confirmPairing(ctx, pairingCode, phoneNumber!!) { success, err ->
                                    if (success) {
                                        isPaired = true
                                        isPairing = false
                                    } else {
                                        isPairing = false
                                        error = err
                                    }
                                }
                            }
                            true
                        } else false
                    }
                    else -> false
                }
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

            if (phoneError != null) {
                BasicText(
                    text = phoneError!!,
                    style = DumbTheme.Text.BodySmall.copy(color = DumbTheme.Colors.Yellow),
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else if (phoneNumber != null) {
                Row(modifier = Modifier.padding(bottom = 10.dp)) {
                        BasicText(
                            text = "ur dumb #: ",
                            style = DumbTheme.Text.Subtitle
                        )
                        BasicText(
                            text = formatDisplay(phoneNumber!!),
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
                        val isCurrent = i == pairingCode.length && !isPaired
                        Box(
                            modifier = Modifier
                                .size(40.dp)
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
                                    fontSize = 26.sp,
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

                // Status — only show when there's something to say
                val statusText = when {
                    isPaired -> "paired!"
                    isPairing -> "pairing..."
                    pairingCode.length == 4 -> "press ok to pair"
                    else -> null
                }
                if (statusText != null) {
                    val statusColor = if (isPairing) DumbTheme.Colors.Gray else DumbTheme.Colors.Yellow
                    BasicText(
                        text = statusText,
                        style = DumbTheme.Text.Subtitle.copy(
                            color = statusColor,
                            textAlign = TextAlign.Center
                        )
                    )
                }
            } else if (phoneNumber == null && phoneError == null) {
                BasicText(
                    text = "reading phone number...",
                    style = DumbTheme.Text.BodySmall.copy(color = DumbTheme.Colors.Gray),
                    modifier = Modifier.padding(top = 16.dp)
                )
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

    // Check if pairing is still active on the backend
    LaunchedEffect(Unit) {
        if (phoneNumber != null) {
            withContext(Dispatchers.IO) {
                try {
                    val apiClient = PairingApiClient(OkHttpClient())
                    val status = apiClient.getPairingStatus(phoneNumber)
                    val paired = status.optBoolean("paired", true)
                    if (!paired) {
                        Log.w(TAG, "[Pairing] Backend says NOT paired — auto-unlinking")
                        withContext(Dispatchers.Main) { onUnpair() }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "[Pairing] Status check failed — ${e.message}")
                }
            }
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
 * Returns (phoneNumber, errorMessage) — one will be null.
 *
 * Tries, in order:
 * 1. SubscriptionManager (Android 13+)
 * 2. TelephonyManager.getLine1Number (deprecated but still works on older builds)
 * 3. Root fallback: content://telephony/siminfo (works on TCL/MediaTek even when
 *    the normal APIs return null because the SIM isn't "ready" yet)
 */
private fun readPhoneNumber(ctx: Context): Pair<String?, String?> {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val subManager = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val subs = subManager?.activeSubscriptionInfoList
            val number = subs?.firstOrNull()?.number
            if (!number.isNullOrBlank()) {
                return formatE164(number) to null
            }
        }
        @Suppress("DEPRECATION")
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val line = tm?.line1Number
        if (!line.isNullOrBlank()) {
            return formatE164(line) to null
        }

        // Root fallback — query the telephony content provider directly.
        // This bypasses the SubscriptionManager/TelephonyManager APIs which
        // can return null on TCL/MediaTek devices or when the SIM is still
        // initializing. Same technique used in dumb-phone-configuration's
        // device_registration.sh.
        val rootNumber = readPhoneNumberViaSu()
        if (rootNumber != null) {
            Log.i(TAG, "Got phone number via root fallback")
            return formatE164(rootNumber) to null
        }

        Log.e(TAG, "SIM did not provide phone number (all methods exhausted)")
        null to "unable to read phone number from SIM"
    } catch (e: SecurityException) {
        Log.w(TAG, "Need phone permission", e)
        null to null // triggers permission request
    } catch (e: Exception) {
        Log.e(TAG, "Unexpected error reading phone number", e)
        null to "unable to read phone number from SIM"
    }
}

/**
 * Root fallback for reading the phone number.
 * Tries two shell approaches (same as device_registration.sh):
 *   1. content://telephony/siminfo — works on TCL/MediaTek
 *   2. service call iphonesubinfo 15 — works on Qualcomm/AOSP
 * Returns the raw number string or null.
 */
private fun readPhoneNumberViaSu(): String? {
    // Method 1: telephony content provider (most reliable on TCL Flip 2)
    try {
        val cp = runSuCommand("content query --uri content://telephony/siminfo --projection number")
        if (cp != null) {
            // Output looks like: Row: 0 number=+15551234567, ...
            val match = Regex("""number=([^,}\s]+)""").find(cp)
            val num = match?.groupValues?.get(1)?.trim()
            if (!num.isNullOrBlank() && num != "NULL" && num.any { it.isDigit() }) {
                Log.d(TAG, "Root fallback (content provider) got number")
                return num
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Root fallback (content provider) failed", e)
    }

    // Method 2: service call iphonesubinfo 15
    try {
        val sc = runSuCommand("service call iphonesubinfo 15")
        if (sc != null) {
            // Output is hex-in-quotes like: Result: Parcel( 0x00000000 '...' ...)
            // Extract the characters between single quotes and strip dots/spaces
            val chars = Regex("'([^']*)'").findAll(sc)
                .map { it.groupValues[1] }
                .joinToString("")
                .replace(".", "")
                .trim()
            if (chars.any { it.isDigit() }) {
                Log.d(TAG, "Root fallback (iphonesubinfo) got number")
                return chars
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Root fallback (iphonesubinfo) failed", e)
    }

    return null
}

/** Runs a command via su and returns stdout, or null on failure. */
private fun runSuCommand(cmd: String): String? {
    return try {
        val proc = ProcessBuilder("su", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
        val exitCode = proc.waitFor()
        if (exitCode == 0 && output.isNotBlank()) output else null
    } catch (e: Exception) {
        Log.w(TAG, "runSuCommand($cmd) failed: ${e.message}")
        null
    }
}

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

private fun formatE164(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return when {
        raw.startsWith("+") -> "+$digits"
        digits.length == 10 -> "+1$digits"
        digits.length == 11 && digits.startsWith("1") -> "+$digits"
        else -> "+$digits"
    }
}

/**
 * Calls the pairing confirm API and stores credentials on success.
 */
private suspend fun confirmPairing(
    ctx: Context,
    code: String,
    phoneNumber: String,
    onResult: (success: Boolean, error: String?) -> Unit
) {
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

        // Auto-set platform if the server knows which smartphone OS was used
        if (smartPlatform == "ios" || smartPlatform == "android") {
            com.offlineinc.dumbdownlauncher.launcher.PlatformPreferences.saveChoice(ctx, smartPlatform)
            Log.i(TAG, "confirmPairing: auto-set platform to $smartPlatform")
        }

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
