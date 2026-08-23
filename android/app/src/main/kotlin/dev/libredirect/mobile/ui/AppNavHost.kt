package dev.libredirect.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.libredirect.mobile.ui.browser.BrowserPickerScreen
import dev.libredirect.mobile.ui.exceptions.ExceptionsScreen
import dev.libredirect.mobile.ui.home.HomeScreen
import dev.libredirect.mobile.ui.service.ServiceDetailScreen
import dev.libredirect.mobile.ui.custom.CustomServicesScreen

private const val ROUTE_HOME = "home"
private const val ROUTE_BROWSERS = "browsers"
private const val ROUTE_EXCEPTIONS = "exceptions"
private const val ROUTE_SERVICE = "service/{routeId}"
private const val ROUTE_CUSTOM_SERVICES = "custom-services"

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    val viewModel: MainViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    NavHost(navController = navController, startDestination = ROUTE_HOME) {
        composable(ROUTE_HOME) {
            HomeScreen(
                state = state,
                onRoutingEnabledChange = viewModel::setRoutingEnabled,
                onBrowserClick = { navController.navigate(ROUTE_BROWSERS) },
                onServiceClick = { routeId -> navController.navigate("service/$routeId") },
                onServiceEnabledChange = viewModel::setRouteEnabled,
                onExceptionsClick = { navController.navigate(ROUTE_EXCEPTIONS) },
                onRefreshClick = viewModel::refreshManifest,
                onCustomServicesClick = { navController.navigate(ROUTE_CUSTOM_SERVICES) },
            )
        }

        composable(ROUTE_BROWSERS) {
            BrowserPickerScreen(
                browsers = viewModel.installedBrowsers(),
                selectedPackageName = state.selectedBrowserPackage,
                onBack = { navController.popBackStack() },
                onBrowserSelected = { packageName ->
                    viewModel.setSelectedBrowser(packageName)
                    navController.popBackStack()
                },
            )
        }

        composable(ROUTE_EXCEPTIONS) {
            ExceptionsScreen(
                exceptions = state.exceptions,
                onBack = { navController.popBackStack() },
                onExceptionsChange = viewModel::setExceptions,
            )
        }

        composable(ROUTE_CUSTOM_SERVICES) {
            CustomServicesScreen(
                routes = state.customRoutes,
                message = state.customServiceMessage,
                onBack = { navController.popBackStack() },
                onAdd = viewModel::addCustomRoute,
                onRemove = viewModel::removeCustomRoute,
            )
        }

        composable(ROUTE_SERVICE) { backStackEntry ->
            val routeId = backStackEntry.arguments?.getString("routeId")
            val service = state.services.find { it.routeId == routeId }
            if (service != null) {
                ServiceDetailScreen(
                    service = service,
                    onBack = { navController.popBackStack() },
                    onFrontendSelected = { frontendId -> viewModel.setSelectedFrontend(service.routeId, frontendId) },
                    onInstanceSelectionChange = { frontendId, selection ->
                        viewModel.setInstanceSelection(service.routeId, frontendId, selection)
                    },
                )
            }
        }
    }
}
