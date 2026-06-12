package com.vitalwork.app.presentation.log

data class BleLogEntry(
    val timestamp: String,
    val message: String,
    val isError: Boolean = false
)
