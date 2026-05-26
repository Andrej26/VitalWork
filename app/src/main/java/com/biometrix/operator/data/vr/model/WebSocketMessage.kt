package com.biometrix.operator.data.vr.model

import kotlinx.serialization.Serializable

@Serializable
data class ServerMessage(
    val type: String,
    val success: Boolean,
    val msg: String? = null,
    val value: Int? = null
)

sealed class WebSocketMessage {
    data class Response(val serverMessage: ServerMessage) : WebSocketMessage()
    data class Event(val serverMessage: ServerMessage) : WebSocketMessage()
    data class Error(val message: String) : WebSocketMessage()
}
