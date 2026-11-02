package com.moyu.reader.book

import android.util.Xml
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * Finds the artwork an EPUB publisher declared as its cover. The text parser deliberately skips
 * images, so the shelf asks for this one image separately; a missing or malformed declaration is
 * not an error, the caller falls back to the rendered first page.
 */
object EpubCover {
    private const val CONTAINER = "META-INF/container.xml"
    private const val MAX_META_BYTES = 4L * 1024 * 1024
    private const val MAX_COVER_BYTES = 32L * 1024 * 1024
    private const val MAX_SCANNED = 65536
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private val WHITESPACE = Regex("\\s+")

    /** Random access is available for private copies, so container/OPF/cover are read directly. */
    fun read(file: File): ByteArray? = runCatching {
        ZipFile(file).use { zip ->
            val entries = entryMap(zip)
            val container = lookup(entries, CONTAINER)?.let { read(zip, it, MAX_META_BYTES) }
            val root = container?.let(::rootPath) ?: return@use null
            val opfEntry = lookup(entries, normalize(root)) ?: return@use null
            val opf = read(zip, opfEntry, MAX_META_BYTES) ?: return@use null
            val cover = coverPath(opf, root.substringBeforeLast('/', "")) ?: return@use null
            val coverEntry = lookup(entries, cover) ?: return@use null
            read(zip, coverEntry, MAX_COVER_BYTES)
        }
    }.getOrNull()

    /** A SAF reference cannot seek; two short passes find the package and then the cover entry. */
    fun read(open: () -> InputStream): ByteArray? = runCatching {
        var container: ByteArray? = null
        val packages = HashMap<String, ByteArray>()
        scan(open()) { entry, zip ->
            val name = normalize(urlDecode(entry.name))
            when {
                name == CONTAINER -> container = read(zip, MAX_META_BYTES)
                name.endsWith(".opf", true) -> packages[name] = read(zip, MAX_META_BYTES)
            }
        }
        val root = container?.let(::rootPath) ?: return@runCatching null
        val opf = packages[normalize(root)]
            ?: packages.entries.firstOrNull { it.key.equals(normalize(root), true) }?.value
            ?: return@runCatching null
        val cover = coverPath(opf, root.substringBeforeLast('/', "")) ?: return@runCatching null
        var bytes: ByteArray? = null
        scan(open()) { entry, zip ->
            if (bytes == null && normalize(urlDecode(entry.name)) == cover) {
                bytes = read(zip, MAX_COVER_BYTES)
            }
        }
        bytes
    }.getOrNull()

    private fun scan(input: InputStream, visit: (ZipEntry, ZipInputStream) -> Unit) {
        ZipInputStream(BufferedInputStream(input, 64 * 1024)).use { zip ->
            var scanned = 0
            while (scanned < MAX_SCANNED) {
                val entry = zip.nextEntry ?: break
                scanned++
                if (!entry.isDirectory) visit(entry, zip)
            }
        }
    }

    private fun read(zip: ZipInputStream, limit: Long): ByteArray =
        Streams.readCapped(zip, limit, "EPUB 封面")

    private fun read(zip: ZipFile, entry: ZipEntry, limit: Long): ByteArray =
        zip.getInputStream(entry).use { Streams.readCapped(it, limit, "EPUB 封面") }

    private fun entryMap(zip: ZipFile): Map<String, ZipEntry> {
        val entries = HashMap<String, ZipEntry>()
        val iterator = zip.entries()
        var scanned = 0
        while (iterator.hasMoreElements() && scanned < MAX_SCANNED) {
            val entry = iterator.nextElement()
            scanned++
            if (!entry.isDirectory) entries[normalize(urlDecode(entry.name))] = entry
        }
        return entries
    }

    private fun lookup(entries: Map<String, ZipEntry>, path: String): ZipEntry? =
        entries[path] ?: entries.entries.firstOrNull { it.key.equals(path, true) }?.value

    private fun rootPath(container: ByteArray): String? {
        val parser = newParser(container)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("rootfile", true)) {
                return parser.getAttributeValue(null, "full-path")?.takeIf { it.isNotBlank() }
            }
            event = parser.next()
        }
        return null
    }

    /** EPUB 3 cover-image, EPUB 2 meta cover and guide cover, then a conservative name fallback. */
    private fun coverPath(opf: ByteArray, directory: String): String? {
        val parser = newParser(opf)
        val manifest = HashMap<String, Pair<String, String>>() // id -> href, properties
        var metaCover: String? = null
        var guideCover: String? = null
        var declaredCover: String? = null
        var inGuide = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        val properties = parser.getAttributeValue(null, "properties") ?: ""
                        if (id != null && href != null) {
                            manifest[id] = href to properties
                            if (properties.split(WHITESPACE).any { it.equals("cover-image", true) }) {
                                declaredCover = href
                            }
                        }
                    }
                    "meta" -> {
                        if (parser.getAttributeValue(null, "name").equals("cover", true)) {
                            metaCover = parser.getAttributeValue(null, "content")
                        }
                    }
                    "guide" -> inGuide = true
                    "reference" -> if (inGuide &&
                        parser.getAttributeValue(null, "type").equals("cover", true)
                    ) {
                        guideCover = parser.getAttributeValue(null, "href")
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name.equals("guide", true)) {
                inGuide = false
            }
            event = parser.next()
        }
        val fromMeta = metaCover?.let { id -> manifest[id]?.first }
        val candidates = listOfNotNull(
            declaredCover,
            fromMeta,
            guideCover?.takeIf { it.substringBefore('#').substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS },
            manifest.entries.firstOrNull { (id, item) ->
                id.contains("cover", true) &&
                    item.first.substringBefore('#').substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
            }?.value?.first,
        )
        return candidates.firstOrNull()?.let { resolve(directory, it) }
    }

    private fun resolve(directory: String, href: String): String {
        val path = urlDecode(href.substringBefore('#'))
        val joined = if (directory.isBlank()) path else "$directory/$path"
        return normalize(joined)
    }

    private fun normalize(path: String): String {
        val parts = ArrayList<String>()
        path.split('/').forEach { part ->
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun newParser(bytes: ByteArray): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(trimXmlPreamble(bytes)), null)
        return parser
    }

    /** Some writers leave spaces before the XML declaration; kxml rejects that without a nudge. */
    private fun trimXmlPreamble(bytes: ByteArray): ByteArray {
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            start = 3
        }
        while (start < bytes.size) {
            val value = bytes[start]
            if (value != 0x20.toByte() && value != 0x09.toByte() &&
                value != 0x0A.toByte() && value != 0x0D.toByte()
            ) {
                break
            }
            start++
        }
        return if (start == 0) bytes else bytes.copyOfRange(start, bytes.size)
    }
}
