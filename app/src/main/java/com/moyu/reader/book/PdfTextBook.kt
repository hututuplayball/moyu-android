package com.moyu.reader.book

/**
 * Turns a cached PDF extraction into the shared text-reader document. The PDF gives no table of
 * contents, so chapters are guessed from the paragraph openers a novel uses.
 */
object PdfTextBook {
    private const val MAX_TITLE_CHARS = 40
    private val CJK_CHAPTER = Regex("^第[一二三四五六七八九十百千零〇0-9]{1,12}[章节卷回部篇]")
    private val LATIN_CHAPTER = Regex("^(?:chapter|part|book)\\s+[0-9ivxl]+", RegexOption.IGNORE_CASE)
    private val SPECIAL_CHAPTER = Regex("^(?:序章|序言|引言|引子|楔子|尾声|后记|番外|前言|终章)")

    fun document(entry: PdfTextCache.Entry): TextDocument {
        val chapters = ArrayList<TextChapter>()
        val paragraphs = entry.paragraphs.mapIndexed { index, text ->
            val heading = looksLikeChapter(text)
            if (heading) chapters += TextChapter(text.take(MAX_TITLE_CHARS), index)
            TextParagraph(text, title = heading, chapterStart = heading)
        }
        return TextDocument.of(paragraphs, chapters)
    }

    private fun looksLikeChapter(text: String): Boolean {
        // A heading is short; a long paragraph that merely starts with "第一章" is body text.
        if (text.length > MAX_TITLE_CHARS) return false
        return CJK_CHAPTER.containsMatchIn(text) || LATIN_CHAPTER.containsMatchIn(text) ||
            SPECIAL_CHAPTER.containsMatchIn(text)
    }
}
