package com.vitalwork.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vitalwork.app.data.prefs.DeviceModePreferencesRepository
import com.vitalwork.app.presentation.navigation.AppNavigation
import com.vitalwork.app.presentation.navigation.Route
import com.vitalwork.app.ui.theme.VitalWorkTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var deviceModePreferences: DeviceModePreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always light-themed, so force dark system-bar icons even when the
        // device itself is in dark mode (otherwise they'd be white-on-white).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        // First launch (no mode picked yet) opens the Server/Client picker; afterwards go to Home.
        val startDestination =
            if (deviceModePreferences.getMode() == null) Route.ModeSelection.route
            else Route.Home.route
        setContent {
            VitalWorkTheme {
                AppNavigation(startDestination = startDestination)
            }
        }
    }
}
