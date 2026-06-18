package com.vitalwork.app.data.link

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress

/**
 * Resolves this device's own LAN IPv4 address.
 *
 * Ported from `CaustrOFFQuest`'s `WebSocketServerManager.GetServerAddress()`: open a UDP socket and
 * "connect" it toward a public address (no packet is actually sent for UDP) so the OS picks the
 * outbound interface, then read back the local endpoint. This reliably returns the Wi-Fi/LAN IP
 * the peer must dial, without enumerating every network interface.
 */
object LanAddress {

    /** Best-effort local IPv4, or `null` if it can't be determined (e.g. no network). */
    fun localIpv4(): String? = try {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress("8.8.8.8", 53))
            (socket.localAddress as? Inet4Address)?.hostAddress
        }
    } catch (_: Exception) {
        null
    }
}
