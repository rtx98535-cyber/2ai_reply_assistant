package com.animus.aireplyassistant.core

import android.content.Context
import java.util.UUID

object InstallIdStore {
    private const val PREFS = "ai_reply_assistant_prefs"
    private const val KEY_INSTALL_ID = "install_id"

    fun getOrCreateInstallId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_INSTALL_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }
}

