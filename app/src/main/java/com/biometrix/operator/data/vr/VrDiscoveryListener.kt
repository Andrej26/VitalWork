package com.biometrix.operator.data.vr

import android.content.Context
import android.net.wifi.WifiManager
import com.biometrix.operator.data.network.NetworkChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens for the Quest's discovery broadcast on the LAN and, once the operator bonds, **replies
 * directly to the chosen Quest with this tablet's address** so the Quest learns where to POST —
 * fully automatically, no manual IP entry.
 *
 * This **inverts** the old [VrUdpBeacon] model: instead of the tablet shouting its address to the
 * whole subnet, the Quest broadcasts "VR headset looking for a tablet" (~1 Hz) and every tablet
 * listens. Each received claim is handed to [VrPairingManager.onClaim] so the operator can tap
 * **Connect** on exactly one tablet.
 *
 * Pairing reply (the elegant part): when the operator taps Connect, [sendBondReply] sends a single
 * UDP packet back to the Quest's own address (captured from its broadcast) containing this tablet's
 * IP + HTTP port. Only the tablet the operator picked replies, so the Quest automatically learns
 * **which** tablet is its partner and **where** to send HTTP. If that first reply is lost, the
 * Quest keeps broadcasting (until it hears a reply), and the receive loop re-sends the reply on each
 * further broadcast from the bonded Quest — so a dropped UDP packet self-heals.
 *
 * Lifecycle is owned by [com.biometrix.operator.service.SessionRecordingService]: started for the
 * whole ACTIVE session. Receiving broadcast UDP requires a held [WifiManager.MulticastLock] (Android
 * drops broadcast/multicast packets to a dozing radio otherwise) — the `CHANGE_WIFI_MULTICAST_STATE`
 * permission must be declared for the lock to work.
 */
@Singleton
class VrDiscoveryListener @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pairingManager: VrPairingManager,
    private val networkChecker: NetworkChecker,
    private val linkLog: VrLinkLog
) {
    private companion object {
        const val DISCOVERY_PORT = 8889
        // The Quest broadcasts from 8889 but listens for our pairing reply on a *separate* port so
        // it doesn't capture its own broadcasts. Reply must go to this fixed port, not the source
        // port of the received broadcast packet.
        const val REPLY_PORT = 8890
        const val SERVICE_NAME = "biometrix-vr"
        const val REPLY_SERVICE_NAME = "biometrix-vr-tablet"
        const val HTTP_PORT = 8080
        const val PROTOCOL_VERSION = 1
        const val MULTICAST_LOCK_TAG = "BioMetrix:VrDiscovery"
        const val BUFFER_SIZE = 1024
        const val TAG = "VrDiscovery"
    }

    /** What the Quest broadcasts to find tablets. [label] is an optional human-readable name. */
    @Serializable
    private data class ClaimPacket(
        val service: String = "",
        val questId: String = "",
        val label: String = "",
        val version: Int = 0
    )

    /** What this tablet replies with so the Quest learns where to POST. */
    @Serializable
    private data class ReplyPacket(
        val service: String = REPLY_SERVICE_NAME,
        val tabletIp: String,
        val httpPort: Int = HTTP_PORT,
        val version: Int = PROTOCOL_VERSION
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * The bound discovery socket, held so the teardown can close it synchronously and free port 8889
     * immediately — cancelling [loopJob] alone closes the socket only when the coroutine's `use{}`
     * unwinds, which races a subsequent start and is what caused the EADDRINUSE crash.
     */
    @Volatile private var socket: DatagramSocket? = null

    /**
     * How many holders currently need the listener running. Both [SessionRecordingService] and the
     * VR Control screen independently [acquire]/[release]; the socket only actually starts on the
     * first acquire and stops on the last release. This lets the screen run discovery for testing
     * without a session, while a session holding its own reference can't have its listener torn down
     * when the operator leaves the screen. Guarded by `this` (all mutators are `@Synchronized`).
     */
    private var holderCount = 0

    /** Address the bonded/candidate Quest broadcast from — where a pairing reply is sent. */
    @Volatile private var lastSender: InetSocketAddress? = null

    /** Register a holder; starts the listener on the first one. Balanced by [release]. */
    @Synchronized
    fun acquire() {
        holderCount++
        if (holderCount == 1) start()
    }

    /** Drop a holder; stops the listener on the last one. Extra releases are a no-op (never < 0). */
    @Synchronized
    fun release() {
        if (holderCount == 0) return
        holderCount--
        if (holderCount == 0) stop()
    }

    private fun start() {
        if (loopJob?.isActive == true) return
        acquireMulticastLock()
        loopJob = scope.launch {
            // Binding 8889 can fail (EADDRINUSE) if a previous listener's socket is still closing
            // after a stop()/start() churn or a service restart — the cancellation that closes the
            // old socket is cooperative and races the rebind. SO_REUSEADDR lets the new socket bind
            // through that window; if the bind still fails for any reason, degrade gracefully instead
            // of crashing the app (an unbound discovery socket just means no pairing this attempt).
            val boundSocket = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
            }.onFailure {
                android.util.Log.e(TAG, "bind $DISCOVERY_PORT failed", it)
                linkLog.add(
                    VrLinkLog.Level.ERROR,
                    "Discovery socket bind failed on port $DISCOVERY_PORT — pairing unavailable"
                )
            }.getOrNull() ?: return@launch
            socket = boundSocket
            android.util.Log.i(TAG, "listening on $DISCOVERY_PORT (multicastLock=${multicastLock?.isHeld})")

            boundSocket.use {
                boundSocket.broadcast = true
                val buffer = ByteArray(BUFFER_SIZE)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching {
                        boundSocket.receive(packet)
                        val text = String(packet.data, packet.offset, packet.length)
                        android.util.Log.d(TAG, "rx ${packet.length}B from ${packet.address.hostAddress}:${packet.port}: $text")
                        val claim = json.decodeFromString<ClaimPacket>(text)
                        val ip = packet.address.hostAddress
                        android.util.Log.d(TAG, "parsed service='${claim.service}' questId='${claim.questId}' (expect service='$SERVICE_NAME')")
                        if (claim.service == SERVICE_NAME && ip != null) {
                            // The Quest doesn't generate a QuestID yet (coming in a later build), so
                            // a blank id is expected for now — fall back to the source IP as the
                            // pairing identity. Once real QuestIDs arrive this transparently switches
                            // back to using them.
                            val questId = claim.questId.ifBlank { ip }
                            // Reply goes to the Quest's address but on its dedicated reply port, not
                            // the source port of this broadcast.
                            val replyTarget = InetSocketAddress(packet.address, REPLY_PORT)
                            lastSender = replyTarget
                            pairingManager.onClaim(questId, ip, claim.label.ifBlank { null })
                            // Self-heal: if we're already bonded to this Quest, re-send the reply
                            // (covers a lost first reply — the Quest keeps broadcasting until it
                            // receives one).
                            if (pairingManager.isBondedTo(questId)) {
                                sendReply(replyTarget)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Called when the operator taps Connect (bond established). Sends this tablet's address to the
     * Quest so it can start POSTing. Safe to call off the main thread (dispatches to IO).
     */
    fun sendBondReply() {
        val target = lastSender ?: return
        scope.launch { sendReply(target) }
    }

    private fun sendReply(target: InetSocketAddress) {
        val tabletIp = networkChecker.localIpv4()
        if (tabletIp == null) {
            android.util.Log.w(TAG, "sendReply skipped: localIpv4()=null")
            return
        }
        val payload = json.encodeToString(ReplyPacket(tabletIp = tabletIp)).toByteArray()
        runCatching {
            DatagramSocket().use { sender ->
                sender.send(DatagramPacket(payload, payload.size, target.address, target.port))
            }
        }.onSuccess {
            android.util.Log.d(TAG, "reply sent to ${target.address.hostAddress}:${target.port} -> $tabletIp:$HTTP_PORT")
            linkLog.add(
                VrLinkLog.Level.INFO,
                "Sent tablet address $tabletIp:$HTTP_PORT to headset at ${target.address.hostAddress}"
            )
        }.onFailure {
            android.util.Log.e(TAG, "reply send failed to ${target.address.hostAddress}:${target.port}", it)
            linkLog.add(
                VrLinkLog.Level.ERROR,
                "Failed to send tablet address to ${target.address.hostAddress} — ${it.message ?: it.javaClass.simpleName}"
            )
        }
    }

    private fun stop() {
        loopJob?.cancel()
        loopJob = null
        // Close the socket synchronously so port 8889 is released immediately; otherwise it stays
        // bound until the cancelled coroutine's use{} unwinds, which races a subsequent start().
        socket?.close()
        socket = null
        lastSender = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return
        // The lock is required to receive broadcast UDP; degrade gracefully if it can't be acquired.
        runCatching {
            multicastLock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }
}
