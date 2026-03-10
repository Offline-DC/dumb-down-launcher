package com.offlineinc.dumbdownlauncher.coverdisplay

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Renders [CoverScreen] (Jetpack Compose) on the secondary cover display.
 *
 * Presentation extends Dialog, which has no built-in Compose lifecycle support.
 * We implement [LifecycleOwner], [ViewModelStoreOwner], and [SavedStateRegistryOwner]
 * directly on this class and wire them into the ComposeView's view-tree so that
 * Compose can resolve them the same way it would inside a ComponentActivity.
 */
class CoverPresentation(context: Context, display: Display) :
    Presentation(context, display),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    // ── Lifecycle plumbing required by Compose ───────────────────────────────

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val vmStore = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = vmStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    // ── Presentation lifecycle ────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        // Restore saved state before super.onCreate so the registry is ready
        savedStateController.performRestore(null)
        super.onCreate(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Full-screen, no title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // Build the ComposeView and hook up the three lifecycle owners
        val composeView = ComposeView(context).apply {
            // Dispose composition when the view is removed from the window (i.e. on dismiss)
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { CoverScreen() }
        }

        // These set* extensions inject the owners into the view-tree so that
        // Compose's LocalLifecycleOwner / LocalViewModelStoreOwner etc. resolve correctly.
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        setContentView(composeView)
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun dismiss() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        vmStore.clear()
        super.dismiss()
    }
}
