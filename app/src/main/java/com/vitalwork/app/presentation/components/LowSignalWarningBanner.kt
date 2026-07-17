package com.vitalwork.app.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vitalwork.app.data.sensor.audio.LowSignalWarning

@Composable
fun LowSignalWarningBanner(
    warningLevel: LowSignalWarning,
    modifier: Modifier = Modifier
) {
    if (warningLevel == LowSignalWarning.NONE) return

    AlertCard(
        title = "Low respiration signal detected",
        description = "Check chest strap placement.",
        icon = Icons.Outlined.Warning,
        severity = AlertSeverity.ADVISORY,
        pulse = true,
        modifier = modifier
    )
}
