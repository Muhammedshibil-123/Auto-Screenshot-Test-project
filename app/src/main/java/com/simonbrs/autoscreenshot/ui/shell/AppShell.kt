package com.simonbrs.autoscreenshot.ui.shell

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.simonbrs.autoscreenshot.callrecorder.ui.CallRecorderScaffold
import com.simonbrs.autoscreenshot.security.MediaVault
import com.simonbrs.autoscreenshot.security.PasswordGateHost
import com.simonbrs.autoscreenshot.security.VaultUnlockDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private enum class AppSection(val title: String, val icon: ImageVector) {
    AutoScreenshot("Auto Screenshot", Icons.Default.Screenshot),
    CallRecorder("Call Recorder", Icons.Default.Mic)
}

/**
 * Hamburger drawer that switches between the Auto Screenshot section
 * (passed in unchanged) and the Call Recorder section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    autoScreenshotContent: @Composable () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Reopen the section that was open last time the app was used.
    var section by rememberSaveable { mutableStateOf(readLastSection(context)) }
    LaunchedEffect(section) {
        saveLastSection(context, section)
    }

    // After a reinstall, ask for the old password to unlock encrypted files.
    var vaultLocked by remember { mutableStateOf(false) }
    var unlockSkipped by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vaultLocked = withContext(Dispatchers.IO) { MediaVault.isLocked() }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    if (vaultLocked && !unlockSkipped) {
        VaultUnlockDialog(
            onUnlocked = { vaultLocked = false },
            onSkip = { unlockSkipped = true }
        )
    }

    PasswordGateHost {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Zoro Tools",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp)
                    )
                    AppSection.entries.forEach { item ->
                        NavigationDrawerItem(
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.title) },
                            selected = section == item,
                            onClick = {
                                section = item
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Open menu")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding())
                        .consumeWindowInsets(innerPadding)
                ) {
                    when (section) {
                        AppSection.AutoScreenshot -> autoScreenshotContent()
                        AppSection.CallRecorder -> CallRecorderScaffold()
                    }
                }
            }
        }
    }
}

private const val SHELL_PREFS = "app_shell_prefs"
private const val KEY_LAST_SECTION = "last_section"

private fun readLastSection(context: Context): AppSection {
    val stored = context.getSharedPreferences(SHELL_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_LAST_SECTION, null)
    return AppSection.entries.firstOrNull { it.name == stored } ?: AppSection.AutoScreenshot
}

private fun saveLastSection(context: Context, section: AppSection) {
    context.getSharedPreferences(SHELL_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_LAST_SECTION, section.name)
        .apply()
}
