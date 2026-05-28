package com.biometrix.operator.presentation.screens.participants

import com.biometrix.operator.data.db.FakeParticipantDao
import com.biometrix.operator.data.db.FakeScenarioDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.FakeSessionDao
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.db.SessionStatus
import com.biometrix.operator.data.repository.ParticipantRepository
import com.biometrix.operator.data.repository.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ParticipantEntryViewModelTest {

    private lateinit var participantDao: FakeParticipantDao
    private lateinit var sessionDao: FakeSessionDao
    private lateinit var scenarioDao: FakeScenarioDao
    private lateinit var sampleDao: FakeSensorSampleDao
    private lateinit var participantRepository: ParticipantRepository
    private lateinit var sessionRepository: SessionRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        participantDao = FakeParticipantDao()
        sessionDao = FakeSessionDao()
        scenarioDao = FakeScenarioDao()
        sampleDao = FakeSensorSampleDao()
        participantRepository = ParticipantRepository(participantDao)
        sessionRepository = SessionRepository(sessionDao, scenarioDao, sampleDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() =
        ParticipantEntryViewModel(participantRepository, sessionRepository)

    @Test
    fun init_suggestsNextParticipantCode() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals("P-001", vm.uiState.value.participantCode)
        assertTrue(vm.uiState.value.isInitialized)
    }

    @Test
    fun init_withActiveSession_emitsActiveSessionDetected() = runTest {
        sessionDao.sessions.add(
            SessionEntity(
                id = 5L,
                participantId = 1L,
                sessionCode = "BMX-X",
                startedAt = 0L,
                status = SessionStatus.ACTIVE
            )
        )

        val vm = newViewModel()
        val event = vm.events.first()
        advanceUntilIdle()

        assertTrue(event is ParticipantEntryEvent.ActiveSessionDetected)
        assertEquals(5L, (event as ParticipantEntryEvent.ActiveSessionDetected).sessionId)
    }

    @Test
    fun submit_emptyCode_setsCodeError() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.onCodeChange("   ")
        vm.submit()
        advanceUntilIdle()

        assertEquals("Participant code is required", vm.uiState.value.codeError)
    }

    @Test
    fun submit_invalidAge_setsAgeError() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.onCodeChange("P-100")
        vm.onAgeChange("12")
        vm.submit()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.ageError)
    }

    @Test
    fun submit_duplicateCode_setsCodeError() = runTest {
        participantRepository.createParticipant("P-007")

        val vm = newViewModel()
        advanceUntilIdle()

        vm.onCodeChange("P-007")
        vm.submit()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.codeError)
    }

    @Test
    fun submit_validInput_createsParticipantAndSessionAndEmitsStarted() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.onCodeChange("P-100")
        vm.onAgeChange("30")
        vm.onGenderChange(GenderOption.MALE)

        vm.submit()
        val event = vm.events.first()
        advanceUntilIdle()

        assertTrue(event is ParticipantEntryEvent.SessionStarted)
        val participant = participantDao.participants.single()
        assertEquals("P-100", participant.participantCode)
        assertEquals(30, participant.age)
        assertEquals("M", participant.gender)

        val session = sessionDao.sessions.single()
        assertEquals(participant.id, session.participantId)
        assertEquals(SessionStatus.ACTIVE, session.status)
        assertEquals(session.id, (event as ParticipantEntryEvent.SessionStarted).sessionId)
    }

    @Test
    fun submit_emptyAge_isOptional() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.onCodeChange("P-100")
        vm.submit()
        val event = vm.events.first()
        advanceUntilIdle()

        assertTrue(event is ParticipantEntryEvent.SessionStarted)
        assertNull(participantDao.participants.single().age)
    }
}
