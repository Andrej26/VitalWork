package com.biometrix.operator.data.vr

import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.vr.model.WebSocketMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface VRConnectionManager {
    val connectionState: StateFlow<ConnectionState>
    val messages: SharedFlow<WebSocketMessage>
    val lastError: StateFlow<String?>
    val isReconnecting: StateFlow<Boolean>
    fun connect(ipAddress: String)
    fun disconnect()
    fun suppressAutoReconnect()
    fun sendCommand(command: String, params: Map<String, Any> = emptyMap()): SendResult
}
