package com.moyu.reader.page

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.LruCache
import com.moyu.reader.pdf.PageSize
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ComicFolderPage(val name: String, val path: String, val uri: Uri, val size: Long)

data class ComicFolder(val name: String, val pages: List<ComicFolderPage>)

/** Lists a picked folder through the Storage Access Framework, including chapter subfolders. */
object ComicFolders {
    private const val MAX_DEPTH = 4
    private const val MAX_PAGES = 5000
    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    fun list(resolver: ContentResolver, treeUri: Uri): ComicFolder = try {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val name = queryName(resolver, rootUri)
            ?: treeUri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "未命名"
        val pages = mutableListOf<ComicFolderPage>()
        walk(resolver, treeUri, rootId, "", 0, pages)
        pages.sortWith { first, second -> COMIC_PATH_ORDER.compare(first.path, second.path) }
        ComicFolder(name, pages)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: PageSourceException) {
        throw error
    } catch (_: SecurityException) {
        throw PageSourceException("无法读取所选文件夹，请重新选择并授予访问权限。")
    } catch (_: IllegalArgumentException) {
        throw PageSourceException("所选文件夹不可用，请重新选择。")
    } catch (_: IOException) {
        throw PageSourceException("读取文件夹失败，请检查该文件夹或云端网络连接。")
    } catch (_: RuntimeException) {
        throw PageSourceException("无法读取所选文件夹，请重新选择。")
    }

    private fun walk(
        resolver: ContentResolver,
        treeUri: Uri,
        documentId: String,
        prefix: String,
        depth: Int,
        pages: MutableList<ComicFolderPage>,
    ) {
        if (depth > MAX_DEPTH) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val cursor = resolver.query(children, PROJECTION, null, null, null)
            ?: throw PageSourceException("无法读取所选文件夹，请重新选择。")
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (it.moveToNext()) {
                val childId = it.getString(idColumn) ?: continue
                val childName = it.getString(nameColumn) ?: continue
                if (isJunkName(childName)) continue
                val path = prefix + childName
                val mime = if (mimeColumn >= 0) it.getString(mimeColumn) else null
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(resolver, treeUri, childId, "$path/", depth + 1, pages)
                } else if (isComicImage(childName)) {
                    if (pages.size >= MAX_PAGES) {
                        throw PageSourceException("图片数量超过 $MAX_PAGES 张，暂不支持。")
                    }
                    val size = if (sizeColumn >= 0 && !it.isNull(sizeColumn)) it.getLong(sizeColumn) else 0L
                    pages += ComicFolderPage(
                        name = childName,
                        path = path,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                        size = size,
                    )
                }
            }
        }
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: Exception) {
        null
    }
}

/** Comic pages stored inside a ZIP/CBZ archive or a folder of image files. */
class ImagePageSource private constructor(
    private val pages: List<ComicPage>,
    private val openStream: (ComicPage) -> InputStream,
    private val release: () -> Unit,
) : PageSource {
    override val pageCount: Int = pages.size

    private val mutex = Mutex()
    private var closed = false
    private val pageSizes = ConcurrentHashMap<Int, PageSize>()
    private val cacheLimit = pageCacheLimit()
    private val bitmaps = object : LruCache<RenderKey, Bitmap>(cacheLimit) {
        override fun sizeOf(key: RenderKey, value: Bitmap): Int = value.allocationByteCount
    }

    override fun cachedPageSize(index: Int): PageSize? = pageSizes[index]

    override suspend fun pageSize(index: Int): PageSize = access { context ->
        sizeOfPage(index, context)
    }

    override suspend fun render(index: Int, width: Int, cache: Boolean): Bitmap = access { context ->
        if (width <= 0) throw PageSourceException("页面渲染宽度必须大于零。")
        val size = sizeOfPage(index, context)
        val (targetWidth, targetHeight) = renderTargetSize(size, width, MAX_WIDTH, MAX_PAGE_HEIGHT, MAX_PIXELS)
        val key = RenderKey(index, targetWidth, targetHeight)
        val cached = if (cache) bitmaps.get(key) else null
        if (cached != null) {
            cached
        } else {
            val bitmap = decodePage(index, size, targetWidth, targetHeight, context)
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

    override suspend fun renderTile(
        index: Int,
        fullWidth: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): Bitmap = access { context ->
        if (fullWidth <= 0 || width <= 0 || height <= 0 || left < 0 || top < 0) {
            throw PageSourceException("页面分块尺寸或位置无效。")
        }
        val size = sizeOfPage(index, context)
        val scale = fullWidth.toDouble() / size.width
        val fullHeight = ceil(size.height * scale)
        if (left >= fullWidth || top.toDouble() >= fullHeight) {
            throw PageSourceException("页面分块超出页面范围。")
        }
        val targetWidth = minOf(width, MAX_TILE_EDGE, fullWidth - left)
        val targetHeight = minOf(
            height,
            MAX_TILE_EDGE,
            (fullHeight - top).coerceAtMost(MAX_TILE_EDGE.toDouble()).toInt(),
            MAX_PIXELS / targetWidth,
        ).coerceAtLeast(1)
        decodeTile(index, size, fullWidth, left, top, targetWidth, targetHeight, context)
    }

    override fun trimMemory() {
        bitmaps.evictAll()
    }

    override suspend fun close() {
        withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock {
                if (!closed) {
                    closed = true
                    bitmaps.evictAll()
                    pageSizes.clear()
                    try {
                        release()
                    } catch (_: IOException) {
                        // The session is already unusable; a zip/file close failure changes nothing.
                    } catch (_: RuntimeException) {
                        // Same: nothing left to recover.
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
                if (closed) throw PageSourceException("文件已关闭，请重新打开。")
                try {
                    val result = block(context)
                    context.ensureActive()
                    result
                } catch (error: CancellationException) {
                    throw error
                } catch (error: PageSourceException) {
                    throw error
                } catch (_: OutOfMemoryError) {
                    throw PageSourceException("内存不足，无法渲染此页，请缩小页面后重试。")
                } catch (_: SecurityException) {
                    throw PageSourceException("无法读取图片，请重新选择文件或文件夹并授予访问权限。")
                } catch (_: IOException) {
                    throw PageSourceException("图片页面无法读取，文件可能已损坏。")
                } catch (_: RuntimeException) {
                    throw PageSourceException("图片页面无法读取，文件可能已损坏。")
                }
            }
        }

    private fun sizeOfPage(index: Int, context: CoroutineContext): PageSize {
        if (index !in 0 until pageCount) throw PageSourceException("图片页码超出范围。")
        pageSizes[index]?.let { return it }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            pageStream(pages[index]).use { BitmapFactory.decodeStream(it, null, options) }
        } catch (error: PageSourceException) {
            throw error
        } catch (_: IOException) {
            throw PageSourceException("图片页面无法读取，文件可能已损坏。")
        }
        context.ensureActive()
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) {
            throw PageSourceException("第 ${index + 1} 页图片无法读取，可能已损坏。")
        }
        return PageSize(width, height).also { pageSizes[index] = it }
    }

    private fun decodePage(
        index: Int,
        size: PageSize,
        targetWidth: Int,
        targetHeight: Int,
        context: CoroutineContext,
    ): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(size.width, size.height, targetWidth, targetHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = openBitmap(pages[index], index, options)
        context.ensureActive()
        if (decoded.width == targetWidth && decoded.height == targetHeight) return decoded
        return try {
            Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
        } finally {
            decoded.recycle()
        }
    }

    private fun decodeTile(
        index: Int,
        size: PageSize,
        fullWidth: Int,
        left: Int,
        top: Int,
        targetWidth: Int,
        targetHeight: Int,
        context: CoroutineContext,
    ): Bitmap {
        val page = pages[index]
        val scale = fullWidth.toDouble() / size.width
        val sourceLeft = floor(left / scale).toInt().coerceIn(0, size.width - 1)
        val sourceTop = floor(top / scale).toInt().coerceIn(0, size.height - 1)
        val sourceRight = ceil((left + targetWidth) / scale).toInt()
            .coerceAtMost(size.width).coerceAtLeast(sourceLeft + 1)
        val sourceBottom = ceil((top + targetHeight) / scale).toInt()
            .coerceAtMost(size.height).coerceAtLeast(sourceTop + 1)
        val region = Rect(sourceLeft, sourceTop, sourceRight, sourceBottom)
        var stream: InputStream? = null
        var decoder: BitmapRegionDecoder? = null
        try {
            stream = pageStream(page)
            decoder = BitmapRegionDecoder.newInstance(stream, false)
            context.ensureActive()
            if (decoder != null) {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(region.width(), region.height(), targetWidth, targetHeight)
                }
                val bitmap = decoder.decodeRegion(region, options)
                context.ensureActive()
                if (bitmap != null) {
                    if (bitmap.width == targetWidth && bitmap.height == targetHeight) return bitmap
                    return try {
                        Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            // Formats without region support (GIF/BMP) fall back to a bounded full-page decode.
        } catch (_: RuntimeException) {
            // Same fallback as above.
        } finally {
            try {
                decoder?.recycle()
            } catch (_: RuntimeException) {
                // The region decoder is already unusable.
            }
            try {
                stream?.close()
            } catch (_: IOException) {
                // Nothing else holds this stream.
            }
        }
        return cropFromPage(index, size, fullWidth, left, top, targetWidth, targetHeight, context)
    }

    private fun cropFromPage(
        index: Int,
        size: PageSize,
        fullWidth: Int,
        left: Int,
        top: Int,
        targetWidth: Int,
        targetHeight: Int,
        context: CoroutineContext,
    ): Bitmap {
        val scale = minOf(
            min(fullWidth, MAX_WIDTH).toDouble() / size.width,
            MAX_PAGE_HEIGHT.toDouble() / size.height,
            sqrt(MAX_PIXELS.toDouble() / (size.width.toDouble() * size.height)),
        )
        val baseWidth = floor(size.width * scale).toInt().coerceAtLeast(1)
        val baseHeight = floor(size.height * scale).toInt().coerceAtLeast(1)
        val base = decodePage(index, size, baseWidth, baseHeight, context)
        val baseScale = base.width.toDouble() / fullWidth
        val cropLeft = (left * baseScale).toInt().coerceIn(0, base.width - 1)
        val cropTop = (top * baseScale).toInt().coerceIn(0, base.height - 1)
        val cropWidth = (targetWidth * baseScale).toInt().coerceIn(1, base.width - cropLeft)
        val cropHeight = (targetHeight * baseScale).toInt().coerceIn(1, base.height - cropTop)
        val crop = try {
            Bitmap.createBitmap(base, cropLeft, cropTop, cropWidth, cropHeight)
        } catch (error: Throwable) {
            base.recycle()
            throw error
        }
        if (crop.width == targetWidth && crop.height == targetHeight) {
            if (crop !== base) base.recycle()
            return crop
        }
        return try {
            Bitmap.createScaledBitmap(crop, targetWidth, targetHeight, true)
        } finally {
            if (crop !== base) crop.recycle()
            base.recycle()
        }
    }

    private fun openBitmap(page: ComicPage, index: Int, options: BitmapFactory.Options): Bitmap {
        val bitmap = try {
            pageStream(page).use { BitmapFactory.decodeStream(it, null, options) }
        } catch (error: PageSourceException) {
            throw error
        } catch (_: IOException) {
            throw PageSourceException("图片页面无法读取，文件可能已损坏。")
        }
        return bitmap ?: throw PageSourceException("第 ${index + 1} 页图片无法解码，可能已损坏。")
    }

    private fun pageStream(page: ComicPage): InputStream = try {
        openStream(page)
    } catch (error: PageSourceException) {
        throw error
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        throw PageSourceException("图片页面无法读取，文件可能已损坏。")
    } catch (_: RuntimeException) {
        throw PageSourceException("图片页面无法读取，文件可能已损坏。")
    }

    private data class RenderKey(val index: Int, val width: Int, val height: Int)

    private class ComicPage(val name: String, val entry: ZipEntry?, val uri: Uri?)

    companion object {
        private const val MAX_WIDTH = 2048
        private const val MAX_PAGE_HEIGHT = 8192
        private const val MAX_TILE_EDGE = 2048
        private const val MAX_PIXELS = 3_000_000
        private const val MAX_DECODE_PIXELS = 8_000_000L
        // A comic archive is metadata-only until a page is rendered, but a hostile central
        // directory must still be bounded before it becomes a list in memory.
        private const val MAX_ZIP_ENTRIES = 65_536
        private const val MAX_ZIP_PAGES = 5000
        private val GBK: Charset = Charset.forName("GBK")

        suspend fun open(file: File): ImagePageSource = withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            var zip: ZipFile? = null
            try {
                val opened = openZip(file)
                zip = opened
                createZipSource(opened)
            } catch (error: Throwable) {
                try {
                    zip?.close()
                } catch (_: IOException) {
                    // Preserve the original failure.
                }
                throw mapOpenError(error)
            }
        }

        /** Takes ownership of [descriptor]; it is closed with the session or on every failure path. */
        suspend fun open(descriptor: ParcelFileDescriptor): ImagePageSource = withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            var zip: ZipFile? = null
            try {
                val opened = openZip(File("/proc/self/fd/${descriptor.fd}"))
                zip = opened
                createZipSource(opened) {
                    opened.close()
                    descriptor.close()
                }
            } catch (error: Throwable) {
                try {
                    zip?.close()
                } catch (_: IOException) {
                    // Preserve the original failure.
                }
                try {
                    descriptor.close()
                } catch (_: IOException) {
                    // Same.
                }
                throw mapOpenError(error)
            }
        }

        /**
         * Opens the archive with a caller-supplied page order instead of sorting every image inside
         * it: an EPUB's spine says which picture is page one, and a plain name sort would not.
         */
        suspend fun open(file: File, pagePaths: List<String>): ImagePageSource = withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            var zip: ZipFile? = null
            try {
                val opened = openZip(file)
                zip = opened
                createZipSource(opened, pagePaths)
            } catch (error: Throwable) {
                try {
                    zip?.close()
                } catch (_: IOException) {
                    // Preserve the original failure.
                }
                throw mapOpenError(error)
            }
        }

        /** Takes ownership of [descriptor], like the archive overload above. */
        suspend fun open(descriptor: ParcelFileDescriptor, pagePaths: List<String>): ImagePageSource =
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                var zip: ZipFile? = null
                try {
                    val opened = openZip(File("/proc/self/fd/${descriptor.fd}"))
                    zip = opened
                    createZipSource(opened, pagePaths) {
                        opened.close()
                        descriptor.close()
                    }
                } catch (error: Throwable) {
                    try {
                        zip?.close()
                    } catch (_: IOException) {
                        // Preserve the original failure.
                    }
                    try {
                        descriptor.close()
                    } catch (_: IOException) {
                        // Same.
                    }
                    throw mapOpenError(error)
                }
            }

        suspend fun openFolder(resolver: ContentResolver, treeUri: Uri): ImagePageSource =
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                openFolder(resolver, ComicFolders.list(resolver, treeUri))
            }

        fun openFolder(resolver: ContentResolver, folder: ComicFolder): ImagePageSource {
            if (folder.pages.isEmpty()) throw PageSourceException("这个文件夹里没有图片，漫画需要 JPG、PNG、WebP 等图片文件。")
            val pages = folder.pages.map { ComicPage(it.path, null, it.uri) }
            return ImagePageSource(pages, { page ->
                val uri = page.uri ?: throw PageSourceException("图片「${page.name}」暂时不可用。")
                try {
                    resolver.openInputStream(uri)
                } catch (_: FileNotFoundException) {
                    throw PageSourceException("图片「${page.name}」不存在或已移动。")
                } catch (_: SecurityException) {
                    throw PageSourceException("无法读取图片，请重新选择文件夹并授予访问权限。")
                } catch (_: IOException) {
                    throw PageSourceException("图片读取中断，请检查文件或云端网络连接。")
                } catch (_: IllegalArgumentException) {
                    throw PageSourceException("图片「${page.name}」无法访问。")
                } ?: throw PageSourceException("图片「${page.name}」暂时不可用。")
            }, {})
        }

        private fun createZipSource(zip: ZipFile, release: (() -> Unit)? = null): ImagePageSource {
            val entries = mutableListOf<ComicPage>()
            var scanned = 0
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                if (++scanned > MAX_ZIP_ENTRIES) {
                    throw PageSourceException("压缩包条目过多，暂不支持。")
                }
                val entry = enumeration.nextElement()
                val path = entry.name.replace('\\', '/')
                if (!entry.isDirectory && isComicImage(path) && !isJunkPath(path)) {
                    if (entries.size >= MAX_ZIP_PAGES) {
                        throw PageSourceException("图片数量超过 $MAX_ZIP_PAGES 张，暂不支持。")
                    }
                    entries += ComicPage(path, entry, null)
                }
            }
            entries.sortWith { first, second -> COMIC_PATH_ORDER.compare(first.name, second.name) }
            if (entries.isEmpty()) throw PageSourceException("这个压缩包里没有图片，漫画需要 JPG、PNG、WebP 等图片文件。")
            return imageSource(zip, entries, release)
        }

        /** Keeps the caller's order; the page list is already resolved to exact archive entries. */
        private fun createZipSource(
            zip: ZipFile,
            pagePaths: List<String>,
            release: (() -> Unit)? = null,
        ): ImagePageSource {
            val entries = ArrayList<ComicPage>(pagePaths.size)
            pagePaths.forEach { path ->
                val entry = zip.getEntry(path) ?: return@forEach
                if (entry.isDirectory) return@forEach
                if (entries.size >= MAX_ZIP_PAGES) {
                    throw PageSourceException("图片数量超过 $MAX_ZIP_PAGES 张，暂不支持。")
                }
                entries += ComicPage(path, entry, null)
            }
            if (entries.isEmpty()) throw PageSourceException("这个 EPUB 里没有可显示的图片页面。")
            return imageSource(zip, entries, release)
        }

        private fun imageSource(
            zip: ZipFile,
            entries: List<ComicPage>,
            release: (() -> Unit)?,
        ): ImagePageSource {
            val close = release ?: { zip.close() }
            return ImagePageSource(entries, { page ->
                val entry = page.entry ?: throw PageSourceException("图片「${page.name}」暂时不可用。")
                zip.getInputStream(entry)
            }, close)
        }

        private fun openZip(file: File): ZipFile {
            val primary = try {
                openZipWith(file, null)
            } catch (first: PageSourceException) {
                // Some runtimes reject a GBK-named central directory while constructing the file.
                return try {
                    openZipWith(file, GBK)
                } catch (_: PageSourceException) {
                    throw first
                }
            }
            // Android validates entry names lazily: iterating a GBK-named archive throws
            // IllegalArgumentException (MALFORMED) instead of yielding replacement characters.
            val broken = try {
                hasBrokenNames(primary)
            } catch (_: IllegalArgumentException) {
                true
            }
            if (!broken) return primary
            try {
                primary.close()
            } catch (_: IOException) {
                // Reopening is the only way to decode these names.
            }
            return openZipWith(file, GBK)
        }

        private fun openZipWith(file: File, charset: Charset?): ZipFile = try {
            if (charset == null) ZipFile(file) else ZipFile(file, charset)
        } catch (_: SecurityException) {
            throw PageSourceException("无法读取压缩包，请重新选择文件。")
        } catch (_: IOException) {
            throw PageSourceException("压缩包无法读取，文件可能已损坏。")
        } catch (_: RuntimeException) {
            throw PageSourceException("压缩包无效或已损坏，无法打开。")
        }

        /** GBK-encoded CBZ files are common; invalid UTF-8 shows up as replacement characters. */
        private fun hasBrokenNames(zip: ZipFile): Boolean {
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                if (enumeration.nextElement().name.contains('\uFFFD')) return true
            }
            return false
        }

        private fun mapOpenError(error: Throwable): Throwable = when (error) {
            is CancellationException, is PageSourceException -> error
            is OutOfMemoryError -> PageSourceException("内存不足，无法打开此压缩包。")
            is SecurityException -> PageSourceException("无法读取压缩包，请重新选择文件。")
            is IOException -> PageSourceException("压缩包无法读取，文件可能已损坏。")
            is RuntimeException -> PageSourceException("压缩包无效或已损坏，无法打开。")
            else -> error
        }

        private fun sampleSize(
            sourceWidth: Int,
            sourceHeight: Int,
            targetWidth: Int,
            targetHeight: Int,
        ): Int {
            var sample = 1
            while (true) {
                val next = sample * 2
                val width = sourceWidth / next
                val height = sourceHeight / next
                if (width < 1 || height < 1) break
                val largerThanTarget = width >= targetWidth && height >= targetHeight
                val tooManyPixels = width.toLong() * height.toLong() > MAX_DECODE_PIXELS
                if (!largerThanTarget && !tooManyPixels) break
                sample = next
            }
            return sample
        }
    }
}

private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "jpe", "jfif", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
)

internal fun isComicImage(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS

private fun isJunkName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isEmpty() || trimmed.startsWith(".") || trimmed.equals("__MACOSX", ignoreCase = true)
}

private fun isJunkPath(path: String): Boolean = path.split('/').any { isJunkName(it) }

/** "第2话/10.jpg" sorts before "第10话/2.jpg"; plain lexical order would not. */
internal fun compareComicPaths(first: String, second: String): Int {
    var i = 0
    var j = 0
    while (i < first.length && j < second.length) {
        val a = first[i]
        val b = second[j]
        if (a in '0'..'9' && b in '0'..'9') {
            var aStart = i
            var bStart = j
            while (aStart < first.length && first[aStart] == '0') aStart++
            while (bStart < second.length && second[bStart] == '0') bStart++
            var aEnd = aStart
            var bEnd = bStart
            while (aEnd < first.length && first[aEnd] in '0'..'9') aEnd++
            while (bEnd < second.length && second[bEnd] in '0'..'9') bEnd++
            val aLength = aEnd - aStart
            val bLength = bEnd - bStart
            if (aLength != bLength) return aLength - bLength
            for (offset in 0 until aLength) {
                val difference = first[aStart + offset].compareTo(second[bStart + offset])
                if (difference != 0) return difference
            }
            i = aEnd
            j = bEnd
        } else {
            val difference = a.lowercaseChar().compareTo(b.lowercaseChar())
            if (difference != 0) return difference
            i++
            j++
        }
    }
    return (first.length - i) - (second.length - j)
}

internal val COMIC_PATH_ORDER = Comparator<String> { first, second ->
    val natural = compareComicPaths(first, second)
    if (natural != 0) natural else first.compareTo(second)
}
