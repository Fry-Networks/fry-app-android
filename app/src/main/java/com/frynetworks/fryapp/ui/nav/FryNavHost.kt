package com.frynetworks.fryapp.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.frynetworks.fryapp.data.Transport
import com.frynetworks.fryapp.ui.device.DeviceDetailScreen
import com.frynetworks.fryapp.ui.home.HomeScreen
import com.frynetworks.fryapp.ui.miners.MinersScreen
import com.frynetworks.fryapp.ui.miners.activity.ActivityScreen
import com.frynetworks.fryapp.ui.miners.detail.MinerDetailScreen
import com.frynetworks.fryapp.ui.miners.keys.MinerKeysScreen
import com.frynetworks.fryapp.ui.miners.rewards.RewardsHistoryScreen
import com.frynetworks.fryapp.ui.provision.ProvisionScreen
import com.frynetworks.fryapp.ui.scan.ScanScreen
import com.frynetworks.fryapp.ui.settings.SettingsScreen

object FryRoutes {
    const val HOME = "home"
    const val SCAN = "scan"
    const val PROVISION = "provision/{address}?transport={transport}"
    const val DEVICE = "device/{minerKey}"
    const val SETTINGS = "settings"
    const val MINERS = "miners"
    const val MINER = "miner/{minerKey}"
    const val MINER_REWARDS = "miner/{minerKey}/rewards"
    const val KEYS = "keys"
    const val ACTIVITY = "activity"

    /** Top-level destinations that show the bottom navigation bar. */
    val BOTTOM_NAV_ROUTES = setOf(HOME, SCAN, MINERS, SETTINGS)

    fun provision(address: String, transport: String) = "provision/$address?transport=$transport"
    fun device(minerKey: String) = "device/$minerKey"
    fun miner(minerKey: String) = "miner/$minerKey"
    fun minerRewards(minerKey: String) = "miner/$minerKey/rewards"
}

@Composable
fun FryNavHost(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute in FryRoutes.BOTTOM_NAV_ROUTES) {
                FryBottomNavigationBar(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = FryRoutes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(FryRoutes.HOME) {
                HomeScreen(
                    onAddDevice = { navController.navigate(FryRoutes.SCAN) },
                    onDeviceClick = { minerKey -> navController.navigate(FryRoutes.device(minerKey)) },
                    onSettings = { navController.navigate(FryRoutes.SETTINGS) },
                )
            }
            composable(FryRoutes.SCAN) {
                ScanScreen(
                    onDeviceChosen = { address, transport ->
                        navController.navigate(FryRoutes.provision(address, transport))
                    },
                )
            }
            composable(
                route = FryRoutes.PROVISION,
                arguments = listOf(
                    navArgument("address") { type = NavType.StringType },
                    navArgument("transport") {
                        type = NavType.StringType
                        defaultValue = Transport.BLE
                    },
                ),
            ) { entry ->
                val address = entry.arguments?.getString("address").orEmpty()
                val transport = entry.arguments?.getString("transport") ?: Transport.BLE
                ProvisionScreen(
                    address = address,
                    transport = transport,
                    onDone = { minerKey ->
                        // "device/" matches no destination; never navigate on a blank key.
                        if (minerKey.isNotBlank()) {
                            navController.navigate(FryRoutes.device(minerKey)) {
                                popUpTo(FryRoutes.HOME)
                            }
                        }
                    },
                )
            }
            composable(
                route = FryRoutes.DEVICE,
                arguments = listOf(navArgument("minerKey") { type = NavType.StringType }),
            ) {
                DeviceDetailScreen(
                    onOpenMiners = { minerKey -> navController.navigate(FryRoutes.miner(minerKey)) },
                )
            }
            composable(FryRoutes.MINERS) {
                MinersScreen(
                    onOpenMiner = { minerKey -> navController.navigate(FryRoutes.miner(minerKey)) },
                    onOpenKeys = { navController.navigate(FryRoutes.KEYS) },
                    onOpenActivity = { navController.navigate(FryRoutes.ACTIVITY) },
                    onAddDevice = { navController.navigate(FryRoutes.SCAN) },
                )
            }
            composable(
                route = FryRoutes.MINER,
                arguments = listOf(navArgument("minerKey") { type = NavType.StringType }),
            ) {
                MinerDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenRewards = { minerKey -> navController.navigate(FryRoutes.minerRewards(minerKey)) },
                )
            }
            composable(
                route = FryRoutes.MINER_REWARDS,
                arguments = listOf(navArgument("minerKey") { type = NavType.StringType }),
            ) {
                RewardsHistoryScreen(onBack = { navController.popBackStack() })
            }
            composable(FryRoutes.KEYS) {
                MinerKeysScreen(onBack = { navController.popBackStack() })
            }
            composable(FryRoutes.ACTIVITY) {
                ActivityScreen(onBack = { navController.popBackStack() })
            }
            composable(FryRoutes.SETTINGS) {
                SettingsScreen()
            }
        }
    }
}

@Composable
private fun FryBottomNavigationBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    badgeViewModel: NavBadgeViewModel = hiltViewModel(),
) {
    val deviceCount by badgeViewModel.deviceCount.collectAsStateWithLifecycle()

    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == FryRoutes.HOME,
            onClick = { onNavigate(FryRoutes.HOME) },
            icon = {
                BadgedBox(badge = {
                    if (deviceCount > 0) {
                        Badge(modifier = Modifier.testTag("nav_home_badge")) { Text(deviceCount.toString()) }
                    }
                }) {
                    Icon(Icons.Filled.Home, contentDescription = null)
                }
            },
            label = { Text("Home") },
            modifier = Modifier.testTag("nav_home"),
        )
        NavigationBarItem(
            selected = currentRoute == FryRoutes.SCAN,
            onClick = { onNavigate(FryRoutes.SCAN) },
            icon = { Icon(Icons.Filled.Search, contentDescription = null) },
            label = { Text("Scan") },
            modifier = Modifier.testTag("nav_scan"),
        )
        NavigationBarItem(
            selected = currentRoute == FryRoutes.MINERS,
            onClick = { onNavigate(FryRoutes.MINERS) },
            icon = { Icon(Icons.Filled.Dashboard, contentDescription = null) },
            label = { Text("Miners") },
            modifier = Modifier.testTag("nav_miners"),
        )
        NavigationBarItem(
            selected = currentRoute == FryRoutes.SETTINGS,
            onClick = { onNavigate(FryRoutes.SETTINGS) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("Settings") },
            modifier = Modifier.testTag("nav_settings"),
        )
    }
}
