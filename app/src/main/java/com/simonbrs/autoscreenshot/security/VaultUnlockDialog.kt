package com.simonbrs.autoscreenshot.security

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.simonbrs.autoscreenshot.callrecorder.service.CallRecorderEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown after a reinstall when encrypted files exist: the old password unlocks
 * them again. Until then, new screenshots and recordings cannot be saved.
 */
@Composable
fun VaultUnlockDialog(
    onUnlocked: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var confirmStartFresh by remember { mutableStateOf(false) }

    if (confirmStartFresh) {
        AlertDialog(
            onDismissRequest = { confirmStartFresh = false },
            title = { Text("Start fresh?") },
            text = {
                Text(
                    "Screenshots and recordings encrypted before the reinstall will never open again. " +
                        "New files will use a new key. This cannot be undone."
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { MediaVault.startFresh() }
                        confirmStartFresh = false
                        Toast.makeText(context, "Started with a new key", Toast.LENGTH_SHORT).show()
                        onUnlocked()
                    }
                }) { Text("Start fresh") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStartFresh = false }) { Text("Back") }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text("Unlock your files") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Encrypted screenshots and recordings from before were found. Enter the Delete / Off " +
                        "password you used before to open them again. New captures and recordings are paused until then.",
                    style = MaterialTheme.typography.bodyMedium
                )
                PasswordInput(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    error = error,
                    enabled = !working
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onSkip, enabled = !working) { Text("Later") }
                    TextButton(onClick = { confirmStartFresh = true }, enabled = !working) { Text("Start fresh") }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = password.isNotEmpty() && !working,
                onClick = {
                    working = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) { MediaVault.unlock(password) }
                        working = false
                        if (ok) {
                            if (!AppLock.hasPassword(context)) AppLock.setPassword(context, password)
                            CallRecorderEngine.flushPending(context)
                            Toast.makeText(context, "Files unlocked", Toast.LENGTH_SHORT).show()
                            onUnlocked()
                        } else {
                            error = "Wrong password"
                        }
                    }
                }
            ) {
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Unlock")
                }
            }
        }
    )
}
