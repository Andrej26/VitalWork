package com.biometrix.operator.data.vr.model

data class DiscoveredVrDevice(
    val name: String,  // mDNS service name (e.g. "NarrowingChamber")
    val host: String,  // resolved IPv4 address
    val port: Int      // advertised port
)
