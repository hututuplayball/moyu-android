package com.moyu.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.moyu.reader.page.PageSource
import com.moyu.reader.page.PageSourceException
import com.moyu.reader.page.pageCacheLimit
import com.moyu.reader.page.renderTargetSize
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PageSize(val width: Int, val height: Int)

class PdfException(message: String) : PageSourceException(message)

class PdfSession private constructor(private val renderer: PdfRenderer) : PageSource {
    override val pageCount: Int = renderer.pageCount

    private val mutex = Mutex()
    private var closed = false
    private val pageSizes = ConcurrentHashMap<Int, PageSize>()
    // Lazy items must recover their real aspect ratio synchronously after disposal.
    override fun cachedPageSize(index: Int): PageSize? = pageSizes[index]

    // Keep the next page or two ready; deep caches cost far more than they save on a phone.
    private val cacheLimit = pageCacheLimit()
    private val bitmaps = object : LruCache<RenderKey, Bitmap>(cacheLimit) {
        override fun sizeOf(key: RenderKey, value: Bitmap): Int = value.allocationByteCount
    }

    override suspend fun pageSize(index: Int): PageSize = access { context ->
        sizeOfPage(index, context)
    }

    /** [cache] is off for navigation thumbnails: they are small, transient, and would push real pages out. */
    override suspend fun render(index: Int, width: Int, cache: Boolean): Bitmap = access { context ->
        if (width <= 0) throw PdfException("页面渲染宽度必须大于零。")
        val size = sizeOfPage(index, context)
        val (targetWidth, targetHeight) = renderTargetSize(size, width, MAX_WIDTH, MAX_PAGE_HEIGHT, MAX_PIXELS)
        val key = RenderKey(index, targetWidth, targetHeight)
        val cached = if (cache) bitmaps.get(key) else null
        if (cached != null) {
            cached
        } else {
            val actualScale = min(
                targetWidth.toDouble() / size.width,
                targetHeight.toDouble() / size.height,
            ).toFloat()
            val transform = Matrix().apply { setScale(actualScale, actualScale) }
            val bitmap = renderBitmap(index, targetWidth, targetHeight, transform, context)
            val bytes = bitmap.allocationByteCount
            if (cache && bytes <= cacheLimit) {
                bitmaps.trimToSize(cacheLimit - bytes)
                bitmaps.put(key, bitmap)
            }
            bitmap
        }
    }

    override fun cachedRender(index: Int, width: Int): Bitmap? {
        if (width <= 0 || index !in 0 until pageCount) return null
        val size = pageSizes[index] ?: return null
        val (targetWidth, targetHeight) = renderTargetSize(size, width, MAX_WIDTH, MAX_PAGE_HEIGHT, MAX_PIXELS)
        return bitmaps.get(RenderKey(index, targetWidth, targetHeight))
    }

    /** Drops cached pages when the reader goes to the background; visible pages keep their own bitmap. */
    override fun trimMemory() {
        bitmaps.evictAll()
    }

    override suspend fun renderTile(
        index: Int,
        fullWidth: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): Bitmap = access { context ->
        if (fullWidth <= 0 || width <= 0 || height <= 0 || left < 0 || top < 0) {
            throw PdfException("页面分块尺寸或位置无效。")
        }
        val size = sizeOfPage(index, context)
        val scale = fullWidth.toDouble() / size.width
        val fullHeight = ceil(size.height * scale)
        if (left >= fullWidth || top.toDouble() >= fullHeight) {
            throw PdfException("页面分块超出页面范围。")
        }
        val targetWidth = minOf(width, MAX_TILE_EDGE, fullWidth - left)
        val targetHeight = minOf(
            height,
            MAX_TILE_EDGE,
            (fullHeight - top).coerceAtMost(MAX_TILE_EDGE.toDouble()).toInt(),
            MAX_PIXELS / targetWidth,
        ).coerceAtLeast(1)
        val transform = Matrix().apply {
            setScale(scale.toFloat(), scale.toFloat())
            postTranslate(-left.toFloat(), -top.toFloat())
        }
        renderBitmap(index, targetWidth, targetHeight, transform, context)
    }

    override suspend fun close() {
        withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock {
                if (!closed) {
                    closed = true
                    bitmaps.evictAll()
                    pageSizes.clear()
                    try {
                        renderer.close()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: IOException) {
                        throw PdfException("关闭 PDF 时发生读取错误。")
                    } catch (_: RuntimeException) {
                        throw PdfException("关闭 PDF 时发生读取错误。")
                    }
                }
            }
        }
    }

    private suspend fun <T> access(block: (CoroutineContext) -> T): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val context = currentCoroutineContext()
                context.ensureActive()
                if (closed) throw PdfException("PDF 已关闭，请重新打开。")
                try {
                    val result = block(context)
                    context.ensureActive()
                    result
                } catch (error: CancellationException) {
                    throw error
                } catch (error: PdfException) {
                    throw error
                } catch (_: OutOfMemoryError) {
                    throw PdfException("内存不足，无法渲染此页，请缩小页面后重试。")
                } catch (_: SecurityException) {
                    throw PdfException("此 PDF 已加密或受密码保护，暂不支持阅读。")
                } catch (_: IOException) {
                    throw PdfException("PDF 页面无法读取，文件可能已损坏。")
                } catch (_: RuntimeException) {
                    throw PdfException("PDF 页面无法读取，文件可能已损坏。")
                }
            }
        }

    private fun sizeOfPage(index: Int, context: CoroutineContext): PageSize {
        if (index !in 0 until pageCount) throw PdfException("PDF 页码超出范围。")
        return pageSizes[index] ?: renderer.openPage(index).use { page ->
            context.ensureActive()
            val size = PageSize(page.width, page.height)
            if (size.width <= 0 || size.height <= 0) {
                throw PdfException("PDF 页面尺寸无效，文件可能已损坏。")
            }
            pageSizes[index] = size
            size
        }
    }

    private fun renderBitmap(
        index: Int,
        width: Int,
        height: Int,
        transform: Matrix,
        context: CoroutineContext,
    ): Bitmap {
        context.ensureActive()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            renderer.openPage(index).use { page ->
                context.ensureActive()
                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                context.ensureActive()
            }
            // The renderer never touches this bitmap again, so no immutable copy is needed.
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    private data class RenderKey(val index: Int, val width: Int, val height: Int)

    companion object {
        private const val MAX_WIDTH = 2048
        private const val MAX_PAGE_HEIGHT = 8192
        private const val MAX_TILE_EDGE = 2048
        private const val MAX_PIXELS = 3_000_000

        suspend fun open(file: File): PdfSession = openResources { openRenderer(file) }

        /** Takes ownership of [descriptor]; it is closed with the session or on every failure path. */
        suspend fun open(descriptor: ParcelFileDescriptor): PdfSession =
            openResources { openRenderer(descriptor) }

        private suspend fun openResources(create: () -> PdfRenderer): PdfSession {
            var opened: PdfSession? = null
            try {
                return withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    val renderer = create()
                    var transferred = false
                    try {
                        val session = PdfSession(renderer)
                        if (session.pageCount <= 0) throw PdfException("此 PDF 没有可阅读的页面。")
                        opened = session
                        transferred = true
                        currentCoroutineContext().ensureActive()
                        session
                    } finally {
                        if (!transferred) closeUnpublished(renderer)
                    }
                }
            } catch (error: CancellationException) {
                // A dispatcher hand-off can cancel after resource creation but before delivery.
                try {
                    opened?.close()
                } catch (_: PdfException) {
                    // Preserve the original cancellation after releasing owned resources.
                }
                throw error
            } catch (error: PdfException) {
                throw error
            } catch (_: OutOfMemoryError) {
                throw PdfException("内存不足，无法打开此 PDF。")
            } catch (_: IOException) {
                throw PdfException("PDF 文件无法读取，文件可能已损坏。")
            } catch (_: RuntimeException) {
                throw PdfException("PDF 文件无效或已损坏，无法打开。")
            }
        }

        private fun openRenderer(file: File): PdfRenderer {
            val descriptor = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (_: FileNotFoundException) {
                throw PdfException("PDF 文件不存在或无法读取，请重新导入。")
            } catch (_: SecurityException) {
                throw PdfException("无法读取 PDF 文件，请重新导入。")
            }
            return openRenderer(descriptor)
        }

        private fun openRenderer(descriptor: ParcelFileDescriptor): PdfRenderer {
            var transferred = false
            try {
                return PdfRenderer(descriptor).also { transferred = true }
            } catch (error: CancellationException) {
                throw error
            } catch (_: SecurityException) {
                throw PdfException("此 PDF 已加密或受密码保护，暂不支持阅读。")
            } catch (_: IOException) {
                throw PdfException("PDF 文件无效或已损坏，无法打开。")
            } catch (_: RuntimeException) {
                throw PdfException("PDF 文件无效或已损坏，无法打开。")
            } finally {
                if (!transferred) {
                    try {
                        descriptor.close()
                    } catch (_: IOException) {
                        // Constructor failure leaves descriptor ownership with the caller.
                    }
                }
            }
        }

        private fun closeUnpublished(renderer: PdfRenderer) {
            try {
                renderer.close()
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                // Keep the validation failure rather than replacing it with a cleanup failure.
            } catch (_: RuntimeException) {
                // The session was never published and cannot be used again.
            }
        }
    }
}
