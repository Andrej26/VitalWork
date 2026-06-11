package com.biometrix.operator.wear

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        setContent { WatchApp(::start, ::stop) }
    }

    private fun requestPermissions() {
        val needed = buildList {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            // Wear OS 6 / API 36 replaced BODY_SENSORS with health.* runtime permissions.
            if (Build.VERSION.SDK_INT >= 36) {
                add("android.permission.health.READ_HEART_RATE")
                add(PERMISSION_ADDITIONAL_HEALTH_DATA) // EDA (Samsung)
            } else {
                add(Manifest.permission.BODY_SENSORS)
            }
            // Background body-sensors: required (API 31+) for the SDK to keep delivering data while
            // backgrounded / screen off. The OS routes this to a settings screen ("Allow all the time").
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add("android.permission.BODY_SENSORS_BACKGROUND")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private companion object {
        const val PERMISSION_ADDITIONAL_HEALTH_DATA =
            "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
    }

    private fun start() {
        val i = Intent(this, WatchSensorService::class.java).apply { action = WatchSensorService.ACTION_START }
        ContextCompat.startForegroundService(this, i)
    }

    private fun stop() {
        val i = Intent(this, WatchSensorService::class.java).apply { action = WatchSensorService.ACTION_STOP }
        startService(i)
    }
}

@Composable
private fun WatchApp(onStart: () -> Unit, onStop: () -> Unit) {
    val tracking by WatchSensorService.isTracking.collectAsState()
    val status by WatchSensorService.connectionText.collectAsState()
    val values by WatchSensorService.lastValues.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("BioMetrix", style = MaterialTheme.typography.titleMedium)
        Text(status, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { if (tracking) onStop() else onStart() }) {
            Text(if (tracking) "Stop" else "Start")
        }
        values.forEach { (type, v) ->
            Text("$type: $v", style = MaterialTheme.typography.bodySmall)
        }
    }
}
