package com.vitalwork.app.presentation.screens.sessions

import androidx.lifecycle.SavedStateHandle
import com.vitalwork.app.data.db.FakeScenarioDao
import com.vitalwork.app.data.db.FakeSensorSampleDao
import com.vitalwork.app.data.db.FakeSessionDao
import com.vitalwork.app.data.db.ScenarioCode
import com.vitalwork.app.data.db.ScenarioEntity
import com.vitalwork.app.data.db.SessionEntity
import com.vitalwork.app.data.db.SessionStatus
import com.vitalwork.app.data.export.SessionExporter
import com.vitalwork.app.data.export.SessionUploader
import com.vitalwork.app.data.prefs.FakeSettingsRepository
import com.vitalwork.app.data.repository.ScenarioRepository
import com.vitalwork.app.data.repository.SessionRepository
import com.vitalwork.app.data.time.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelTest {

    private lateinit var fakeSessionDao: FakeSessionDao
    private lateinit var fakeScenarioDao: FakeScenarioDao
    private lateinit var fakeSampleDao: FakeSensorSampleDao
    private lateinit var sessionRepository: SessionRepository
    private lateinit var scenarioRepository: ScenarioRepository
    private lateinit var exporter: FakeSessionExporter
    private lateinit var uploader: FakeSessionUploader

    private val sessionId = 1L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fakeSessionDao = FakeSessionDao()
        fakeScenarioDao = FakeScenarioDao()
        fakeSampleDao = FakeSensorSampleDao()
        sessionRepository = SessionRepository(
            fakeSessionDao,
            fakeScenarioDao,
            fakeSampleDao,
            FakeSettingsRepository("A"),
            TimeProvider.system()
        )
        scenarioRepository = ScenarioRepository(fakeScenarioDao, fakeSampleDao, TimeProvider.system())
        exporter = FakeSessionExporter()
        uploader = FakeSessionUploader()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seedSession(status: SessionStatus = SessionStatus.COMPLETED): SessionEntity {
        val session = SessionEntity(
            id = sessionId,
            participantId = 1L,
            sessionCode = "VW-260101-120000",
            startedAt = 1_000L,
            status = status
        )
        fakeSessionDao.sessions.add(session)
        return session
    }

    private fun seedScenario(id: Long, code: ScenarioCode = ScenarioCode.FALLING_PALLET) {
        fakeScenarioDao.scenarios.add(
            ScenarioEntity(
                id = id,
                sessionId = sessionId,
                scenarioCode = code,
                scenarioCategory = code.category,
                startedAt = 2_000L + id
            )
        )
    }

    private fun newViewModel(): SessionDetailViewModel {
        val handle = SavedStateHandle(mapOf("sessionId" to sessionId))
        return SessionDetailViewModel(sessionRepository, scenarioRepository, exporter, uploader, handle)
    }

    @Test
    fun loadSession_populatesStateAndClearsLoading() = runTest {
        val session = seedSession()
        seedScenario(id = 10)
        seedScenario(id = 11, code = ScenarioCode.MACHINE_JAM)

        val vm = newViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(session.sessionCode, state.session?.sessionCode)
        assertEquals(2, state.scenarios.size)
    }

    @Test
    fun loadSession_missing_yieldsNullAndEmptyList() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.session)
        assertTrue(state.scenarios.isEmpty())
    }

    @Test
    fun autoUpload_firesOnLoad_forCompletedSessionWithScenarios_marksUploaded() = runTest {
        seedSession(SessionStatus.COMPLETED)
        seedScenario(id = 10)
        uploader.result = Result.success("Session uploaded.")

        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals(1, uploader.callCount)
        assertEquals(UploadState.Success, vm.uiState.value.uploadState)
        assertEquals(SessionStatus.UPLOADED, fakeSessionDao.sessions.single().status)
    }

    @Test
    fun autoUpload_doesNotFire_whenNoScenarios() = runTest {
        seedSession(SessionStatus.COMPLETED)

        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals(0, uploader.callCount)
        assertEquals(UploadState.Idle, vm.uiState.value.uploadState)
    }

    @Test
    fun autoUpload_doesNotFire_whenAlreadyUploaded() = runTest {
        seedSession(SessionStatus.UPLOADED)
        seedScenario(id = 10)

        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals(0, uploader.callCount)
    }

    @Test
    fun uploadSession_failure_leavesStatusCompleted_andShowsFailedState() = runTest {
        seedSession(SessionStatus.COMPLETED)
        seedScenario(id = 10)
        uploader.result = Result.failure(IllegalStateException("offline"))

        val vm = newViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.uploadState is UploadState.Failed)
        assertEquals("offline", (state.uploadState as UploadState.Failed).message)
        assertEquals(SessionStatus.COMPLETED, fakeSessionDao.sessions.single().status)
    }

    @Test
    fun uploadSession_retryAfterFailure_succeeds_marksUploaded() = runTest {
        seedSession(SessionStatus.COMPLETED)
        seedScenario(id = 10)
        uploader.result = Result.failure(IllegalStateException("offline"))

        val vm = newViewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.uploadState is UploadState.Failed)

        // Retry path (manual button) after network recovers.
        uploader.result = Result.success("ok")
        vm.uploadSession()
        advanceUntilIdle()

        assertEquals(UploadState.Success, vm.uiState.value.uploadState)
        assertEquals(SessionStatus.UPLOADED, fakeSessionDao.sessions.single().status)
    }

    @Test
    fun exportSession_writesFiles_butDoesNotMarkUploaded() = runTest {
        seedSession(SessionStatus.COMPLETED)
        seedScenario(id = 10)
        // Auto-upload would mark UPLOADED, so block it to isolate export behavior.
        uploader.result = Result.failure(IllegalStateException("offline"))
        exporter.result = Result.success("Documents/VitalWork/VW-260101-120000/export.json")

        val vm = newViewModel()
        advanceUntilIdle()

        vm.exportSession()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isExporting)
        assertEquals(
            "Exported to: Documents/VitalWork/VW-260101-120000/export.json",
            state.exportResult
        )
        // Crucially: export does NOT set UPLOADED.
        assertEquals(SessionStatus.COMPLETED, fakeSessionDao.sessions.single().status)
    }

    @Test
    fun dismissUploadDialog_resetsToIdle() = runTest {
        seedSession(SessionStatus.COMPLETED)
        seedScenario(id = 10)
        uploader.result = Result.success("ok")

        val vm = newViewModel()
        advanceUntilIdle()
        assertEquals(UploadState.Success, vm.uiState.value.uploadState)

        vm.dismissUploadDialog()
        assertEquals(UploadState.Idle, vm.uiState.value.uploadState)
    }

    private class FakeSessionExporter : SessionExporter {
        var result: Result<String> = Result.success("")
        override suspend fun exportSession(sessionId: Long): Result<String> = result
    }

    private class FakeSessionUploader : SessionUploader {
        var result: Result<String> = Result.success("")
        var callCount = 0
        override suspend fun upload(sessionId: Long): Result<String> {
            callCount++
            return result
        }
    }
}
