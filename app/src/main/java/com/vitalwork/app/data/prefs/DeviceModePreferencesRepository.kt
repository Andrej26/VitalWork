package com.vitalwork.app.data.prefs

import android.content.Context
import com.vitalwork.app.data.link.PeerRole
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which device-link role this device runs as (Server or Client). Chosen once on first
 * launch via the mode-selection screen; persisted so subsequent launches go straight to Home. The
 * operator can change it later from Home. Until a mode is picked, [getMode] returns null and the
 * app shows the selection screen at startup.
 */
@Singleton
class DeviceModePreferencesRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("device_mode_prefs", Context.MODE_PRIVATE)

    fun getMode(): PeerRole? =
        prefs.getString(KEY_DEVICE_MODE, null)?.let { stored ->
            runCatching { PeerRole.valueOf(stored) }.getOrNull()
        }

    fun setMode(role: PeerRole) {
        prefs.edit().putString(KEY_DEVICE_MODE, role.name).apply()
    }

    private companion object {
        const val KEY_DEVICE_MODE = "device_mode"
    }
}
