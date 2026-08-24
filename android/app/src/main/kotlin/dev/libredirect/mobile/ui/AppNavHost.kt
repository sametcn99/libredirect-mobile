package dev.libredirect.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.libredirect.mobile.ui.browser.BrowserPickerScreen
import dev.libredirect.mobile.ui.custom.CustomServicesScreen
import dev.libredirect.mobile.ui.exceptions.ExceptionsScreen
import dev.libredirect.mobile.ui.home.HomeActions
import dev.libredirect.mobile.ui.home.HomeScreen
import dev.libredirect.mobile.ui.service.ServiceDetailScreen

private const val ROUTE_HOME = "home"
private const val ROUTE_BROWSERS = "browsers"
private const val ROUTE_EXCEPTIONS = "exceptions"
private const val ROUTE_SERVICE = "service/{routeId}"
private const val ROUTE_CUSTOM_SERVICES = "custom-services"

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    val viewModel: MainViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    val currentState by rememberUpdatedState(state)
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.errorShown()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = ROUTE_HOME) {
            addHomeDestination(navController, { currentState }, viewModel)
            addBrowserDestination(navController, { currentState }, viewModel)
            addExceptionsDestination(navController, { currentState }, viewModel)
            addCustomServicesDestination(navController, { currentState }, viewModel)
            addServiceDestination(navController, { currentState }, viewModel)
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun NavGraphBuilder.addHomeDestination(
    navController: NavHostController,
    stateProvider: () -> MainUiState,
    viewModel: MainViewModel,
) {
    composable(ROUTE_HOME) {
        HomeScreen(
            state = stateProvider(),
            actions =
                HomeActions(
                    onRoutingEnabledChange = viewModel::setRoutingEnabled,
                    onBrowserClick = { navController.navigate(ROUTE_BROWSERS) },
                    onServiceClick = { routeId -> navController.navigate("service/$routeId") },
                    onServiceEnabledChange = viewModel::setRouteEnabled,
                    onExceptionsClick = { navController.navigate(ROUTE_EXCEPTIONS) },
                    onRefreshClick = viewModel::refreshManifest,
                    onCustomServicesClick = { navController.navigate(ROUTE_CUSTOM_SERVICES) },
                ),
        )
    }
}

private fun NavGraphBuilder.addBrowserDestination(
    navController: NavHostController,
    stateProvider: () -> MainUiState,
    viewModel: MainViewModel,
) {
    composable(ROUTE_BROWSERS) {
        val state = stateProvider()
        BrowserPickerScreen(
            browsers = viewModel.installedBrowsers(),
            selectedPackageName = state.selectedBrowserPackage,
            onBack = navController::popBackStack,
            onBrowserSelected = { packageName ->
                viewModel.setSelectedBrowser(packageName)
                navController.popBackStack()
            },
        )
    }
}

private fun NavGraphBuilder.addExceptionsDestination(
    navController: NavHostController,
    stateProvider: () -> MainUiState,
    viewModel: MainViewModel,
) {
    composable(ROUTE_EXCEPTIONS) {
        val state = stateProvider()
        ExceptionsScreen(
            exceptions = state.exceptions,
            onBack = navController::popBackStack,
            onExceptionsChange = viewModel::setExceptions,
        )
    }
}

private fun NavGraphBuilder.addCustomServicesDestination(
    navController: NavHostController,
    stateProvider: () -> MainUiState,
    viewModel: MainViewModel,
) {
    composable(ROUTE_CUSTOM_SERVICES) {
        val state = stateProvider()
        CustomServicesScreen(
            routes = state.customRoutes,
            message = state.customServiceMessage,
            onBack = navController::popBackStack,
            onAdd = viewModel::addCustomRoute,
            onRemove = viewModel::removeCustomRoute,
        )
    }
}

private fun NavGraphBuilder.addServiceDestination(
    navController: NavHostController,
    stateProvider: () -> MainUiState,
    viewModel: MainViewModel,
) {
    composable(ROUTE_SERVICE) { backStackEntry ->
        val state = stateProvider()
        val routeId = backStackEntry.arguments?.getString("routeId")
        val service = state.services.find { it.routeId == routeId }
        if (service != null) {
            ServiceDetailScreen(
                service = service,
                onBack = navController::popBackStack,
                onFrontendSelected = { frontendId ->
                    viewModel.setSelectedFrontend(service.routeId, frontendId)
                },
                onInstanceSelectionChange = { frontendId, selection ->
                    viewModel.setInstanceSelection(service.routeId, frontendId, selection)
                },
            )
        }
    }
}
