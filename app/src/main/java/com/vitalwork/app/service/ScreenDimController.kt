package com.vitalwork.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Keeps the **sharer's** display rendering while it appears off, so screen-share (MediaProjection)
 * keeps producing frames even when the phone looks asleep.
 *
 * Android stops compositing a display that is *truly powered off*, so a powered-off screen yields a
 * black/frozen capture — there is no API to capture an off display. The workaround is to keep the
 * display technically **on** while making it *look* off:
 *
 * 1. A [PowerManager.SCREEN_DIM_WAKE_LOCK] holds the screen on regardless of which app is in the
 *    foreground (a window `KEEP_SCREEN_ON` flag would only work while our own activity is visible).
 * 2. The backlight is dimmed to near-zero via [Settings.System.SCREEN_BRIGHTNESS]. Backlight level
 *    is a panel property and does **not** affect the captured framebuffer, so the operator still
 *    sees full-brightness content while the physical panel looks black and draws little power.
 *
 * Step 2 needs the `WRITE_SETTINGS` special access ([canDim]). Without it we still keep the screen on
 * (step 1), just not dark. The original brightness + mode are saved and restored on [disable].
 *
 * Screen wake locks are deprecated but remain the only mechanism to keep a non-foreground display on;
 * the lock is released deterministically in [disable] (and the owning service's `onDestroy`).
 */
class ScreenDimController(private val appContext: Context) {

    private var wakeLock: PowerManager.WakeLock? = null
    private var savedBrightness: Int? = null
    private var savedMode: Int? = null

    @SuppressLint("WakelockTimeout") // held only for the duration of a share; released in disable()
    @Suppress("DEPRECATION") // screen wake locks are the only way to keep a non-foreground display on
    fun enable() {
        if (wakeLock == null) {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                WAKE_LOCK_TAG
            )?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        }
        applyDimIfAllowed()
    }

    fun disable() {
        restoreBrightness()
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    private fun applyDimIfAllowed() {
        if (!canDim(appContext)) return
        val resolver = appContext.contentResolver
        try {
            // Save the user's brightness once (first enable) so repeated enables don't clobber it.
            if (savedBrightness == null) {
                savedMode = Settings.System.getInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                savedBrightness = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            }
            // Manual mode is required for an explicit brightness to stick (auto-brightness overrides it).
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, MIN_BRIGHTNESS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dim screen", e)
        }
    }

    private fun restoreBrightness() {
        if (savedBrightness == null && savedMode == null) return
        if (!canDim(appContext)) return
        val resolver = appContext.contentResolver
        try {
            savedMode?.let { Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, it) }
            savedBrightness?.let { Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore brightness", e)
        } finally {
            savedBrightness = null
            savedMode = null
        }
    }

    companion object {
        private const val TAG = "ScreenDimController"
        private const val WAKE_LOCK_TAG = "VitalWork:ScreenShareKeepOn"
        // 0 fully blanks some panels (looks like a hard power-off); 1 is reliably near-black yet lit.
        private const val MIN_BRIGHTNESS = 1

        /** True if WRITE_SETTINGS is granted, so the backlight can be dimmed. */
        fun canDim(context: Context): Boolean = Settings.System.canWrite(context)

        /** Opens the system "Modify system settings" page so the user can grant WRITE_SETTINGS. */
        fun openWriteSettings(context: Context): Boolean {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
