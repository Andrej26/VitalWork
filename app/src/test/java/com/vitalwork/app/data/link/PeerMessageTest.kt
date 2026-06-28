package com.vitalwork.app.data.link

import com.vitalwork.app.data.link.model.PeerMessage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the wire format of the peer link: a [PeerMessage] must survive an encode→decode round-trip
 * unchanged, so both devices agree on the JSON exchanged over the WebSocket.
 */
class PeerMessageTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `round-trips all fields`() {
        val original = PeerMessage(type = "log", text = "Test message #3", ts = 1_718_700_000_000L)

        val encoded = json.encodeToString(PeerMessage.serializer(), original)
        val decoded = json.decodeFromString(PeerMessage.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `tolerates unknown keys from a newer peer`() {
        val withExtra = """{"type":"hello","text":"hi","ts":42,"futureField":"ignored"}"""

        val decoded = json.decodeFromString(PeerMessage.serializer(), withExtra)

        assertEquals(PeerMessage(type = "hello", text = "hi", ts = 42L), decoded)
    }

    @Test
    fun `signaling offer round-trips its sdp`() {
        val original = PeerMessage(type = "offer", sdp = "v=0\r\no=- 123 2 IN IP4 0.0.0.0\r\n")

        val decoded = json.decodeFromString(
            PeerMessage.serializer(),
            json.encodeToString(PeerMessage.serializer(), original)
        )

        assertEquals(original, decoded)
        assertEquals("offer", decoded.type)
        assertEquals(original.sdp, decoded.sdp)
    }

    @Test
    fun `signaling ice round-trips candidate fields`() {
        val original = PeerMessage(
            type = "ice",
            sdpMid = "0",
            sdpMLineIndex = 0,
            candidate = "candidate:1 1 udp 2122260223 192.168.1.5 54321 typ host"
        )

        val decoded = json.decodeFromString(
            PeerMessage.serializer(),
            json.encodeToString(PeerMessage.serializer(), original)
        )

        assertEquals(original, decoded)
    }
}
