package com.simple.proxyconnect.ui.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.simple.proxyconnect.ui.screen.AddNodeScreen
import com.simple.proxyconnect.ui.screen.MainScreen
import com.simple.proxyconnect.viewmodel.ProxyViewModel

@Composable
fun AppNavigation(viewModel: ProxyViewModel = viewModel()) {
    val navController = rememberNavController()

    // VPN permission launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onVpnPermissionGranted()
        } else {
            viewModel.onVpnPermissionDenied()
        }
    }

    // Launch VPN permission request when needed
    LaunchedEffect(viewModel.vpnPermissionIntent) {
        viewModel.vpnPermissionIntent?.let { intent ->
            vpnPermissionLauncher.launch(intent)
        }
    }

    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScreen(
                viewModel = viewModel,
                onAddNode = { navController.navigate("add_node") }
            )
        }
        composable("add_node") {
            AddNodeScreen(
                viewModel = viewModel,
                onDismiss = { navController.popBackStack() }
            )
        }
    }
}
