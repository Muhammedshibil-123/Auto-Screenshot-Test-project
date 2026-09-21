package com.simonbrs.autoscreenshot.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.simonbrs.autoscreenshot.ui.screens.MainScaffold
import com.simonbrs.autoscreenshot.ui.screens.OnboardingScreens
import com.simonbrs.autoscreenshot.security.LocalPasswordGate
import com.simonbrs.autoscreenshot.ui.shell.AppShell

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    startDestination: String,
    isCaptureRunning: Boolean,
    isAccessibilityEnabled: Boolean,
    hasStorageAccess: Boolean,
    isBatteryUnrestricted: Boolean,
    isAutostartSetupAcknowledged: Boolean,
    initialIntervalSeconds: Long,
    onStartCapture: (Long) -> Unit,
    onStopCapture: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestStorage: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onOpenAutostartSettings: () -> Unit,
    onSkipReliabilitySetup: () -> Unit,
    onRefresh: () -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("onboarding") {
            OnboardingScreens(
                isAccessibilityEnabled = isAccessibilityEnabled,
                hasStorageAccess = hasStorageAccess,
                isBatteryUnrestricted = isBatteryUnrestricted,
                isAutostartSetupAcknowledged = isAutostartSetupAcknowledged,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onRequestStorage = onRequestStorage,
                onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                onOpenAutostartSettings = onOpenAutostartSettings,
                onSkipReliabilitySetup = {
                    onSkipReliabilitySetup()
                    navController.navigate("main") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                },
                onRefresh = onRefresh,
                onFinish = {
                    navController.navigate("main") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            )
        }
        
        composable("main") {
            AppShell(
                autoScreenshotContent = {
                    val passwordGate = LocalPasswordGate.current
                    MainScaffold(
                        isCaptureRunning = isCaptureRunning,
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        hasStorageAccess = hasStorageAccess,
                        isBatteryUnrestricted = isBatteryUnrestricted,
                        isAutostartSetupAcknowledged = isAutostartSetupAcknowledged,
                        initialIntervalSeconds = initialIntervalSeconds,
                        onStartCapture = { seconds ->
                            passwordGate.guard("Enter the password to turn on Auto Screenshot.") {
                                onStartCapture(seconds)
                            }
                        },
                        onStopCapture = {
                            passwordGate.guard("Enter the password to turn off Auto Screenshot.") {
                                onStopCapture()
                            }
                        },
                        onOpenSetup = {
                            navController.navigate("onboarding")
                        },
                        onRefresh = onRefresh
                    )
                }
            )
        }
    }
}
