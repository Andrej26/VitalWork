package com.biometrix.operator.data.vr

import android.content.Context
import android.net.wifi.WifiManager
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens for the Quest's discovery broadcast on the LAN. This **inverts** the old [VrUdpBeacon]
 * model: instead of the tablet shouting its address to the whole subnet, the Quest broadcasts
 * "VR headset looking for a tablet" (~1 Hz) and every tablet listens. Each received claim is handed
 * to [VrPairingManager.onClaim] so the operator can tap **Connect** on exactly one tablet.
 *
 * Lifecycle is owned by [com.biometrix.operator.service.SessionRecordingService]: started while
 * UNPAIRED, stopped once BONDED, restarted on re-arm. Receiving broadcast UDP requires a held
 * [WifiManager.MulticastLock] (Android drops broadcast/multicast packets to a dozing radio
 * otherwise) — the `CHANGE_WIFI_MULTICAST_STATE` permission must be declared for the lock to work.
 */
@Singleton
class VrDiscoveryListener @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pairingManager: VrPairingManager
) {
    private companion object {
        const val DISCOVERY_PORT = 8889
        const val SERVICE_NAME = "biometrix-vr"
        const val MULTICAST_LOCK_TAG = "BioMetrix:VrDiscovery"
        const val BUFFER_SIZE = 1024
    }

    @Serializable
    private data class ClaimPacket(
        val service: String = "",
        val questId: String = "",
        val version: Int = 0
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

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
                        if (claim.service == SERVICE_NAME && claim.questId.isNotBlank()) {
                            pairingManager.onClaim(claim.questId, packet.address.hostAddress ?: return@runCatching)
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        loopJob?.cancel()
        loopJob = null
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
