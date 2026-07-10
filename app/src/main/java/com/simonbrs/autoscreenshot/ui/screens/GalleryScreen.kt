package com.simonbrs.autoscreenshot.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val SCREENSHOT_ROOT_PATH = "/storage/emulated/0/Screenshot"

private val imageExtensions = setOf("webp", "jpg", "jpeg", "png")
private val filenameTimeRegex = Regex("""(\d{2})_(\d{2})_(\d{2})""")
private val dateLabelFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy", Locale.getDefault())
private val shortDateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())
private val timeFormatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault())
private val hourFormatter = DateTimeFormatter.ofPattern("h a", Locale.getDefault())
private val hoursOfDay = (0..23).toList()

data class ScreenshotImage(
    val file: File,
    val capturedAt: LocalDateTime,
    val date: LocalDate,
    val hour: Int,
    val dayLabel: String,
    val timeLabel: String,
    val lastModified: Long
)

data class GalleryDateOption(
    val date: LocalDate,
    val label: String,
    val count: Int
)

data class GalleryHourOption(
    val hour: Int,
    val label: String,
    val count: Int
)

data class GalleryUiState(
    val isLoading: Boolean = true,
    val allImages: List<ScreenshotImage> = emptyList(),
    val visibleImages: List<ScreenshotImage> = emptyList(),
    val dateOptions: List<GalleryDateOption> = emptyList(),
    val hourOptions: List<GalleryHourOption> = hoursOfDay.map { hour ->
        GalleryHourOption(hour = hour, label = hourLabel(hour), count = 0)
    },
    val selectedDate: LocalDate? = null,
    val selectedHour: Int? = null
)

class GalleryViewModel : ViewModel() {
    var uiState by mutableStateOf(GalleryUiState())
        private set

    private var refreshJob: Job? = null

    fun refresh(hasStorageAccess: Boolean) {
        refreshJob?.cancel()

        if (!hasStorageAccess) {
            uiState = GalleryUiState(isLoading = false)
            return
        }

        refreshJob = viewModelScope.launch {
            uiState = uiState.copy(isLoading = true)
            val images = loadScreenshotImages()
            val selectedDate = uiState.selectedDate?.takeIf { selected ->
                images.any { image -> image.date == selected }
            }
            val selectedHour = uiState.selectedHour?.takeIf { hour ->
                selectedDate != null && images.any { image ->
                    image.date == selectedDate && image.hour == hour
                }
            }

            uiState = buildState(
                images = images,
                selectedDate = selectedDate,
                selectedHour = selectedHour,
                isLoading = false
            )
        }
    }

    fun selectDate(date: LocalDate) {
        uiState = buildState(
            images = uiState.allImages,
            selectedDate = date,
            selectedHour = null,
            isLoading = false
        )
    }

    fun selectHour(hour: Int) {
        val date = uiState.selectedDate ?: return
        uiState = buildState(
            images = uiState.allImages,
            selectedDate = date,
            selectedHour = hour,
            isLoading = false
        )
    }

    fun clearFilter() {
        uiState = buildState(
            images = uiState.allImages,
            selectedDate = null,
            selectedHour = null,
            isLoading = false
        )
    }

    private fun buildState(
        images: List<ScreenshotImage>,
        selectedDate: LocalDate?,
        selectedHour: Int?,
        isLoading: Boolean
    ): GalleryUiState {
        val dateOptions = images
            .groupBy { it.date }
            .map { (date, dateImages) ->
                GalleryDateOption(
                    date = date,
                    label = dateLabelFormatter.format(date),
                    count = dateImages.size
                )
            }
            .sortedByDescending { it.date }

        val visibleImages = images.filter { image ->
            val dateMatches = selectedDate == null || image.date == selectedDate
            val hourMatches = selectedHour == null || image.hour == selectedHour
            dateMatches && hourMatches
        }

        val hourOptions = hoursOfDay.map { hour ->
            GalleryHourOption(
                hour = hour,
                label = hourLabel(hour),
                count = images.count { image -> image.date == selectedDate && image.hour == hour }
            )
        }

        return GalleryUiState(
            isLoading = isLoading,
            allImages = images,
            visibleImages = visibleImages,
            dateOptions = dateOptions,
            hourOptions = hourOptions,
            selectedDate = selectedDate,
            selectedHour = selectedHour
        )
    }
}

private enum class FilterSheetStep {
    Dates,
    Hours
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    hasStorageAccess: Boolean,
    onOpenSetup: () -> Unit,
    onRefresh: () -> Unit,
    viewModel: GalleryViewModel = viewModel()
) {
    val uiState = viewModel.uiState
    var selectedImage by remember { mutableStateOf<ScreenshotImage?>(null) }
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    var filterSheetStep by remember { mutableStateOf(FilterSheetStep.Dates) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(hasStorageAccess) {
        viewModel.refresh(hasStorageAccess)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        GalleryTopBar(
            uiState = uiState,
            onOpenFilter = {
                filterSheetStep = FilterSheetStep.Dates
                showFilterSheet = true
            },
            onClearFilter = viewModel::clearFilter,
            onRefresh = {
                onRefresh()
                viewModel.refresh(hasStorageAccess)
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        when {
            !hasStorageAccess -> StorageNeededState(onOpenSetup = onOpenSetup)
            uiState.isLoading -> LoadingState()
            uiState.allImages.isEmpty() -> EmptyGalleryState(
                onRefresh = {
                    onRefresh()
                    viewModel.refresh(hasStorageAccess)
                }
            )
            uiState.visibleImages.isEmpty() -> EmptyFilterState(
                filterLabel = uiState.activeFilterLabel(),
                onChangeFilter = {
                    filterSheetStep = FilterSheetStep.Dates
                    showFilterSheet = true
                },
                onClearFilter = viewModel::clearFilter
            )
            else -> ScreenshotGrid(
                images = uiState.visibleImages,
                onImageClick = { selectedImage = it }
            )
        }
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = sheetState
        ) {
            GalleryFilterSheet(
                step = filterSheetStep,
                uiState = uiState,
                onBackToDates = { filterSheetStep = FilterSheetStep.Dates },
                onDateSelected = { date ->
                    viewModel.selectDate(date)
                    filterSheetStep = FilterSheetStep.Hours
                },
                onHourSelected = { hour ->
                    viewModel.selectHour(hour)
                    showFilterSheet = false
                }
            )
        }
    }

    selectedImage?.let { image ->
        ScreenshotPreviewDialog(
            images = uiState.visibleImages,
            initialImage = image,
            onDismiss = { selectedImage = null }
        )
    }
}

@Composable
private fun GalleryTopBar(
    uiState: GalleryUiState,
    onOpenFilter: () -> Unit,
    onClearFilter: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Gallery",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = uiState.activeFilterLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (uiState.selectedDate != null || uiState.selectedHour != null) {
            TextButton(onClick = onClearFilter) {
                Text("Clear")
            }
        }
        IconButton(onClick = onOpenFilter) {
            Icon(Icons.Default.DateRange, contentDescription = "Filter screenshots by date")
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh gallery")
        }
    }
}

@Composable
private fun ScreenshotGrid(
    images: List<ScreenshotImage>,
    onImageClick: (ScreenshotImage) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "${images.size} screenshot${if (images.size == 1) "" else "s"}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(images, key = { it.file.absolutePath }) { image ->
                ScreenshotThumbnail(
                    image = image,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.72f),
                    onClick = { onImageClick(image) }
                )
            }
        }
    }
}

@Composable
private fun ScreenshotThumbnail(
    image: ScreenshotImage,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            FileBitmapImage(
                file = image.file,
                targetSize = 520,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.58f)
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(
                        text = image.timeLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        maxLines = 1
                    )
                    Text(
                        text = image.dayLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryFilterSheet(
    step: FilterSheetStep,
    uiState: GalleryUiState,
    onBackToDates: () -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onHourSelected: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp)
    ) {
        when (step) {
            FilterSheetStep.Dates -> DateSelectionContent(
                dateOptions = uiState.dateOptions,
                selectedDate = uiState.selectedDate,
                onDateSelected = onDateSelected
            )

            FilterSheetStep.Hours -> HourSelectionContent(
                selectedDate = uiState.selectedDate,
                selectedHour = uiState.selectedHour,
                hourOptions = uiState.hourOptions,
                onBackToDates = onBackToDates,
                onHourSelected = onHourSelected
            )
        }
    }
}

@Composable
private fun DateSelectionContent(
    dateOptions: List<GalleryDateOption>,
    selectedDate: LocalDate?,
    onDateSelected: (LocalDate) -> Unit
) {
    Text(
        text = "Choose date",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(12.dp))
    LazyColumn(
        modifier = Modifier.heightIn(max = 500.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(dateOptions, key = { it.date.toString() }) { option ->
            val selected = option.date == selectedDate
            ListItem(
                headlineContent = {
                    Text(
                        text = option.label,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                supportingContent = {
                    Text("${option.count} screenshot${if (option.count == 1) "" else "s"}")
                },
                trailingContent = {
                    if (selected) {
                        Text(
                            text = "Selected",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onDateSelected(option.date) }
            )
        }
    }
}

@Composable
private fun HourSelectionContent(
    selectedDate: LocalDate?,
    selectedHour: Int?,
    hourOptions: List<GalleryHourOption>,
    onBackToDates: () -> Unit,
    onHourSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackToDates) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to dates")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Choose hour",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = selectedDate?.let { shortDateFormatter.format(it) } ?: "Select a date",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
            )
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.height(390.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(hourOptions, key = { it.hour }) { option ->
            val selected = option.hour == selectedHour
            val colors = if (selected) {
                ButtonDefaults.buttonColors()
            } else {
                ButtonDefaults.outlinedButtonColors()
            }

            OutlinedButton(
                onClick = { onHourSelected(option.hour) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(8.dp),
                colors = colors,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = option.count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenshotPreviewDialog(
    images: List<ScreenshotImage>,
    initialImage: ScreenshotImage,
    onDismiss: () -> Unit
) {
    val initialIndex = images.indexOfFirst {
        it.file.absolutePath == initialImage.file.absolutePath
    }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialIndex) { images.size }
    val currentImage = images.getOrNull(pagerState.currentPage) ?: initialImage

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = images.size > 1
                ) { page ->
                    val image = images[page]
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        FileBitmapImage(
                            file = image.file,
                            targetSize = 1600,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                    color = Color.Black.copy(alpha = 0.72f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentImage.dayLabel,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 1
                            )
                            Text(
                                text = buildString {
                                    append(currentImage.timeLabel)
                                    if (images.size > 1) {
                                        append("  ")
                                        append(pagerState.currentPage + 1)
                                        append("/")
                                        append(images.size)
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close image",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileBitmapImage(
    file: File,
    targetSize: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale
) {
    var bitmap by remember(file.absolutePath, targetSize) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(file.absolutePath, targetSize) {
        bitmap = withContext(Dispatchers.IO) {
            decodeSampledBitmap(file, targetSize)
        }
    }

    val currentBitmap = bitmap
    if (currentBitmap == null) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.36f),
                modifier = Modifier.size(32.dp)
            )
        }
    } else {
        Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = file.name,
            modifier = modifier,
            contentScale = contentScale
        )
    }
}

@Composable
private fun StorageNeededState(onOpenSetup: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Storage access is needed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Allow storage setup to show screenshots saved in the Screenshot folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))
            Button(onClick = onOpenSetup) {
                Text("Open Setup")
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyGalleryState(onRefresh: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "No screenshots yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Captured images will appear here newest first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))
            OutlinedButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun EmptyFilterState(
    filterLabel: String,
    onChangeFilter: () -> Unit,
    onClearFilter: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.DateRange,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "No screenshots in this hour",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = filterLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChangeFilter) {
                    Text("Change")
                }
                Button(onClick = onClearFilter) {
                    Text("Clear")
                }
            }
        }
    }
}

private suspend fun loadScreenshotImages(): List<ScreenshotImage> = withContext(Dispatchers.IO) {
    val root = File(SCREENSHOT_ROOT_PATH)
    if (!root.exists() || !root.isDirectory) {
        return@withContext emptyList()
    }

    root.walkTopDown()
        .filter { file -> file.isFile && file.extension.lowercase(Locale.US) in imageExtensions }
        .map { file -> file.toScreenshotImage(root) }
        .sortedByDescending { it.capturedAt }
        .toList()
}

private fun File.toScreenshotImage(root: File): ScreenshotImage {
    val capturedAt = captureTimeFromPath(root, this)
    return ScreenshotImage(
        file = this,
        capturedAt = capturedAt,
        date = capturedAt.toLocalDate(),
        hour = capturedAt.hour,
        dayLabel = dateLabelFormatter.format(capturedAt),
        timeLabel = timeFormatter.format(capturedAt),
        lastModified = lastModified()
    )
}

private fun captureTimeFromPath(root: File, file: File): LocalDateTime {
    val fallback = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(file.lastModified()),
        ZoneId.systemDefault()
    )

    val segments = runCatching {
        root.toPath()
            .relativize(file.toPath())
            .iterator()
            .asSequence()
            .map { it.toString() }
            .toList()
    }.getOrDefault(emptyList())

    if (segments.size < 4) {
        return fallback
    }

    val year = segments.getOrNull(0)?.toIntOrNull() ?: return fallback
    val month = segments.getOrNull(1)?.toIntOrNull() ?: return fallback
    val day = segments.getOrNull(2)?.toIntOrNull() ?: return fallback
    val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return fallback

    val filenameMatch = filenameTimeRegex.find(file.nameWithoutExtension)
    val filenameHour = filenameMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
    val minute = filenameMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
    val second = filenameMatch?.groupValues?.getOrNull(3)?.toIntOrNull() ?: 0

    val folderHour = segments.getOrNull(3)
        ?.toIntOrNull()
        ?.takeIf { hour -> hour in 0..23 && segments.size >= 5 }

    val hour = folderHour ?: filenameHour ?: fallback.hour
    val time = runCatching {
        LocalTime.of(
            hour.coerceIn(0, 23),
            minute.coerceIn(0, 59),
            second.coerceIn(0, 59)
        )
    }.getOrDefault(fallback.toLocalTime())

    return LocalDateTime.of(date, time)
}

private fun decodeSampledBitmap(file: File, targetSize: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(file.absolutePath, bounds)

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetSize)
    }

    return BitmapFactory.decodeFile(file.absolutePath, options)
}

private fun calculateInSampleSize(width: Int, height: Int, targetSize: Int): Int {
    var sampleSize = 1
    val halfWidth = width / 2
    val halfHeight = height / 2

    while (halfWidth / sampleSize >= targetSize && halfHeight / sampleSize >= targetSize) {
        sampleSize *= 2
    }

    return sampleSize.coerceAtLeast(1)
}

private fun GalleryUiState.activeFilterLabel(): String {
    val date = selectedDate ?: return "All screenshots"
    val dateLabel = shortDateFormatter.format(date)
    val hour = selectedHour ?: return dateLabel
    return "$dateLabel, ${hourLabel(hour)}"
}

private fun hourLabel(hour: Int): String {
    return hourFormatter.format(LocalTime.of(hour.coerceIn(0, 23), 0))
}
