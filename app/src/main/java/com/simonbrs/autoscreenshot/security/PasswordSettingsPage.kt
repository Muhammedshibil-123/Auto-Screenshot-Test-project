package com.simonbrs.autoscreenshot.security

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class PasswordDialog { None, Set, Change, Remove }

/** Password settings page shared by the Auto Screenshot and Call Recorder settings. */
@Composable
fun PasswordSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val hasPassword = remember(version) { AppLock.hasPassword(context) }
    var hasRecoveryCopy by remember { mutableStateOf(false) }
    var showSaveRecovery by remember { mutableStateOf(false) }
    LaunchedEffect(version) {
        // The copy is written in the background; check again shortly after changes.
        repeat(3) {
            hasRecoveryCopy = withContext(Dispatchers.IO) { MediaVault.hasRecoveryCopy() }
            delay(700)
        }
    }
    var dialog by remember { mutableStateOf(PasswordDialog.None) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Password",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PasswordCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (hasPassword) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Delete / Off password",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (hasPassword) "On" else "Off",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
                        )
                    }
                }
                Text(
                    text = "One password for Auto Screenshot and Call Recorder. It is asked before turning " +
                        "capture or recording on/off, deleting screenshots or recordings, and changing automatic delete.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
                if (hasPassword) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { dialog = PasswordDialog.Remove }, modifier = Modifier.weight(1f)) {
                            Text("Remove")
                        }
                        Button(onClick = { dialog = PasswordDialog.Change }, modifier = Modifier.weight(1f)) {
                            Text("Change")
                        }
                    }
                } else {
                    Button(onClick = { dialog = PasswordDialog.Set }, modifier = Modifier.fillMaxWidth()) {
                        Text("Set password")
                    }
                }
            }

            PasswordCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (hasPassword && hasRecoveryCopy) Icons.Default.Lock else Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Recovery after reinstall",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when {
                                !hasPassword -> "Off - set a password to turn it on"
                                hasRecoveryCopy -> "On"
                                else -> "Not saved yet"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
                        )
                    }
                }
                if (hasPassword && !hasRecoveryCopy) {
                    Button(onClick = { showSaveRecovery = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Save recovery copy")
                    }
                }
            }

            PasswordCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "If you lose your password, everything will be gone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )
                }
            }
        }
    }

    if (showSaveRecovery) {
        PasswordPromptDialog(
            title = "Save recovery copy",
            message = "Enter your password to save the recovery copy.",
            onDismiss = { showSaveRecovery = false },
            onSubmit = { password ->
                if (AppLock.verify(context, password)) {
                    MediaVault.saveRecoveryCopyAsync(password)
                    showSaveRecovery = false
                    version += 1
                    true
                } else {
                    false
                }
            }
        )
    }

    when (dialog) {
        PasswordDialog.None -> Unit

        PasswordDialog.Set -> NewPasswordDialog(
            title = "Set password",
            requireCurrent = false,
            onDismiss = { dialog = PasswordDialog.None },
            onSave = { newPassword ->
                AppLock.setPassword(context, newPassword)
                MediaVault.saveRecoveryCopyAsync(newPassword)
                dialog = PasswordDialog.None
                version += 1
                Toast.makeText(context, "Password set", Toast.LENGTH_SHORT).show()
            }
        )

        PasswordDialog.Change -> NewPasswordDialog(
            title = "Change password",
            requireCurrent = true,
            onDismiss = { dialog = PasswordDialog.None },
            onSave = { newPassword ->
                AppLock.setPassword(context, newPassword)
                MediaVault.saveRecoveryCopyAsync(newPassword)
                dialog = PasswordDialog.None
                version += 1
                Toast.makeText(context, "Password changed", Toast.LENGTH_SHORT).show()
            }
        )

        PasswordDialog.Remove -> PasswordPromptDialog(
            title = "Remove password",
            message = "Enter the current password to remove it.",
            onDismiss = { dialog = PasswordDialog.None },
            onSubmit = { password ->
                if (AppLock.verify(context, password)) {
                    AppLock.clearPassword(context)
                    MediaVault.deleteRecoveryCopies()
                    dialog = PasswordDialog.None
                    version += 1
                    Toast.makeText(context, "Password removed", Toast.LENGTH_SHORT).show()
                    true
                } else {
                    false
                }
            }
        )
    }
}

@Composable
private fun NewPasswordDialog(
    title: String,
    requireCurrent: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val context = LocalContext.current
    var current by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (requireCurrent) {
                    PasswordField("Current password", current) { current = it; error = null }
                }
                PasswordField("New password", newPassword) { newPassword = it; error = null }
                PasswordField("Confirm new password", confirm) { confirm = it; error = null }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                error = when {
                    requireCurrent && !AppLock.verify(context, current) -> "Current password is wrong"
                    newPassword.length < AppLock.MIN_LENGTH -> "Use at least ${AppLock.MIN_LENGTH} characters"
                    newPassword != confirm -> "Passwords do not match"
                    else -> null
                }
                if (error == null) onSave(newPassword)
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    PasswordInput(value = value, onValueChange = onChange, label = label)
}

@Composable
private fun PasswordCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}
