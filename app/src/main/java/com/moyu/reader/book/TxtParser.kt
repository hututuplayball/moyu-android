package com.moyu.reader.book

import java.io.InputStream

/** Reads a plain-text novel: decode, split into paragraphs and mark chapter headings. */
object TxtParser {
    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val MAX_PARAGRAPH_CHARS = 2400
    private const val MAX_HEADING_CHARS = 45
    private const val MAX_TITLE_CHARS = 40
    private const val SPLIT_CHARS = 1200
    private const val SPLIT_MIN_CHARS = 400

    /** "第一章 …", "序章", "Chapter 1" and friends, as long as the whole line stays short. */
    private val HEADING = Regex(
        "^(第\\s*[0-9〇零一二三四五六七八九十百千万两]+\\s*[章节卷回部集][^，。！？]{0,24}|" +
            "(序章|楔子|引子|前言|后记|尾声|终章|番外)[^，。！？]{0,20}|" +
            "chapter\\s+\\d+[^，。！？]{0,24})$",
        RegexOption.IGNORE_CASE,
    )

    fun read(input: InputStream): TextDocument {
        val text = TextEncoding.decode(Streams.readCapped(input, MAX_BYTES, "这本书"))
        val paragraphs = ArrayList<TextParagraph>()
        val chapters = ArrayList<TextChapter>()
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val heading = isHeading(line)
            if (heading) chapters += TextChapter(line.take(MAX_TITLE_CHARS), paragraphs.size)
            if (line.length <= MAX_PARAGRAPH_CHARS) {
                paragraphs += TextParagraph(line, title = heading, chapterStart = heading)
            } else {
                // A wall of text with no line breaks would become one unbreakable page.
                splitLongLine(line).forEach { paragraphs += TextParagraph(it) }
            }
        }
        return TextDocument.of(paragraphs, chapters)
    }

    private fun isHeading(line: String): Boolean = line.length <= MAX_HEADING_CHARS && HEADING.matches(line)

    /** Breaks an oversized block at sentence enders, so lines never exceed [SPLIT_CHARS] by much. */
    private fun splitLongLine(line: String): List<String> {
        val parts = ArrayList<String>()
        var start = 0
        while (start < line.length) {
            var end = minOf(start + SPLIT_CHARS, line.length)
            if (end < line.length) {
                val boundary = line.lastIndexOfAny(charArrayOf('。', '！', '？', '…', '；'), end)
                if (boundary > start + SPLIT_MIN_CHARS) end = boundary + 1
            }
            parts += line.substring(start, end)
            start = end
        }
        return parts
    }
}
