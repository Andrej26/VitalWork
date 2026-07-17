package com.vitalwork.app.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vitalwork.app.data.sensor.watch.WatchBatteryAlert

/**
 * Persistent (while shown) low-battery banner for the Galaxy Watch, hosted at the top of Home so
 * the operator sees it at the start-a-new-session decision point. Renders nothing when [alert] is
 * [WatchBatteryAlert.NONE]. WARNING is advisory (amber); CRITICAL is blocking-level (red) and
 * pulses to draw attention. No vibration/sound — purely a visual cue between sessions.
 */
@Composable
fun WatchBatteryWarningCard(
    alert: WatchBatteryAlert,
    level: Int?,
    modifier: Modifier = Modifier
) {
    if (alert == WatchBatteryAlert.NONE) return

    val isCritical = alert == WatchBatteryAlert.CRITICAL
    val pct = level?.let { " ($it%)" } ?: ""

    AlertCard(
        title = if (isCritical) "Galaxy Watch battery critically low$pct"
            else "Galaxy Watch battery low$pct",
        description = if (isCritical) {
            "At this level the watch may stop recording sensor data while the screen is off and " +
                "that data will be lost. Do not start a new session without charging the watch."
        } else {
            "Charge as soon as possible. If it drops further the watch can stop recording during " +
                "sleep and lose that data."
        },
        icon = Icons.Outlined.BatteryAlert,
        severity = if (isCritical) AlertSeverity.BLOCKING else AlertSeverity.ADVISORY,
        pulse = isCritical,
        modifier = modifier
    )
}
