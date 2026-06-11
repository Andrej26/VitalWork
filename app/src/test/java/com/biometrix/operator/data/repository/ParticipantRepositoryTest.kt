package com.biometrix.operator.data.repository

import com.biometrix.operator.data.db.FakeParticipantDao
import com.biometrix.operator.data.prefs.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        repository = ParticipantRepository(fakeDao, settings)
    }

    @Test
    fun generateNextParticipantCode_startsAtA001() = runTest {
        assertEquals("A-001", repository.generateNextParticipantCode())
    }

    @Test
    fun generateNextParticipantCode_incrementsAfterInsert() = runTest {
        repository.createParticipant("A-001", age = 30, gender = "M")
        assertEquals("A-002", repository.generateNextParticipantCode())
    }

    @Test
    fun generateNextParticipantCode_usesConfiguredPrefix() = runTest {
        settings.prefix = "B"
        assertEquals("B-001", repository.generateNextParticipantCode())
    }

    @Test
    fun generateNextParticipantCode_countsPerPrefix() = runTest {
        // An A-prefixed row must not bump the count for prefix B.
        repository.createParticipant("A-001")
        settings.prefix = "B"
        assertEquals("B-001", repository.generateNextParticipantCode())
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
