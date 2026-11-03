package com.moyu.reader.page

import android.graphics.Bitmap
import com.moyu.reader.pdf.PageSize
import java.io.IOException
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

open class PageSourceException(message: String) : IOException(message)

/** Rendering surface shared by PDF documents and image-based comics. */
interface PageSource {
    val pageCount: Int
    fun cachedPageSize(index: Int): PageSize?
    suspend fun pageSize(index: Int): PageSize
    suspend fun render(index: Int, width: Int, cache: Boolean = true): Bitmap
    suspend fun renderTile(index: Int, fullWidth: Int, left: Int, top: Int, width: Int, height: Int): Bitmap
    fun trimMemory()
    suspend fun close()

    /** A page at this exact width that is already in the render cache, without decoding a new one. */
    fun cachedRender(index: Int, width: Int): Bitmap? = null

    /**
     * True when drawing a full page is cheap enough to stay sharp while the reader is swiping.
     * PDF pages and comic scans keep the half-scale motion preview; flowing text does not need it.
     */
    val sharpWhileScrolling: Boolean get() = false

    /**
     * Changes whenever pages are replaced in the same source instance. UI state that caches a
     * rendered page must include this key, otherwise a chapter jump can keep showing the previous
     * chapter's bitmap while the page list, progress and table of contents already moved on.
     */
    val renderVersion: Int get() = 0
}

/** Current page plus three on each side: enough to hide two page turns in either direction. */
internal fun pageCacheLimit(): Int = min(
    Runtime.getRuntime().maxMemory() / 6L,
    48L * 1024L * 1024L,
).coerceAtLeast(1L).toInt()

/** One sizing rule for rendering and cache lookup, so a prefetched page is a real hit later. */
internal fun renderTargetSize(
    size: PageSize,
    width: Int,
    maxWidth: Int,
    maxHeight: Int,
    maxPixels: Int,
): Pair<Int, Int> {
    val scale = minOf(
        min(width, maxWidth).toDouble() / size.width,
        maxHeight.toDouble() / size.height,
        sqrt(maxPixels.toDouble() / (size.width.toDouble() * size.height)),
    )
    return floor(size.width * scale).toInt().coerceAtLeast(1) to
        floor(size.height * scale).toInt().coerceAtLeast(1)
}
