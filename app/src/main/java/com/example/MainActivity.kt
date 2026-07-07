package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.smb.MainViewModel
import com.example.smb.ui.DeviceListScreen
import com.example.smb.ui.FileBrowserScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val navController = rememberNavController()
            val mainViewModel: MainViewModel = viewModel()
            
            NavHost(navController = navController, startDestination = "devices") {
                composable("devices") {
                    DeviceListScreen(
                        viewModel = mainViewModel,
                        onNavigateToBrowser = {
                            navController.navigate("browser") {
                                launchSingleTop = true
                            }
                        }
                    )
                }
                composable("browser") {
                    FileBrowserScreen(
                        viewModel = mainViewModel,
                        onNavigateBack = {
                            val currentEntry = navController.currentBackStackEntry
                            if (currentEntry?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED) {
                                navController.popBackStack("devices", inclusive = false)
                            }
                        }
                    )
                }
            }
        }
      }
    }
  }
}
