package com.simonbrs.autoscreenshot.callrecorder.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Environment
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simonbrs.autoscreenshot.callrecorder.data.CallDirection
import com.simonbrs.autoscreenshot.callrecorder.data.CallRecording
import com.simonbrs.autoscreenshot.callrecorder.service.CallRecorderEngine
import com.simonbrs.autoscreenshot.ui.theme.AccentGreen
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class RecordingFilter { SavedContacts, UnknownNumbers }

@Composable
fun RecordingsScreen(
    filter: RecordingFilter,
    viewModel: RecordingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val resumeTick = rememberResumeTick()
    val recordingsVersion by CallRecorderEngine.recordingsVersion.collectAsState()
    var query by rememberSaveable(filter) { mutableStateOf("") }
    var expandedPath by rememberSaveable(filter) { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<CallRecording?>(null) }
    val player = rememberRecordingPlayer()

    LaunchedEffect(resumeTick, recordingsVersion) {
        viewModel.refresh(context)
    }

    val state = viewModel.state
    val visible = remember(state.recordings, filter, query) {
        state.recordings
            .filter { recording ->
                when (filter) {
                    RecordingFilter.SavedContacts -> recording.contactName != null
                    RecordingFilter.UnknownNumbers -> recording.contactName == null
                }
            }
            .filter { recording ->
                query.isBlank() ||
                    recording.contactName?.contains(query, ignoreCase = true) == true ||
                    recording.number?.contains(query.filter { it.isDigit() || it == '+' }.ifEmpty { query }) == true
            }
    }
    val grouped = remember(visible) { visible.groupBy { dayLabel(it.recordedAt) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = if (filter == RecordingFilter.SavedContacts) "Recordings" else "Unknown numbers",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = {
                Text(if (filter == RecordingFilter.SavedContacts) "Search name or number" else "Search number")
            }
        )
        Spacer(modifier = Modifier.height(12.dp))

        when {
            !Environment.isExternalStorageManager() -> EmptyRecordingsMessage(
                icon = Icons.Default.Contacts,
                title = "Storage access needed",
                body = "Open the Call Recorder Home tab and grant storage access to see recordings."
            )

            state.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            visible.isEmpty() -> EmptyRecordingsMessage(
                icon = if (filter == RecordingFilter.SavedContacts) Icons.Default.Contacts else Icons.Default.PersonOff,
                title = if (query.isNotBlank()) "No matches" else "No recordings yet",
                body = if (filter == RecordingFilter.SavedContacts) {
                    "Calls with people saved in your contacts appear here."
                } else {
                    "Calls with new or unsaved numbers appear here."
                }
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                grouped.forEach { (day, recordings) ->
                    item(key = "header_$day") {
                        Text(
                            text = day,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                        )
                    }
                    items(recordings, key = { it.file.absolutePath }) { recording ->
                        val path = recording.file.absolutePath
                        RecordingRow(
                            recording = recording,
                            isExpanded = expandedPath == path,
                            player = player,
                            showAddContact = filter == RecordingFilter.UnknownNumbers,
                            onToggleExpand = {
                                expandedPath = if (expandedPath == path) null else path
                            },
                            onPlayPause = {
                                expandedPath = path
                                player.toggle(recording.file)
                            },
                            onStar = {
                                if (player.currentPath == path) player.stop()
                                viewModel.toggleStar(context, recording)
                            },
                            onShare = { shareRecording(context, recording) },
                            onDelete = { pendingDelete = recording },
                            onAddContact = { addToContacts(context, recording.number) }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { recording ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete recording?") },
            text = { Text("${recording.displayName} - ${formatCallDuration(recording.durationSeconds)}. This cannot be undone.") },
            confirmButton = {
                Button(onClick = {
                    if (player.currentPath == recording.file.absolutePath) player.stop()
                    viewModel.delete(context, recording)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun RecordingRow(
    recording: CallRecording,
    isExpanded: Boolean,
    player: RecordingPlayer,
    showAddContact: Boolean,
    onToggleExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onStar: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onAddContact: () -> Unit
) {
    val isCurrent = player.currentPath == recording.file.absolutePath
    val isPlaying = isCurrent && player.isPlaying

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onToggleExpand),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = recording.contactName?.firstOrNull()?.uppercase() ?: "#",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = recording.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (recording.isStarred) {
                            Spacer(modifier = Modifier.size(4.dp))
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Starred",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (recording.direction == CallDirection.Incoming) {
                                Icons.AutoMirrored.Filled.CallReceived
                            } else {
                                Icons.AutoMirrored.Filled.CallMade
                            },
                            contentDescription = recording.direction.name,
                            tint = AccentGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        val numberPart = if (recording.contactName != null && recording.number != null) {
                            "${recording.number} · "
                        } else {
                            ""
                        }
                        Text(
                            text = numberPart +
                                SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(recording.recordedAt)) +
                                " · " + formatCallDuration(recording.durationSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                val durationMs = if (isCurrent && player.durationMs > 0) {
                    player.durationMs
                } else {
                    (recording.durationSeconds * 1000L).toInt().coerceAtLeast(1)
                }
                val positionMs = if (isCurrent) player.positionMs else 0
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                    onValueChange = { value ->
                        if (!isCurrent) player.toggle(recording.file)
                        player.seekTo(value.toInt())
                    },
                    valueRange = 0f..durationMs.toFloat()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatCallDuration(positionMs / 1000L), style = MaterialTheme.typography.labelSmall)
                    Text(formatRecorderBytes(recording.sizeBytes), style = MaterialTheme.typography.labelSmall)
                    Text(formatCallDuration(durationMs / 1000L), style = MaterialTheme.typography.labelSmall)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RecordingAction(
                        icon = if (recording.isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                        label = if (recording.isStarred) "Unstar" else "Keep",
                        onClick = onStar
                    )
                    RecordingAction(icon = Icons.Default.Share, label = "Share", onClick = onShare)
                    if (showAddContact && recording.number != null) {
                        RecordingAction(icon = Icons.Default.PersonAdd, label = "Save", onClick = onAddContact)
                    }
                    RecordingAction(icon = Icons.Default.Delete, label = "Delete", onClick = onDelete)
                }
            }
        }
    }
}

@Composable
private fun RecordingAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun EmptyRecordingsMessage(icon: ImageVector, title: String, body: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Plays one recording at a time. */
@Stable
class RecordingPlayer {
    private var mediaPlayer: MediaPlayer? = null

    var currentPath by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set

    fun toggle(file: File) {
        val active = mediaPlayer
        if (active != null && currentPath == file.absolutePath) {
            if (active.isPlaying) {
                active.pause()
                isPlaying = false
            } else {
                active.start()
                isPlaying = true
            }
            return
        }

        stop()
        val created = MediaPlayer()
        try {
            created.setDataSource(file.absolutePath)
            created.prepare()
            created.setOnCompletionListener {
                isPlaying = false
                positionMs = 0
                it.seekTo(0)
            }
            created.start()
            mediaPlayer = created
            currentPath = file.absolutePath
            durationMs = created.duration.coerceAtLeast(1)
            positionMs = 0
            isPlaying = true
        } catch (_: Exception) {
            created.release()
        }
    }

    fun seekTo(ms: Int) {
        mediaPlayer?.seekTo(ms)
        positionMs = ms
    }

    fun updatePosition() {
        mediaPlayer?.let { if (it.isPlaying) positionMs = it.currentPosition }
    }

    fun stop() {
        mediaPlayer?.release()
        mediaPlayer = null
        currentPath = null
        isPlaying = false
        positionMs = 0
        durationMs = 0
    }
}

@Composable
private fun rememberRecordingPlayer(): RecordingPlayer {
    val player = remember { RecordingPlayer() }
    DisposableEffect(player) {
        onDispose { player.stop() }
    }
    LaunchedEffect(player.isPlaying) {
        while (player.isPlaying) {
            player.updatePosition()
            delay(250)
        }
    }
    return player
}

private fun dayLabel(timestamp: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun Calendar.sameDay(other: Calendar) =
        get(Calendar.YEAR) == other.get(Calendar.YEAR) && get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
    return when {
        day.sameDay(today) -> "Today"
        day.sameDay(yesterday) -> "Yesterday"
        else -> SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun shareRecording(context: Context, recording: CallRecording) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.callrecorder.fileprovider",
            recording.file
        )
        val intent = Intent(Intent.ACTION_SEND)
            .setType("audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share recording"))
    } catch (_: Exception) {
        Toast.makeText(context, "Could not share this recording", Toast.LENGTH_SHORT).show()
    }
}

private fun addToContacts(context: Context, number: String?) {
    val intent = Intent(ContactsContract.Intents.Insert.ACTION)
        .setType(ContactsContract.RawContacts.CONTENT_TYPE)
        .putExtra(ContactsContract.Intents.Insert.PHONE, number)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No contacts app found", Toast.LENGTH_SHORT).show()
    }
}
