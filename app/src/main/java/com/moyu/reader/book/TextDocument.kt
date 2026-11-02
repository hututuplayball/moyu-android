package com.moyu.reader.book

import com.moyu.reader.page.PageSourceException

/**
 * [retryable] marks a failure caused by the environment (missing folder, full storage) rather than
 * by the book itself, so the reader may try again on the next visit.
 */
class TextBookException(message: String, val retryable: Boolean = false) : PageSourceException(message)

/** One laid-out block; [title] blocks use the larger bold face and [chapterStart] opens a new page. */
class TextParagraph(val text: String, val title: Boolean = false, val chapterStart: Boolean = false)

/** A chapter entry: its [title] and the paragraph that starts it. */
class TextChapter(val title: String, val paragraphIndex: Int)

/** The whole book as ordered paragraphs plus chapter marks, independent of any typesetting. */
class TextDocument(val paragraphs: List<TextParagraph>, val chapters: List<TextChapter>) {
    companion object {
        private const val EMPTY_BOOK = "这本书没有可显示的文本。"

        /** Keeps the reader usable for an empty book instead of failing later in the page source. */
        fun of(paragraphs: List<TextParagraph>, chapters: List<TextChapter>): TextDocument =
            if (paragraphs.isNotEmpty()) TextDocument(paragraphs, chapters)
            else TextDocument(listOf(TextParagraph(EMPTY_BOOK)), emptyList())
    }
}
