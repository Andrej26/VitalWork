package com.vitalwork.app.data.link.model

import kotlinx.serialization.Serializable

/**
 * The wire envelope exchanged over the peer WebSocket link — for both pairing/log messages and the
 * WebRTC signaling handshake (the link doubles as the signaling channel).
 *
 * @param type a short tag: `"hello"` (greeting), `"log"` (test message), or a signaling type
 *   (`"request_screen"`, `"offer"`, `"answer"`, `"ice"`, `"stop_screen"`).
 * @param text human-readable payload shown in the on-screen log (empty for signaling).
 * @param ts   sender's `System.currentTimeMillis()` stamp.
 * @param sdp  SDP for `offer`/`answer`.
 * @param sdpMid / sdpMLineIndex / candidate  ICE candidate fields for `ice`.
 *
 * Signaling fields default to null so old `hello`/`log` messages (and their tests) are unaffected;
 * with `encodeDefaults = false` they're omitted from the wire unless set.
 */
@Serializable
data class PeerMessage(
    val type: String,
    val text: String = "",
    val ts: Long = 0,
    val sdp: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val candidate: String? = null
)
