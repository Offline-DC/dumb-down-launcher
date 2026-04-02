package com.offlineinc.dumbdownlauncher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.ui.graphics.vector.ImageVector
import com.offlineinc.dumbdownlauncher.CONTACT_SYNC
import com.offlineinc.dumbdownlauncher.DEVICE_SETUP
import com.offlineinc.dumbdownlauncher.CHECK_UPDATES
import com.offlineinc.dumbdownlauncher.GOOGLE_MESSAGES
import com.offlineinc.dumbdownlauncher.QUACK
import com.offlineinc.dumbdownlauncher.R

/**
 * Single source of truth for app icon overrides.
 * Used by both the 3×3 grid (MainAppGridCell) and the app list (AppRow).
 * Change an icon here and it updates everywhere.
 */
val appVectorIcons: Map<String, ImageVector> = mapOf(
    // Smart txt
    "com.openbubbles.messaging"       to Icons.Filled.Psychology,
    "com.offline.googlemessageslauncher" to Icons.Filled.Psychology,
    GOOGLE_MESSAGES                   to Icons.Filled.Psychology,
    // SMS
    "com.android.mms"                 to Icons.Filled.Message,
    // Uber
    "com.ubercab.uberlite"            to Icons.Filled.DirectionsCar,
    // Maps
    "com.google.android.apps.mapslite" to Icons.Filled.Map,
    // Contacts
    "com.android.contacts"            to Icons.Filled.Contacts,
    // Call history
    "com.android.dialer"              to Icons.Filled.History,
    // Camera
    "com.tcl.camera"                  to Icons.Filled.CameraAlt,
    // Clock
    "com.tcl.deskclock"               to Icons.Filled.AccessTime,
    "com.android.deskclock"           to Icons.Filled.AccessTime,
    // Settings
    "com.android.settings"            to Icons.Filled.Settings,
    // Special all-apps items
    CONTACT_SYNC                      to Icons.Filled.Settings,
    DEVICE_SETUP                      to Icons.Filled.Link,
    CHECK_UPDATES                     to Icons.Filled.SystemUpdate,
)

/**
 * Drawable-resource icon overrides for apps that need a custom bitmap/vector
 * drawable instead of a tintable Material icon (e.g. the pixel-art duck).
 * Checked in AppRow before [appVectorIcons].
 */
val appDrawableResIcons: Map<String, Int> = mapOf(
    QUACK to R.drawable.ic_duck,
)
