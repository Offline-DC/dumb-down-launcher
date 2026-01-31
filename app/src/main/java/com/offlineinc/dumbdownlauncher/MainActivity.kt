// MainActivity.kt
package com.offlineinc.dumbdownlauncher

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.offlineinc.dumbdownlauncher.launcher.KeyDispatcher
import com.offlineinc.dumbdownlauncher.launcher.LaunchResolver
import com.offlineinc.dumbdownlauncher.launcher.LauncherController
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.ui.AppAdapter

const val ALL_APPS = "__ALL_APPS__"

const val NOTIFICATIONS = "__NOTIFICATIONS__"

class MainActivity : AppCompatActivity() {

    private val allowedPackages = listOf(
        "com.android.mms",
        "com.android.contacts",
        "com.android.dialer",
        "com.android.settings",
        "com.mediatek.camera",
        "com.android.gallery3d",
        "com.android.browser",
        "com.whatsapp",
    )

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppAdapter
    private lateinit var controller: LauncherController

    private var selectedIndex = 0
    private val items = mutableListOf<AppItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.statusBarColor = 0xFF000000.toInt()

        recyclerView = findViewById(R.id.list)
        recyclerView.layoutManager = LinearLayoutManager(this)

        loadApps()

        controller = LauncherController(
            context = this,
            getSelectedIndex = { selectedIndex },
            getItems = { items },
            onStartActivity = { startActivity(it) },
            onNoAnim = { overridePendingTransition(0, 0) }
        )

        adapter = AppAdapter(
            recyclerView = recyclerView,
            items = items,
            getSelectedIndex = { selectedIndex },
            setSelectedIndex = { selectedIndex = it },
            onActivate = { controller.launchSelected() }
        )
        recyclerView.adapter = adapter

        recyclerView.itemAnimator = null
        recyclerView.setHasFixedSize(true)
        recyclerView.setItemViewCacheSize(items.size)

        recyclerView.post {
            if (items.isNotEmpty()) {
                selectedIndex = 0
                recyclerView.scrollToPosition(0)
                recyclerView.requestFocus()
                recyclerView.post {
                    recyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
    }

    private fun loadApps() {
        items.clear()

        for (pkg in allowedPackages) {
            try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                val defaultLabel = packageManager.getApplicationLabel(appInfo).toString()
                val label = com.offlineinc.dumbdownlauncher.launcher.AppLabelOverrides
                    .getLabel(pkg, defaultLabel)
                val defaultIcon = packageManager.getApplicationIcon(appInfo)
                val icon = com.offlineinc.dumbdownlauncher.launcher.AppIconOverrides
                    .getIcon(this, pkg, defaultIcon)
                val launchComponent = LaunchResolver.resolveLaunchComponent(packageManager, pkg)
                items.add(AppItem(pkg, label, icon, launchComponent))
            } catch (_: Exception) {
                // ignore
            }
        }

        val uberPkg = resolveInstalledPackage("com.ubercab", "com.offline.uberlauncher")
        if (uberPkg != null) {
            try {
                val appInfo = packageManager.getApplicationInfo(uberPkg, 0)
                val label = "Uber"

                val defaultIcon = packageManager.getApplicationIcon(appInfo)
                val icon = com.offlineinc.dumbdownlauncher.launcher.AppIconOverrides
                    .getIcon(this, uberPkg, defaultIcon)

                val launchComponent = LaunchResolver.resolveLaunchComponent(packageManager, uberPkg)
                items.add(AppItem(uberPkg, label, icon, launchComponent))
            } catch (_: Exception) { }
        }

        items.add(
            AppItem(
                packageName = ALL_APPS,
                label = "All Apps",
                icon = getDrawable(R.drawable.ic_all_apps)!!,
                launchComponent = null
            )
        )

        items.add(
            AppItem(
                packageName = NOTIFICATIONS,
                label = "Notifications",
                icon = getDrawable(R.drawable.ic_notifications)!!,
                launchComponent = null
            )
        )


        if (items.isEmpty()) {
            Toast.makeText(this, "No allowed apps found/installed.", Toast.LENGTH_LONG).show()
        }

        selectedIndex = 0.coerceAtMost(items.lastIndex)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val result = KeyDispatcher.handle(event)
        if (!result.consumed) return super.dispatchKeyEvent(event)

        when {
            result.activateSelected -> controller.launchSelected()
            result.openDialerBlank -> controller.openDialerBlank()
            result.dialDigits != null -> controller.openDialerWithDigits(result.dialDigits)
        }
        return true
    }

    private fun resolveInstalledPackage(primary: String, fallback: String): String? {
        return try {
            packageManager.getApplicationInfo(primary, 0)
            primary
        } catch (_: Exception) {
            try {
                packageManager.getApplicationInfo(fallback, 0)
                fallback
            } catch (_: Exception) {
                null
            }
        }
    }
}
