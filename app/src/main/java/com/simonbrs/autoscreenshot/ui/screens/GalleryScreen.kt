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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SCREENSHOT_ROOT_PATH = "/storage/emulated/0/Screenshot"

private val imageExtensions = setOf("webp", "jpg", "jpeg", "png")
private val dayFormatter = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault())
private val timeFormatter = SimpleDateFormat("hh:mm a", Locale.getDefault())

data class ScreenshotImage(
    val file: File,
    val dayLabel: String,
    val timeLabel: String,
    val lastModified: Long
)

data class ScreenshotDayGroup(
    val dayLabel: String,
    val images: List<ScreenshotImage>
)

@Composable
fun GalleryScreen(
    hasStorageAccess: Boolean,
    onOpenSetup: () -> Unit,
    onRefresh: () -> Unit
) {
    var groups by remember { mutableStateOf<List<ScreenshotDayGroup>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedImage by remember { mutableStateOf<ScreenshotImage?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(hasStorageAccess, refreshKey) {
        isLoading = true
        groups = if (hasStorageAccess) {
            loadScreenshotGroups()
        } else {
            emptyList()
        }
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Gallery",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = SCREENSHOT_ROOT_PATH,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = {
                    onRefresh()
                    refreshKey += 1
                }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh gallery")
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        when {
            !hasStorageAccess -> StorageNeededState(onOpenSetup = onOpenSetup)
            isLoading -> LoadingState()
            groups.isEmpty() -> EmptyGalleryState(
                onRefresh = {
                    onRefresh()
                    refreshKey += 1
                }
            )
            else -> ScreenshotGroupList(
                groups = groups,
                onImageClick = { selectedImage = it }
            )
        }
    }

    selectedImage?.let { image ->
        ScreenshotPreviewDialog(
            image = image,
            onDismiss = { selectedImage = null }
        )
    }
}

@Composable
private fun ScreenshotGroupList(
    groups: List<ScreenshotDayGroup>,
    onImageClick: (ScreenshotImage) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        items(groups, key = { it.dayLabel }) { group ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = group.dayLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(gridHeightFor(group.images.size)),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    userScrollEnabled = false
                ) {
                    items(group.images, key = { it.file.absolutePath }) { image ->
                        ScreenshotThumbnail(
                            image = image,
                            onClick = { onImageClick(image) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenshotThumbnail(
    image: ScreenshotImage,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            FileBitmapImage(
                file = image.file,
                targetSize = 360,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.56f)
            ) {
                Text(
                    text = image.timeLabel,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenshotPreviewDialog(
    image: ScreenshotImage,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                FileBitmapImage(
                    file = image.file,
                    targetSize = 1600,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
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
                                text = image.dayLabel,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 1
                            )
                            Text(
                                text = image.timeLabel,
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
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
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
                text = "Captured images will appear here by day, newest first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
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

private suspend fun loadScreenshotGroups(): List<ScreenshotDayGroup> = withContext(Dispatchers.IO) {
    val root = File(SCREENSHOT_ROOT_PATH)
    if (!root.exists() || !root.isDirectory) {
        return@withContext emptyList()
    }

    root.walkTopDown()
        .filter { file -> file.isFile && file.extension.lowercase(Locale.US) in imageExtensions }
        .map { file ->
            ScreenshotImage(
                file = file,
                dayLabel = dayLabelFor(file),
                timeLabel = timeFormatter.format(Date(file.lastModified())),
                lastModified = file.lastModified()
            )
        }
        .sortedByDescending { it.lastModified }
        .groupBy { it.dayLabel }
        .map { (dayLabel, images) ->
            ScreenshotDayGroup(dayLabel = dayLabel, images = images)
        }
}

private fun dayLabelFor(file: File): String {
    val day = file.parentFile
    val month = day?.parentFile
    val year = month?.parentFile

    val folderDate = runCatching {
        val yearName = year?.name ?: return@runCatching null
        val monthName = month?.name ?: return@runCatching null
        val dayName = day?.name ?: return@runCatching null
        val yearValue = yearName.toIntOrNull() ?: return@runCatching null
        val monthValue = monthName.toIntOrNull() ?: return@runCatching null
        val dayValue = dayName.toIntOrNull() ?: return@runCatching null
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(
            String.format(Locale.US, "%04d-%02d-%02d", yearValue, monthValue, dayValue)
        )
    }.getOrNull()

    return dayFormatter.format(folderDate ?: Date(file.lastModified()))
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
    var halfWidth = width / 2
    var halfHeight = height / 2

    while (halfWidth / sampleSize >= targetSize && halfHeight / sampleSize >= targetSize) {
        sampleSize *= 2
    }

    return sampleSize.coerceAtLeast(1)
}

private fun gridHeightFor(itemCount: Int): androidx.compose.ui.unit.Dp {
    val rows = ((itemCount - 1) / 3 + 1).coerceAtLeast(1)
    return (rows * 148).dp
}
