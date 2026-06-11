package com.biometrix.operator.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _lanAvailable = MutableStateFlow(isLanAvailable())
    val lanAvailable: StateFlow<Boolean> = _lanAvailable.asStateFlow()

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { updateLanState() }
            override fun onLost(network: Network) { updateLanState() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { updateLanState() }
        })
    }

    /**
     * Checks if the device has WiFi or Ethernet connectivity (LAN).
     * Does NOT check for internet access — only local network availability,
     * since the VR headset is on the same local network.
     */
    fun isLanAvailable(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun updateLanState() {
        _lanAvailable.value = isLanAvailable()
    }

    /**
     * The device's IPv4 address on the active LAN (Wi-Fi/Ethernet), or null if unavailable.
     * Used by the VR UDP beacon to advertise where the Quest should POST. Read fresh each tick
     * so it survives a DHCP change. Returns the first site-local IPv4 link address on the active
     * network's link properties.
     */
    fun localIpv4(): String? {
        val network = cm.activeNetwork ?: return null
        val linkProps = cm.getLinkProperties(network) ?: return null
        return linkProps.linkAddresses
            .map { it.address }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }
}
