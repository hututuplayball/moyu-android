package com.moyu.reader.book

import android.util.Xml
import androidx.core.text.HtmlCompat
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream
import kotlin.math.abs
import org.xmlpull.v1.XmlPullParser

/**
 * Reads an EPUB package into paragraphs: container.xml → OPF spine → optional NCX titles → XHTML.
 * Media entries are skipped and the text budget is capped, so an illustrated book does not pull
 * every image into memory just to show its first page.
 */
object EpubPackageReader {
    private const val MAX_ENTRY_BYTES = 48L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 96L * 1024 * 1024
    private const val MAX_KEPT_ENTRIES = 4096
    private const val MAX_SCANNED_ENTRIES = 65536
    private const val MAX_TITLE_CHARS = 40
    private const val MAX_SNIPPET_CHARS = 30
    private const val MAX_LEADING_HEADINGS = 3
    private const val CONTAINER_ENTRY = "META-INF/container.xml"
    private val TEXT_EXTENSIONS = setOf("xhtml", "html", "htm")
    private val WHITESPACE = Regex("\\s+")
    // A heading may be repeated as the first body block; this recognises chapter openers even when
    // the navigation label and the document's own heading spell the title slightly differently.
    private val CHAPTER_OPENER = Regex(
        "^(第\\s*[0-9〇零一二三四五六七八九十百千万两]+\\s*[章节卷回部集篇]|" +
            "(序章|楔子|引子|前言|后记|尾声|终章|番外)|" +
            "chapter\\s+\\d+)",
        RegexOption.IGNORE_CASE,
    )
    private val HEADING_PUNCTUATION = Regex("[\\s\\u3000\\p{Punct}，。！？：；、·—…“”‘’（）《》【】]")
    /** Sentence punctuation separates a real opening line from a chapter label. */
    private val SENTENCE_BREAK = Regex("[。！？!?；;，,]")

    /** The first `<h1>`–`<h6>` of a document, used when no navigation label names its chapter. */
    private val HEADING = Regex("<h[1-6][^>]*>(.*?)</h[1-6]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** Entries that never hold readable text; reading them would only waste the text budget. */
    private val MEDIA_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg",
        "ttf", "otf", "woff", "woff2", "ttc",
        "mp3", "mp4", "m4a", "m4v", "aac", "ogg", "wav", "css",
    )

    /** Object replacement, soft hyphen and zero-width marks: invisible, but they break long runs. */
    private const val IGNORABLE_CHARS = "\uFFFC\u00AD\u200B\u200C\u200D\u2060\uFEFF"

    fun read(input: InputStream): TextDocument {
        val entries = readEntries(input)
        val container = entries[CONTAINER_ENTRY] ?: entries.find("container.xml")
            ?: throw TextBookException("EPUB 结构损坏，无法解析目录。")
        val opfPath = rootFilePath(container)
        val packageDir = opfPath.substringBeforeLast('/', "")
        val opf = entries.resolve(opfPath) ?: throw TextBookException("EPUB 结构损坏，无法解析目录。")
        val packageInfo = parsePackage(opf, packageDir)
        // Manifest hrefs inside the OPF are relative to the OPF, not to the archive root.
        val titles = navigationTitles(entries, packageDir, packageInfo)

        val paragraphs = ArrayList<TextParagraph>()
        val chapters = ArrayList<TextChapter>()
        packageInfo.documents.forEach { href ->
            val html = entries.resolve(href)?.let { TextEncoding.decode(it) } ?: return@forEach
            val blocks = htmlBlocks(html)
            if (blocks.isEmpty()) return@forEach
            // The navigation label wins, then the document's own heading, then its opening line.
            val navTitle = titles[entries.key(href)]?.trim()?.take(MAX_TITLE_CHARS)
            val ownHeading = htmlHeading(html)?.trim()?.take(MAX_TITLE_CHARS)
            val labels = listOfNotNull(
                navTitle?.takeIf { it.isNotBlank() },
                ownHeading?.takeIf { it.isNotBlank() },
            )
            val explicit = labels.firstOrNull()
            val heading = (explicit ?: blocks.first().take(MAX_SNIPPET_CHARS))
                .trim()
                .take(MAX_TITLE_CHARS)
                .ifBlank { blocks.first().take(MAX_TITLE_CHARS) }
            chapters += TextChapter(heading, paragraphs.size)
            if (explicit != null) {
                // Some EPUBs repeat the heading as the opening paragraph, and a few print it twice
                // (for example an <h1> plus a subtitle <p>). Skip every leading copy, not just one.
                var firstBody = 0
                while (firstBody < blocks.size && firstBody < MAX_LEADING_HEADINGS &&
                    labels.any { repeatedHeading(blocks[firstBody], it) }
                ) {
                    firstBody++
                }
                paragraphs += TextParagraph(heading, title = true, chapterStart = true)
                blocks.drop(firstBody).forEach { paragraphs += TextParagraph(it) }
            } else {
                // No heading metadata: keep the real first block as body text and let it open the
                // chapter. The older behaviour printed a 30-character snippet as a title and then
                // the same words again as the opening paragraph.
                paragraphs += TextParagraph(blocks.first(), chapterStart = true)
                blocks.drop(1).forEach { paragraphs += TextParagraph(it) }
            }
        }
        return TextDocument.of(paragraphs, chapters)
    }

    /** A short first block that matches the chapter label, or plainly looks like a chapter line. */
    private fun repeatedHeading(block: String, heading: String): Boolean {
        if (block.length > MAX_TITLE_CHARS + 12) return false
        val label = heading.replace(HEADING_PUNCTUATION, "")
        val candidate = block.replace(HEADING_PUNCTUATION, "")
        if (label.isEmpty() || candidate.isEmpty()) return false
        if (candidate == label) return true
        // Tolerate a punctuation or numeral spelling difference ("第一章 垂髻客" / "第1章：垂髻客"),
        // but never swallow a real sentence that merely starts with the chapter label.
        if ((candidate.startsWith(label) || label.startsWith(candidate)) &&
            abs(candidate.length - label.length) <= 4 && !SENTENCE_BREAK.containsMatchIn(block)
        ) {
            return true
        }
        return !SENTENCE_BREAK.containsMatchIn(block) && CHAPTER_OPENER.containsMatchIn(block)
    }

    private fun readEntries(input: InputStream): ZipEntries {
        val files = HashMap<String, ByteArray>()
        var total = 0L
        var scanned = 0
        var kept = 0
        ZipInputStream(BufferedInputStream(input, 64 * 1024)).use { zip ->
            // Skipped media entries only cost the scan budget: an illustrated book may carry
            // thousands of images before the first chapter, so they never use up the keep budget.
            while (scanned < MAX_SCANNED_ENTRIES && kept < MAX_KEPT_ENTRIES) {
                val entry = zip.nextEntry ?: break
                scanned++
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.extension() in MEDIA_EXTENSIONS) continue
                // Read only what is left of the whole-book budget: an archive cannot make us
                // allocate 48 MiB per entry while still passing the 96 MiB total check.
                val remaining = MAX_TOTAL_BYTES - total
                if (remaining <= 0) throw TextBookException("这本书的正文体积过大，暂时无法打开。")
                val bytes = Streams.readCapped(
                    zip,
                    if (remaining < MAX_ENTRY_BYTES) remaining else MAX_ENTRY_BYTES,
                    "EPUB 内部文件",
                )
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) throw TextBookException("这本书的正文体积过大，暂时无法打开。")
                files[name] = bytes
                kept++
                // Some writers percent-encode entry names; register the decoded alias as well.
                val decoded = urlDecode(name)
                if (decoded != name) files.putIfAbsent(decoded, bytes)
            }
        }
        if (files.isEmpty()) throw TextBookException("这不是有效的 EPUB 文件。")
        return ZipEntries(files)
    }

    /** In-memory archive contents plus the path rules EPUB manifest hrefs rely on. */
    private class ZipEntries(private val files: Map<String, ByteArray>) {
        operator fun get(name: String): ByteArray? = files[name]

        /** Falls back to a suffix match for packages that spell the container path differently. */
        fun find(nameSuffix: String): ByteArray? =
            files.entries.firstOrNull { it.key.endsWith(nameSuffix, true) }?.value

        fun resolve(href: String): ByteArray? = files[key(href)]

        /** Manifest hrefs may carry a fragment, percent-encoding or `..` segments. */
        fun key(href: String): String = normalize(urlDecode(href.substringBefore('#')))
    }

    /** EPUB 2 points at an NCX; EPUB 3 may only ship an XHTML navigation document. */
    private class PackageInfo(val documents: List<String>, val ncx: String?, val nav: String?)

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

    /** Collects the spine documents in reading order plus the NCX and nav-document paths. */
    private fun parsePackage(opf: ByteArray, directory: String): PackageInfo {
        val parser = newParser(opf)
        val manifest = HashMap<String, String>()
        val documents = ArrayList<String>()
        var ncx: String? = null
        var nav: String? = null
        var inSpine = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when {
                event == XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        val type = parser.getAttributeValue(null, "media-type") ?: ""
                        val properties = parser.getAttributeValue(null, "properties") ?: ""
                        if (id != null && href != null) {
                            manifest[id] = href
                            if (type.contains("dtbncx")) ncx = href
                            if (properties.split(' ').any { it.equals("nav", true) }) nav = href
                        }
                    }
                    "spine" -> {
                        inSpine = true
                        if (ncx == null) ncx = parser.getAttributeValue(null, "toc")?.let(manifest::get)
                    }
                    "itemref" -> if (inSpine) {
                        val href = parser.getAttributeValue(null, "idref")?.let(manifest::get)
                        if (href != null && href.substringBefore('#').extension() in TEXT_EXTENSIONS) {
                            documents += join(directory, href)
                        }
                    }
                }
                event == XmlPullParser.END_TAG && parser.name.equals("spine", true) -> inSpine = false
            }
            event = parser.next()
        }
        if (documents.isEmpty()) throw TextBookException("EPUB 里没有可阅读的正文。")
        return PackageInfo(documents, ncx, nav)
    }

    /** Titles per target document: NCX first, then the EPUB 3 nav document, else none. */
    private fun navigationTitles(entries: ZipEntries, directory: String, info: PackageInfo): Map<String, String> {
        info.ncx?.let { href -> entries.resolve(join(directory, href)) }
            ?.let { parseNcx(it, directory) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        info.nav?.let { href -> entries.resolve(join(directory, href)) }
            ?.let { parseNavDocument(it, directory) }
            ?.let { return it }
        return emptyMap()
    }

    /** NCX labels keyed by their target document; nested navPoints are collected as well. */
    private fun parseNcx(ncx: ByteArray, directory: String): Map<String, String> {
        val labels = HashMap<String, String>()
        val parser = newParser(ncx)
        var inLabel = false
        var pendingLabel: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "navlabel" -> inLabel = true
                    "text" -> if (inLabel) pendingLabel = runCatching { parser.nextText().trim() }.getOrNull()
                    "content" -> {
                        val src = parser.getAttributeValue(null, "src")
                        val label = pendingLabel
                        if (src != null && !label.isNullOrBlank()) {
                            val target = join(directory, src.substringBefore('#'))
                            labels[normalize(urlDecode(target))] = label
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "navlabel" -> inLabel = false
                    // A navPoint without its own label must not inherit the previous one's title.
                    "navpoint" -> pendingLabel = null
                }
            }
            event = parser.next()
        }
        return labels
    }

    /**
     * EPUB 3 keeps the table of contents in an XHTML `nav` marked `epub:type="toc"`. Labels come
     * from the anchor text and targets from its href, normalised exactly like NCX targets.
     */
    private fun parseNavDocument(nav: ByteArray, directory: String): Map<String, String> {
        val labels = HashMap<String, String>()
        val parser = newParser(nav)
        var inToc = false
        var label: StringBuilder? = null
        var href: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "nav" -> if (!inToc) inToc = isTocNav(parser)
                    "a" -> if (inToc) {
                        label = StringBuilder()
                        href = parser.getAttributeValue(null, "href")
                    }
                }
                XmlPullParser.TEXT -> label?.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "nav" -> inToc = false
                    "a" -> {
                        val title = label?.toString()?.replace(WHITESPACE, " ")?.trim()
                        val target = href
                        if (!title.isNullOrBlank() && !target.isNullOrBlank()) {
                            val path = join(directory, target.substringBefore('#'))
                            labels[normalize(urlDecode(path))] = title
                        }
                        label = null
                        href = null
                    }
                }
            }
            event = parser.next()
        }
        return labels
    }

    /** Accepts `epub:type="toc"`, its namespace-less form and the ARIA role used by some writers. */
    private fun isTocNav(parser: XmlPullParser): Boolean {
        val types = listOfNotNull(
            parser.getAttributeValue(null, "epub:type"),
            parser.getAttributeValue(null, "type"),
            parser.getAttributeValue(null, "role"),
        )
        val id = parser.getAttributeValue(null, "id")
        return types.any { value ->
            value.split(' ').any { it.equals("toc", true) || it.equals("doc-toc", true) }
        } || id.equals("toc", true) || id.equals("toc-title", true)
    }

    /** HtmlCompat flattens block elements into newlines; empty lines never become paragraphs. */
    private fun htmlBlocks(html: String): List<String> = textBlocks(html.replace(WHITESPACE, " "))

    /** The document's own heading: plenty of books ship without any navigation labels. */
    private fun htmlHeading(html: String): String? = HEADING.find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { textBlocks(it.replace(WHITESPACE, " ")) }
        ?.joinToString(" ")
        ?.takeIf { it.isNotEmpty() }

    /** Source newlines inside a paragraph are cosmetic: only block tags and <br> may split text. */
    private fun textBlocks(html: String): List<String> = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
        .split('\n')
        // Without an ImageGetter, <img> and other hidden objects become placeholder glyphs.
        .map { line -> line.filterNot { it in IGNORABLE_CHARS }.trim() }
        .filter { it.isNotEmpty() }

    private fun newParser(bytes: ByteArray): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(bytes), null)
        return parser
    }

    private fun join(directory: String, href: String): String = if (directory.isBlank()) href else "$directory/$href"

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

    private fun String.extension(): String = substringAfterLast('.', "").lowercase()
}
