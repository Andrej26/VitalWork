package com.vitalwork.app.data.link.model

import kotlinx.serialization.Serializable

/**
 * The wire envelope exchanged over the peer WebSocket link.
 *
 * Deliberately minimal for this phase (pairing + test logs). When WebRTC signaling lands it can be
 * extended/aligned to the established `{type,success,msg,value}` shape used by the sibling projects.
 *
 * @param type a short tag — `"hello"` for the auto-greeting on connect, `"log"` for a test message.
 * @param text human-readable payload shown in the on-screen log.
 * @param ts   sender's `System.currentTimeMillis()` stamp.
 */
@Serializable
data class PeerMessage(
    val type: String,
    val text: String,
    val ts: Long
)
