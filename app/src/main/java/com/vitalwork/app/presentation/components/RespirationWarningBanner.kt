package com.vitalwork.app.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vitalwork.app.data.sensor.audio.RespirationWarning

/**
 * The single respiration warning banner. [RespirationWarning] is mutually exclusive by construction,
 * so only one message can ever be on screen — two banners both telling the operator to check the
 * strap would be noise, not information.
 */
@Composable
fun RespirationWarningBanner(
    warning: RespirationWarning,
    modifier: Modifier = Modifier
) {
    val (title, description) = when (warning) {
        RespirationWarning.NONE -> return
        RespirationWarning.SIGNAL_LOST ->
            "Respiration signal lost" to "Check chest strap placement."
        RespirationWarning.NO_BREATHING ->
            "No breathing detected" to "Check the respiration chest strap."
    }

    AlertCard(
        title = title,
        description = description,
        icon = Icons.Outlined.Warning,
        severity = AlertSeverity.ADVISORY,
        pulse = true,
        modifier = modifier
    )
}
