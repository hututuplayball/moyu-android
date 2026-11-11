package com.moyu.reader.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moyu.reader.data.BookEntity
import com.moyu.reader.data.FORMAT_ARCHIVE
import com.moyu.reader.data.FORMAT_EPUB
import com.moyu.reader.data.FORMAT_FOLDER
import com.moyu.reader.data.FORMAT_TEXT
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decoded covers outlive a shelf. Switching kinds drops the whole grid, and without a cache the same
 * JPEGs were decoded and uploaded again on every switch — the dropped frames at the start of the slide.
 */
internal val coverCache = object : LruCache<String, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceAtLeast(4 * 1024),
) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

/** Drops decoded covers when the process is hidden or the system is running low on memory. */
internal fun clearCoverCache() {
    coverCache.evictAll()
}

/** The cover file's timestamp is part of the key, so re-importing a book refreshes its artwork. */
internal fun coverKey(path: String, importedAt: Long): String {
    val modified = runCatching { File(path).lastModified() }.getOrDefault(0L)
    return "$path@$importedAt@$modified"
}

internal fun coverPath(dir: File, book: BookEntity): String =
    File(File(dir, "covers"), "${book.id}.jpg").absolutePath

internal fun decodeCover(path: String): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sampleSize = 1
    // Covers are drawn at most ~150dp wide; 640px is still sharp there and a third of the texture
    // upload a 1000px bitmap costs on this class of device.
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 640) sampleSize *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
}.getOrNull()

@Composable
internal fun PdfCover(book: BookEntity, modifier: Modifier = Modifier) {
    val filesDir = LocalContext.current.filesDir
    val path = remember(filesDir, book.id) { coverPath(filesDir, book) }
    // Recompute the key on composition: a re-import rewrites the file and must invalidate its decode.
    val cacheKey = coverKey(path, book.importedAt)
    var bitmap by remember(cacheKey) { mutableStateOf(coverCache.get(cacheKey)) }
    LaunchedEffect(cacheKey) {
        if (bitmap != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) { decodeCover(path) }
        if (decoded != null) coverCache.put(cacheKey, decoded)
        bitmap = decoded
    }
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))) {
        val cover = bitmap
        if (cover != null) {
            Image(cover.asImageBitmap(), null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape(5.dp)).background(Color.White))
        } else {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    when (book.format) {
                        FORMAT_FOLDER -> "文件夹"
                        FORMAT_ARCHIVE -> "ZIP"
                        FORMAT_TEXT -> "TXT"
                        FORMAT_EPUB -> "EPUB"
                        else -> "PDF"
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
