package com.biometrix.operator.data.repository

import com.biometrix.operator.data.db.FakeParticipantDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ParticipantRepositoryTest {

    private lateinit var fakeDao: FakeParticipantDao
    private lateinit var repository: ParticipantRepository

    @Before
    fun setUp() {
        fakeDao = FakeParticipantDao()
        repository = ParticipantRepository(fakeDao)
    }

    @Test
    fun generateNextParticipantCode_startsAtP001() = runTest {
        assertEquals("P-001", repository.generateNextParticipantCode())
    }

    @Test
    fun generateNextParticipantCode_incrementsAfterInsert() = runTest {
        repository.createParticipant("P-001", age = 30, gender = "M")
        assertEquals("P-002", repository.generateNextParticipantCode())
    }

    @Test
    fun createParticipant_returnsRowWithId() = runTest {
        val p = repository.createParticipant("P-007", age = 25, gender = "F")
        assertEquals("P-007", p.participantCode)
        assertEquals(25, p.age)
        assertEquals("F", p.gender)
        assertNotNull(repository.getParticipantById(p.id))
    }

    @Test
    fun createParticipant_duplicateCode_throws() = runTest {
        repository.createParticipant("P-007")
        try {
            repository.createParticipant("P-007")
            fail("Expected duplicate insert to throw")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun getParticipantByCode_returnsMatch() = runTest {
        val p = repository.createParticipant("P-042", age = 40)
        assertEquals(p.id, repository.getParticipantByCode("P-042")?.id)
    }

    @Test
    fun getParticipantByCode_returnsNullForMissing() = runTest {
        assertNull(repository.getParticipantByCode("P-NOPE"))
    }
}
