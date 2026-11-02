package com.moyu.reader.book

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Page maps kept on disk. Laying a book out means measuring every paragraph once; with this cache
 * the same book with the same typesetting only reads a few kilobytes, so opening a text version is
 * neither a second full layout nor a second full read of the text.
 *
 * A map is only valid for the exact geometry it was measured with, so [Key.signature] carries the
 * page size, text metrics and platform version, and a mismatch simply falls back to a fresh layout.
 */
class TextLayoutCache(private val directory: File) {

    /** Identifies one layout of one book: its content, its page geometry and its text metrics. */
    class Key(
        val bookId: String,
        val paragraphCount: Int,
        val totalChars: Int,
        val signature: String,
    )

    /** One page as stored on disk: its first character plus (paragraph, startLine, lineCount) triples. */
    class StoredPage(val startChar: Int, val segments: List<IntArray>)

    /** Pages plus the character offset of every paragraph; enough to rebuild pages without measuring. */
    class Snapshot(val pages: List<StoredPage>, val paragraphOffsets: IntArray)

    fun read(key: Key): Snapshot? {
        if (!ID.matches(key.bookId)) return null
        val file = file(key)
        if (!file.isFile) return null
        return try {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                if (input.readUTF() != MAGIC || input.readInt() != VERSION) return null
                if (input.readUTF() != key.signature) return null
                if (input.readInt() != key.paragraphCount || input.readInt() != key.totalChars) return null
                val pageCount = input.readInt()
                if (pageCount !in 1..MAX_PAGES) return null
                val pages = ArrayList<StoredPage>(pageCount)
                var previousStart = -1
                var segmentBudget = MAX_SEGMENTS
                repeat(pageCount) {
                    val startChar = input.readInt()
                    if (startChar < 0 || startChar > key.totalChars || startChar < previousStart) return null
                    previousStart = startChar
                    val segmentCount = input.readInt()
                    if (segmentCount !in 1..MAX_SEGMENTS_PER_PAGE) return null
                    segmentBudget -= segmentCount
                    if (segmentBudget < 0) return null
                    val segments = ArrayList<IntArray>(segmentCount)
                    repeat(segmentCount) {
                        val paragraph = input.readInt()
                        val startLine = input.readInt()
                        val lineCount = input.readInt()
                        if (paragraph !in 0 until key.paragraphCount) return null
                        if (startLine < 0 || lineCount < 1) return null
                        segments += intArrayOf(paragraph, startLine, lineCount)
                    }
                    pages += StoredPage(startChar, segments)
                }
                val offsetCount = input.readInt()
                if (offsetCount != key.paragraphCount + 1) return null
                val offsets = IntArray(offsetCount) { input.readInt() }
                if (offsets[0] != 0 || offsets[offsetCount - 1] != key.totalChars) return null
                for (index in 1 until offsetCount) {
                    if (offsets[index] < offsets[index - 1]) return null
                }
                if (pages[0].startChar != 0) return null
                Snapshot(pages, offsets)
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    fun write(key: Key, snapshot: Snapshot) {
        if (!ID.matches(key.bookId)) return
        if (snapshot.pages.isEmpty() || snapshot.paragraphOffsets.size != key.paragraphCount + 1) return
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return
        val target = file(key)
        val temp = File(directory, "${target.name}.tmp")
        try {
            DataOutputStream(FileOutputStream(temp).buffered()).use { output ->
                output.writeUTF(MAGIC)
                output.writeInt(VERSION)
                output.writeUTF(key.signature)
                output.writeInt(key.paragraphCount)
                output.writeInt(key.totalChars)
                output.writeInt(snapshot.pages.size)
                snapshot.pages.forEach { page ->
                    output.writeInt(page.startChar)
                    output.writeInt(page.segments.size)
                    page.segments.forEach { segment ->
                        output.writeInt(segment[0])
                        output.writeInt(segment[1])
                        output.writeInt(segment[2])
                    }
                }
                output.writeInt(snapshot.paragraphOffsets.size)
                snapshot.paragraphOffsets.forEach(output::writeInt)
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("分页缓存无法替换。")
            }
        } catch (_: IOException) {
            temp.delete()
        }
    }

    /** Drops every stored layout of [bookId]: the book itself is gone or was re-imported. */
    fun delete(bookId: String) {
        if (!ID.matches(bookId)) return
        val files = directory.listFiles { file -> file.name.startsWith("$bookId-") } ?: return
        files.forEach { it.delete() }
    }

    /** Keeps the newest [keep] maps of [bookId]: going back to a previous size must not re-measure. */
    fun retainRecent(key: Key, keep: Int = KEEP_MAPS) {
        if (!ID.matches(key.bookId)) return
        val newest = file(key).name
        val files = directory.listFiles { file -> file.name.startsWith("${key.bookId}-") } ?: return
        files.filterNot { it.name == newest }
            .sortedByDescending { it.lastModified() }
            .drop((keep - 1).coerceAtLeast(0))
            .forEach { it.delete() }
    }

    private fun file(key: Key): File = File(directory, "${key.bookId}-${digest(key.signature).take(16)}.layout")

    private fun digest(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val MAGIC = "MOYU-TEXT-LAYOUT"
        const val VERSION = 1
        // Enough for a reader to compare two or three sizes, small enough never to crowd the disk.
        const val KEEP_MAPS = 3
        const val MAX_PAGES = 20_000
        const val MAX_SEGMENTS_PER_PAGE = 400
        const val MAX_SEGMENTS = 200_000
        val ID = Regex("[0-9a-f]{64}")
    }
}
