package com.biometrix.operator.presentation.screens.sessions

import androidx.lifecycle.SavedStateHandle
import com.biometrix.operator.data.db.FakeScenarioDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.FakeSessionDao
import com.biometrix.operator.data.db.ScenarioCategory
import com.biometrix.operator.data.db.ScenarioCode
import com.biometrix.operator.data.db.ScenarioEntity
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.db.SessionStatus
import com.biometrix.operator.data.export.SessionUploader
import com.biometrix.operator.data.prefs.FakeSettingsRepository
import com.biometrix.operator.data.repository.ScenarioRepository
import com.biometrix.operator.data.repository.SessionRepository
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
import org.junit.Assert.assertNotNull
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
    private lateinit var exportService: FakeSessionUploader

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
            FakeSettingsRepository("A")
        )
        scenarioRepository = ScenarioRepository(fakeScenarioDao, fakeSampleDao)
        exportService = FakeSessionUploader()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seedSession(status: SessionStatus = SessionStatus.COMPLETED): SessionEntity {
        val session = SessionEntity(
            id = sessionId,
            participantId = 1L,
            sessionCode = "BMX-260101-120000",
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
        // Touch ScenarioCategory to keep enum referenced if needed
        @Suppress("UNUSED_EXPRESSION") ScenarioCategory.A
    }

    private fun newViewModel(): SessionDetailViewModel {
        val handle = SavedStateHandle(mapOf("sessionId" to sessionId))
        return SessionDetailViewModel(sessionRepository, scenarioRepository, exportService, handle)
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
        assertEquals(session, state.session)
        assertEquals(2, state.scenarios.size)
        assertEquals(
            listOf(ScenarioCode.FALLING_PALLET, ScenarioCode.MACHINE_JAM),
            state.scenarios.map { it.scenarioCode }
        )
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
    fun exportSession_success_marksUploadedAndSurfacesPath() = runTest {
        seedSession(SessionStatus.COMPLETED)
        exportService.result = Result.success("Documents/BioMetrix/BMX-260101-120000/export.json")

        val vm = newViewModel()
        advanceUntilIdle()
        vm.exportSession()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isExporting)
        assertEquals(SessionStatus.UPLOADED, state.session?.status)
        assertEquals(SessionStatus.UPLOADED, fakeSessionDao.sessions.single().status)
        assertEquals(
            "Exported to: Documents/BioMetrix/BMX-260101-120000/export.json",
            state.exportResult
        )
    }

    @Test
    fun exportSession_failure_leavesStatusUnchangedAndSurfacesError() = runTest {
        seedSession(SessionStatus.COMPLETED)
        exportService.result = Result.failure(IllegalStateException("disk full"))

        val vm = newViewModel()
        advanceUntilIdle()
        vm.exportSession()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isExporting)
        assertEquals(SessionStatus.COMPLETED, state.session?.status)
        assertEquals(SessionStatus.COMPLETED, fakeSessionDao.sessions.single().status)
        assertEquals("Export failed: disk full", state.exportResult)
    }

    @Test
    fun clearExportResult_resetsToNull() = runTest {
        seedSession()
        exportService.result = Result.success("path")

        val vm = newViewModel()
        advanceUntilIdle()
        vm.exportSession()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.exportResult)

        vm.clearExportResult()
        assertNull(vm.uiState.value.exportResult)
    }

    @Test
    fun deleteSession_removesRowAndInvokesCallback() = runTest {
        seedSession()
        var called = false

        val vm = newViewModel()
        advanceUntilIdle()
        vm.deleteSession { called = true }
        advanceUntilIdle()

        assertTrue(called)
        assertTrue(fakeSessionDao.sessions.isEmpty())
        assertTrue(vm.uiState.value.isDeleting)
    }

    private class FakeSessionUploader : SessionUploader {
        var result: Result<String> = Result.success("")
        override suspend fun upload(sessionId: Long): Result<String> = result
    }
}
