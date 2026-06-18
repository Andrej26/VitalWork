package com.vitalwork.app.data.system

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.vitalwork.app.service.BackgroundConnectionService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts the single app-wide [BackgroundConnectionService]. Pulled behind an interface so
 * [KeepAliveCoordinator]'s reason-set logic stays free of Android `Context` and is host-JVM
 * unit-testable (mirrors the `LocationChecker`/`SystemReadinessChecker` interface+impl convention).
 */
interface ForegroundServiceLauncher {
    fun startBackgroundService()
}

@Singleton
class ForegroundServiceLauncherImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ForegroundServiceLauncher {

    override fun startBackgroundService() {
        // All real acquisitions happen from a foreground user action, so this is normally allowed.
        // Defensive: a background start (e.g. an odd post-process-death path) can throw
        // ForegroundServiceStartNotAllowedException — never let that crash the caller.
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BackgroundConnectionService::class.java)
            )
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService failed", e)
        }
    }

    private companion object {
        const val TAG = "FgServiceLauncher"
    }
}
