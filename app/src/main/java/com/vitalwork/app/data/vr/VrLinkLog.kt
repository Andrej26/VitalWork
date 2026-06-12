package com.vitalwork.app.data.vr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared, in-memory diagnostic log of the whole VR link: discovery broadcasts, the pairing
 * handshake, heartbeats, scenario events, and request rejections. Every VR data-layer component
 * ([VrDiscoveryListener], [VrPairingManager], [VrEventReceiver], [VrHttpServer]) writes the moments
 * it knows about here, and [com.vitalwork.app.presentation.screens.vr.VRConnectionViewModel]
 * renders the result as the VR Control "Event log".
 *
 * Why a [Singleton] and not a per-ViewModel list: the operator needs to see *what happened to the
 * connection* even across leaving and re-entering the screen. The old per-ViewModel list was reset
 * on every visit, so the pairing exchange (which happens right as the screen opens) and any drop
 * that occurred while the screen was closed were lost. Keeping the buffer in a singleton survives
 * navigation; the VR link components are singletons too, so they always write to this same instance.
 *
 * Bounded to [MAX_ENTRIES] newest entries so the steady ~0.2 Hz heartbeat stream can't grow
 * unbounded; that holds roughly a quarter-hour of history, plenty for live diagnosis.
 */
@Singleton
class VrLinkLog @Inject constructor() {

    /** Severity, mapped to a UI tone by the ViewModel. ERROR is what the operator should notice. */
    enum class Level { INFO, SUCCESS, WARNING, ERROR }

    data class Entry(val atMs: Long, val level: Level, val message: String)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    /** Full buffer, oldest-first. The UI reverses it for newest-first display. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun add(level: Level, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, message)
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
