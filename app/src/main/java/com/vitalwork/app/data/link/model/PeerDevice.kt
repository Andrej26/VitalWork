package com.vitalwork.app.data.link.model

/**
 * A peer device discovered on the local network via mDNS (NsdManager).
 *
 * Mirrors ClaustrOFFOperator's `DiscoveredVrDevice`: the resolved name + IPv4 host + advertised
 * port are everything the client needs to open `ws://host:port`.
 */
data class PeerDevice(
    val name: String, // mDNS service instance name, e.g. "VitalWork-Pixel7"
    val host: String, // resolved IPv4 address, e.g. "192.168.1.42"
    val port: Int     // port the WebSocket server is listening on
)
