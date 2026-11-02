package com.moyu.reader.book

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Parsed text kept on disk. EPUB parsing (HTML flattening across every spine document) is the
 * expensive half of a cold open; the page engine only needs the paragraph list to start. Book ids
 * are content hashes, so a cache entry can never belong to a different file.
 */
class TextDocumentCache(private val directory: File) {

    fun read(id: String): TextDocument? {
        if (!ID.matches(id)) return null
        val file = file(id)
        if (!file.isFile) return null
        return try {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                if (input.readUTF() != MAGIC || input.readInt() != VERSION) return null
                val paragraphCount = input.readInt()
                if (paragraphCount !in 1..MAX_PARAGRAPHS) return null
                var budget = MAX_CHARS
                val paragraphs = ArrayList<TextParagraph>(paragraphCount)
                repeat(paragraphCount) {
                    val title = input.readBoolean()
                    val chapterStart = input.readBoolean()
                    val bytes = ByteArray(input.readInt().also { length ->
                        if (length < 0 || length > MAX_PARAGRAPH_BYTES) return null
                        budget -= length
                        if (budget < 0) return null
                    })
                    input.readFully(bytes)
                    paragraphs += TextParagraph(String(bytes, Charsets.UTF_8), title, chapterStart)
                }
                val chapterCount = input.readInt()
                if (chapterCount !in 0..MAX_PARAGRAPHS) return null
                val chapters = ArrayList<TextChapter>(chapterCount)
                repeat(chapterCount) {
                    val title = input.readUTF()
                    val index = input.readInt()
                    if (index !in 0 until paragraphCount) return null
                    chapters += TextChapter(title, index)
                }
                TextDocument.of(paragraphs, chapters)
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    fun write(id: String, document: TextDocument) {
        if (!ID.matches(id)) return
        if (document.paragraphs.isEmpty() || document.paragraphs.size > MAX_PARAGRAPHS) return
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return
        val target = file(id)
        val temp = File(directory, "$id.tmp")
        try {
            var total = 0
            DataOutputStream(FileOutputStream(temp).buffered()).use { output ->
                output.writeUTF(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(document.paragraphs.size)
                document.paragraphs.forEach { paragraph ->
                    val bytes = paragraph.text.toByteArray(Charsets.UTF_8)
                    total += bytes.size
                    if (bytes.size > MAX_PARAGRAPH_BYTES || total > MAX_CHARS) {
                        throw IOException("文档缓存超过上限。")
                    }
                    output.writeBoolean(paragraph.title)
                    output.writeBoolean(paragraph.chapterStart)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
                output.writeInt(document.chapters.size)
                document.chapters.forEach { chapter ->
                    output.writeUTF(chapter.title.take(MAX_TITLE_CHARS))
                    output.writeInt(chapter.paragraphIndex)
                }
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("文档缓存无法替换。")
            }
        } catch (_: IOException) {
            temp.delete()
        }
    }

    fun delete(id: String) {
        if (ID.matches(id)) file(id).delete()
    }

    private fun file(id: String): File = File(directory, "$id.textdoc")

    private companion object {
        const val MAGIC = "MOYU-TEXT-DOC"
        // v3: every leading repeat of a chapter heading is dropped, not only the first one.
        const val VERSION = 3
        const val MAX_PARAGRAPHS = 1_000_000
        const val MAX_PARAGRAPH_BYTES = 8 * 1024 * 1024
        const val MAX_CHARS = 64 * 1024 * 1024
        const val MAX_TITLE_CHARS = 255
        val ID = Regex("[0-9a-f]{64}")
    }
}
