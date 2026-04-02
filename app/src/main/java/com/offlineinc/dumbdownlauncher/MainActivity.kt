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
import com.offlineinc.dumbdownlauncher.launcher.PlatformPreferences
import com.offlineinc.dumbdownlauncher.launcher.dnd.DndMuteManager
import com.offlineinc.dumbdownlauncher.notifications.ui.NotificationsActivity
import com.offlineinc.dumbdownlauncher.pairing.PairingApiClient
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import android.net.Uri
import com.offlineinc.dumbdownlauncher.ui.DpadDirection
import com.offlineinc.dumbdownlauncher.ui.HomeScreen
import com.offlineinc.dumbdownlauncher.ui.IntentChoiceScreen
import com.offlineinc.dumbdownlauncher.ui.LinkingChoiceScreen
import com.offlineinc.dumbdownlauncher.ui.MouseTutorialScreen
import com.offlineinc.dumbdownlauncher.ui.PairingScreen
import com.offlineinc.dumbdownlauncher.ui.PlatformChoiceDialog


const val ALL_APPS = "__ALL_APPS__"
const val NOTIFICATIONS = "__NOTIFICATIONS__"
const val DEVICE_SETUP = "__DEVICE_SETUP__"
const val GOOGLE_MESSAGES = "__GOOGLE_MESSAGES__"
const val CHECK_UPDATES = "__CHECK_UPDATES__"
const val CONTACT_SYNC = "__CONTACT_SYNC__"
const val QUACK = "__QUACK__"

val WEB_APP_URLS = mapOf(
    GOOGLE_MESSAGES to "https://messages.google.com/web",
)

class MainActivity : AppCompatActivity() {
    private lateinit var dndMuteManager: DndMuteManager
    private lateinit var controller: LauncherController
    /** Onboarding step: "linking" → "intent" → ["pairing" → "contactsync" →] "mousetutorial" → null */
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

        val platformChoice = PlatformPreferences.getChoice(this)
        val linkingChoice  = PlatformPreferences.getLinkingChoice(this)
        // "none" (super dumb) is a valid choice — no smart text, no pairing needed.
        // "skipped" is the legacy value for users who tapped skip on the old pairing screen.
        val validPlatforms = setOf("ios", "android", "skipped", "none")
        // New users: neither linking nor platform chosen yet
        val needsLinking  = linkingChoice == null && platformChoice == null && !pairingStore.isPaired
        // Linking chosen but still picking platform
        val needsIntent   = linkingChoice != null && platformChoice == null && !pairingStore.isPaired
        // Chose to link — always pair, even if platform is "none" (super dumb users
        // who still want their contacts synced etc.)
        val needsPairing  = !pairingStore.isPaired && platformChoice != null && linkingChoice == true
        // Backwards-compat: paired users from before intent screen, platform still unknown
        val needsPlatform = platformChoice !in validPlatforms && !needsLinking && !needsIntent

        // Backwards-compat: existing users who already completed pairing + platform
        // before the mouse tutorial was added should not be forced through it on update.
        if (!needsLinking && !needsIntent && !needsPairing && !needsPlatform && !isMouseTutorialDone()) {
            Log.d("ONBOARDING", "Existing user detected — auto-marking mouse tutorial done")
            markMouseTutorialDone()
        }

        onboardingStep.value = when {
            needsLinking  -> "linking"   // brand-new users: are you linking?
            needsIntent   -> "intent"    // linking chosen: pick imessage / google messages / none
            needsPairing  -> "pairing"   // linking=yes: pair the phone
            needsPlatform -> "platform"  // backwards-compat: paired but platform unknown
            !isMouseTutorialDone() -> "mousetutorial"
            else -> null
        }
        Log.d("ONBOARDING", "onCreate: isPaired=${pairingStore.isPaired} platform=$platformChoice linking=$linkingChoice step=${onboardingStep.value}")

        // If paired but platform unknown, fetch it from the server in the background
        if (!needsPairing && needsPlatform && platformChoice != "none") {
            val phone = pairingStore.flipPhoneNumber
            if (phone != null) {
                Thread {
                    try {
                        val api = com.offlineinc.dumbdownlauncher.pairing.PairingApiClient(okhttp3.OkHttpClient())
                        val status = api.getPairingStatus(phone)
                        val serverPlatform = status.optString("smartPlatform", "")
                        if (serverPlatform == "ios" || serverPlatform == "android") {
                            PlatformPreferences.saveChoice(this@MainActivity, serverPlatform)
                            Log.d("ONBOARDING", "Auto-detected platform from server: $serverPlatform")
                            runOnUiThread {
                                if (!isDestroyed && onboardingStep.value == "platform") {
                                    onboardingStep.value = nextStepAfterPlatform()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("ONBOARDING", "Failed to fetch platform from server: ${e.message}")
                    }
                }.start()
            }
        }

        // Report launcher version to server if it changed since last report
        if (pairingStore.isPaired) {
            val currentVersion = BuildConfig.VERSION_NAME
            if (currentVersion != pairingStore.lastReportedVersion) {
                val phone = pairingStore.flipPhoneNumber
                val secret = pairingStore.sharedSecret
                if (phone != null && secret != null) {
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
                    "linking" -> {
                        LinkingChoiceScreen(
                            onChoose = { willLink ->
                                PlatformPreferences.saveLinkingChoice(this@MainActivity, willLink)
                                Log.d("ONBOARDING", "Linking choice: $willLink")
                                onboardingStep.value = "intent"
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
                                when {
                                    PlatformPreferences.getLinkingChoice(this@MainActivity) == true -> {
                                        // Linking=yes → always go through pairing, regardless of platform
                                        onboardingStep.value = "pairing"
                                    }
                                    choice == "none" -> {
                                        // Not linking + super dumb — skip everything, go home
                                        markMouseTutorialDone()
                                        onboardingStep.value = null
                                    }
                                    else -> {
                                        // Not linking + has smart platform — skip pairing/sync,
                                        // go straight to mouse tutorial
                                        onboardingStep.value = if (isMouseTutorialDone()) null else "mousetutorial"
                                    }
                                }
                            }
                        )
                    }
                    "pairing" -> {
                        PairingScreen(
                            permissionsReady = permissionsReady.value,
                            onPaired = {
                                // Reset mouse tutorial so the full flow replays
                                getSharedPreferences("launcher_prefs", MODE_PRIVATE)
                                    .edit().putBoolean("mouse_tutorial_done", false).apply()
                                Log.d("ONBOARDING", "Pairing complete — launching contact sync")
                                AllAppsActivity.invalidateCache()
                                MainAppsGridActivity.invalidateItemCache()
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
                                Log.d("ONBOARDING", "User skipped pairing — marking setup complete")
                                PairingStore(this@MainActivity).isPaired = true
                                // Keep the platform the user already chose on the intent screen;
                                // only fall back to "skipped" if no platform was set yet.
                                val existingPlatform = PlatformPreferences.getChoice(this@MainActivity)
                                if (existingPlatform.isNullOrEmpty()) {
                                    PlatformPreferences.saveChoice(this@MainActivity, "skipped")
                                }
                                markMouseTutorialDone()
                                onboardingStep.value = null
                            }
                        )
                    }
                    // ContactSyncActivity is running on top — show a black overlay
                    // so the home screen doesn't flash through while launching.
                    "contactsync" -> {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                    }
                    "platform" -> {
                        PlatformChoiceDialog(
                            onChoose = { choice ->
                                if (choice != "skipped" && choice != "skip") {
                                    PlatformPreferences.saveChoice(this@MainActivity, choice)
                                    Log.d("ONBOARDING", "Platform choice saved: $choice")
                                } else {
                                    Log.d("ONBOARDING", "Platform choice skipped")
                                }
                                onboardingStep.value = nextStepAfterPlatform()
                                MainAppsGridActivity.invalidateAndRebuildAsync(applicationContext)
                            }
                        )
                    }
                    "platform_for_smarttxt" -> {
                        PlatformChoiceDialog(
                            onChoose = { choice ->
                                if (choice != "skipped" && choice != "skip") {
                                    PlatformPreferences.saveChoice(this@MainActivity, choice)
                                    Log.d("ONBOARDING", "Platform choice saved (for smart txt): $choice")
                                    onboardingStep.value = "launching_smarttxt"
                                    MainAppsGridActivity.invalidateAndRebuildAsync(applicationContext)
                                    launchSmartTxtForPlatform(choice)
                                } else {
                                    Log.d("ONBOARDING", "Platform choice skipped — going to home")
                                    onboardingStep.value = null
                                    MainAppsGridActivity.invalidateAndRebuildAsync(applicationContext)
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
        if (PlatformPreferences.consumeShowDialog(this)) {
            // "device setup" from AllAppsActivity re-runs the full flow from the very beginning
            // so the user can change any of their choices (linking preference, messaging app, etc.).
            onboardingStep.value = "linking"
        }
        // User returned from smart txt — clear the launching overlay
        if (onboardingStep.value == "launching_smarttxt") {
            onboardingStep.value = null
        }
        // User returned from ContactSyncActivity — advance to tutorial or done.
        // Platform is already known (set on the intent screen before pairing), so
        // we skip the old platform picker step.
        if (onboardingStep.value == "contactsync") {
            val platform = PlatformPreferences.getChoice(this)
            if (platform == "ios" || platform == "android" || platform == "none" || platform == "skipped") {
                Log.d("ONBOARDING", "Contact sync done, platform=$platform")
                onboardingStep.value = nextStepAfterPlatform()
            } else {
                Log.d("ONBOARDING", "Contact sync done, platform unknown — showing picker (backwards compat)")
                onboardingStep.value = "platform"
            }
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
        MouseAccessibilityService.setMouseEnabled(this, true)
        when (platform) {
            "android" -> {
                Log.d("ONBOARDING", "Launching Google Messages web for Android")
                openUrlInChrome("https://messages.google.com/web")
            }
            "ios" -> {
                Log.d("ONBOARDING", "Launching OpenBubbles for iOS")
                val intent = packageManager.getLaunchIntentForPackage("com.openbubbles.messaging")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                } else {
                    Log.w("ONBOARDING", "OpenBubbles not installed — falling back to Google Messages web")
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
        Thread {
            try {
                ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            } catch (e: Throwable) {
                Log.w("MainActivity", "su grant failed: ${e.message}")
            }
            runOnUiThread(onDone)
        }.start()
    }

    /** After platform is chosen/detected, go to mouse tutorial if not done, else finish. */
    private fun nextStepAfterPlatform(): String? =
        if (isMouseTutorialDone()) null else "mousetutorial"

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
