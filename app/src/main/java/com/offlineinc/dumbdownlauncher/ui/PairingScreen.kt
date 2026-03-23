package com.offlineinc.dumbdownlauncher.ui

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.offlineinc.dumbdownlauncher.pairing.PairingApiClient
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

private const val TAG = "PairingScreen"

/**
 * Full-screen pairing code entry — shown during onboarding before the
 * "what is ur smart phone?" question.  Uses D-pad number keys for input,
 * same BioRhyme / yellow-on-black style as the rest of the launcher.
 */
@Composable
fun PairingScreen(
    onPaired: () -> Unit
) {
    val ctx = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    var phoneNumber by remember { mutableStateOf<String?>(null) }
    var phoneError by remember { mutableStateOf<String?>(null) }
    var pairingCode by remember { mutableStateOf("") }
    var isPairing by remember { mutableStateOf(false) }
    var isPaired by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Try reading the phone number on every resume — covers the case where
    // the permission dialog was up and the user just granted it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && phoneNumber == null) {
                val result = readPhoneNumber(ctx)
                phoneNumber = result.first
                phoneError = result.second
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                style = TextStyle(
                    fontFamily = DumbTheme.BioRhyme,
                    fontSize = 20.sp,
                    color = DumbTheme.Colors.White
                ),
                modifier = Modifier.padding(bottom = 4.dp)
            )

            BasicText(
                text = "download Dumb Down on the App Store or Google Play on ur smart phone to link",
                style = TextStyle(
                    fontFamily = DumbTheme.BioRhyme,
                    fontSize = 11.sp,
                    color = DumbTheme.Colors.Gray
                ),
                modifier = Modifier.padding(bottom = 10.dp)
            )

            if (phoneError != null) {
                BasicText(
                    text = phoneError!!,
                    style = TextStyle(
                        fontFamily = DumbTheme.BioRhyme,
                        fontSize = 14.sp,
                        color = DumbTheme.Colors.Yellow
                    ),
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else if (phoneNumber != null) {
                Row(modifier = Modifier.padding(bottom = 10.dp)) {
                        BasicText(
                            text = "ur dumb #: ",
                            style = TextStyle(
                                fontFamily = DumbTheme.BioRhyme,
                                fontSize = 13.sp,
                                color = DumbTheme.Colors.Gray
                            )
                        )
                        BasicText(
                            text = formatDisplay(phoneNumber!!),
                            style = TextStyle(
                                fontFamily = DumbTheme.BioRhyme,
                                fontSize = 13.sp,
                                color = DumbTheme.Colors.Yellow
                            )
                        )
                    }

                BasicText(
                    text = "enter code from Dumb Down app:",
                    style = TextStyle(
                        fontFamily = DumbTheme.BioRhyme,
                        fontSize = 13.sp,
                        color = DumbTheme.Colors.White
                    ),
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
                        style = TextStyle(
                            fontFamily = DumbTheme.BioRhyme,
                            fontSize = 13.sp,
                            color = DumbTheme.Colors.Yellow
                        ),
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
                        style = TextStyle(
                            fontFamily = DumbTheme.BioRhyme,
                            fontSize = 13.sp,
                            color = statusColor,
                            textAlign = TextAlign.Center
                        )
                    )
                }
            } else if (phoneNumber == null && phoneError == null) {
                BasicText(
                    text = "reading phone number...",
                    style = TextStyle(
                        fontFamily = DumbTheme.BioRhyme,
                        fontSize = 14.sp,
                        color = DumbTheme.Colors.Gray
                    ),
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
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
        Log.e(TAG, "SIM did not provide phone number")
        null to "unable to read phone number from SIM"
    } catch (e: SecurityException) {
        Log.w(TAG, "Need phone permission", e)
        null to null // triggers permission request
    } catch (e: Exception) {
        Log.e(TAG, "Unexpected error reading phone number", e)
        null to "unable to read phone number from SIM"
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
            apiClient.confirmPairing(code, phoneNumber)
        }

        val secret = result.getString("sharedSecret")
        val pairingId = result.optInt("pairingId", 0)
        val smartPlatform = result.optString("smartPlatform", "")
        Log.i(TAG, "confirmPairing: success — pairingId=$pairingId platform=$smartPlatform")

        val store = PairingStore(ctx)
        store.flipPhoneNumber = phoneNumber
        store.sharedSecret = secret
        store.pairingId = pairingId
        store.isPaired = true

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
