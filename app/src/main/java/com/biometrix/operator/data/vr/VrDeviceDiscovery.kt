package com.biometrix.operator.data.vr

import com.biometrix.operator.data.vr.model.DiscoveredVrDevice
import kotlinx.coroutines.flow.StateFlow

interface VrDeviceDiscovery {
    val discoveredDevices: StateFlow<List<DiscoveredVrDevice>>
    val isDiscovering: StateFlow<Boolean>
    val isWifiAvailable: StateFlow<Boolean>
    fun startDiscovery()
    fun stopDiscovery()
}
