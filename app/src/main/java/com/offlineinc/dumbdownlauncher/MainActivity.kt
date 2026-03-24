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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.offlineinc.dumbdownlauncher.launcher.KeyDispatcher
import com.offlineinc.dumbdownlauncher.launcher.LauncherController
import com.offlineinc.dumbdownlauncher.launcher.PlatformPreferences
import com.offlineinc.dumbdownlauncher.launcher.dnd.DndMuteManager
import com.offlineinc.dumbdownlauncher.notifications.ui.NotificationsActivity
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import com.offlineinc.dumbdownlauncher.ui.DpadDirection
import com.offlineinc.dumbdownlauncher.ui.HomeScreen
import com.offlineinc.dumbdownlauncher.ui.PairingScreen
import com.offlineinc.dumbdownlauncher.ui.PlatformChoiceDialog


const val ALL_APPS = "__ALL_APPS__"
const val NOTIFICATIONS = "__NOTIFICATIONS__"
const val DEVICE_SETUP = "__DEVICE_SETUP__"
const val GOOGLE_MESSAGES = "__GOOGLE_MESSAGES__"
const val CHECK_UPDATES = "__CHECK_UPDATES__"
const val WEB_KEYBOARD = "__WEB_KEYBOARD__"
const val CONTACT_SYNC = "__CONTACT_SYNC__"

val WEB_APP_URLS = mapOf(
    GOOGLE_MESSAGES to "https://messages.google.com/web",
)

class MainActivity : AppCompatActivity() {
    private lateinit var dndMuteManager: DndMuteManager
    private lateinit var controller: LauncherController
    /** Onboarding step: "pairing" → "platform" → null (done) */
    private val onboardingStep = mutableStateOf<String?>(null)
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
        val platformChoice = PlatformPreferences.getChoice(this)
        val needsPairing = !pairingStore.isPaired
        val needsPlatform = platformChoice != "ios" && platformChoice != "android"

        onboardingStep.value = when {
            needsPairing -> "pairing"
            needsPlatform -> "platform"
            else -> null
        }
        Log.d("ONBOARDING", "onCreate: isPaired=${pairingStore.isPaired} platform=$platformChoice step=${onboardingStep.value}")

        // If paired but platform unknown, fetch it from the server in the background
        if (!needsPairing && needsPlatform) {
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
                                    onboardingStep.value = null
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("ONBOARDING", "Failed to fetch platform from server: ${e.message}")
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
                    "pairing" -> {
                        PairingScreen(
                            onPaired = {
                                val platform = PlatformPreferences.getChoice(this@MainActivity)
                                if (platform == "ios" || platform == "android") {
                                    // Platform was auto-detected from server — skip the picker
                                    Log.d("ONBOARDING", "Pairing complete, platform auto-detected: $platform")
                                    onboardingStep.value = null
                                } else {
                                    // Old server or old smartphone app — fall back to manual picker
                                    Log.d("ONBOARDING", "Pairing complete, platform unknown — showing picker")
                                    onboardingStep.value = "platform"
                                }
                                AllAppsActivity.invalidateCache()
                                MainAppsGridActivity.invalidateItemCache()
                            }
                        )
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
                                onboardingStep.value = null
                                MainAppsGridActivity.invalidateItemCache()
                            }
                        )
                    }
                }

            }
        }

        val permissionsNeeded = mutableListOf<String>()
        // Phone number permission — needed for device pairing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_PHONE_NUMBERS)
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                @Suppress("DEPRECATION")
                permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (permissionsNeeded.isNotEmpty()) {
            requestPermissions(permissionsNeeded.toTypedArray(), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        MouseAccessibilityService.forceDisable(this)
        overridePendingTransition(0, 0)
        if (PlatformPreferences.consumeShowDialog(this)) {
            // "device setup" from AllAppsActivity re-runs both steps from the beginning
            onboardingStep.value = "pairing"
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
}
