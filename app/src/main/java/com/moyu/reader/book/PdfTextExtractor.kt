package com.moyu.reader.book

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Reads the text layer of a PDF one page at a time. Pages come back as cleaned lines; paragraph
 * rebuilding and the "is there any text at all" decision belong to [PdfTextLayout].
 *
 * The document is spilled to [tempDir] instead of being held in memory: a novel-sized PDF parses
 * inside the few megabytes a phone can spare, and every page boundary is a cancellation point.
 */
object PdfTextExtractor {

    suspend fun read(input: InputStream, tempDir: File, maxPages: Int): List<List<String>> =
        withContext(Dispatchers.IO) {
            val document = load(input, tempDir)
            try {
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                    lineSeparator = "\n"
                }
                val pages = ArrayList<List<String>>()
                for (page in 1..minOf(document.numberOfPages, maxPages)) {
                    currentCoroutineContext().ensureActive()
                    stripper.startPage = page
                    stripper.endPage = page
                    pages += PdfTextLayout.cleanLines(stripper.getText(document))
                }
                pages
            } catch (error: TextBookException) {
                throw error
            } catch (_: IOException) {
                throw TextBookException("这个 PDF 的文字层读取失败，请用原版阅读。")
            } catch (_: RuntimeException) {
                throw TextBookException("这个 PDF 的文字层读取失败，请用原版阅读。")
            } finally {
                close(document)
            }
        }

    private fun load(input: InputStream, tempDir: File): PDDocument {
        if (!tempDir.isDirectory && !tempDir.mkdirs() && !tempDir.isDirectory) {
            throw TextBookException("无法创建解析缓存目录。", retryable = true)
        }
        val setting = MemoryUsageSetting.setupTempFileOnly().apply { setTempDir(tempDir) }
        return try {
            PDDocument.load(input, setting)
        } catch (_: InvalidPasswordException) {
            throw TextBookException("此 PDF 已加密或受密码保护，暂不支持文字版。")
        } catch (_: IOException) {
            throw TextBookException("这个 PDF 的文件已损坏，无法读取文字。")
        } catch (_: OutOfMemoryError) {
            throw TextBookException("内存不足，无法解析这个 PDF 的文字。")
        } catch (_: RuntimeException) {
            throw TextBookException("这个 PDF 无法解析文字，请用原版阅读。")
        }
    }

    private fun close(document: PDDocument) {
        try {
            document.close()
        } catch (_: IOException) {
            // The extraction result is already in hand; a failed close must not replace it.
        } catch (_: RuntimeException) {
            // Same here: the document is discarded either way.
        }
    }
}
