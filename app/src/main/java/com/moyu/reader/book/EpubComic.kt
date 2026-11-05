package com.moyu.reader.book

import android.os.ParcelFileDescriptor
import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

/**
 * Reads a fixed-layout comic EPUB. The OPF spine is the page order and every spine document points
 * at the image that fills its page, so the pages are the archive's entries in spine order — not the
 * image files sorted by name. Text is never read: these packages carry none to speak of.
 */
object EpubComic {
    private const val CONTAINER = "META-INF/container.xml"
    private const val MAX_META_BYTES = 8L * 1024 * 1024
    private const val MAX_PAGE_BYTES = 2L * 1024 * 1024
    private const val MAX_SPINE = 5000
    private const val MAX_SCANNED = 65536
    private const val MIN_COMIC_SAMPLES = 3
    /** A comic page is a picture; its own words are page labels, not prose. */
    private const val MAX_COMIC_WORDS = 40
    private val TAG = Regex("<[^>]+>")
    private val WHITESPACE = Regex("\\s+")
    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "jpe", "jfif", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
    )
    private val IMG_SRC = Regex("<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val SVG_HREF = Regex(
        "<image\\b[^>]*?(?:xlink:href|\\bhref)\\s*=\\s*[\"']([^\"']+)[\"']",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The page images of [file] in reading order, as exact archive entry names. Throws when the
     * package has no image pages, which is how a text EPUB is told apart from a comic one.
     */
    fun pages(file: File): List<String> {
        val zip = try {
            ZipFile(file)
        } catch (_: IOException) {
            throw TextBookException("EPUB 无法读取，文件可能已损坏。")
        } catch (_: RuntimeException) {
            throw TextBookException("EPUB 无法读取，文件可能已损坏。")
        }
        try {
            return pages(zip)
        } catch (error: TextBookException) {
            throw error
        } catch (_: IOException) {
            throw TextBookException("EPUB 无法读取，文件可能已损坏。")
        } catch (_: RuntimeException) {
            throw TextBookException("这个 EPUB 无法解析，文件可能已损坏。")
        } finally {
            try {
                zip.close()
            } catch (_: IOException) {
                // The caller only needs the page list.
            }
        }
    }

    /** Reference imports hold a descriptor instead of a private copy; both reach the same zip. */
    fun pages(descriptor: ParcelFileDescriptor): List<String> =
        pages(File("/proc/self/fd/${descriptor.fd}"))

    /**
     * A cheap guess that this EPUB is a comic: most manifest items are pictures and the sampled
     * spine pages are one image with almost no words. It only drives a hint on the shelf the
     * reader chose, so a false positive costs a sentence instead of a wrong import.
     */
    fun looksLikeComic(file: File): Boolean = try {
        ZipFile(file).use { zip -> looksLikeComic(zip) }
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    fun looksLikeComic(descriptor: ParcelFileDescriptor): Boolean =
        looksLikeComic(File("/proc/self/fd/${descriptor.fd}"))

    private fun looksLikeComic(zip: ZipFile): Boolean {
        return try {
            val index = entryIndex(zip)
            val container = find(index, CONTAINER) ?: return false
            val root = rootFilePath(read(zip, container, MAX_META_BYTES))
            val opf = find(index, root) ?: return false
            val packageInfo = packageInfo(read(zip, opf, MAX_META_BYTES), opf.name.substringBeforeLast('/', ""))
            val spine = packageInfo.spine
            // Short books are too ambiguous to guess at, and a package with a picture per few pages
            // still reads as a normal illustrated book.
            if (spine.size < MIN_COMIC_SAMPLES || packageInfo.imageItems * 2 < spine.size) return false
            var sampled = 0
            var comicPages = 0
            sampleIndices(spine.size).forEach { position ->
                val item = spine[position]
                val entry = find(index, item.href) ?: return@forEach
                sampled++
                if (item.image) {
                    comicPages++
                    return@forEach
                }
                val html = runCatching { TextEncoding.decode(read(zip, entry, MAX_PAGE_BYTES)) }.getOrNull()
                    ?: return@forEach
                val hasImage = IMG_SRC.containsMatchIn(html) || SVG_HREF.containsMatchIn(html)
                if (hasImage && visibleTextLength(html) <= MAX_COMIC_WORDS) comicPages++
            }
            sampled >= MIN_COMIC_SAMPLES && comicPages * 3 >= sampled * 2
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun pages(zip: ZipFile): List<String> {
        val index = entryIndex(zip)
        val container = find(index, CONTAINER) ?: throw TextBookException("EPUB 结构损坏，无法解析目录。")
        val root = rootFilePath(read(zip, container, MAX_META_BYTES))
        val opf = find(index, root) ?: throw TextBookException("EPUB 结构损坏，无法解析目录。")
        val spine = packageInfo(read(zip, opf, MAX_META_BYTES), opf.name.substringBeforeLast('/', "")).spine
        if (spine.isEmpty()) throw TextBookException("EPUB 里没有正文页面。")
        val pages = ArrayList<String>(spine.size)
        spine.forEach { item ->
            if (pages.size >= MAX_SPINE) throw TextBookException("这本漫画页数过多，暂不支持。")
            val entry = find(index, item.href) ?: return@forEach
            if (!entry.isDirectory) {
                val image = if (item.image) entry.name else pageImage(zip, index, entry)
                if (image != null) pages += image
            }
        }
        if (pages.isEmpty()) {
            throw TextBookException("这个 EPUB 里没有图片页面，可能是文字书，请到「电子书」书架导入。")
        }
        return pages.distinct()
    }

    private fun entryIndex(zip: ZipFile): Map<String, ZipEntry> {
        val index = HashMap<String, ZipEntry>()
        var scanned = 0
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            if (++scanned > MAX_SCANNED) throw TextBookException("EPUB 内部条目过多，暂不支持。")
            val entry = entries.nextElement()
            if (!entry.isDirectory) index[normalize(entry.name)] = entry
        }
        return index
    }

    /** Exact match first, then a case-insensitive one: writers disagree about entry name case. */
    private fun find(index: Map<String, ZipEntry>, href: String): ZipEntry? {
        val wanted = normalize(href)
        index[wanted]?.let { return it }
        return index.entries.firstOrNull { it.key.equals(wanted, ignoreCase = true) }?.value
    }

    /** The image a page points at, including the common `<svg><image href>` wrapper. */
    private fun pageImage(zip: ZipFile, index: Map<String, ZipEntry>, page: ZipEntry): String? {
        val html = try {
            TextEncoding.decode(read(zip, page, MAX_PAGE_BYTES))
        } catch (_: Exception) {
            return null
        }
        val href = IMG_SRC.find(html)?.groupValues?.getOrNull(1)
            ?: SVG_HREF.find(html)?.groupValues?.getOrNull(1)
            ?: return null
        val directory = page.name.substringBeforeLast('/', "")
        val target = find(index, join(directory, href)) ?: return null
        if (target.name.isRasterImage()) return target.name
        // A page may hand an SVG file to the browser; the raster it embeds is the real page.
        if (target.name.substringAfterLast('.', "").equals("svg", ignoreCase = true)) {
            val svg = try {
                TextEncoding.decode(read(zip, target, MAX_PAGE_BYTES))
            } catch (_: Exception) {
                return null
            }
            val inner = SVG_HREF.find(svg)?.groupValues?.getOrNull(1) ?: return null
            val raster = find(index, join(target.name.substringBeforeLast('/', ""), inner))
            if (raster != null && raster.name.isRasterImage()) return raster.name
        }
        return null
    }

    private fun read(zip: ZipFile, entry: ZipEntry, limit: Long): ByteArray =
        zip.getInputStream(entry).use { Streams.readCapped(it, limit, "EPUB 内部文件") }

    private fun rootFilePath(container: ByteArray): String {
        val parser = newParser(container)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("rootfile", true)) {
                val path = parser.getAttributeValue(null, "full-path")
                if (!path.isNullOrBlank()) return path
            }
            event = parser.next()
        }
        throw TextBookException("EPUB 结构损坏，无法解析目录。")
    }

    private class SpineItem(val href: String, val image: Boolean)

    private class PackageInfo(val spine: List<SpineItem>, val imageItems: Int)

    /** The spine in reading order, with manifest hrefs already made archive-relative. */
    private fun packageInfo(opf: ByteArray, directory: String): PackageInfo {
        val parser = newParser(opf)
        val manifest = HashMap<String, Pair<String, Boolean>>()
        val items = ArrayList<SpineItem>()
        var imageItems = 0
        var inSpine = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when {
                event == XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        val type = parser.getAttributeValue(null, "media-type") ?: ""
                        if (id != null && href != null) {
                            val image = type.lowercase(Locale.ROOT).startsWith("image/")
                            if (image) imageItems++
                            manifest[id] = join(directory, href) to image
                        }
                    }
                    "spine" -> inSpine = true
                    "itemref" -> if (inSpine) {
                        val item = parser.getAttributeValue(null, "idref")?.let(manifest::get)
                        // The table of contents page is a spine item with no picture; a page
                        // without an image is skipped later, the same way a missing entry is.
                        if (item != null) items += SpineItem(item.first, item.second)
                    }
                }
                event == XmlPullParser.END_TAG && parser.name.equals("spine", true) -> inSpine = false
            }
            event = parser.next()
        }
        return PackageInfo(items, imageItems)
    }

    /** One page each from the first, middle and last quarter: enough to tell a comic from a novel. */
    private fun sampleIndices(size: Int): List<Int> =
        listOf(size / 4, size / 2, (size * 3) / 4).distinct().filter { it in 0 until size }

    /** Characters a reader would actually see: tags and whitespace dropped. */
    private fun visibleTextLength(html: String): Int =
        TAG.replace(html, " ").replace(WHITESPACE, " ").trim().length

    private fun newParser(bytes: ByteArray): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(bytes), null)
        return parser
    }

    private fun join(directory: String, href: String): String =
        if (directory.isBlank()) href else "$directory/$href"

    /** Manifest hrefs may carry a fragment, percent-encoding or `..` segments. */
    private fun normalize(path: String): String {
        val decoded = urlDecode(path.substringBefore('#')).replace('\\', '/')
        val parts = ArrayList<String>()
        decoded.split('/').forEach { part ->
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("&amp;", "&"), "UTF-8") }.getOrDefault(value)

    private fun String.isRasterImage(): Boolean =
        substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS
}
