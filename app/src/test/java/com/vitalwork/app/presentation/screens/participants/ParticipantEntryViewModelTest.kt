package com.vitalwork.app.presentation.screens.participants

import com.vitalwork.app.data.db.FakeParticipantDao
import com.vitalwork.app.data.db.FakeScenarioDao
import com.vitalwork.app.data.db.FakeSensorSampleDao
import com.vitalwork.app.data.db.FakeSessionDao
import com.vitalwork.app.data.db.SessionEntity
import com.vitalwork.app.data.db.SessionStatus
import com.vitalwork.app.data.prefs.FakeSettingsRepository
import com.vitalwork.app.data.repository.ParticipantRepository
import com.vitalwork.app.data.repository.SessionRepository
import com.vitalwork.app.data.time.TimeProvider
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
        participantRepository = ParticipantRepository(participantDao, FakeSettingsRepository("A"), TimeProvider.system())
        sessionRepository = SessionRepository(
            sessionDao,
            scenarioDao,
            sampleDao,
            FakeSettingsRepository("A"),
            TimeProvider.system()
        )
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

        // Code carries a UTC timestamp tail (A-001-yyMMdd-HHmmss) for cross-reinstall uniqueness.
        assertTrue(vm.uiState.value.participantCode.startsWith("A-001-"))
        assertTrue(vm.uiState.value.isInitialized)
    }

    @Test
    fun init_withActiveSession_emitsActiveSessionDetected() = runTest {
        sessionDao.sessions.add(
            SessionEntity(
                id = 5L,
                participantId = 1L,
                sessionCode = "VW-X",
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
    fun submit_invalidAge_setsAgeError() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.onAgeChange("12")
        vm.submit()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.ageError)
    }

    @Test
    fun submit_duplicateGeneratedCode_setsCodeError() = runTest {
        // The generated code carries a UTC timestamp tail, so natural count collisions can't happen;
        // drive the uniqueness safety-net directly by seeding a row with the exact code the VM minted
        // (e.g. a restored backup or another tablet that produced the same stamp).
        val vm = newViewModel()
        advanceUntilIdle()
        val generated = vm.uiState.value.participantCode
        participantRepository.createParticipant(generated)

        vm.submit()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.codeError)
    }

    @Test
    fun submit_validInput_createsParticipantAndSessionAndEmitsStarted() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.onAgeChange("30")
        vm.onGenderChange(GenderOption.MALE)

        vm.submit()
        val event = vm.events.first()
        advanceUntilIdle()

        assertTrue(event is ParticipantEntryEvent.SessionStarted)
        val participant = participantDao.participants.single()
        assertTrue(participant.participantCode.startsWith("A-001-"))
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

        vm.submit()
        val event = vm.events.first()
        advanceUntilIdle()

        assertTrue(event is ParticipantEntryEvent.SessionStarted)
        assertNull(participantDao.participants.single().age)
    }
}
