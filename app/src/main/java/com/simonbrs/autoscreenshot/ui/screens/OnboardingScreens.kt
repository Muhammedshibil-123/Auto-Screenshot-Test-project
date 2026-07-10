package com.simonbrs.autoscreenshot.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun OnboardingScreens(
    isAccessibilityEnabled: Boolean,
    hasStorageAccess: Boolean,
    isBatteryUnrestricted: Boolean,
    isAutostartSetupAcknowledged: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestStorage: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onOpenAutostartSettings: () -> Unit,
    onSkipReliabilitySetup: () -> Unit,
    onRefresh: () -> Unit,
    onFinish: () -> Unit
) {
    var currentStep by remember { mutableIntStateOf(firstIncompleteStep(hasStorageAccess, isAccessibilityEnabled)) }

    LaunchedEffect(hasStorageAccess, isAccessibilityEnabled) {
        if (currentStep == 1 && hasStorageAccess) {
            currentStep = 2
        }
        if (currentStep == 2 && isAccessibilityEnabled) {
            currentStep = 3
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally(animationSpec = tween(300)) { width -> width } + fadeIn()).togetherWith(
                            slideOutHorizontally(animationSpec = tween(300)) { width -> -width } + fadeOut())
                } else {
                    (slideInHorizontally(animationSpec = tween(300)) { width -> -width } + fadeIn()).togetherWith(
                            slideOutHorizontally(animationSpec = tween(300)) { width -> width } + fadeOut())
                }
            },
            label = "OnboardingTransition"
        ) { step ->
            when (step) {
                1 -> OnboardingStepCard(
                    title = "Storage Permission",
                    description = "We need storage permission to save your screenshots locally.",
                    icon = Icons.Default.Storage,
                    isComplete = hasStorageAccess,
                    primaryActionText = if (hasStorageAccess) "Continue" else "Grant Permission",
                    onRefresh = onRefresh,
                    onPrimaryAction = {
                        if (hasStorageAccess) {
                            currentStep = 2
                        } else {
                            onRequestStorage()
                        }
                    }
                )
                2 -> OnboardingStepCard(
                    title = "Accessibility Service",
                    description = "Required to detect screen changes and take screenshots automatically.",
                    icon = Icons.Default.Accessibility,
                    isComplete = isAccessibilityEnabled,
                    primaryActionText = if (isAccessibilityEnabled) "Continue" else "Open Settings",
                    onRefresh = onRefresh,
                    onPrimaryAction = {
                        if (isAccessibilityEnabled) {
                            currentStep = 3
                        } else {
                            onOpenAccessibilitySettings()
                        }
                    }
                )
                3 -> OnboardingStepCard(
                    title = "Auto-Start",
                    description = "Allow the app to start automatically after a device reboot.",
                    icon = Icons.Default.RocketLaunch,
                    isComplete = isAutostartSetupAcknowledged,
                    primaryActionText = if (isAutostartSetupAcknowledged) "Continue" else "Enable",
                    onRefresh = onRefresh,
                    onPrimaryAction = {
                        if (isAutostartSetupAcknowledged) {
                            currentStep = 4
                        } else {
                            onOpenAutostartSettings()
                        }
                    },
                    secondaryActionText = "Skip",
                    onSecondaryAction = {
                        currentStep = 4
                    }
                )
                4 -> OnboardingStepCard(
                    title = "Battery Optimization",
                    description = "Ignore battery optimizations to ensure the background service doesn't get killed.",
                    icon = Icons.Default.BatteryAlert,
                    isComplete = isBatteryUnrestricted,
                    primaryActionText = if (isBatteryUnrestricted) "Finish" else "Allow",
                    onRefresh = onRefresh,
                    onPrimaryAction = {
                        if (isBatteryUnrestricted) {
                            onFinish()
                        } else {
                            onRequestBatteryUnrestricted()
                        }
                    },
                    secondaryActionText = "Skip",
                    onSecondaryAction = {
                        onSkipReliabilitySetup()
                    }
                )
            }
        }
    }
}

private fun firstIncompleteStep(
    hasStorageAccess: Boolean,
    isAccessibilityEnabled: Boolean
): Int {
    return when {
        !hasStorageAccess -> 1
        !isAccessibilityEnabled -> 2
        else -> 3
    }
}

@Composable
fun OnboardingStepCard(
    title: String,
    description: String,
    icon: ImageVector,
    isComplete: Boolean,
    primaryActionText: String,
    onRefresh: () -> Unit,
    onPrimaryAction: () -> Unit,
    secondaryActionText: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = if (isComplete) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (isComplete) "Complete" else "Required",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isComplete) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(text = primaryActionText, modifier = Modifier.padding(8.dp))
                }
                
                if (secondaryActionText != null && onSecondaryAction != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = onSecondaryAction,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = secondaryActionText, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Refresh status")
                }
            }
        }
    }
}
