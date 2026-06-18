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
}
