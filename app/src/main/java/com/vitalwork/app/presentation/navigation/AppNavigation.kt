package com.vitalwork.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vitalwork.app.presentation.screens.home.HomeScreen
import com.vitalwork.app.presentation.screens.link.PeerLinkScreen
import com.vitalwork.app.presentation.screens.mode.ModeSelectionScreen
import com.vitalwork.app.presentation.screens.participants.ParticipantEntryScreen
import com.vitalwork.app.presentation.screens.sensors.SensorDetailScreen
import com.vitalwork.app.presentation.screens.sensors.SensorsScreen
import com.vitalwork.app.presentation.screens.sessions.ScenarioSelectionScreen
import com.vitalwork.app.presentation.screens.sessions.SessionControlScreen
import com.vitalwork.app.presentation.screens.sessions.SessionDetailScreen
import com.vitalwork.app.presentation.screens.sessions.SessionsScreen
import com.vitalwork.app.presentation.screens.settings.SettingsScreen
import com.vitalwork.app.presentation.screens.tutorial.TutorialScreen

sealed class Route(val route: String) {
    data object Home : Route("home")
    data object Sensors : Route("sensors")
    data object SensorDetail : Route("sensors/{sensorId}") {
        fun createRoute(sensorId: String) = "sensors/$sensorId"
    }
    data object Sessions : Route("sessions")
    data object ParticipantEntry : Route("participants/new")
    data object SessionSetup : Route("sessions/setup/{sessionId}") {
        fun createRoute(sessionId: Long) = "sessions/setup/$sessionId"
    }
    data object ScenarioSelection : Route("sessions/scenario-select/{sessionId}") {
        fun createRoute(sessionId: Long) = "sessions/scenario-select/$sessionId"
    }
    data object SessionActive : Route("sessions/active/{sessionId}?scenario={scenario}") {
        fun createRoute(sessionId: Long, scenario: Int = 0) =
            "sessions/active/$sessionId?scenario=$scenario"
    }
    data object SessionReview : Route("sessions/review/{sessionId}?showCsvSaved={showCsvSaved}") {
        fun createRoute(sessionId: Long, showCsvSaved: Boolean = false) =
            "sessions/review/$sessionId?showCsvSaved=$showCsvSaved"
    }
    data object Tutorial : Route("tutorial")
    data object Settings : Route("settings")
    data object ModeSelection : Route("mode")
    data object PeerLink : Route("link/{role}") {
        fun createRoute(role: String) = "link/$role"
    }
}

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    /** Where to land at launch: the mode picker until a mode is chosen, otherwise Home. */
    startDestination: String = Route.Home.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Route.ModeSelection.route) {
            ModeSelectionScreen(
                onModeSelected = {
                    navController.navigate(Route.Home.route) {
                        // Picking a mode replaces the picker so Back doesn't return to it.
                        popUpTo(Route.ModeSelection.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Route.Home.route) {
            HomeScreen(
                onNavigateToTutorial = { navController.navigate(Route.Tutorial.route) },
                onNavigateToSensors = { navController.navigate(Route.Sensors.route) },
                onNavigateToSettings = { navController.navigate(Route.Settings.route) },
                onNavigateToSessions = { navController.navigate(Route.Sessions.route) },
                onNavigateToParticipantEntry = {
                    navController.navigate(Route.ParticipantEntry.route)
                },
                onNavigateToSessionActive = { sessionId ->
                    // Resuming an active session lands on the scenario hub (setup is a one-time gate).
                    navController.navigate(Route.ScenarioSelection.createRoute(sessionId))
                },
                onNavigateToSessionReview = { sessionId ->
                    navController.navigate(Route.SessionReview.createRoute(sessionId))
                },
                onNavigateToLinkServer = {
                    navController.navigate(Route.PeerLink.createRoute("server"))
                },
                onNavigateToLinkClient = {
                    navController.navigate(Route.PeerLink.createRoute("client"))
                },
                onNavigateToModeSelection = {
                    navController.navigate(Route.ModeSelection.route)
                }
            )
        }

        composable(
            route = Route.PeerLink.route,
            arguments = listOf(navArgument("role") { type = NavType.StringType })
        ) {
            PeerLinkScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Route.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Route.Tutorial.route) {
            TutorialScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSessions = {
                    navController.navigate(Route.Sessions.route) {
                        popUpTo(Route.Home.route)
                    }
                }
            )
        }

        composable(Route.Sensors.route) {
            SensorsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSensorDetail = { sensorId ->
                    navController.navigate(Route.SensorDetail.createRoute(sensorId))
                }
            )
        }

        composable(
            route = Route.SensorDetail.route,
            arguments = listOf(navArgument("sensorId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sensorId = backStackEntry.arguments?.getString("sensorId") ?: ""
            SensorDetailScreen(
                sensorId = sensorId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Route.Sessions.route) {
            SessionsScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenSession = { sessionId ->
                    navController.navigate(Route.SessionReview.createRoute(sessionId))
                },
                onOpenactiveSession = { sessionId ->
                    // Resuming an active session lands on the scenario hub (setup is a one-time gate).
                    navController.navigate(Route.ScenarioSelection.createRoute(sessionId))
                }
            )
        }

        composable(Route.ParticipantEntry.route) {
            ParticipantEntryScreen(
                onNavigateBack = { navController.popBackStack() },
                onSessionStarted = { sessionId ->
                    navController.navigate(Route.SessionSetup.createRoute(sessionId)) {
                        popUpTo(Route.Home.route)
                    }
                }
            )
        }

        composable(
            route = Route.SessionSetup.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: -1L
            // One-time sensor-connection gate: reuses the session control screen with the countdown
            // and recording controls hidden, plus a Proceed button that opens the scenario hub.
            SessionControlScreen(
                sessionId = sessionId,
                onNavigateBack = { navController.popBackStack() },
                onCountdownFinished = {},
                onSessionEnded = {},
                setupMode = true,
                onProceed = {
                    navController.navigate(Route.ScenarioSelection.createRoute(sessionId)) {
                        popUpTo(Route.Home.route)
                    }
                }
            )
        }

        composable(
            route = Route.ScenarioSelection.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: -1L
            ScenarioSelectionScreen(
                sessionId = sessionId,
                onScenarioSelected = { scenarioNumber ->
                    navController.navigate(
                        Route.SessionActive.createRoute(sessionId, scenarioNumber)
                    )
                },
                onSessionEnded = { endedSessionId ->
                    navController.navigate(
                        Route.SessionReview.createRoute(endedSessionId, showCsvSaved = true)
                    ) {
                        popUpTo(Route.Home.route)
                    }
                }
            )
        }

        composable(
            route = Route.SessionActive.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.LongType },
                navArgument("scenario") {
                    type = NavType.IntType
                    defaultValue = 0
                }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: -1L
            SessionControlScreen(
                sessionId = sessionId,
                onNavigateBack = { navController.popBackStack() },
                onCountdownFinished = {
                    // Return to the scenario-selection hub. If it's still on the back stack just
                    // pop to it; otherwise navigate fresh (e.g. session resumed from the list).
                    val popped = navController.popBackStack(
                        Route.ScenarioSelection.createRoute(sessionId),
                        inclusive = false
                    )
                    if (!popped) {
                        navController.navigate(Route.ScenarioSelection.createRoute(sessionId)) {
                            popUpTo(Route.Home.route)
                        }
                    }
                },
                onSessionEnded = { endedSessionId ->
                    navController.navigate(Route.SessionReview.createRoute(endedSessionId, showCsvSaved = true)) {
                        popUpTo(Route.Home.route)
                    }
                }
            )
        }

        composable(
            route = Route.SessionReview.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.LongType },
                navArgument("showCsvSaved") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: -1L
            val showCsvSaved = backStackEntry.arguments?.getBoolean("showCsvSaved") ?: false
            SessionDetailScreen(
                sessionId = sessionId,
                showCsvSaved = showCsvSaved,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
