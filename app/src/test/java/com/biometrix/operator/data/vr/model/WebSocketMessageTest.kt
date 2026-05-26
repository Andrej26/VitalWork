package com.biometrix.operator.data.vr.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WebSocketMessageTest {

    private val json = Json

    @Test
    fun decode_minimalSuccessResponse() {
        val raw = """{"type":"connect","success":true}"""
        val msg = json.decodeFromString<ServerMessage>(raw)
        assertEquals("connect", msg.type)
        assertEquals(true, msg.success)
        assertNull(msg.msg)
        assertNull(msg.value)
    }

    @Test
    fun decode_fullResponse() {
        val raw = """{"type":"status","success":true,"msg":"ok","value":42}"""
        val msg = json.decodeFromString<ServerMessage>(raw)
        assertEquals("status", msg.type)
        assertEquals(true, msg.success)
        assertEquals("ok", msg.msg)
        assertEquals(42, msg.value)
    }

    @Test
    fun decode_failureResponse() {
        val raw = """{"type":"connect","success":false,"msg":"connection refused"}"""
        val msg = json.decodeFromString<ServerMessage>(raw)
        assertEquals("connect", msg.type)
        assertEquals(false, msg.success)
        assertEquals("connection refused", msg.msg)
        assertNull(msg.value)
    }

    @Test
    fun encode_decodeRoundTrip_preservesAllFields() {
        val original = ServerMessage(
            type = "status",
            success = true,
            msg = "running",
            value = 7
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ServerMessage>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun encode_decodeRoundTrip_withNullOptionals_preservesNulls() {
        val original = ServerMessage(type = "ping", success = true)
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ServerMessage>(encoded)
        assertEquals(original, decoded)
        assertNull(decoded.msg)
        assertNull(decoded.value)
    }

    @Test
    fun decode_malformedJson_throws() {
        val raw = """{"type":"connect","success":}"""
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<ServerMessage>(raw)
        }
    }

    @Test
    fun decode_missingRequiredField_throws() {
        // `success` is non-nullable and has no default -- omitting it must fail
        val raw = """{"type":"connect"}"""
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<ServerMessage>(raw)
        }
    }

    @Test
    fun decode_unknownExtraField_throwsByDefault() {
        // The production client uses default Json (no ignoreUnknownKeys),
        // so unknown fields fail. This test pins that behavior so a future
        // change to the Json config is intentional, not accidental.
        val raw = """{"type":"connect","success":true,"unexpected":"field"}"""
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<ServerMessage>(raw)
        }
    }
}
