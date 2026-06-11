package com.biometrix.operator.data.vr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single app-scoped owner of the VR link: the discovery listener, the HTTP server, **and** the
 * pairing lifecycle. The connection stays up wherever the operator navigates — not just while the
 * VR Control screen is on-screen — and a session reuses whatever is already connected instead of
 * re-pairing.
 *
 * Why this exists / what it replaced:
 *  - VR Control used to acquire the link on enter and release it on leave, so going back to Home
 *    silently dropped the headset.
 *  - [com.biometrix.operator.service.SessionRecordingService] used to *independently* acquire the
 *    link and call [VrPairingManager.onSessionStart] (which clears the bond) on every session start.
 *    With a bond already established from VR Control, that wiped it — the headset's next heartbeat
 *    was then rejected (403 not_paired) and the link "dropped" 2–3 s later. Centralising ownership
 *    here makes [start] idempotent, so a session that finds the link already up keeps the bond.
 *
 * [start] is called by both VR Control (when the screen opens) and the session service (when a
 * session begins); the first call wins and the rest are no-ops. [stop] (the operator's Stop button)
 * is the only thing that tears the link down — it clears the bond and resets liveness via
 * [VrEventReceiver.onLinkStopped] so heartbeats ceasing afterwards don't trip a false "lost" warning.
 *
 * While active it owns a pairing observer that used to live in the session service, so it now runs
 * whenever the link is up (including VR Control with no session): on
 * [VrPairingManager.PairingState.BONDED] it replies to the Quest with this tablet's address.
 *
 * Heartbeat loss deliberately does **not** drop the bond — a sleeping headset is just temporarily
 * quiet, so the bond is kept and the Quest auto-reconnects when its heartbeats resume (see
 * [startObservers]). Only [stop] clears the bond.
 */
@Singleton
class VrLinkManager @Inject constructor(
    private val discoveryListener: VrDiscoveryListener,
    private val httpServer: VrHttpServer,
    private val pairingManager: VrPairingManager,
    private val eventReceiver: VrEventReceiver,
    private val linkLog: VrLinkLog
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _active = MutableStateFlow(false)
    /** Whether the VR link is currently running. Drives the Start/Stop button. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var observersJob: Job? = null

    /**
     * Start the link and keep it running across every screen and across sessions. Idempotent: a
     * second call while already active is a no-op, so re-entering VR Control or starting a session
     * neither over-acquires nor resets an existing bond.
     */
    @Synchronized
    fun start() {
        if (_active.value) return
        _active.value = true
        httpServer.acquire()
        discoveryListener.acquire()
        startObservers()
        linkLog.add(VrLinkLog.Level.INFO, "VR link started — listening for a headset")
    }

    /**
     * Operator tapped Stop: cleanly tear the link down. Clearing the bond and resetting liveness
     * first means the headset quitting afterwards won't trip the "connection lost" warning.
     */
    @Synchronized
    fun stop() {
        if (!_active.value) return
        _active.value = false
        observersJob?.cancel()
        observersJob = null
        // Clear the bond and reset liveness BEFORE releasing, so no late heartbeat watchdog tick
        // reports a loss for a link the operator deliberately ended.
        pairingManager.clearBond()
        eventReceiver.onLinkStopped()
        discoveryListener.release()
        httpServer.release()
        linkLog.add(VrLinkLog.Level.INFO, "VR link stopped by operator")
    }

    private fun startObservers() {
        if (observersJob != null) return
        observersJob = scope.launch {
            // When the operator bonds (here or from VR Control), reply to the Quest with this
            // tablet's address so it learns where to POST — re-sent on each fresh bond.
            launch {
                pairingManager.pairingState.collect { state ->
                    if (state == VrPairingManager.PairingState.BONDED) {
                        discoveryListener.sendBondReply()
                    }
                }
            }
            // NOTE: heartbeat loss intentionally does NOT drop the bond. A sleeping/dozing headset
            // just goes quiet for a while; tearing the bond down would make the tablet reject the
            // Quest's POSTs (403 not_paired) when it wakes and force a manual re-pair. Keeping the
            // bond lets the same Quest (same IP) auto-reconnect the moment its heartbeats resume —
            // the watchdog only flips the UI to "reconnecting" in the meantime. The bond is cleared
            // solely by the operator's Stop ([clearBond]). The discovery listener stays running, so
            // if the Quest re-broadcasts on wake the self-heal reply re-sends our address.
        }
    }
}
