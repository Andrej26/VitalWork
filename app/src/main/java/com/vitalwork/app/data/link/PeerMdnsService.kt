package com.vitalwork.app.data.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.vitalwork.app.data.link.model.PeerDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mDNS for the device-to-device link, via Android's built-in [NsdManager]. One service handles both
 * roles:
 *
 * - **Server** [register]s the WebSocket service (ported from `CaustrOFFQuest`'s `NsdHelper.java`).
 * - **Client** [startDiscovery]s and resolves peers into [discoveredDevices] (ported from
 *   ClaustrOFFOperator's `MdnsDiscoveryService`, kept minimal — no network-callback auto-restart).
 *
 * A non-reference-counted [WifiManager.MulticastLock] is held while either role is active so the
 * Wi-Fi chipset actually delivers the mDNS multicast packets.
 */
@Singleton
class PeerMdnsService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PeerMdnsService"

        /** Android's register API wants no trailing dot; discover wants one (proven asymmetry). */
        const val SERVICE_TYPE_REGISTER = "_vitalwork._tcp"
        const val SERVICE_TYPE_DISCOVER = "_vitalwork._tcp."
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock = wifiManager.createMulticastLock("VitalWorkPeerMdns").apply {
        setReferenceCounted(false)
    }

    private val _discoveredDevices = MutableStateFlow<List<PeerDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<PeerDevice>> = _discoveredDevices.asStateFlow()

    // --- Register (server) ---
    private var registrationListener: NsdManager.RegistrationListener? = null

    // --- Discover (client) ---
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val pendingResolves = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val isResolving = AtomicBoolean(false)

    /** Advertise a WebSocket server running on [port] under instance name [serviceName]. */
    fun register(serviceName: String, port: Int) {
        if (registrationListener != null) return
        acquireLock()
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE_REGISTER
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
            }

            override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }

            override fun onServiceRegistered(si: NsdServiceInfo) {
                Log.i(TAG, "Service registered: ${si.serviceName}")
            }

            override fun onServiceUnregistered(si: NsdServiceInfo) {
                Log.i(TAG, "Service unregistered: ${si.serviceName}")
            }
        }
        registrationListener = listener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register service", e)
            registrationListener = null
            releaseLockIfIdle()
        }
    }

    fun unregister() {
        val listener = registrationListener ?: return
        registrationListener = null
        try {
            nsdManager.unregisterService(listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister service", e)
        }
        releaseLockIfIdle()
    }

    /** Start browsing for peers; results land in [discoveredDevices]. Idempotent. */
    fun startDiscovery() {
        if (discoveryListener != null) return
        acquireLock()
        _discoveredDevices.value = emptyList()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                discoveryListener = null
                releaseLockIfIdle()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _discoveredDevices.update { current ->
                    current.filter { it.name != serviceInfo.serviceName }
                }
            }
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE_DISCOVER, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            discoveryListener = null
            releaseLockIfIdle()
        }
    }

    fun stopDiscovery() {
        // Always drop the discovered list so a stale peer never lingers after the link ends — even if
        // discovery was already stopped (e.g. it's stopped at connect time, then the link drops).
        _discoveredDevices.value = emptyList()
        pendingResolves.clear()
        val listener = discoveryListener ?: return
        discoveryListener = null
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop discovery", e)
        }
        releaseLockIfIdle()
    }

    // NsdManager resolves one service at a time — serialize via a queue + flag.
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

    @Suppress("DEPRECATION") // resolveService deprecated on API 34+, still works; matches Operator.
    private fun resolveNext(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                isResolving.set(false)
                drainResolveQueue()
            }

            override fun onServiceResolved(si: NsdServiceInfo) {
                // Prefer IPv4 — a resolved IPv6/link-local would need bracketed ws://[..] and is
                // flaky on LAN. Fall back to whatever host was resolved.
                val host = (si.host as? Inet4Address)?.hostAddress ?: si.host?.hostAddress
                if (host != null) {
                    val device = PeerDevice(name = si.serviceName, host = host, port = si.port)
                    _discoveredDevices.update { current ->
                        current.filter { it.name != device.name } + device
                    }
                }
                isResolving.set(false)
                drainResolveQueue()
            }
        })
    }

    private fun acquireLock() {
        if (!multicastLock.isHeld) multicastLock.acquire()
    }

    /** Release the lock only when neither role is active. */
    private fun releaseLockIfIdle() {
        if (registrationListener == null && discoveryListener == null && multicastLock.isHeld) {
            multicastLock.release()
        }
    }
}
