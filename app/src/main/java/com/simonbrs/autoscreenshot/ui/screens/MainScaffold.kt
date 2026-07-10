package com.simonbrs.autoscreenshot.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    isCaptureRunning: Boolean,
    isAccessibilityEnabled: Boolean,
    hasStorageAccess: Boolean,
    isBatteryUnrestricted: Boolean,
    isAutostartSetupAcknowledged: Boolean,
    initialIntervalSeconds: Long,
    onStartCapture: (Long) -> Unit,
    onStopCapture: () -> Unit,
    onOpenSetup: () -> Unit,
    onRefresh: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auto Screenshot") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.background,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Image, contentDescription = "Gallery") },
                    label = { Text("Gallery") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.background,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.background,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                0 -> HomeScreen(
                    isCaptureRunning = isCaptureRunning,
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    hasStorageAccess = hasStorageAccess,
                    isBatteryUnrestricted = isBatteryUnrestricted,
                    isAutostartSetupAcknowledged = isAutostartSetupAcknowledged,
                    initialIntervalSeconds = initialIntervalSeconds,
                    onStartCapture = onStartCapture,
                    onStopCapture = onStopCapture,
                    onOpenSetup = onOpenSetup,
                    onRefresh = onRefresh
                )
                1 -> GalleryScreen(
                    hasStorageAccess = hasStorageAccess,
                    onOpenSetup = onOpenSetup,
                    onRefresh = onRefresh
                )
                2 -> SettingsScreen(
                    hasStorageAccess = hasStorageAccess,
                    onOpenSetup = onOpenSetup,
                    onRefresh = onRefresh
                )
            }
        }
    }
}
