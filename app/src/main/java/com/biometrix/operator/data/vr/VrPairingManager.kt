package com.biometrix.operator.data.vr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the VR↔tablet **pairing bond** so that, with two operator stations in one room on the same
 * Wi-Fi, a tablet talks to exactly one Quest and silently rejects everyone else.
 *
 * Discovery is inverted from the old beacon model: the Quest broadcasts "looking for a tablet" and
 * every tablet listens ([VrDiscoveryListener]). When a claim arrives the tablet enters [PairingState.PENDING]
 * and surfaces the candidate; the operator taps **Connect** ([confirm]) to reach [PairingState.BONDED],
 * snapshotting `(questId, sourceIp)`. From then on [isAuthorized] is the single gate every HTTP route
 * checks ([VrHttpServer]) — only requests matching **both** the bonded questId and source IP pass.
 *
 * Lifecycle (owned by [com.biometrix.operator.data.vr.VrLinkManager]):
 *  - [confirm] bonds (operator tapped Connect) → the link replies to the Quest.
 *  - [clearBond] clears any bond when the operator stops the link.
 *
 * Note: the bond deliberately **persists across sessions and across heartbeat loss** now — a
 * sleeping headset is just temporarily quiet, so the same Quest auto-reconnects when its heartbeats
 * resume rather than being forced to re-pair. Starting a session reuses whatever is already
 * connected (see [VrLinkManager]).
 *
 * State is a [StateFlow] with `@Synchronized` mutators: the listener emits claims from
 * `Dispatchers.IO`, [confirm] is called from the UI thread, and the service collects on `Main`.
 */
@Singleton
class VrPairingManager @Inject constructor(
    private val linkLog: VrLinkLog
) {

    enum class PairingState { UNPAIRED, PENDING, BONDED }

    /**
     * The Quest that has claimed (PENDING) or is bonded (BONDED). [label] is an optional
     * human-readable name the operator sees (e.g. "Station Left"); [questId] is the unique id.
     */
    data class VrCandidate(val questId: String, val sourceIp: String, val label: String? = null)

    private val _pairingState = MutableStateFlow(PairingState.UNPAIRED)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    /** The pending/bonded Quest's id + source IP, or null when UNPAIRED. */
    private val _candidate = MutableStateFlow<VrCandidate?>(null)
    val candidate: StateFlow<VrCandidate?> = _candidate.asStateFlow()

    /** A Quest broadcast was received. While UNPAIRED, promotes to PENDING with this candidate. */
    @Synchronized
    fun onClaim(questId: String, sourceIp: String, label: String? = null) {
        val before = _pairingState.value
        when (_pairingState.value) {
            PairingState.UNPAIRED -> {
                _candidate.value = VrCandidate(questId, sourceIp, label)
                _pairingState.value = PairingState.PENDING
                // Only the UNPAIRED→PENDING transition is logged; the headset re-broadcasts ~1 Hz
                // while waiting, and logging each would bury everything else.
                linkLog.add(
                    VrLinkLog.Level.INFO,
                    "Headset found: ${label ?: questId} at $sourceIp — waiting for operator to Connect"
                )
            }
            PairingState.PENDING -> {
                // Refresh the candidate (e.g. the Quest's IP changed before the operator tapped).
                _candidate.value = VrCandidate(questId, sourceIp, label)
            }
            PairingState.BONDED -> {
                // Already bonded; ignore stray broadcasts (incl. from a second Quest in the room).
            }
        }
        android.util.Log.d("VrDiscovery", "onClaim($questId,$sourceIp): $before -> ${_pairingState.value}, candidate=${_candidate.value}")
    }

    /** Operator tapped Connect: bond to the current candidate. No-op if there is none. */
    @Synchronized
    fun confirm() {
        if (_pairingState.value == PairingState.PENDING && _candidate.value != null) {
            _pairingState.value = PairingState.BONDED
            val bonded = _candidate.value
            linkLog.add(
                VrLinkLog.Level.SUCCESS,
                "Operator tapped Connect — bonded to ${bonded?.label ?: bonded?.questId} at ${bonded?.sourceIp}"
            )
        }
    }

    /**
     * The gate every HTTP route checks: true only when BONDED and the request matches the bonded
     * Quest. Normally both id + IP must match. The Quest doesn't send its id yet (no QuestID
     * generation until a later build), so a blank/missing incoming [questId] falls back to IP-only
     * matching — consistent with the discovery side, which bonds using the source IP as the identity
     * when no real id is present. Once real QuestIDs arrive on both the discovery and HTTP sides this
     * transparently tightens back to full id + IP matching.
     */
    @Synchronized
    fun isAuthorized(questId: String?, sourceIp: String?): Boolean {
        if (_pairingState.value != PairingState.BONDED) return false
        val bonded = _candidate.value ?: return false
        if (sourceIp != bonded.sourceIp) return false
        return questId.isNullOrBlank() || questId == bonded.questId
    }

    /** True when bonded to this specific Quest — used to (re)send the pairing reply to it. */
    @Synchronized
    fun isBondedTo(questId: String): Boolean =
        _pairingState.value == PairingState.BONDED && _candidate.value?.questId == questId

    /** Operator stopped the link: clear the bond and return to the unpaired/idle state. */
    @Synchronized
    fun clearBond() {
        _candidate.value = null
        _pairingState.value = PairingState.UNPAIRED
    }
}
