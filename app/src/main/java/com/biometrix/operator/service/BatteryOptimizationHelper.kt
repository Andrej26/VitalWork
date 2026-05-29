package com.biometrix.operator.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers for the battery-optimization exemption.
 *
 * A foreground service satisfies stock-Android Doze rules, but aggressive OEM power managers
 * (Samsung, Xiaomi, …) will still kill even a foreground service during a long, screen-locked
 * session — silently ending recording. Exempting the app prevents that. The session-readiness
 * card surfaces this when the exemption is missing and routes the operator here via
 * [openExemptionSettings].
 */
object BatteryOptimizationHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the system exemption request for this app. Returns true if the screen was launched.
     * Falls back to false if no OEM ROM screen handles the intent, so the caller can react.
     */
    @SuppressLint("BatteryLife") // intentional: continuous physiological recording justifies it
    fun openExemptionSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
