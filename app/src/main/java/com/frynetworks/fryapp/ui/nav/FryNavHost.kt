package com.frynetworks.fryapp.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.frynetworks.fryapp.data.Transport
import com.frynetworks.fryapp.ui.device.DeviceDetailScreen
import com.frynetworks.fryapp.ui.home.HomeScreen
import com.frynetworks.fryapp.ui.provision.ProvisionScreen
import com.frynetworks.fryapp.ui.scan.ScanScreen
import com.frynetworks.fryapp.ui.settings.SettingsScreen

object FryRoutes {
    const val HOME = "home"
    const val SCAN = "scan"
    const val PROVISION = "provision/{address}?transport={transport}"
    const val DEVICE = "device/{minerKey}"
    const val SETTINGS = "settings"

    fun provision(address: String, transport: String) = "provision/$address?transport=$transport"
    fun device(minerKey: String) = "device/$minerKey"
}

@Composable
fun FryNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = FryRoutes.HOME) {
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
        ) { backStackEntry ->
            val address = backStackEntry.arguments?.getString("address").orEmpty()
            val transport = backStackEntry.arguments?.getString("transport") ?: Transport.BLE
            ProvisionScreen(
                address = address,
                transport = transport,
                onDone = { minerKey ->
                    navController.navigate(FryRoutes.device(minerKey)) {
                        popUpTo(FryRoutes.HOME)
                    }
                },
            )
        }
        composable(
            route = FryRoutes.DEVICE,
            arguments = listOf(navArgument("minerKey") { type = NavType.StringType }),
        ) {
            DeviceDetailScreen()
        }
        composable(FryRoutes.SETTINGS) {
            SettingsScreen()
        }
    }
}
