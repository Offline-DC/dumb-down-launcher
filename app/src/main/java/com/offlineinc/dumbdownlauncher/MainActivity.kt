package com.offlineinc.dumbdownlauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.lifecycleScope
import com.offlineinc.dumbdownlauncher.launcher.KeyDispatcher
import com.offlineinc.dumbdownlauncher.launcher.LauncherController
import com.offlineinc.dumbdownlauncher.launcher.NetworkUtils
import com.offlineinc.dumbdownlauncher.launcher.PlatformPreferences
import com.offlineinc.dumbdownlauncher.launcher.dnd.DndMuteManager
import com.offlineinc.dumbdownlauncher.notifications.ui.NotificationsActivity
import com.offlineinc.dumbdownlauncher.pairing.PairingApiClient
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import com.offlineinc.dumbdownlauncher.pairing.PhoneNumberNotFoundException
import android.net.Uri
import com.offlineinc.dumbdownlauncher.ui.BootRegistrationScreen
import com.offlineinc.dumbdownlauncher.ui.DpadDirection
import com.offlineinc.dumbdownlauncher.ui.HomeScreen
import com.offlineinc.dumbdownlauncher.ui.IntentChoiceScreen
import com.offlineinc.dumbdownlauncher.ui.LinkingChoiceScreen
import com.offlineinc.dumbdownlauncher.ui.MouseTutorialScreen
import com.offlineinc.dumbdownlauncher.ui.PairingScreen


const val ALL_APPS = "__ALL_APPS__"
const val NOTIFICATIONS = "__NOTIFICATIONS__"
const val DEVICE_SETUP = "__DEVICE_SETUP__"
const val GOOGLE_MESSAGES = "__GOOGLE_MESSAGES__"
const val CHECK_UPDATES = "__CHECK_UPDATES__"
const val CONTACT_SYNC = "__CONTACT_SYNC__"
const val QUACK = "__QUACK__"
const val SNAKE = "__SNAKE__"
const val WEATHER = "__WEATHER__"

val WEB_APP_URLS = mapOf(
    GOOGLE_MESSAGES to "https://messages.google.com/web",
)

class MainActivity : AppCompatActivity() {
    private lateinit var dndMuteManager: DndMuteManager
    private lateinit var controller: LauncherController
    /**
     * Onboarding step machine:
     *   boot_registration → linking → [pairing → contactsync →] intent → mousetutorial → null
     *
     * `boot_registration` runs FIRST for every user on a fresh phone or on
     * Device Setup re-entry — it reads the SIM, hits POST /api/v1/register,
     * and fetches the Gigs-tier bundle flags. Doing this before showing
     * any user choices lets dumbest-tier users skip the rest of onboarding
     * entirely (no linking screen, no platform picker). See
     * [BootRegistrationScreen] and [nextStepAfterBoot].
     *
     * For users already past registration, the flow continues with
     * `linking` (yes/no), then `pairing` if yes (followed by contactsync
     * and `intent`), or straight to `intent` if no.
     */
    private val onboardingStep = mutableStateOf<String?>(null)
    /** Flips to true once su permission grant finishes (or was unnecessary). */
    private val permissionsReady = mutableStateOf(false)
    // Incremented on every onResume so HomeScreen re-fetches the wallpaper
    // immediately if the user changed it while away.
    private val wallpaperRefreshKey = mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = 0xFF000000.toInt()

        dndMuteManager = DndMuteManager(
            appContext = applicationContext,
            scope = lifecycleScope,
        )
        dndMuteManager.refreshFromSystem()

        // Determine onboarding step: pairing first, then platform choice
        val pairingStore = PairingStore(this)

        // ── Migration: import pairing data from the old contact-sync app ────
        // Before this update, pairing was owned by the contact-sync app and
        // exposed via its ContentProvider. If the user is already paired
        // there, pull that data into the launcher's own PairingStore so the
        // onboarding screen doesn't re-appear after the update.
        if (!pairingStore.isPaired) {
            migrateFromContactSync(pairingStore)
        }

        // If the subscription already includes smart txt, treat the device as
        // fully set up with no linking and "none" platform — skip all onboarding
        // and hide the device setup option.
        val effectivePlatformChoice = if (pairingStore.hideSmartTxt) "none" else PlatformPreferences.getChoice(this)
        val effectiveLinkingChoice  = if (pairingStore.hideSmartTxt) false   else PlatformPreferences.getLinkingChoice(this)
        val platformChoice = effectivePlatformChoice
        val linkingChoice  = effectiveLinkingChoice
        // "none" (super dumb) is a valid choice — no smart text, no pairing needed.
        // "skipped" is the legacy value for users who tapped skip on the old pairing screen.
        val validPlatforms = setOf("ios", "android", "skipped", "none")
        // Any already-paired user must have been registered during the old pairing
        // flow, so treat pairing as an implicit registration success for legacy
        // users who don't have the `deviceRegistered` flag set.
        val isRegistered = pairingStore.deviceRegistered || pairingStore.isPaired
        // New users: neither linking nor platform chosen yet
        val needsLinking     = linkingChoice == null && !pairingStore.isPaired
        // Linking choice made but device not yet registered — loop back
        // through BootRegistrationScreen so /register + bundle-flag fetch
        // complete before the rest of onboarding.
        val needsRegistering = linkingChoice != null && !isRegistered
        // Linking=yes → pair first, intent comes after contactsync
        val needsPairing     = linkingChoice == true && !pairingStore.isPaired
        // Need to pick messaging platform (after pairing+contactsync if linked,
        // or right after linking=no, or backwards-compat for old paired users)
        val needsIntent      = platformChoice !in validPlatforms &&
                               !needsLinking && !needsRegistering && !needsPairing

        // Backwards-compat: existing users who already completed pairing + platform
        // before the mouse tutorial was added should not be forced through it on update.
        if (!needsLinking && !needsRegistering && !needsPairing && !needsIntent && !isMouseTutorialDone()) {
            Log.d("ONBOARDING", "Existing user detected — auto-marking mouse tutorial done")
            markMouseTutorialDone()
        }

        onboardingStep.value = when {
            // Fresh phones (no linking choice) AND users who picked a
            // linking option but never finished registration both go
            // through the boot screen first — it runs SIM read + backend
            // register + bundle-flag fetch before any UI branches.
            needsLinking || needsRegistering -> "boot_registration"
            needsPairing     -> "pairing"       // linking=yes: pair the phone
            needsIntent      -> "intent"        // pick imessage / google messages / none
            !isMouseTutorialDone() -> "mousetutorial"
            else -> null
        }
        Log.d("ONBOARDING", "onCreate: isPaired=${pairingStore.isPaired} platform=$platformChoice linking=$linkingChoice step=${onboardingStep.value}")
        Log.i("ONBOARDING", "onCreate: stripeProductIds=${pairingStore.stripeProductIds}")

        // Platform (ios/android) is set solely by IntentChoiceScreen
        // (iMessage vs Google Messages). Don't auto-detect from the server.

        // Start (or restart) the always-on Type Sync WebSocket if paired.
        // The relay now lives inside MouseAccessibilityService (no foreground
        // service notification required).
        // Deferred until network is available — on boot the cellular radio may
        // not be up yet, which previously caused a crash.
        if (pairingStore.isPaired) {
            NetworkUtils.whenNetworkAvailable(this) {
                MouseAccessibilityService.startRelay(this, pairingStore.flipPhoneNumber)
                Log.d("TYPESYNC", "Started/refreshed Type Sync WebSocket")
            }
        }

        // Report launcher version to server if it changed since last report.
        // Also deferred until network is ready to avoid crash on early boot.
        if (pairingStore.isPaired) {
            val currentVersion = BuildConfig.VERSION_NAME
            if (currentVersion != pairingStore.lastReportedVersion) {
                val phone = pairingStore.flipPhoneNumber
                val secret = pairingStore.sharedSecret
                if (phone != null && secret != null) {
                    NetworkUtils.whenNetworkAvailable(this) {
                        Thread {
                            try {
                                val api = PairingApiClient(okhttp3.OkHttpClient())
                                api.reportVersion(phone, currentVersion, secret)
                                pairingStore.lastReportedVersion = currentVersion
                                Log.d("ONBOARDING", "Reported launcher version $currentVersion to server")
                            } catch (e: Exception) {
                                Log.w("ONBOARDING", "Failed to report version: ${e.message}")
                            }
                        }.start()
                    }
                }
            }
        }

        // Fetch stripeProductIds on every launch if not yet stored.
        // Uses the unauthenticated /stripe-product-ids endpoint so it works
        // regardless of pairing state.
        // Falls back to PhoneNumberReader if flipPhoneNumber isn't in PairingStore
        // (e.g. devices that were paired before the phone number was persisted).
        if (pairingStore.stripeProductIds == null) {
            val appCtx = applicationContext
            NetworkUtils.whenNetworkAvailable(this) {
                Thread {
                    try {
                        val phone = pairingStore.flipPhoneNumber
                            ?: com.offlineinc.dumbdownlauncher.launcher.PhoneNumberReader
                                .readWithWait(appCtx).first
                        if (phone == null) {
                            Log.w("ONBOARDING", "Cannot fetch stripeProductIds — phone number unavailable")
                            return@Thread
                        }
                        val api = PairingApiClient(okhttp3.OkHttpClient())
                        val result = api.getStripeProductIds(phone)
                        pairingStore.stripeProductIds = result.productIds
                        pairingStore.hideAudioBundle = result.hideAudioBundle
                        pairingStore.hideSmartTxt = result.hideSmartTxt
                        AllAppsActivity.invalidateCache()
                        Log.i("ONBOARDING", "Fetched stripeProductIds=${result.productIds} hideAudioBundle=${result.hideAudioBundle} hideSmartTxt=${result.hideSmartTxt}")
                    } catch (e: PhoneNumberNotFoundException) {
                        // Phone number isn't on file with the backend yet — treat
                        // as "no subscription data": no product IDs, both bundle
                        // flags false so the upsells remain visible. Persisting
                        // these defaults also prevents refetching every launch.
                        pairingStore.stripeProductIds = emptyList()
                        pairingStore.hideAudioBundle = false
                        pairingStore.hideSmartTxt = false
                        AllAppsActivity.invalidateCache()
                        Log.i("ONBOARDING", "Phone number not found (404); defaulting hideAudioBundle=false hideSmartTxt=false")
                    } catch (e: Exception) {
                        Log.w("ONBOARDING", "Failed to fetch stripeProductIds: ${e.message}")
                    }
                }.start()
            }
        }

        // Controller is kept for dialer digit launching from KeyDispatcher
        controller = LauncherController(
            context = this,
            getSelectedIndex = { 0 },
            getItems = { emptyList() },
            onStartActivity = { startActivity(it) },
            onNoAnim = { overridePendingTransition(0, 0) }
        )

        setContent {
            val muted by dndMuteManager.muted.collectAsState()
            val currentStep by onboardingStep
            val wallpaperKey by wallpaperRefreshKey

            Box(modifier = Modifier.fillMaxSize()) {
                HomeScreen(
                    messagesMuted = muted,
                    wallpaperRefreshKey = wallpaperKey,
                    onOpenAppsGrid = {
                        startActivity(Intent(this@MainActivity, MainAppsGridActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        })
                        overridePendingTransition(0, 0)
                    },
                    onOpenNotifications = {
                        startActivity(Intent(this@MainActivity, NotificationsActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        })
                        overridePendingTransition(0, 0)
                    },
                    onOpenAllApps = {
                        startActivity(Intent(this@MainActivity, AllAppsActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        })
                        overridePendingTransition(0, 0)
                    },
                    onDpadDirection = { direction ->
                        launchDpadShortcut(direction)
                    },
                )

                when (currentStep) {
                    "boot_registration" -> {
                        BootRegistrationScreen(
                            permissionsReady = permissionsReady.value,
                            onComplete = { phone ->
                                val store = PairingStore(this@MainActivity)
                                store.saveRegistration(phone)
                                Log.d("ONBOARDING", "Boot registration complete (phone=$phone)")
                                onboardingStep.value = nextStepAfterBoot(store)
                            },
                            onSkip = {
                                // Escape hatch from the error states: drop the
                                // user on the home screen and skip the rest of
                                // device setup for this session. No persistent
                                // flags are written, so onCreate will route
                                // them back through boot registration on next
                                // launch (where they can retry or skip again).
                                Log.d("ONBOARDING", "User skipped boot registration — going to home")
                                onboardingStep.value = null
                            }
                        )
                    }
                    "linking" -> {
                        LinkingChoiceScreen(
                            onChoose = { willLink ->
                                PlatformPreferences.saveLinkingChoice(this@MainActivity, willLink)
                                Log.d("ONBOARDING", "Linking choice: $willLink")
                                // Registration already ran in boot_registration, so
                                // route straight to whatever the user's linking +
                                // platform state calls for next (pairing / intent /
                                // mouse tutorial / home).
                                onboardingStep.value =
                                    nextStepAfterRegistration(PairingStore(this@MainActivity))
                            }
                        )
                    }
                    "intent" -> {
                        IntentChoiceScreen(
                            onChoose = { choice ->
                                // choice: "ios" | "android" | "none"
                                PlatformPreferences.saveChoice(this@MainActivity, choice)
                                AllAppsActivity.invalidateCache()
                                MainAppsGridActivity.invalidateAndRebuildAsync(applicationContext)
                                Log.d("ONBOARDING", "Intent choice: $choice linking=${PlatformPreferences.getLinkingChoice(this@MainActivity)}")
                                if (choice == "none") {
                                    // Super dumb — no smart txt, no mouse tutorial needed.
                                    // Go straight to the "click ok to start using ur phone" done screen.
                                    markMouseTutorialDone()
                                    onboardingStep.value = "onboarding_complete"
                                } else {
                                    onboardingStep.value = if (isMouseTutorialDone()) null else "mousetutorial"
                                }
                            }
                        )
                    }
                    "pairing" -> {
                        PairingScreen(
                            onPaired = {
                                // Reset mouse tutorial so the full flow replays
                                getSharedPreferences("launcher_prefs", MODE_PRIVATE)
                                    .edit().putBoolean("mouse_tutorial_done", false).apply()
                                Log.d("ONBOARDING", "Pairing complete — launching contact sync")
                                AllAppsActivity.invalidateCache()
                                MainAppsGridActivity.invalidateItemCache()

                                // Start (or restart) TypeSync with the fresh shared secret
                                // immediately so it's ready by the time the user finishes
                                // onboarding and starts typing.
                                val store = PairingStore(this@MainActivity)
                                MouseAccessibilityService.startRelay(this@MainActivity, store.flipPhoneNumber)
                                Log.d("TYPESYNC", "Started/refreshed Type Sync after pairing")

                                // After pairing, send user straight to contact sync
                                onboardingStep.value = "contactsync"
                                startActivity(
                                    Intent(this@MainActivity, com.offlineinc.dumbdownlauncher.contactsync.ContactSyncActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                        .putExtra(com.offlineinc.dumbdownlauncher.contactsync.ContactSyncActivity.EXTRA_ONBOARDING, true)
                                )
                                overridePendingTransition(0, 0)
                            },
                            onSkip = {
                                Log.d("ONBOARDING", "User skipped pairing — moving to intent")
                                PairingStore(this@MainActivity).isPaired = true
                                // Platform not chosen yet — intent screen comes next
                                onboardingStep.value = "intent"
                            }
                        )
                    }
                    // ContactSyncActivity is running on top — show a black overlay
                    // so the home screen doesn't flash through while launching.
                    "contactsync" -> {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                    }
                    "platform_for_smarttxt" -> {
                        IntentChoiceScreen(
                            onChoose = { choice ->
                                PlatformPreferences.saveChoice(this@MainActivity, choice)
                                AllAppsActivity.invalidateCache()
                                MainAppsGridActivity.invalidateAndRebuildAsync(applicationContext)
                                if (choice == "ios" || choice == "android") {
                                    Log.d("ONBOARDING", "Platform choice saved (for smart txt): $choice")
                                    onboardingStep.value = "launching_smarttxt"
                                    launchSmartTxtForPlatform(choice)
                                } else {
                                    // Super dumb — skip smart txt, show done screen
                                    Log.d("ONBOARDING", "Platform choice 'none' — showing done screen")
                                    markMouseTutorialDone()
                                    onboardingStep.value = "onboarding_complete"
                                }
                            }
                        )
                    }
                    "mousetutorial" -> {
                        MouseTutorialScreen(
                            onComplete = {
                                markMouseTutorialDone()
                                MouseAccessibilityService.forceDisable(this@MainActivity)
                                val platform = PlatformPreferences.getChoice(this@MainActivity)
                                if (platform == "none") {
                                    // Super dumb users don't need smart txt — show simple done screen
                                    Log.d("ONBOARDING", "Mouse tutorial complete (none platform) — showing done screen")
                                    onboardingStep.value = "onboarding_complete"
                                } else {
                                    Log.d("ONBOARDING", "Mouse tutorial complete — launching smart txt")
                                    onboardingStep.value = "launching_smarttxt"
                                    launchSmartTxt()
                                }
                            }
                        )
                    }
                    "onboarding_complete" -> {
                        com.offlineinc.dumbdownlauncher.ui.OnboardingDoneScreen(
                            onOk = { onboardingStep.value = null }
                        )
                    }
                    "launching_smarttxt" -> {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                text = "launching smart txt...",
                                style = com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme.Text.Hint.copy(
                                    textAlign = TextAlign.Center
                                )
                            )
                        }
                    }
                }

            }
        }

        grantPermissionsViaSu { permissionsReady.value = true }
    }

    override fun onResume() {
        super.onResume()
        // Don't disable mouse during the tutorial — the user needs it active
        if (onboardingStep.value != "mousetutorial") {
            MouseAccessibilityService.forceDisable(this)
        }
        overridePendingTransition(0, 0)
        // Re-read the mute preference from SharedPreferences so the home screen
        // badge reflects any toggle the user made in NotificationsActivity.
        // NotificationsActivity owns its own DndMuteManager instance and only
        // updates its own StateFlow, so we must refresh ours on every resume.
        dndMuteManager.refreshFromSystem()
        if (PlatformPreferences.consumeShowDialog(this)) {
            // "device setup" from AllAppsActivity re-runs the full flow from the very beginning
            // so the user can change any of their choices (linking preference, messaging app, etc.).
            // Every re-entry starts at the boot screen so the backend gets a
            // fresh /register call and the Gigs bundle flags are re-fetched —
            // crucial if the user's plan tier changed since last time.
            onboardingStep.value = "boot_registration"
        }
        // User returned from smart txt — clear the launching overlay
        if (onboardingStep.value == "launching_smarttxt") {
            onboardingStep.value = null
        }
        // User returned from ContactSyncActivity — show intent screen to pick
        // messaging platform (iMessage / Google Messages / none).
        if (onboardingStep.value == "contactsync") {
            Log.d("ONBOARDING", "Contact sync done — showing intent screen")
            onboardingStep.value = "intent"
        }
        // Bump the key so HomeScreen re-fetches the wallpaper in case the user
        // changed it while the launcher was in the background.
        wallpaperRefreshKey.intValue++
        // Pre-warm caches in the background so sub-screens open instantly.
        AllAppsActivity.warmCacheAsync(applicationContext)
        MainAppsGridActivity.warmCacheAsync(applicationContext)
        MainAppsGridActivity.warmWallpaperAsync(applicationContext)
    }

    /**
     * Launch a fixed app for each D-pad direction on the home screen.
     *   Up    → Settings
     *   Down  → Call history (Dialer)
     *   Left  → Camera
     *   Right → Contacts
     */
    private fun launchDpadShortcut(direction: DpadDirection) {
        val pkg = when (direction) {
            DpadDirection.UP    -> "com.android.settings"
            DpadDirection.DOWN  -> "com.android.dialer"
            DpadDirection.LEFT  -> "com.tcl.camera"
            DpadDirection.RIGHT -> "com.android.contacts"
        }

        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                overridePendingTransition(0, 0)
            } else {
                Log.w("MainActivity", "No launch intent for $pkg")
                Toast.makeText(this, "App not found", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to launch $pkg for $direction: ${e.message}")
            Toast.makeText(this, "App not found", Toast.LENGTH_SHORT).show()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.d("DUMB_KEYS", "DOWN keyCode=${event.keyCode} scanCode=${event.scanCode} unicode=${event.unicodeChar}")
        }

        // While onboarding (pairing or platform-picker) is on screen, let all key
        // events flow straight to Compose so the dialog's onPreviewKeyEvent handles
        // Up/Down/Enter/Back correctly.  Without this guard, KeyDispatcher intercepts
        // Enter/Center and launches the 3×3 grid before the dialog can react.
        if (onboardingStep.value != null) {
            return super.dispatchKeyEvent(event)
        }

        val result = KeyDispatcher.handle(event)

        if (result.consumed) {
            Log.d("DUMB_KEYS", "CONSUMED → notif=${result.openNotifications} allApps=${result.openAllApps} dial=${result.dialDigits} blankDial=${result.openDialerBlank}")
        }

        if (!result.consumed) return super.dispatchKeyEvent(event)

        return when {
            result.openNotifications -> {
                startActivity(Intent(this, NotificationsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                })
                overridePendingTransition(0, 0)
                true
            }
            result.openAllApps -> {
                startActivity(Intent(this, AllAppsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                })
                overridePendingTransition(0, 0)
                true
            }
            result.openDialerBlank -> {
                controller.openDialerBlank()
                true
            }
            result.dialDigits != null -> {
                controller.openDialerWithDigits(result.dialDigits)
                true
            }
            // Center/Enter is handled by HomeScreen's onPreviewKeyEvent → onOpenAppsGrid
            // so it won't reach here, but if it does, open the grid
            result.activateSelected -> {
                startActivity(Intent(this, MainAppsGridActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                })
                overridePendingTransition(0, 0)
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    // ─── Smart txt launch ─────────────────────────────────────────────────

    /**
     * Launch the appropriate smart txt (messaging) app based on the
     * stored platform choice.
     *
     * - "android" → Google Messages web via Chrome
     * - "ios"     → OpenBubbles native app (fallback: Google Messages web)
     *
     * If the platform isn't set yet, show the platform picker first.
     */
    private fun launchSmartTxt() {
        val platform = PlatformPreferences.getChoice(this)
        if (platform != "ios" && platform != "android") {
            Log.d("ONBOARDING", "Platform not set — showing picker before smart txt launch")
            onboardingStep.value = "platform_for_smarttxt"
            return
        }
        launchSmartTxtForPlatform(platform)
    }

    private fun launchSmartTxtForPlatform(platform: String) {
        when (platform) {
            "android" -> {
                MouseAccessibilityService.setMouseEnabled(this, true)
                Log.d("ONBOARDING", "Launching Google Messages web for Android")
                openUrlInChrome("https://messages.google.com/web")
            }
            "ios" -> {
                Log.d("ONBOARDING", "Launching OpenBubbles for iOS")
                if (MouseAccessibilityService.isOpenBubblesMouseNeeded(this)) {
                    MouseAccessibilityService.setMouseEnabled(this, true)
                }
                val intent = packageManager.getLaunchIntentForPackage("com.openbubbles.messaging")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                } else {
                    Log.w("ONBOARDING", "OpenBubbles not installed — falling back to Google Messages web")
                    MouseAccessibilityService.setMouseEnabled(this, true)
                    openUrlInChrome("https://messages.google.com/web")
                }
            }
        }
    }

    /** Open a URL directly in Chrome, bypassing the browser chooser dialog. */
    private fun openUrlInChrome(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            overridePendingTransition(0, 0)
        } catch (e: Exception) {
            Log.w("ONBOARDING", "Chrome not available, falling back to default browser: ${e.message}")
            try {
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallback)
                overridePendingTransition(0, 0)
            } catch (e2: Exception) {
                Log.e("ONBOARDING", "No browser available: ${e2.message}")
            }
        }
    }

    /**
     * Pick the onboarding step to run after [BootRegistrationScreen] finishes.
     *
     * Boot registration is the FIRST screen every user sees (fresh phone
     * or Device Setup re-entry). By the time it completes, PairingStore
     * knows the Gigs tier, so we can decide whether to suppress the rest
     * of onboarding:
     *  - `hideSmartTxt` (dumbest tier)  — no linking, no messaging app, no
     *    mouse tutorial makes sense. Skip straight to home. Mirrors the
     *    `effectiveLinkingChoice`/`effectivePlatformChoice` override in
     *    [onCreate] for the same reason.
     *  - No linking choice saved yet (first-run)  — show [LinkingChoiceScreen].
     *  - Linking choice already saved (re-entry mid-flow, or upgrade-path
     *    users who completed registration but not linking)  — fall through
     *    to [nextStepAfterRegistration] which routes by existing state.
     */
    private fun nextStepAfterBoot(store: PairingStore): String? {
        if (store.hideSmartTxt) {
            // Dumbest tier — nothing else to configure. Mark the mouse
            // tutorial done so we don't re-prompt on next launch and then
            // go straight home.
            markMouseTutorialDone()
            Log.d("ONBOARDING", "Boot done, hideSmartTxt=true — skipping rest of onboarding")
            return null
        }
        if (PlatformPreferences.getLinkingChoice(this) == null) {
            return "linking"
        }
        return nextStepAfterRegistration(store)
    }

    /**
     * Pick the onboarding step to run after [BootRegistrationScreen] +
     * [LinkingChoiceScreen] have both been resolved.
     *
     * Respects any state the user already has: if they're already paired
     * we don't force them back to pairing, if they've already chosen a
     * messaging platform we don't re-show IntentChoiceScreen, and if
     * they've already seen the mouse tutorial we send them straight home.
     */
    private fun nextStepAfterRegistration(store: PairingStore): String? {
        val willLink    = PlatformPreferences.getLinkingChoice(this) == true
        val platform    = PlatformPreferences.getChoice(this)
        val hasPlatform = platform in setOf("ios", "android", "skipped", "none")
        return when {
            // If the user chose "link" during this Device Setup pass, always
            // show the PairingScreen. When they're already paired it renders
            // as DeviceLinkedContent (with "next" + "unpair") so they can
            // continue or re-pair — never swallowed silently.
            willLink                    -> "pairing"
            !hasPlatform                -> "intent"
            !isMouseTutorialDone()      -> "mousetutorial"
            else                        -> null
        }
    }

    // ─── Mouse tutorial persistence ────────────────────────────────────────

    private fun isMouseTutorialDone(): Boolean =
        getSharedPreferences("launcher_prefs", MODE_PRIVATE)
            .getBoolean("mouse_tutorial_done", false)

    private fun markMouseTutorialDone() {
        getSharedPreferences("launcher_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("mouse_tutorial_done", true)
            .apply()
    }

    /**
     * Grant all runtime permissions via a single `su` call.
     * Only runs the shell command if at least one permission is missing.
     * Calls [onDone] on the main thread when finished (or immediately if nothing to grant).
     */
    private fun grantPermissionsViaSu(onDone: () -> Unit) {
        val pkg = packageName
        val allPerms = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            allPerms.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            allPerms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = allPerms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onDone()
            return
        }

        val cmd = missing.joinToString(" && ") { "pm grant $pkg $it" }
        // Use the shared single-threaded boot executor so this su command
        // doesn't compete with other boot-time su calls for eMMC I/O.
        DumbDownApp.bootExecutor.execute {
            try {
                ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            } catch (e: Throwable) {
                Log.w("MainActivity", "su grant failed: ${e.message}")
            }
            runOnUiThread(onDone)
        }
    }

    /**
     * One-time migration: pull pairing data from the old contact-sync app's
     * ContentProvider (content://com.offlineinc.dumbcontactsync.devicelink/pairing)
     * into the launcher's PairingStore.
     *
     * If the contact-sync app has already been updated and removed its provider,
     * we fall back to checking whether the user previously completed setup
     * (platform choice is saved). If so, mark as paired so we don't force
     * re-onboarding — the full pairing credentials will be re-established
     * when the user opens device setup or the next sync occurs.
     */
    private fun migrateFromContactSync(store: PairingStore) {
        // Attempt 1: read from the old contact-sync ContentProvider
        try {
            val oldUri = Uri.parse("content://com.offlineinc.dumbcontactsync.devicelink/pairing")
            contentResolver.query(oldUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val isPaired = cursor.getInt(cursor.getColumnIndexOrThrow("is_paired")) == 1
                    val secret = cursor.getString(cursor.getColumnIndexOrThrow("shared_secret")).orEmpty()
                    val phone = cursor.getString(cursor.getColumnIndexOrThrow("flip_phone_number")).orEmpty()
                    val pairingId = cursor.getInt(cursor.getColumnIndexOrThrow("pairing_id"))

                    if (isPaired && secret.isNotEmpty()) {
                        store.isPaired = true
                        store.sharedSecret = secret
                        store.flipPhoneNumber = phone
                        store.pairingId = pairingId
                        Log.i("ONBOARDING", "Migrated pairing from contact-sync: pairingId=$pairingId phone=$phone")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("ONBOARDING", "Could not read old contact-sync provider (may already be updated): ${e.message}")
        }

        // Attempt 2: if the user already chose a platform, they went through
        // setup before this update — treat them as paired so they don't see
        // the pairing screen again.
        val existingPlatform = PlatformPreferences.getChoice(this)
        if (existingPlatform == "ios" || existingPlatform == "android") {
            store.isPaired = true
            Log.i("ONBOARDING", "Marked as paired based on existing platform choice ($existingPlatform) — credentials will sync on next use")
        }
    }
}
