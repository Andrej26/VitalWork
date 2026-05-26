package com.biometrix.operator.presentation.screens.tests

import androidx.lifecycle.SavedStateHandle
import com.biometrix.operator.data.db.FakeRecordingDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.FakeTestDao
import com.biometrix.operator.data.db.RecordingEntity
import com.biometrix.operator.data.db.TestEntity
import com.biometrix.operator.data.db.TestStatus
import com.biometrix.operator.data.export.TestExporter
import com.biometrix.operator.data.repository.RecordingRepository
import com.biometrix.operator.data.repository.TestRepository
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
class TestDetailViewModelTest {

    private lateinit var fakeTestDao: FakeTestDao
    private lateinit var fakeRecordingDao: FakeRecordingDao
    private lateinit var fakeSampleDao: FakeSensorSampleDao
    private lateinit var testRepository: TestRepository
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var exportService: FakeTestExporter

    private val testId = 1L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fakeTestDao = FakeTestDao()
        fakeRecordingDao = FakeRecordingDao()
        fakeSampleDao = FakeSensorSampleDao()
        testRepository = TestRepository(fakeTestDao, fakeRecordingDao)
        recordingRepository = RecordingRepository(fakeRecordingDao, fakeSampleDao)
        exportService = FakeTestExporter()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seedTest(status: TestStatus = TestStatus.COMPLETED): TestEntity {
        val test = TestEntity(
            id = testId,
            testNumber = "260101-120000",
            testIdentifier = "BMX-260101-120000",
            createdAt = 1_000L,
            status = status
        )
        fakeTestDao.tests.add(test)
        return test
    }

    private fun seedRecording(id: Long, seq: Int) {
        fakeRecordingDao.recordings.add(
            RecordingEntity(
                id = id,
                testId = testId,
                recordingIdentifier = "BMX-260101-120000-R%02d".format(seq),
                sequenceNumber = seq,
                startedAt = 2_000L
            )
        )
    }

    private fun newViewModel(): TestDetailViewModel {
        val handle = SavedStateHandle(mapOf("testId" to testId))
        return TestDetailViewModel(testRepository, recordingRepository, exportService, handle)
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
        seedTest(TestStatus.COMPLETED)
        exportService.result = Result.success("Documents/BioMetrix/BMX-260101-120000/...json")

        val vm = newViewModel()
        advanceUntilIdle()
        vm.exportTest()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isExporting)
        assertEquals(TestStatus.EXPORTED, state.test?.status)
        assertEquals(TestStatus.EXPORTED, fakeTestDao.tests.single().status)
        assertEquals("Exported to: Documents/BioMetrix/BMX-260101-120000/...json", state.exportResult)
    }

    @Test
    fun `exportTest failure leaves status unchanged and surfaces error`() = runTest {
        seedTest(TestStatus.COMPLETED)
        exportService.result = Result.failure(IllegalStateException("disk full"))

        val vm = newViewModel()
        advanceUntilIdle()
        vm.exportTest()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isExporting)
        assertEquals(TestStatus.COMPLETED, state.test?.status)
        assertEquals(TestStatus.COMPLETED, fakeTestDao.tests.single().status)
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
    fun `deleteTest removes row and invokes callback`() = runTest {
        seedTest()
        var called = false

        val vm = newViewModel()
        advanceUntilIdle()
        vm.deleteTest { called = true }
        advanceUntilIdle()

        assertTrue(called)
        assertTrue(fakeTestDao.tests.isEmpty())
        assertTrue(vm.uiState.value.isDeleting)
    }

    private class FakeTestExporter : TestExporter {
        var result: Result<String> = Result.success("")
        override suspend fun exportTest(testId: Long): Result<String> = result
    }
}
