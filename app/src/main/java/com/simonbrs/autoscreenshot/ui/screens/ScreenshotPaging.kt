package com.simonbrs.autoscreenshot.ui.screens

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Screenshots shown at first, and added each time "Load more" is tapped. */
internal const val SCREENSHOT_PAGE_SIZE = 50

/**
 * Full-width footer for a screenshot grid: shows "X of Y" and a
 * "Load 50 more" button while there are more screenshots to show.
 */
internal fun LazyGridScope.loadMoreFooter(
    shown: Int,
    total: Int,
    onLoadMore: () -> Unit
) {
    if (total <= SCREENSHOT_PAGE_SIZE) return
    item(key = "load_more_footer", span = { GridItemSpan(maxLineSpan) }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Showing ${shown.coerceAtMost(total)} of $total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f)
            )
            if (shown < total) {
                OutlinedButton(onClick = onLoadMore) {
                    Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = "Load ${minOf(SCREENSHOT_PAGE_SIZE, total - shown)} more",
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * Keeps recently decoded thumbnails in memory so scrolling back up or
 * reopening a screen shows them instantly instead of decoding again.
 */
internal object ThumbnailCache {
    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt()

    private val cache = object : LruCache<String, Bitmap>(maxKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun key(path: String, lastModified: Long, targetSize: Int) = "$path|$lastModified|$targetSize"

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}
