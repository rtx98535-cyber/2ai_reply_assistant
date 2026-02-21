package com.animus.aireplyassistant.core

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

fun isAccessibilityServiceEnabled(context: Context, service: ComponentName): Boolean {
    val enabled = Settings.Secure.getInt(
        context.contentResolver,
        Settings.Secure.ACCESSIBILITY_ENABLED,
        0,
    ) == 1
    if (!enabled) return false

    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false

    return enabledServices.split(':').any { it.equals(service.flattenToString(), ignoreCase = true) }
}

