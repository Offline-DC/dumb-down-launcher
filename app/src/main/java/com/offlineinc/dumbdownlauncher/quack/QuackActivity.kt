package com.offlineinc.dumbdownlauncher.quack

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/**
 * Hosts the Quack anonymous message board within the launcher app.
 * Follows the same pattern as ContactSyncActivity.
 */
class QuackActivity : AppCompatActivity() {

    companion object {
        private const val LOC_PERM_REQ = 42
    }

    private lateinit var viewModel: QuackViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF000000.toInt()

        viewModel = ViewModelProvider(this)[QuackViewModel::class.java]

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DumbTheme.Colors.Black)
            ) {
                QuackScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                )
            }
        }

        // Request location to kick things off
        requestLocationPermission()
    }

    private fun requestLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startLocation()
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                LOC_PERM_REQ,
            )
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(req, perms, grants)
        if (req == LOC_PERM_REQ && grants.isNotEmpty() && grants[0] == PackageManager.PERMISSION_GRANTED) {
            viewModel.startLocation()
        } else {
            Toast.makeText(this, "Location permission required for quack", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
