package com.offlineinc.dumbdownlauncher.contactsync

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.offlineinc.dumbdownlauncher.contactsync.ui.ContactSyncNav
import com.offlineinc.dumbdownlauncher.contactsync.ui.theme.DumbContactSyncTheme

/**
 * Hosts the Contact Sync UI within the launcher app.
 * Replaces the standalone com.offlineinc.dumbcontactsync app.
 */
class ContactSyncActivity : AppCompatActivity() {
    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF000000.toInt()

        setContent {
            DumbContactSyncTheme(darkTheme = true) {
                Scaffold(modifier = Modifier.fillMaxSize()) {
                    ContactSyncNav()
                }
            }
        }
    }
}
