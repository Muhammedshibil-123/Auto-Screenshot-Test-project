package com.simonbrs.autoscreenshot.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.simonbrs.autoscreenshot.ui.theme.AccentGreen
import com.simonbrs.autoscreenshot.ui.theme.AccentRed
import kotlin.math.roundToLong

@Composable
fun HomeScreen(
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
    var intervalSeconds by rememberSaveable {
        mutableFloatStateOf(initialIntervalSeconds.toFloat().coerceIn(1f, 60f))
    }

    LaunchedEffect(initialIntervalSeconds) {
        intervalSeconds = initialIntervalSeconds.toFloat().coerceIn(1f, 60f)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StatusCard(isActive = isCaptureRunning)

        Spacer(modifier = Modifier.height(16.dp))

        SetupSummaryCard(
            isAccessibilityEnabled = isAccessibilityEnabled,
            hasStorageAccess = hasStorageAccess,
            isBatteryUnrestricted = isBatteryUnrestricted,
            isAutostartSetupAcknowledged = isAutostartSetupAcknowledged,
            onOpenSetup = onOpenSetup,
            onRefresh = onRefresh
        )

        Spacer(modifier = Modifier.weight(1f))

        StartStopButton(
            isActive = isCaptureRunning,
            onClick = {
                if (isCaptureRunning) {
                    onStopCapture()
                } else {
                    onStartCapture(intervalSeconds.roundToLong())
                }
            }
        )

        Spacer(modifier = Modifier.weight(1f))

        TimerInput(
            intervalSeconds = intervalSeconds,
            onIntervalChange = { intervalSeconds = it }
        )
    }
}

@Composable
fun StatusCard(isActive: Boolean) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Service Status:",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (isActive) "ACTIVE" else "INACTIVE",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (isActive) AccentGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun SetupSummaryCard(
    isAccessibilityEnabled: Boolean,
    hasStorageAccess: Boolean,
    isBatteryUnrestricted: Boolean,
    isAutostartSetupAcknowledged: Boolean,
    onOpenSetup: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Setup",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            SetupStatusRow(label = "Storage access", isComplete = hasStorageAccess)
            SetupStatusRow(label = "Accessibility service", isComplete = isAccessibilityEnabled)
            SetupStatusRow(label = "Battery unrestricted", isComplete = isBatteryUnrestricted)
            SetupStatusRow(label = "Autostart acknowledged", isComplete = isAutostartSetupAcknowledged)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Refresh")
                }
                Button(
                    onClick = onOpenSetup,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Open Setup")
                }
            }
        }
    }
}

@Composable
fun SetupStatusRow(label: String, isComplete: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = if (isComplete) "Done" else "Needed",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = if (isComplete) AccentGreen else AccentRed
        )
    }
}

@Composable
fun StartStopButton(isActive: Boolean, onClick: () -> Unit) {
    val buttonColor by animateColorAsState(
        targetValue = if (isActive) AccentRed else AccentGreen,
        label = "ButtonColor"
    )
    val elevation by animateDpAsState(
        targetValue = if (isActive) 12.dp else 4.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "ButtonElevation"
    )

    Button(
        onClick = onClick,
        modifier = Modifier
            .size(160.dp)
            .shadow(elevation, CircleShape),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (isActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isActive) "STOP" else "START",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        }
    }
}

@Composable
fun TimerInput(intervalSeconds: Float, onIntervalChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Screenshot Interval",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${intervalSeconds.toInt()}s",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Slider(
            value = intervalSeconds,
            onValueChange = onIntervalChange,
            valueRange = 1f..60f,
            steps = 59,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surface
            )
        )
    }
}
