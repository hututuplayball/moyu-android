package com.moyu.reader.book

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Extraction results kept on disk: the rebuilt text plus its page anchors, so a PDF is parsed once
 * and every later visit opens straight into the same layout. Book ids are content hashes, so a
 * re-imported file never reuses a stale entry.
 */
class PdfTextCache(private val directory: File) {

    /**
     * [pageCharStarts] anchors every PDF page in [paragraphs]; see [PdfTextLayout]. A [note] records
     * a file-level failure that no [PdfTextLayout.Problem] describes (encrypted, damaged, too large),
     * so the reader can explain it and skip a second parse.
     */
    class Entry(
        val problem: PdfTextLayout.Problem?,
        val paragraphs: List<String>,
        val pageCharStarts: IntArray,
        val note: String? = null,
    ) {
        /** True when this entry really carries readable text for the text view. */
        val usable: Boolean get() = problem == null && note == null && paragraphs.isNotEmpty()

        companion object {
            fun of(result: PdfTextLayout.Result): Entry =
                Entry(result.problem, result.paragraphs, result.pageCharStarts)
        }
    }

    /**
     * The cheap half of an [Entry]: enough to offer or explain the text view without loading the
     * whole book from disk on every reader launch.
     */
    class Header(val problem: PdfTextLayout.Problem?, val pageCharStarts: IntArray, val note: String?) {
        val usable: Boolean get() = problem == null && note == null

        /** What the text view panel should tell the reader, or null when the text is usable. */
        fun explanation(): String? = problem?.message ?: note
    }

    fun read(id: String): Entry? {
        if (!ID.matches(id)) return null
        val file = file(id)
        if (!file.isFile) return null
        return try {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                val head = readHead(input) ?: return null
                val paragraphs = ArrayList<String>(head.count)
                repeat(head.count) {
                    val length = input.readInt()
                    if (length !in 1..MAX_PARAGRAPH_CHARS) return null
                    val builder = StringBuilder(length)
                    repeat(length) { builder.append(input.readChar()) }
                    paragraphs += builder.toString()
                }
                Entry(head.problem, paragraphs, head.pageCharStarts, head.note)
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Reads only the anchors and the verdict; paragraphs stay on disk. */
    fun readHeader(id: String): Header? {
        if (!ID.matches(id)) return null
        val file = file(id)
        if (!file.isFile) return null
        return try {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                readHead(input)?.let { head -> Header(head.problem, head.pageCharStarts, head.note) }
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    fun write(id: String, entry: Entry) {
        if (!ID.matches(id)) return
        val pages = entry.pageCharStarts.size - 1
        if (pages !in 1..MAX_PAGES) return
        if (entry.problem == null && entry.note == null && entry.paragraphs.isEmpty()) return
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return
        val note = entry.note?.take(MAX_NOTE_CHARS)
        val target = file(id)
        val temp = File(directory, "$id.tmp")
        try {
            DataOutputStream(FileOutputStream(temp).buffered()).use { output ->
                output.writeUTF(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(entry.pageCharStarts.size - 1)
                entry.pageCharStarts.forEach(output::writeInt)
                output.writeByte(entry.problem?.ordinal ?: -1)
                output.writeBoolean(note != null)
                if (note != null) output.writeUTF(note)
                output.writeInt(entry.paragraphs.size)
                entry.paragraphs.forEach { text ->
                    output.writeInt(text.length)
                    text.forEach { char -> output.writeChar(char.code) }
                }
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("缓存文件无法替换。")
            }
        } catch (_: IOException) {
            temp.delete()
        }
    }

    /**
     * Remembers a failure that will repeat for this file, so opening the book again neither parses
     * the PDF a second time nor loses the explanation.
     */
    fun writeFailure(id: String, note: String) {
        write(id, Entry(null, emptyList(), IntArray(2), note))
    }

    fun delete(id: String) {
        if (ID.matches(id)) file(id).delete()
    }

    private class Head(
        val problem: PdfTextLayout.Problem?,
        val pageCharStarts: IntArray,
        val note: String?,
        val count: Int,
    )

    private fun readHead(input: DataInputStream): Head? {
        if (input.readUTF() != MAGIC || input.readInt() != VERSION) return null
        val pages = input.readInt()
        if (pages !in 1..MAX_PAGES) return null
        val starts = IntArray(pages + 1) { input.readInt() }
        if (!starts.isMonotonic()) return null
        val ordinal = input.readByte().toInt()
        val problem = if (ordinal < 0) null else PdfTextLayout.Problem.entries.getOrNull(ordinal)
        if (ordinal >= 0 && problem == null) return null
        val note = if (input.readBoolean()) input.readUTF() else null
        val count = input.readInt()
        if (count !in 0..MAX_PARAGRAPHS) return null
        // A usable extraction always has text; a rejected one carries a reason instead.
        if (problem == null && note == null && count == 0) return null
        return Head(problem, starts, note, count)
    }

    private fun file(id: String): File = File(directory, "$id.pdftext")

    /** Anchors must never move backwards, or the page lookup would return a later page. */
    private fun IntArray.isMonotonic(): Boolean {
        for (index in 1 until size) {
            if (this[index] < this[index - 1]) return false
        }
        return true
    }

    private companion object {
        const val MAGIC = "MOYU-PDF-TEXT"
        const val VERSION = 2
        const val MAX_PAGES = 100_000
        const val MAX_PARAGRAPHS = 1_000_000
        const val MAX_PARAGRAPH_CHARS = 1_000_000
        const val MAX_NOTE_CHARS = 200
        val ID = Regex("[0-9a-f]{64}")
    }
}
