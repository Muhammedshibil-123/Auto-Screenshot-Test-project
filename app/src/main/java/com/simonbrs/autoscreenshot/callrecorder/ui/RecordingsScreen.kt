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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.simonbrs.autoscreenshot.callrecorder.data.RecorderPrefs
import com.simonbrs.autoscreenshot.callrecorder.service.CallRecorderEngine
import com.simonbrs.autoscreenshot.security.LocalPasswordGate
import com.simonbrs.autoscreenshot.security.MediaCrypto
import com.simonbrs.autoscreenshot.ui.theme.AccentGreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val FIRST_PAGE_SIZE = 50
private const val NEXT_PAGE_SIZE = 100

enum class RecordingFilter { Unknown, AllCalls }

private enum class DirectionFilter(val label: String) {
    All("In & Out"),
    Incoming("Incoming"),
    Outgoing("Outgoing")
}

private enum class DurationFilter(val label: String, val range: LongRange) {
    Any("Any length", 0L..Long.MAX_VALUE),
    UnderOne("Under 1 min", 0L..59L),
    OneToFive("1–5 min", 60L..300L),
    OverFive("Over 5 min", 301L..Long.MAX_VALUE)
}

private enum class SortOrder(val label: String) {
    Newest("Newest first"),
    Oldest("Oldest first"),
    Longest("Longest first")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    filter: RecordingFilter,
    viewModel: RecordingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val passwordGate = LocalPasswordGate.current
    val scope = rememberCoroutineScope()
    val resumeTick = rememberResumeTick()
    val recordingsVersion by CallRecorderEngine.recordingsVersion.collectAsState()

    var query by rememberSaveable(filter) { mutableStateOf("") }
    var dateEpochDay by rememberSaveable(filter) { mutableStateOf<Long?>(null) }
    var direction by rememberSaveable(filter) { mutableStateOf(DirectionFilter.All) }
    var duration by rememberSaveable(filter) { mutableStateOf(DurationFilter.Any) }
    var numberKey by rememberSaveable(filter) { mutableStateOf<String?>(null) }
    var sort by rememberSaveable(filter) { mutableStateOf(SortOrder.Newest) }

    var expandedPath by rememberSaveable(filter) { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<CallRecording?>(null) }
    var pendingApprove by remember { mutableStateOf<CallRecording?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showContactPicker by remember { mutableStateOf(false) }
    val player = rememberRecordingPlayer()

    LaunchedEffect(resumeTick, recordingsVersion) {
        viewModel.refresh(context)
    }

    val state = viewModel.state
    val tabRecordings = remember(state, filter) {
        when (filter) {
            RecordingFilter.AllCalls -> state.recordings
            RecordingFilter.Unknown -> state.recordings.filterNot { state.isApproved(it) }
        }
    }
    val filtered = remember(tabRecordings, query, dateEpochDay, direction, duration, numberKey, sort) {
        val digits = query.filter { it.isDigit() }
        tabRecordings
            .asSequence()
            .filter { dateEpochDay == null || it.recordedAt.toLocalDate().toEpochDay() == dateEpochDay }
            .filter {
                when (direction) {
                    DirectionFilter.All -> true
                    DirectionFilter.Incoming -> it.direction == CallDirection.Incoming
                    DirectionFilter.Outgoing -> it.direction == CallDirection.Outgoing
                }
            }
            .filter { it.durationSeconds in duration.range }
            .filter { numberKey == null || it.filterKey() == numberKey }
            .filter { r ->
                query.isBlank() ||
                    r.contactName?.contains(query, ignoreCase = true) == true ||
                    (digits.isNotEmpty() && r.number?.filter { it.isDigit() }?.contains(digits) == true)
            }
            .let { seq ->
                when (sort) {
                    SortOrder.Newest -> seq.sortedByDescending { it.recordedAt }
                    SortOrder.Oldest -> seq.sortedBy { it.recordedAt }
                    SortOrder.Longest -> seq.sortedByDescending { it.durationSeconds }
                }
            }
            .toList()
    }

    // Paging: 50 first, then 100 more each time the end of the list is reached.
    var shown by rememberSaveable(filter, query, dateEpochDay, direction, duration, numberKey, sort) {
        mutableIntStateOf(FIRST_PAGE_SIZE)
    }
    val page = remember(filtered, shown) { filtered.take(shown) }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, filtered.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 5
        }.collect { nearEnd ->
            if (nearEnd && shown < filtered.size) shown += NEXT_PAGE_SIZE
        }
    }

    val hasActiveFilter = dateEpochDay != null || direction != DirectionFilter.All ||
        duration != DurationFilter.Any || numberKey != null || sort != SortOrder.Newest

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = if (filter == RecordingFilter.Unknown) "Unknown" else "Recordings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (filter == RecordingFilter.Unknown) {
            Text(
                text = "New calls wait here until you tap ✓ to mark the number as safe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.66f)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("Search name or number") }
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = dateEpochDay != null,
                onClick = { showDatePicker = true },
                label = {
                    Text(dateEpochDay?.let { LocalDate.ofEpochDay(it).format(chipDateFormatter) } ?: "Date")
                },
                leadingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            DropdownChip(
                label = direction.label,
                selected = direction != DirectionFilter.All,
                options = DirectionFilter.entries,
                optionLabel = { it.label },
                onSelect = { direction = it }
            )
            DropdownChip(
                label = duration.label,
                selected = duration != DurationFilter.Any,
                options = DurationFilter.entries,
                optionLabel = { it.label },
                onSelect = { duration = it }
            )
            FilterChip(
                selected = numberKey != null,
                onClick = { showContactPicker = true },
                label = {
                    Text(
                        numberKey?.let { key -> tabRecordings.firstOrNull { it.filterKey() == key }?.displayName }
                            ?: "Contact",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = { Icon(Icons.Default.Contacts, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            DropdownChip(
                label = sort.label,
                selected = sort != SortOrder.Newest,
                options = SortOrder.entries,
                optionLabel = { it.label },
                onSelect = { sort = it }
            )
            if (hasActiveFilter) {
                TextButton(onClick = {
                    dateEpochDay = null
                    direction = DirectionFilter.All
                    duration = DurationFilter.Any
                    numberKey = null
                    sort = SortOrder.Newest
                }) {
                    Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Clear")
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        when {
            !Environment.isExternalStorageManager() -> EmptyRecordingsMessage(
                icon = Icons.Default.Contacts,
                title = "Storage access needed",
                body = "Open the Call Recorder Home tab and grant storage access to see recordings."
            )

            state.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            filtered.isEmpty() -> EmptyRecordingsMessage(
                icon = if (filter == RecordingFilter.Unknown) Icons.Default.PersonOff else Icons.Default.Contacts,
                title = when {
                    query.isNotBlank() || hasActiveFilter -> "No matches"
                    filter == RecordingFilter.Unknown -> "All caught up"
                    else -> "No recordings yet"
                },
                body = if (filter == RecordingFilter.Unknown) {
                    "Calls from numbers you haven't marked as safe appear here."
                } else {
                    "Every recorded call appears here."
                }
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                val groups: Map<String, List<CallRecording>> = if (sort == SortOrder.Longest) {
                    mapOf("" to page)
                } else {
                    page.groupBy { dayLabel(it.recordedAt) }
                }
                groups.forEach { (day, recordings) ->
                    if (day.isNotEmpty()) {
                        item(key = "header_$day") {
                            Text(
                                text = day,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                            )
                        }
                    }
                    items(recordings, key = { it.file.absolutePath }) { recording ->
                        val path = recording.file.absolutePath
                        RecordingRow(
                            recording = recording,
                            isExpanded = expandedPath == path,
                            player = player,
                            showAddContact = recording.contactName == null,
                            onToggleExpand = { expandedPath = if (expandedPath == path) null else path },
                            onPlayPause = {
                                expandedPath = path
                                player.toggle(recording.file)
                            },
                            onStar = {
                                if (player.currentPath == path) player.stop()
                                viewModel.toggleStar(context, recording)
                            },
                            onShare = { scope.launch { shareRecording(context, recording) } },
                            onDelete = {
                                passwordGate.guard("Enter the password to delete this recording.") {
                                    pendingDelete = recording
                                }
                            },
                            onAddContact = { addToContacts(context, recording.number) },
                            onApprove = if (filter == RecordingFilter.Unknown) {
                                { pendingApprove = recording }
                            } else {
                                null
                            }
                        )
                    }
                }
                item(key = "footer") {
                    Text(
                        text = if (page.size < filtered.size) {
                            "Showing ${page.size} of ${filtered.size} · loading more…"
                        } else {
                            "${filtered.size} recording${if (filtered.size == 1) "" else "s"}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = dateEpochDay?.let { LocalDate.ofEpochDay(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateEpochDay = pickerState.selectedDateMillis?.let {
                        Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    dateEpochDay = null
                    showDatePicker = false
                }) { Text("Any date") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showContactPicker) {
        val options = remember(tabRecordings) {
            tabRecordings.groupBy { it.filterKey() }
                .map { (key, list) -> Triple(key, list.first().displayName, list.size) }
                .sortedByDescending { it.third }
        }
        AlertDialog(
            onDismissRequest = { showContactPicker = false },
            title = { Text("Choose contact") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    item {
                        ListItem(
                            headlineContent = { Text("Everyone") },
                            modifier = Modifier.clickable {
                                numberKey = null
                                showContactPicker = false
                            }
                        )
                    }
                    items(options, key = { it.first }) { (key, name, count) ->
                        ListItem(
                            headlineContent = {
                                Text(name, fontWeight = if (key == numberKey) FontWeight.Bold else FontWeight.Normal)
                            },
                            supportingContent = { Text("$count call${if (count == 1) "" else "s"}") },
                            modifier = Modifier.clickable {
                                numberKey = key
                                showContactPicker = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showContactPicker = false }) { Text("Close") }
            }
        )
    }

    pendingApprove?.let { recording ->
        AlertDialog(
            onDismissRequest = { pendingApprove = null },
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen) },
            title = { Text("Mark as safe?") },
            text = {
                Text(
                    if (recording.number != null) {
                        "${recording.displayName}: all calls from this number will leave Unknown, and future calls won't appear here. They stay in Recordings."
                    } else {
                        "This recording has no number. Only this recording will leave Unknown. It stays in Recordings."
                    }
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.approve(context, recording)
                    pendingApprove = null
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { pendingApprove = null }) { Text("Cancel") }
            }
        )
    }

    pendingDelete?.let { recording ->
        DeleteRecordingDialog(
            recording = recording,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                if (player.currentPath == recording.file.absolutePath) player.stop()
                viewModel.delete(context, recording)
                pendingDelete = null
            }
        )
    }
}

/** Settings → Saved: every recording the user tapped Keep on. */
@Composable
internal fun SavedRecordingsPage(
    onBack: () -> Unit,
    viewModel: RecordingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val passwordGate = LocalPasswordGate.current
    val scope = rememberCoroutineScope()
    val recordingsVersion by CallRecorderEngine.recordingsVersion.collectAsState()
    var expandedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<CallRecording?>(null) }
    val player = rememberRecordingPlayer()

    LaunchedEffect(recordingsVersion) {
        viewModel.refresh(context)
    }

    val saved = remember(viewModel.state.recordings) { viewModel.state.recordings.filter { it.isStarred } }

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
                text = "Saved",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (saved.isEmpty()) {
            EmptyRecordingsMessage(
                icon = Icons.Default.StarBorder,
                title = "No saved recordings",
                body = "Tap Keep on a recording to save it here. Saved recordings are never auto-deleted."
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(saved, key = { it.file.absolutePath }) { recording ->
                    val path = recording.file.absolutePath
                    RecordingRow(
                        recording = recording,
                        isExpanded = expandedPath == path,
                        player = player,
                        showAddContact = recording.contactName == null,
                        showDate = true,
                        onToggleExpand = { expandedPath = if (expandedPath == path) null else path },
                        onPlayPause = {
                            expandedPath = path
                            player.toggle(recording.file)
                        },
                        onStar = {
                            if (player.currentPath == path) player.stop()
                            viewModel.toggleStar(context, recording)
                        },
                        onShare = { scope.launch { shareRecording(context, recording) } },
                        onDelete = {
                            passwordGate.guard("Enter the password to delete this recording.") {
                                pendingDelete = recording
                            }
                        },
                        onAddContact = { addToContacts(context, recording.number) },
                        onApprove = null
                    )
                }
            }
        }
    }

    pendingDelete?.let { recording ->
        DeleteRecordingDialog(
            recording = recording,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                if (player.currentPath == recording.file.absolutePath) player.stop()
                viewModel.delete(context, recording)
                pendingDelete = null
            }
        )
    }
}

@Composable
private fun DeleteRecordingDialog(
    recording: CallRecording,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete recording?") },
        text = { Text("${recording.displayName} - ${formatCallDuration(recording.durationSeconds)}. This cannot be undone.") },
        confirmButton = { Button(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun <T> DropdownChip(
    label: String,
    selected: Boolean,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        open = false
                    }
                )
            }
        }
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
    onAddContact: () -> Unit,
    onApprove: (() -> Unit)?,
    showDate: Boolean = false
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
                                contentDescription = "Kept",
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
                        val timePattern = if (showDate) "dd MMM, hh:mm a" else "hh:mm a"
                        Text(
                            text = numberPart +
                                SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(recording.recordedAt)) +
                                " · " + formatCallDuration(recording.durationSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onPlayPause) {
                    if (isCurrent && player.isPreparing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (onApprove != null) {
                    IconButton(onClick = onApprove) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Mark as safe",
                            tint = AccentGreen
                        )
                    }
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
                        label = if (recording.isStarred) "Kept" else "Keep",
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

/**
 * Plays one recording at a time. Encrypted recordings are decrypted into the
 * app's private cache first (MediaPlayer needs a seekable plain file); the
 * decrypted copy is deleted when playback stops.
 */
@Stable
class RecordingPlayer(private val context: Context, private val scope: CoroutineScope) {
    private var mediaPlayer: MediaPlayer? = null
    private var playbackFile: File? = null
    private var prepareJob: Job? = null

    var currentPath by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isPreparing by mutableStateOf(false)
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
        if (isPreparing && currentPath == file.absolutePath) return

        stop()
        currentPath = file.absolutePath
        isPreparing = true
        prepareJob = scope.launch {
            val playable = try {
                withContext(Dispatchers.IO) {
                    if (MediaCrypto.isEncrypted(file)) {
                        val dir = File(context.cacheDir, "playback").apply { mkdirs() }
                        dir.listFiles()?.forEach { it.delete() }
                        File(dir, "play_${System.currentTimeMillis()}.m4a").also { MediaCrypto.decryptToFile(file, it) }
                    } else {
                        file
                    }
                }
            } catch (_: Exception) {
                null
            }
            isPreparing = false
            if (playable == null || currentPath != file.absolutePath) {
                if (playable != null && playable != file) playable.delete()
                if (playable == null) {
                    currentPath = null
                    Toast.makeText(context, "Could not open this recording", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (playable != file) playbackFile = playable

            val created = MediaPlayer()
            try {
                created.setDataSource(playable.absolutePath)
                created.prepare()
                created.setOnCompletionListener {
                    isPlaying = false
                    positionMs = 0
                    it.seekTo(0)
                }
                created.start()
                mediaPlayer = created
                durationMs = created.duration.coerceAtLeast(1)
                positionMs = 0
                isPlaying = true
            } catch (_: Exception) {
                created.release()
                currentPath = null
            }
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
        prepareJob?.cancel()
        prepareJob = null
        mediaPlayer?.release()
        mediaPlayer = null
        playbackFile?.delete()
        playbackFile = null
        currentPath = null
        isPlaying = false
        isPreparing = false
        positionMs = 0
        durationMs = 0
    }
}

@Composable
private fun rememberRecordingPlayer(): RecordingPlayer {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val player = remember { RecordingPlayer(context, scope) }
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

private val chipDateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())

private fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

/** Groups calls from the same person, whatever the number format. */
private fun CallRecording.filterKey(): String =
    RecorderPrefs.normalizeNumber(number) ?: "file:${file.name}"

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

private suspend fun shareRecording(context: Context, recording: CallRecording) {
    try {
        // Share a decrypted copy from the app's private cache; old copies are cleaned up here.
        val shareFile = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val dayAgo = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            dir.listFiles()?.filter { it.lastModified() < dayAgo }?.forEach { it.delete() }
            File(dir, recording.file.name).also { MediaCrypto.decryptToFile(recording.file, it) }
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.callrecorder.fileprovider",
            shareFile
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
