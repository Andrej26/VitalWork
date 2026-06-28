package com.vitalwork.app.data.repository

import com.vitalwork.app.data.db.FakeParticipantDao
import com.vitalwork.app.data.prefs.FakeSettingsRepository
import com.vitalwork.app.data.time.TimeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ParticipantRepositoryTest {

    private lateinit var fakeDao: FakeParticipantDao
    private lateinit var settings: FakeSettingsRepository
    private lateinit var repository: ParticipantRepository

    @Before
    fun setUp() {
        fakeDao = FakeParticipantDao()
        settings = FakeSettingsRepository("A")
        repository = ParticipantRepository(fakeDao, settings, TimeProvider.system())
    }

    // <prefix>-NNN-yyMMdd-HHmmss, e.g. A-001-260620-143022. The timestamp tail is non-deterministic,
    // so tests assert on the stable prefix+counter head plus the timestamp's shape.
    private val codeRegex = Regex("""^([A-Z])-(\d{3})-\d{6}-\d{6}$""")

    private fun assertCode(expectedHead: String, code: String) {
        val match = codeRegex.matchEntire(code)
        assertNotNull("Code '$code' is not in <prefix>-NNN-yyMMdd-HHmmss format", match)
        assertEquals(expectedHead, "${match!!.groupValues[1]}-${match.groupValues[2]}")
    }

    @Test
    fun generateNextParticipantCode_startsAtA001() = runTest {
        assertCode("A-001", repository.generateNextParticipantCode())
    }

    @Test
    fun generateNextParticipantCode_incrementsAfterInsert() = runTest {
        repository.createParticipant("A-001-260620-143022", age = 30, gender = "M")
        assertCode("A-002", repository.generateNextParticipantCode())
    }

    @Test
    fun generateNextParticipantCode_usesConfiguredPrefix() = runTest {
        settings.prefix = "B"
        assertCode("B-001", repository.generateNextParticipantCode())
    }

    @Test
    fun generateNextParticipantCode_countsPerPrefix() = runTest {
        // An A-prefixed row must not bump the count for prefix B.
        repository.createParticipant("A-001-260620-143022")
        settings.prefix = "B"
        assertCode("B-001", repository.generateNextParticipantCode())
    }

    @Test
    fun generateNextParticipantCode_tokenIsUtcNotDeviceLocal() = runTest {
        // 2026-06-20T14:04:12.860Z — the true-UTC instant from the test export. In any zone east of
        // UTC (e.g. CEST, where this app runs) device-local would render a different hour, so a token
        // ending in 140412 proves the clock is read as UTC, not the device zone.
        val fixedUtc = 1781964252860L
        val repo = ParticipantRepository(fakeDao, settings, TimeProvider { fixedUtc })
        val code = repo.generateNextParticipantCode()
        assertTrue("Code '$code' should carry the UTC token 260620-140412", code.endsWith("260620-140412"))
    }

    @Test
    fun createParticipant_returnsRowWithId() = runTest {
        val p = repository.createParticipant("A-007", age = 25, gender = "F")
        assertEquals("A-007", p.participantCode)
        assertEquals(25, p.age)
        assertEquals("F", p.gender)
        assertNotNull(repository.getParticipantById(p.id))
    }

    @Test
    fun createParticipant_duplicateCode_throws() = runTest {
        repository.createParticipant("A-007")
        try {
            repository.createParticipant("A-007")
            fail("Expected duplicate insert to throw")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun getParticipantByCode_returnsMatch() = runTest {
        val p = repository.createParticipant("A-042", age = 40)
        assertEquals(p.id, repository.getParticipantByCode("A-042")?.id)
    }

    @Test
    fun getParticipantByCode_returnsNullForMissing() = runTest {
        assertNull(repository.getParticipantByCode("A-NOPE"))
    }
}
