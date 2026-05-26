package com.biometrix.operator.data.vr

import com.biometrix.operator.data.vr.model.DiscoveredVrDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeVrDeviceDiscovery : VrDeviceDiscovery {

    override val discoveredDevices = MutableStateFlow<List<DiscoveredVrDevice>>(emptyList())
    override val isDiscovering = MutableStateFlow(false)
    override val isWifiAvailable = MutableStateFlow(true)

    var startDiscoveryCallCount = 0
        private set
    var stopDiscoveryCallCount = 0
        private set

    override fun startDiscovery() {
        startDiscoveryCallCount++
    }

    override fun stopDiscovery() {
        stopDiscoveryCallCount++
    }
}
