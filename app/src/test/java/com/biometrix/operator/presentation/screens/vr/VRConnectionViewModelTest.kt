package com.biometrix.operator.presentation.screens.vr

import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.vr.FakeVRConnectionManager
import com.biometrix.operator.data.vr.FakeVrDeviceDiscovery
import com.biometrix.operator.data.vr.SendResult
import com.biometrix.operator.data.vr.model.DiscoveredVrDevice
import com.biometrix.operator.data.vr.model.ServerMessage
import com.biometrix.operator.data.vr.model.WebSocketMessage
import com.biometrix.operator.presentation.log.LogType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class VRConnectionViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeClient: FakeVRConnectionManager
    private lateinit var fakeDiscovery: FakeVrDeviceDiscovery
    private lateinit var viewModel: VRConnectionViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeClient = FakeVRConnectionManager()
        fakeDiscovery = FakeVrDeviceDiscovery()
        viewModel = VRConnectionViewModel(fakeClient, fakeDiscovery)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val testDevice = DiscoveredVrDevice(
        name = "TestHeadset",
        host = "192.168.1.42",
        port = 9090
    )

    // ---- Initial state ----

    @Test
    fun initialState_isDisconnected() {
        val state = viewModel.uiState.value
        assertEquals(ConnectionState.DISCONNECTED, state.connectionState)
        assertNull(state.lastError)
        assertNull(state.selectedDevice)
        assertTrue(state.discoveredDevices.isEmpty())
    }

    // ---- Connect / disconnect / rescan ----

    @Test
    fun selectAndConnect_stopsDiscoveryAndConnects() {
        viewModel.selectAndConnect(testDevice)

        assertEquals(testDevice, viewModel.uiState.value.selectedDevice)
        assertEquals("192.168.1.42", fakeClient.lastConnectedIp)
        assertEquals(1, fakeDiscovery.stopDiscoveryCallCount)
    }

    @Test
    fun disconnect_disconnectsAndRestartsDiscovery() {
        viewModel.selectAndConnect(testDevice)

        viewModel.disconnect()

        assertEquals(1, fakeClient.disconnectCallCount)
        assertNull(viewModel.uiState.value.selectedDevice)
        // init calls startDiscovery once, disconnect calls it again = 2
        assertEquals(2, fakeDiscovery.startDiscoveryCallCount)
    }

    @Test
    fun rescan_disconnectsAndClearsAndRestartsDiscovery() {
        viewModel.selectAndConnect(testDevice)

        viewModel.rescan()

        assertEquals(1, fakeClient.disconnectCallCount)
        assertNull(viewModel.uiState.value.selectedDevice)
        assertEquals(2, fakeDiscovery.startDiscoveryCallCount)
    }

    // ---- Connection state flow ----

    @Test
    fun connectionStateChange_updatesToUiState() {
        fakeClient.connectionState.value = ConnectionState.CONNECTED

        assertEquals(ConnectionState.CONNECTED, viewModel.uiState.value.connectionState)
    }

    // ---- Send commands ----

    @Test
    fun sendLoadSceneCommand_withEmptyName_addsErrorLog() {
        fakeClient.connectionState.value = ConnectionState.CONNECTED
        viewModel.updateSceneName("")

        viewModel.sendLoadSceneCommand()

        val lastLog = viewModel.uiState.value.logEntries.first()
        assertEquals(LogType.ERROR, lastLog.type)
        assertTrue(lastLog.message.contains("empty", ignoreCase = true))
    }

    @Test
    fun sendLoadSceneCommand_withName_sendsCommand() {
        fakeClient.connectionState.value = ConnectionState.CONNECTED
        viewModel.updateSceneName("TestScene")

        viewModel.sendLoadSceneCommand()

        assertEquals("scene", fakeClient.lastCommand)
        assertEquals("load", fakeClient.lastParams?.get("action"))
        assertEquals("TestScene", fakeClient.lastParams?.get("sceneName"))
    }

    @Test
    fun sendReloadSceneCommand_sendsReloadAction() {
        fakeClient.connectionState.value = ConnectionState.CONNECTED

        viewModel.sendReloadSceneCommand()

        assertEquals("scene", fakeClient.lastCommand)
        assertEquals("reload", fakeClient.lastParams?.get("action"))
    }

    @Test
    fun sendTriggerEventCommand_withEmptyTarget_addsErrorLog() {
        fakeClient.connectionState.value = ConnectionState.CONNECTED
        viewModel.updateTriggerTarget("")

        viewModel.sendTriggerEventCommand()

        val lastLog = viewModel.uiState.value.logEntries.first()
        assertEquals(LogType.ERROR, lastLog.type)
        assertTrue(lastLog.message.contains("empty", ignoreCase = true))
    }

    @Test
    fun sendTriggerEventCommand_withValidTarget_sendsCommand() {
        fakeClient.connectionState.value = ConnectionState.CONNECTED
        viewModel.updateTriggerTarget("Door")

        viewModel.sendTriggerEventCommand()

        assertEquals("trigger_event", fakeClient.lastCommand)
        assertEquals("Door", fakeClient.lastParams?.get("target"))
        assertEquals("onClick", fakeClient.lastParams?.get("eventName"))
    }

    @Test
    fun sendCommand_whenNotConnected_addsErrorLog() {
        viewModel.updateSceneName("TestScene")

        viewModel.sendLoadSceneCommand()

        val lastLog = viewModel.uiState.value.logEntries.first()
        assertEquals(LogType.ERROR, lastLog.type)
        assertTrue(lastLog.message.contains("Not connected", ignoreCase = true))
    }

    @Test
    fun sendCommand_failure_addsErrorLog() {
        fakeClient.connectionState.value = ConnectionState.CONNECTED
        fakeClient.sendCommandResult = SendResult.Failure("timeout")
        viewModel.updateSceneName("TestScene")

        viewModel.sendLoadSceneCommand()

        val lastLog = viewModel.uiState.value.logEntries.first()
        assertEquals(LogType.ERROR, lastLog.type)
        assertTrue(lastLog.message.contains("Send failed", ignoreCase = true))
    }

    // ---- Incoming message flows ----

    @Test
    fun incomingResponse_success_addsSuccessLog() = runTest {
        fakeClient.messages.emit(
            WebSocketMessage.Response(ServerMessage("cmd", true, "done"))
        )

        val lastLog = viewModel.uiState.value.logEntries.first()
        assertEquals(LogType.SUCCESS, lastLog.type)
        assertTrue(lastLog.message.contains("done"))
    }

    @Test
    fun incomingResponse_failure_addsErrorLog() = runTest {
        fakeClient.messages.emit(
            WebSocketMessage.Response(ServerMessage("cmd", false, "bad"))
        )

        val lastLog = viewModel.uiState.value.logEntries.first()
        assertEquals(LogType.ERROR, lastLog.type)
        assertTrue(lastLog.message.contains("bad"))
    }

    @Test
    fun incomingEvent_addsNotificationLog() = runTest {
        fakeClient.messages.emit(
            WebSocketMessage.Event(ServerMessage("evt", true, "hello"))
        )

        val lastLog = viewModel.uiState.value.logEntries.first()
        assertEquals(LogType.NOTIFICATION, lastLog.type)
        assertTrue(lastLog.message.contains("hello"))
    }

    @Test
    fun incomingError_addsErrorLog() = runTest {
        fakeClient.messages.emit(WebSocketMessage.Error("ws broke"))

        val lastLog = viewModel.uiState.value.logEntries.first()
        assertEquals(LogType.ERROR, lastLog.type)
        assertTrue(lastLog.message.contains("ws broke"))
    }

    // ---- Discovery flows ----

    @Test
    fun discoveredDevices_flowsToUiState() {
        fakeDiscovery.discoveredDevices.value = listOf(testDevice)

        assertEquals(listOf(testDevice), viewModel.uiState.value.discoveredDevices)
    }

    @Test
    fun isDiscovering_flowsToUiState() {
        fakeDiscovery.isDiscovering.value = true

        assertTrue(viewModel.uiState.value.isDiscovering)
    }

    @Test
    fun wifiUnavailable_flowsToUiState() {
        fakeDiscovery.isWifiAvailable.value = false

        assertFalse(viewModel.uiState.value.isWifiAvailable)
    }

    // ---- Reconnecting flow ----

    @Test
    fun reconnecting_updatesUiStateAndAddsLog() {
        fakeClient.isReconnecting.value = true

        assertTrue(viewModel.uiState.value.isReconnecting)
        val lastLog = viewModel.uiState.value.logEntries.first()
        assertEquals(LogType.INFO, lastLog.type)
        assertTrue(lastLog.message.contains("reconnect", ignoreCase = true))
    }

    // ---- Error flow ----

    @Test
    fun errorFlow_updatesLastErrorInUiState() {
        fakeClient.lastError.value = "Connection timed out"

        assertEquals("Connection timed out", viewModel.uiState.value.lastError)
    }

    // ---- Input updates ----

    @Test
    fun updateTriggerEventName_updatesUiState() {
        viewModel.updateTriggerEventName("onHover")

        assertEquals("onHover", viewModel.uiState.value.triggerEventName)
    }

    // ---- Clear log ----

    @Test
    fun clearLog_emptiesLogEntries() {
        fakeClient.connectionState.value = ConnectionState.CONNECTED
        fakeClient.connectionState.value = ConnectionState.DISCONNECTED
        assertTrue(viewModel.uiState.value.logEntries.isNotEmpty())

        viewModel.clearLog()

        assertTrue(viewModel.uiState.value.logEntries.isEmpty())
    }
}
