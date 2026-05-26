package com.biometrix.operator.data.vr

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.biometrix.operator.data.network.NetworkChecker
import com.biometrix.operator.data.vr.model.DiscoveredVrDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MdnsDiscoveryService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkChecker: NetworkChecker
) : VrDeviceDiscovery {

    companion object {
        private const val SERVICE_TYPE = "_narrowingchamber._tcp."
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock = wifiManager.createMulticastLock("MdnsDiscovery").apply {
        setReferenceCounted(false)
    }

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredVrDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredVrDevice>> = _discoveredDevices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    override val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _isWifiAvailable = MutableStateFlow(false)
    override val isWifiAvailable: StateFlow<Boolean> = _isWifiAvailable.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // NsdManager only supports one active resolve at a time; queue pending requests
    private val pendingResolves = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val isResolving = AtomicBoolean(false)

    // Set to true when startDiscovery() is called but WiFi is unavailable, so we can auto-start when WiFi is restored
    private val pendingDiscovery = AtomicBoolean(false)

    // Tracks currently-available LAN (Wi-Fi/Ethernet) networks to avoid race conditions with
    // getActiveNetwork() inside onLost(). registerNetworkCallback() calls onAvailable() for all
    // currently-matching networks, so this set is populated immediately on registration.
    private val availableLanNetworks = mutableSetOf<Network>()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return
            val isLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isLan) return
            synchronized(availableLanNetworks) { availableLanNetworks.add(network) }
            _isWifiAvailable.value = true
            if (pendingDiscovery.compareAndSet(true, false)) {
                startDiscovery()
            }
        }

        override fun onLost(network: Network) {
            val lanEmpty = synchronized(availableLanNetworks) {
                availableLanNetworks.remove(network)
                availableLanNetworks.isEmpty()
            }
            if (lanEmpty) {
                _isWifiAvailable.value = false
                val wasDiscovering = _isDiscovering.value || discoveryListener != null
                stopDiscovery()
                if (wasDiscovering) {
                    pendingDiscovery.set(true)
                }
            }
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) { /* no-op if registration fails */ }
    }

    override fun startDiscovery() {
        if (_isDiscovering.value) return
        if (!networkChecker.isLanAvailable()) {
            _isWifiAvailable.value = false
            pendingDiscovery.set(true)
            return
        }
        pendingDiscovery.set(false)
        _isWifiAvailable.value = true
        _discoveredDevices.value = emptyList()

        multicastLock.acquire()
        val listener = createDiscoveryListener()
        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            multicastLock.release()
            _isDiscovering.value = false
            discoveryListener = null
        }
    }

    override fun stopDiscovery() {
        pendingDiscovery.set(false)
        val listener = discoveryListener ?: return
        discoveryListener = null
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (_: Exception) {
            _isDiscovering.value = false
        }
        multicastLock.release()
    }

    private fun createDiscoveryListener() = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            _isDiscovering.value = false
            discoveryListener = null
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            _isDiscovering.value = false
        }

        override fun onDiscoveryStarted(serviceType: String) {
            _isDiscovering.value = true
        }

        override fun onDiscoveryStopped(serviceType: String) {
            _isDiscovering.value = false
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            enqueueResolve(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            _discoveredDevices.update { current ->
                current.filter { it.name != serviceInfo.serviceName }
            }
        }
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        pendingResolves.add(serviceInfo)
        drainResolveQueue()
    }

    private fun drainResolveQueue() {
        if (!isResolving.compareAndSet(false, true)) return
        val serviceInfo = pendingResolves.poll() ?: run {
            isResolving.set(false)
            return
        }
        resolveNext(serviceInfo)
    }

    @Suppress("DEPRECATION")
    private fun resolveNext(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isResolving.set(false)
                drainResolveQueue()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress
                if (host != null) {
                    val device = DiscoveredVrDevice(
                        name = serviceInfo.serviceName,
                        host = host,
                        port = serviceInfo.port
                    )
                    _discoveredDevices.update { current ->
                        current.filter { it.name != device.name } + device
                    }
                }
                isResolving.set(false)
                drainResolveQueue()
            }
        })
    }
}
