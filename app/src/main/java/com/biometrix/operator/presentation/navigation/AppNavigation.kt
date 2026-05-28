package com.biometrix.operator.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.biometrix.operator.presentation.screens.vr.VRConnectionScreen
import com.biometrix.operator.presentation.screens.home.HomeScreen
import com.biometrix.operator.presentation.screens.participants.ParticipantEntryScreen
import com.biometrix.operator.presentation.screens.sensors.SensorDetailScreen
import com.biometrix.operator.presentation.screens.sensors.SensorsScreen
import com.biometrix.operator.presentation.screens.sessions.SessionControlScreen
import com.biometrix.operator.presentation.screens.sessions.SessionDetailScreen
import com.biometrix.operator.presentation.screens.sessions.SessionsScreen
import com.biometrix.operator.presentation.screens.tutorial.TutorialScreen

sealed class Route(val route: String) {
    data object Home : Route("home")
    data object Sensors : Route("sensors")
    data object SensorDetail : Route("sensors/{sensorId}") {
        fun createRoute(sensorId: String) = "sensors/$sensorId"
    }
    data object VrControl : Route("vr_control")
    data object Sessions : Route("sessions")
    data object ParticipantEntry : Route("participants/new")
    data object SessionActive : Route("sessions/active/{sessionId}") {
        fun createRoute(sessionId: Long) = "sessions/active/$sessionId"
    }
    data object SessionReview : Route("sessions/review/{sessionId}?showCsvSaved={showCsvSaved}") {
        fun createRoute(sessionId: Long, showCsvSaved: Boolean = false) =
            "sessions/review/$sessionId?showCsvSaved=$showCsvSaved"
    }
    data object Tutorial : Route("tutorial")
}

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Route.Home.route
    ) {
        composable(Route.Home.route) {
            HomeScreen(
                onNavigateToTutorial = { navController.navigate(Route.Tutorial.route) },
                onNavigateToSensors = { navController.navigate(Route.Sensors.route) },
                onNavigateToVrControl = { navController.navigate(Route.VrControl.route) },
                onNavigateToSessions = { navController.navigate(Route.Sessions.route) },
                onNavigateToParticipantEntry = {
                    navController.navigate(Route.ParticipantEntry.route)
                },
                onNavigateToSessionActive = { sessionId ->
                    navController.navigate(Route.SessionActive.createRoute(sessionId))
                },
                onNavigateToSessionReview = { sessionId ->
                    navController.navigate(Route.SessionReview.createRoute(sessionId))
                }
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

        composable(Route.VrControl.route) {
            VRConnectionScreen(
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
                    navController.navigate(Route.SessionActive.createRoute(sessionId))
                }
            )
        }

        composable(Route.ParticipantEntry.route) {
            ParticipantEntryScreen(
                onNavigateBack = { navController.popBackStack() },
                onSessionStarted = { sessionId ->
                    navController.navigate(Route.SessionActive.createRoute(sessionId)) {
                        popUpTo(Route.Home.route)
                    }
                }
            )
        }

        composable(
            route = Route.SessionActive.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: -1L
            SessionControlScreen(
                sessionId = sessionId,
                onNavigateBack = { navController.popBackStack() },
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
