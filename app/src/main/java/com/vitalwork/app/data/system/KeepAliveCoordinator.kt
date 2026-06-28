package com.vitalwork.app.data.system

import com.vitalwork.app.data.db.SessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Why the app must stay alive in the background. Add a value per new long-lived connection. */
enum class KeepAliveReason { SESSION, LINK, SCREEN_SHARE }

/**
 * The single source of truth for app-wide backgrounding. Subsystems [acquire]/[release] a
 * [KeepAliveReason]; while the set is non-empty the one [com.vitalwork.app.service.BackgroundConnectionService]
 * runs (started on the empty→non-empty edge) and keeps the whole process + its sockets alive with a
 * foreground notification and a Wi-Fi lock. The service self-stops when the set drains.
 *
 * The SESSION reason is driven here (app-wide, screen-independent) by observing the active session;
 * the LINK reason is acquired/released by [com.vitalwork.app.data.link.PeerLinkManager].
 */
@Singleton
class KeepAliveCoordinator @Inject constructor(
    private val launcher: ForegroundServiceLauncher,
    @Named("activeSession") private val activeSession: Flow<SessionEntity?>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeReasons = MutableStateFlow<Set<KeepAliveReason>>(emptySet())
    val activeReasons: StateFlow<Set<KeepAliveReason>> = _activeReasons.asStateFlow()

    private var started = false

    @Synchronized
    fun acquire(reason: KeepAliveReason) {
        val current = _activeReasons.value
        if (reason in current) return
        _activeReasons.value = current + reason
        // Empty → non-empty: bring up the foreground service.
        if (current.isEmpty()) launcher.startBackgroundService()
    }

    @Synchronized
    fun release(reason: KeepAliveReason) {
        val current = _activeReasons.value
        if (reason !in current) return
        _activeReasons.value = current - reason
        // The running service observes activeReasons and self-stops when it drains.
    }

    /**
     * Begin driving the SESSION reason from the active-session flow. Called once from
     * [com.vitalwork.app.VitalWorkApplication.onCreate] so it's live from process start (an ACTIVE
     * session surviving a process restart re-acquires SESSION). Idempotent.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            activeSession.collect { session ->
                if (session != null) acquire(KeepAliveReason.SESSION)
                else release(KeepAliveReason.SESSION)
            }
        }
    }
}
