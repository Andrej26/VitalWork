package com.biometrix.operator.presentation.screens.sessions

import androidx.lifecycle.SavedStateHandle
import com.biometrix.operator.data.db.FakeRecordingDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.FakeSessionDao
import com.biometrix.operator.data.db.RecordingEntity
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.db.SessionStatus
import com.biometrix.operator.data.export.SessionUploader
import com.biometrix.operator.data.repository.RecordingRepository
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
    private lateinit var fakeRecordingDao: FakeRecordingDao
    private lateinit var fakeSampleDao: FakeSensorSampleDao
    private lateinit var sessionRepository: SessionRepository
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var exportService: FakeSessionUploader

    private val sessionId = 1L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fakeSessionDao = FakeSessionDao()
        fakeRecordingDao = FakeRecordingDao()
        fakeSampleDao = FakeSensorSampleDao()
        sessionRepository = SessionRepository(fakeSessionDao, fakeRecordingDao)
        recordingRepository = RecordingRepository(fakeRecordingDao, fakeSampleDao)
        exportService = FakeSessionUploader()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seedTest(status: SessionStatus = SessionStatus.COMPLETED): SessionEntity {
        val test = SessionEntity(
            id = sessionId,
            sessionNumber = "260101-120000",
            sessionIdentifier = "BMX-260101-120000",
            createdAt = 1_000L,
            status = status
        )
        fakeSessionDao.tests.add(test)
        return test
    }

    private fun seedRecording(id: Long, seq: Int) {
        fakeRecordingDao.recordings.add(
            RecordingEntity(
                id = id,
                sessionId = sessionId,
                recordingIdentifier = "BMX-260101-120000-R%02d".format(seq),
                sequenceNumber = seq,
                startedAt = 2_000L
            )
        )
    }

    private fun newViewModel(): SessionDetailViewModel {
        val handle = SavedStateHandle(mapOf("testId" to sessionId))
        return SessionDetailViewModel(sessionRepository, recordingRepository, exportService, handle)
    }

    @Test
    fun `loadTest populates state and clears loading`() = runTest {
        val test = seedTest()
        seedRecording(id = 10, seq = 1)
        seedRecording(id = 11, seq = 2)

        val vm = newViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(test, state.test)
        assertEquals(2, state.recordings.size)
        assertEquals(listOf(1, 2), state.recordings.map { it.sequenceNumber })
    }

    @Test
    fun `loadTest with missing test yields null test and empty recordings`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.test)
        assertTrue(state.recordings.isEmpty())
    }

    @Test
    fun `exportTest success marks test EXPORTED and surfaces path in exportResult`() = runTest {
        seedTest(SessionStatus.COMPLETED)
        exportService.result = Result.success("Documents/BioMetrix/BMX-260101-120000/...json")

        val vm = newViewModel()
        advanceUntilIdle()
        vm.exportTest()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isExporting)
        assertEquals(SessionStatus.EXPORTED, state.test?.status)
        assertEquals(SessionStatus.EXPORTED, fakeSessionDao.tests.single().status)
        assertEquals("Exported to: Documents/BioMetrix/BMX-260101-120000/...json", state.exportResult)
    }

    @Test
    fun `exportTest failure leaves status unchanged and surfaces error`() = runTest {
        seedTest(SessionStatus.COMPLETED)
        exportService.result = Result.failure(IllegalStateException("disk full"))

        val vm = newViewModel()
        advanceUntilIdle()
        vm.exportTest()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isExporting)
        assertEquals(SessionStatus.COMPLETED, state.test?.status)
        assertEquals(SessionStatus.COMPLETED, fakeSessionDao.tests.single().status)
        assertEquals("Export failed: disk full", state.exportResult)
    }

    @Test
    fun `clearExportResult resets exportResult to null`() = runTest {
        seedTest()
        exportService.result = Result.success("path")

        val vm = newViewModel()
        advanceUntilIdle()
        vm.exportTest()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.exportResult)

        vm.clearExportResult()
        assertNull(vm.uiState.value.exportResult)
    }

    @Test
    fun `deleteSession removes row and invokes callback`() = runTest {
        seedTest()
        var called = false

        val vm = newViewModel()
        advanceUntilIdle()
        vm.deleteSession { called = true }
        advanceUntilIdle()

        assertTrue(called)
        assertTrue(fakeSessionDao.tests.isEmpty())
        assertTrue(vm.uiState.value.isDeleting)
    }

    private class FakeSessionUploader : SessionUploader {
        var result: Result<String> = Result.success("")
        override suspend fun upload(sessionId: Long): Result<String> = result
    }
}
