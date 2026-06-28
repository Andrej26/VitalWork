package com.vitalwork.app.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerNamingTest {

    @Test
    fun `advertise embeds prefix between base and model`() {
        assertEquals("VitalWork-A-Pixel7", PeerNaming.advertise("A", "Pixel7"))
        assertEquals("VitalWork-B-SM-X200", PeerNaming.advertise("B", "SM-X200"))
    }

    @Test
    fun `prefixOf extracts the single-letter pair prefix`() {
        assertEquals("A", PeerNaming.prefixOf("VitalWork-A-Pixel7"))
        // Model that itself contains hyphens must not confuse the parse.
        assertEquals("B", PeerNaming.prefixOf("VitalWork-B-SM-X200"))
    }

    @Test
    fun `prefixOf tolerates NsdManager collision-rename suffix`() {
        assertEquals("A", PeerNaming.prefixOf("VitalWork-A-Pixel7 (1)"))
    }

    @Test
    fun `prefixOf is null for an un-prefixed legacy name`() {
        assertNull(PeerNaming.prefixOf("VitalWork-Pixel7"))
        assertNull(PeerNaming.prefixOf("SomethingElse-A-Pixel7"))
    }

    @Test
    fun `matchesPrefix is case-insensitive and pair-scoped`() {
        assertTrue(PeerNaming.matchesPrefix("VitalWork-A-Pixel7", "A"))
        assertTrue(PeerNaming.matchesPrefix("VitalWork-a-Pixel7", "A"))
        assertFalse(PeerNaming.matchesPrefix("VitalWork-B-Pixel7", "A"))
        // No embedded prefix → excluded, so it can't leak across pairs.
        assertFalse(PeerNaming.matchesPrefix("VitalWork-Pixel7", "A"))
    }
}
