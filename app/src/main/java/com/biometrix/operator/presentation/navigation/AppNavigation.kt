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
import com.biometrix.operator.presentation.screens.sensors.SensorDetailScreen
import com.biometrix.operator.presentation.screens.sensors.SensorsScreen
import com.biometrix.operator.presentation.screens.tests.TestControlScreen
import com.biometrix.operator.presentation.screens.tests.TestDetailScreen
import com.biometrix.operator.presentation.screens.tests.TestsScreen
import com.biometrix.operator.presentation.screens.tutorial.TutorialScreen

sealed class Route(val route: String) {
    data object Home : Route("home")
    data object Sensors : Route("sensors")
    data object SensorDetail : Route("sensors/{sensorId}") {
        fun createRoute(sensorId: String) = "sensors/$sensorId"
    }
    data object VrControl : Route("vr_control")
    data object Tests : Route("tests")
    data object TestActive : Route("tests/active/{testId}") {
        fun createRoute(testId: Long) = "tests/active/$testId"
    }
    data object TestReview : Route("tests/review/{testId}?showCsvSaved={showCsvSaved}") {
        fun createRoute(testId: Long, showCsvSaved: Boolean = false) =
            "tests/review/$testId?showCsvSaved=$showCsvSaved"
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
                onNavigateToTests = { navController.navigate(Route.Tests.route) }
            )
        }

        composable(Route.Tutorial.route) {
            TutorialScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTests = {
                    navController.navigate(Route.Tests.route) {
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

        composable(Route.Tests.route) {
            TestsScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenTest = { testId ->
                    navController.navigate(Route.TestReview.createRoute(testId))
                },
                onOpenActiveTest = { testId ->
                    navController.navigate(Route.TestActive.createRoute(testId))
                }
            )
        }

        composable(
            route = Route.TestActive.route,
            arguments = listOf(
                navArgument("testId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val testId = backStackEntry.arguments?.getLong("testId") ?: -1L
            TestControlScreen(
                testId = testId,
                onNavigateBack = { navController.popBackStack() },
                onTestEnded = { endedTestId ->
                    // Navigate to review screen, replacing the active test in the back stack
                    navController.navigate(Route.TestReview.createRoute(endedTestId, showCsvSaved = true)) {
                        popUpTo(Route.Tests.route)
                    }
                }
            )
        }

        composable(
            route = Route.TestReview.route,
            arguments = listOf(
                navArgument("testId") { type = NavType.LongType },
                navArgument("showCsvSaved") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val testId = backStackEntry.arguments?.getLong("testId") ?: -1L
            val showCsvSaved = backStackEntry.arguments?.getBoolean("showCsvSaved") ?: false
            TestDetailScreen(
                testId = testId,
                showCsvSaved = showCsvSaved,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
