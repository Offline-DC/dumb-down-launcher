// AllAppsActivity.kt
package com.offlineinc.dumbdownlauncher

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.offlineinc.dumbdownlauncher.launcher.KeyDispatcher
import com.offlineinc.dumbdownlauncher.launcher.LaunchResolver
import com.offlineinc.dumbdownlauncher.launcher.LauncherController
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.ui.AppAdapter
import android.content.Intent

class AllAppsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppAdapter
    private lateinit var controller: LauncherController

    private var selectedIndex = 0
    private val items = mutableListOf<AppItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // reuse same layout with @id/list

        window.statusBarColor = 0xFF000000.toInt()

        recyclerView = findViewById(R.id.list)
        recyclerView.layoutManager = LinearLayoutManager(this)

        loadAllApps()

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
        recyclerView.setItemViewCacheSize(60)

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

    private fun loadAllApps() {
        items.clear()

        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolved = pm.queryIntentActivities(intent, 0)

        val appItems = resolved.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val defaultLabel = pm.getApplicationLabel(appInfo).toString()
                val label = com.offlineinc.dumbdownlauncher.launcher.AppLabelOverrides
                    .getLabel(pkg, defaultLabel)
                val defaultIcon = pm.getApplicationIcon(appInfo)
                val icon = com.offlineinc.dumbdownlauncher.launcher.AppIconOverrides
                    .getIcon(this, pkg, defaultIcon)

                val component = LaunchResolver.resolveLaunchComponent(pm, pkg)
                AppItem(pkg, label, icon, component)
            } catch (_: Exception) {
                null
            }
        }
            // Remove duplicates by packageName (some OEMs return multiple entries)
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

        items.addAll(appItems)

        selectedIndex = 0
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
}
