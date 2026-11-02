package dev.libredirect.mobile.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.libredirect.mobile.R
import dev.libredirect.mobile.ui.browser.BrowserPickerScreen
import dev.libredirect.mobile.ui.browser.BrowserTab
import dev.libredirect.mobile.ui.browser.BrowserViewModel
import dev.libredirect.mobile.ui.browser.InAppBrowserScreen
import dev.libredirect.mobile.ui.custom.CustomServicesScreen
import dev.libredirect.mobile.ui.exceptions.ExceptionsScreen
import dev.libredirect.mobile.ui.home.HomeActions
import dev.libredirect.mobile.ui.home.HomeScreen
import dev.libredirect.mobile.ui.service.ServiceDetailScreen
import dev.libredirect.mobile.ui.services.ServicesScreen
import dev.libredirect.mobile.ui.settings.SettingsActions
import dev.libredirect.mobile.ui.settings.SettingsScreen

private const val ROUTE_HOME = "home"
private const val ROUTE_BROWSER = "browser"
private const val ROUTE_SERVICES = "services"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_BROWSERS = "browsers"
private const val ROUTE_EXCEPTIONS = "exceptions"
private const val ROUTE_SERVICE = "service/{routeId}"
private const val ROUTE_CUSTOM_SERVICES = "custom-services"

private data class NavItem(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val NAV_ITEMS =
    listOf(
        NavItem(ROUTE_HOME, R.string.nav_home, Icons.Default.Home),
        NavItem(ROUTE_BROWSER, R.string.nav_browser, Icons.Default.Public),
        NavItem(ROUTE_SERVICES, R.string.nav_services, Icons.Default.Tune),
        NavItem(ROUTE_SETTINGS, R.string.nav_settings, Icons.Default.Settings),
    )

@Composable
fun AppNavHost(
    browserViewModel: BrowserViewModel = viewModel(),
    mainViewModel: MainViewModel = viewModel(),
    navController: NavHostController = rememberNavController(),
) {
    val state by mainViewModel.uiState.collectAsState()
    val currentState by rememberUpdatedState(state)
    val snackbarHostState = remember { SnackbarHostState() }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    LaunchedEffect(browserViewModel) {
        browserViewModel.openBrowserTabEvent.collect {
            if (currentRoute != ROUTE_BROWSER) {
                navController.navigate(ROUTE_BROWSER) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        mainViewModel.errorShown()
    }

    val showBottomBar = currentRoute in NAV_ITEMS.map { it.route }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NAV_ITEMS.forEach { item ->
                        val selected = currentRoute == item.route
                        val label = stringResource(item.labelRes)
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = label) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding()),
        ) {
            NavHost(
                navController = navController,
                startDestination = ROUTE_HOME,
            ) {
                composable(ROUTE_HOME) {
                    HomeScreen(
                        state = currentState,
                        actions =
                            HomeActions(
                                onRoutingEnabledChange = mainViewModel::setRoutingEnabled,
                                onBrowserClick = { navController.navigate(ROUTE_BROWSERS) },
                                onServiceClick = { routeId -> navController.navigate("service/$routeId") },
                                onServiceEnabledChange = mainViewModel::setRouteEnabled,
                                onExceptionsClick = { navController.navigate(ROUTE_EXCEPTIONS) },
                                onRefreshClick = mainViewModel::refreshManifest,
                                onCustomServicesClick = { navController.navigate(ROUTE_CUSTOM_SERVICES) },
                                onOpenInAppBrowser = { url ->
                                    browserViewModel.openUrlInNewOrActiveTab(url ?: BrowserTab.DEFAULT_HOME_URL)
                                    navController.navigate(ROUTE_BROWSER) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onNavigateToServices = {
                                    navController.navigate(ROUTE_SERVICES) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onNavigateToSettings = {
                                    navController.navigate(ROUTE_SETTINGS) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                            ),
                    )
                }

                composable(ROUTE_BROWSER) {
                    InAppBrowserScreen(viewModel = browserViewModel)
                }

                composable(ROUTE_SERVICES) {
                    ServicesScreen(
                        state = currentState,
                        onServiceClick = { routeId -> navController.navigate("service/$routeId") },
                        onServiceEnabledChange = mainViewModel::setRouteEnabled,
                    )
                }

                composable(ROUTE_SETTINGS) {
                    SettingsScreen(
                        state = currentState,
                        actions =
                            SettingsActions(
                                onBrowserClick = { navController.navigate(ROUTE_BROWSERS) },
                                onExceptionsClick = { navController.navigate(ROUTE_EXCEPTIONS) },
                                onCustomServicesClick = { navController.navigate(ROUTE_CUSTOM_SERVICES) },
                                onRefreshManifest = mainViewModel::refreshManifest,
                            ),
                    )
                }

                composable(ROUTE_BROWSERS) {
                    BrowserPickerScreen(
                        browsers = mainViewModel.installedBrowsers(),
                        selectedPackageName = currentState.selectedBrowserPackage,
                        onBack = navController::popBackStack,
                        onBrowserSelected = { packageName ->
                            mainViewModel.setSelectedBrowser(packageName)
                            navController.popBackStack()
                        },
                    )
                }

                composable(ROUTE_EXCEPTIONS) {
                    ExceptionsScreen(
                        exceptions = currentState.exceptions,
                        onBack = navController::popBackStack,
                        onExceptionsChange = mainViewModel::setExceptions,
                    )
                }

                composable(ROUTE_CUSTOM_SERVICES) {
                    CustomServicesScreen(
                        routes = currentState.customRoutes,
                        message = currentState.customServiceMessage,
                        onBack = navController::popBackStack,
                        onAdd = mainViewModel::addCustomRoute,
                        onRemove = mainViewModel::removeCustomRoute,
                    )
                }

                composable(ROUTE_SERVICE) { backStackEntry ->
                    val routeId = backStackEntry.arguments?.getString("routeId")
                    val service = currentState.services.find { it.routeId == routeId }
                    if (service != null) {
                        ServiceDetailScreen(
                            service = service,
                            onBack = navController::popBackStack,
                            onFrontendSelected = { frontendId ->
                                mainViewModel.setSelectedFrontend(service.routeId, frontendId)
                            },
                            onInstanceSelectionChange = { frontendId, selection ->
                                mainViewModel.setInstanceSelection(service.routeId, frontendId, selection)
                            },
                        )
                    }
                }
            }
        }
    }
}
