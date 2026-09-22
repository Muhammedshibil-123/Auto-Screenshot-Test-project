package com.simonbrs.autoscreenshot.security

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Asks for the shared password before running a protected action.
 * If no password is set, the action runs immediately.
 */
class PasswordGate internal constructor(private val context: Context) {
    internal class Request(
        val reason: String,
        val action: () -> Unit,
        val onCancel: () -> Unit
    )

    internal var pending by mutableStateOf<Request?>(null)

    fun guard(reason: String, onCancel: () -> Unit = {}, action: () -> Unit) {
        if (!AppLock.hasPassword(context)) {
            action()
            return
        }
        pending = Request(reason, action, onCancel)
    }
}

val LocalPasswordGate = staticCompositionLocalOf<PasswordGate> {
    error("PasswordGateHost is missing")
}

@Composable
fun PasswordGateHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val gate = remember(context) { PasswordGate(context.applicationContext) }

    CompositionLocalProvider(LocalPasswordGate provides gate) {
        content()
    }

    gate.pending?.let { request ->
        PasswordPromptDialog(
            title = "Enter password",
            message = request.reason,
            onDismiss = {
                gate.pending = null
                request.onCancel()
            },
            onSubmit = { password ->
                if (AppLock.verify(context, password)) {
                    MediaVault.saveRecoveryCopyIfMissingAsync(password)
                    gate.pending = null
                    request.action()
                    true
                } else {
                    false
                }
            }
        )
    }
}

/** A single masked password field. [onSubmit] returns false to show "Wrong password". */
@Composable
internal fun PasswordPromptDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Boolean
) {
    var password by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
                PasswordInput(
                    value = password,
                    onValueChange = {
                        password = it
                        showError = false
                    },
                    error = if (showError) "Wrong password" else null
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (!onSubmit(password)) showError = true },
                enabled = password.isNotEmpty()
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Password text field with an eye button to show or hide what is typed. */
@Composable
internal fun PasswordInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "Password",
    error: String? = null,
    enabled: Boolean = true
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = enabled,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password"
                )
            }
        }
    )
}
