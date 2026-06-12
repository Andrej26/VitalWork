package com.vitalwork.app.presentation.components

/**
 * Represents which dialog is currently shown on a BLE screen.
 * Shared between sensor and test screens.
 */
sealed interface BleDialogState {
    data object LocationServicesRequired : BleDialogState
    data object ScanTimeout : BleDialogState
    data class ConnectionTimeout(val deviceName: String) : BleDialogState
    data class LowBattery(val percent: Int) : BleDialogState
    data class UnexpectedDisconnection(val deviceName: String, val reason: String) : BleDialogState
}

/**
 * Actions that can be taken from BLE dialogs.
 */
enum class DialogAction {
    OpenLocationSettings, RetryConnection, Reconnect, Dismiss
}

/**
 * Maps a GATT status code to a human-readable string.
 */
fun gattStatusToString(status: Int): String {
    return when (status) {
        0x08 -> "Connection timeout"
        0x13 -> "Connection terminated by device"
        0x16 -> "Connection terminated locally"
        0x22 -> "Connection failed to establish"
        0x85 -> "GATT error"
        else -> "Error code: $status"
    }
}
