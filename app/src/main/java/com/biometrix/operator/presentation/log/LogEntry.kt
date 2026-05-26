package com.biometrix.operator.presentation.log

data class LogEntry(
    val timestamp: String,
    val type: LogType,
    val message: String
)

enum class LogType {
    SENT,
    RECEIVED,
    SUCCESS,
    ERROR,
    INFO,
    NOTIFICATION
}