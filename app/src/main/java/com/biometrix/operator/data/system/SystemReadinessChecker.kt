package com.biometrix.operator.data.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The prerequisites a session needs to record reliably while the screen is locked. Each can be
 * granted once and is then remembered by the system — but the OS or an OEM can silently revoke
 * it later (auto-revoke for unused apps, manual change in Settings, an update resetting battery
 * optimization). So readiness is always re-derived from the live system state, never cached.
 */
enum class SessionPrerequisite {
    /** POST_NOTIFICATIONS — needed to show the recording foreground-service notification. */
    NOTIFICATIONS,

    /** Battery-optimization exemption — stops aggressive OEMs killing long locked sessions. */
    BATTERY_OPTIMIZATION,

    /** BLUETOOTH_SCAN/CONNECT (+ location) — needed to scan/connect the eSense Pulse. */
    BLUETOOTH,

    /** RECORD_AUDIO — needed for the eSense Respiration (audio-jack) sensor. */
    MICROPHONE
}

/**
 * Computes, on demand, which [SessionPrerequisite]s are currently *not* satisfied. Always reads
 * the live OS state so a silent revocation is detected on the next check (e.g. when the operator
 * returns to the Home or session screen). Interface + impl mirrors the existing [LocationChecker]
 * pattern, keeping host JVM unit tests free of Android `Context`.
 */
interface SystemReadinessChecker {
    /** Returns the set of prerequisites that need (re-)granting right now. Empty == all good. */
    fun missingPrerequisites(): Set<SessionPrerequisite>

    companion object {
        /**
         * The BLE permissions required at runtime. Kept identical to the array built in
         * SessionControlScreen so readiness and the existing BLE flow never disagree.
         */
        fun requiredBluetoothPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            } else {
                @Suppress("DEPRECATION")
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            }
    }
}

@Singleton
class SystemReadinessCheckerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SystemReadinessChecker {

    override fun missingPrerequisites(): Set<SessionPrerequisite> =
        buildSet {
            if (!isNotificationsGranted()) add(SessionPrerequisite.NOTIFICATIONS)
            if (!isBatteryOptimizationIgnored()) add(SessionPrerequisite.BATTERY_OPTIMIZATION)
            if (!isBluetoothGranted()) add(SessionPrerequisite.BLUETOOTH)
            if (!isMicrophoneGranted()) add(SessionPrerequisite.MICROPHONE)
        }

    private fun isNotificationsGranted(): Boolean {
        // POST_NOTIFICATIONS only exists (and is required) on Android 13+. Below that, posting
        // notifications needs no runtime grant.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return isGranted(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun isBluetoothGranted(): Boolean =
        SystemReadinessChecker.requiredBluetoothPermissions().all { isGranted(it) }

    private fun isMicrophoneGranted(): Boolean = isGranted(Manifest.permission.RECORD_AUDIO)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
