package com.biometrix.operator.data.vr

import com.biometrix.operator.data.network.NetworkChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Broadcasts the tablet's presence on the LAN so the Quest can discover where to POST, without
 * mDNS (which Unreal can't speak). Sends a small JSON packet to the limited broadcast address
 * every [BROADCAST_INTERVAL_MS]. The IP and active session id are re-read each tick so the packet
 * survives a DHCP change and always advertises the current session.
 *
 * Lifecycle is owned by [com.biometrix.operator.service.SessionRecordingService] (runs only while a
 * session is ACTIVE), so [sessionIdProvider] is expected to return non-null while broadcasting.
 */
@Singleton
class VrUdpBeacon @Inject constructor(
    private val networkChecker: NetworkChecker
) {
    private companion object {
        const val BROADCAST_PORT = 8888
        const val BROADCAST_INTERVAL_MS = 5_000L
        const val HTTP_PORT = 8080
        const val SERVICE_NAME = "biometrix-operator"
        const val PROTOCOL_VERSION = 1
    }

    @Serializable
    private data class BeaconPacket(
        val service: String = SERVICE_NAME,
        val ip: String,
        val httpPort: Int = HTTP_PORT,
        val sessionId: Long?,
        val version: Int = PROTOCOL_VERSION
    )

    private val json = Json { encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    @Synchronized
    fun start(sessionIdProvider: () -> Long?) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                while (isActive) {
                    val ip = networkChecker.localIpv4()
                    if (ip != null) {
                        runCatching {
                            val payload = json.encodeToString(
                                BeaconPacket(ip = ip, sessionId = sessionIdProvider())
                            ).toByteArray()
                            socket.send(
                                DatagramPacket(payload, payload.size, broadcastAddress, BROADCAST_PORT)
                            )
                        }
                    }
                    delay(BROADCAST_INTERVAL_MS)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }
}
