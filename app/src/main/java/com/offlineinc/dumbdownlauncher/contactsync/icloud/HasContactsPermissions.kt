package com.offlineinc.dumbdownlauncher.contactsync.icloud

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

fun hasContactsPermissions(ctx: Context): Boolean {
        val r = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
        val w = ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CONTACTS)
        return r == PackageManager.PERMISSION_GRANTED && w == PackageManager.PERMISSION_GRANTED
}
