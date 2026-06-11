package com.biometrix.operator.data.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Device-assignment prefixes operators pick from when several tablets test in parallel. */
val DEVICE_PREFIXES = listOf("A", "B", "C", "D")

/** Prefix used out of the box (single-device use needs no Settings visit). */
const val DEFAULT_DEVICE_PREFIX = "A"

/**
 * Per-device operator settings. The device prefix tags both generated participant codes
 * (`A-001`) and session codes (`BMX-A-…`) so multiple tablets testing at the same time never
 * mint colliding codes.
 */
interface SettingsRepository {
    fun getDevicePrefix(): String
    fun setDevicePrefix(value: String)
}

@Singleton
class SharedPrefsSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : SettingsRepository {
    private val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

    override fun getDevicePrefix(): String =
        prefs.getString(KEY_DEVICE_PREFIX, DEFAULT_DEVICE_PREFIX) ?: DEFAULT_DEVICE_PREFIX

    override fun setDevicePrefix(value: String) {
        prefs.edit().putString(KEY_DEVICE_PREFIX, value).apply()
    }

    private companion object {
        const val KEY_DEVICE_PREFIX = "device_prefix"
    }
}
