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
    private val networkChecker: NetworkChecker
) {
    private companion object {
        const val DISCOVERY_PORT = 8889
        const val SERVICE_NAME = "biometrix-vr"
        const val REPLY_SERVICE_NAME = "biometrix-vr-tablet"
        const val HTTP_PORT = 8080
        const val PROTOCOL_VERSION = 1
        const val MULTICAST_LOCK_TAG = "BioMetrix:VrDiscovery"
        const val BUFFER_SIZE = 1024
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

    /** Address the bonded/candidate Quest broadcast from — where a pairing reply is sent. */
    @Volatile private var lastSender: InetSocketAddress? = null

    @Synchronized
    fun start() {
        if (loopJob?.isActive == true) return
        acquireMulticastLock()
        loopJob = scope.launch {
            DatagramSocket(DISCOVERY_PORT).use { socket ->
                socket.broadcast = true
                val buffer = ByteArray(BUFFER_SIZE)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching {
                        socket.receive(packet)
                        val text = String(packet.data, packet.offset, packet.length)
                        val claim = json.decodeFromString<ClaimPacket>(text)
                        val ip = packet.address.hostAddress
                        if (claim.service == SERVICE_NAME && claim.questId.isNotBlank() && ip != null) {
                            lastSender = InetSocketAddress(packet.address, packet.port)
                            pairingManager.onClaim(claim.questId, ip, claim.label.ifBlank { null })
                            // Self-heal: if we're already bonded to this Quest, re-send the reply
                            // (covers a lost first reply — the Quest keeps broadcasting until it
                            // receives one).
                            if (pairingManager.isBondedTo(claim.questId)) {
                                sendReply(InetSocketAddress(packet.address, packet.port))
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
        val tabletIp = networkChecker.localIpv4() ?: return
        runCatching {
            val payload = json.encodeToString(ReplyPacket(tabletIp = tabletIp)).toByteArray()
            DatagramSocket().use { sender ->
                sender.send(DatagramPacket(payload, payload.size, target.address, target.port))
            }
        }
    }

    @Synchronized
    fun stop() {
        loopJob?.cancel()
        loopJob = null
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
