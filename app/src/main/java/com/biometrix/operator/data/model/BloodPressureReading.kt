package com.biometrix.operator.data.model

data class BloodPressureReading(
    val systolicMmHg: Int,
    val diastolicMmHg: Int,
    val meanArterialMmHg: Int?,
    val pulseRateBpm: Int?,
    val receivedAtMs: Long = System.currentTimeMillis()
)
