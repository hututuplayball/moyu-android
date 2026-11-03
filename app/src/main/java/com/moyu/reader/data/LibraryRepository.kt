package com.moyu.reader.data

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.LruCache
import com.moyu.reader.book.EpubCover
import com.moyu.reader.book.EpubSniffer
import com.moyu.reader.book.PdfTextBook
import com.moyu.reader.book.PdfTextCache
import com.moyu.reader.book.PdfTextExtractor
import com.moyu.reader.book.PdfTextLayout
import com.moyu.reader.book.TextBookException
import com.moyu.reader.book.TextDocument
import com.moyu.reader.book.TextDocumentCache
import com.moyu.reader.book.TextEncoding
import com.moyu.reader.book.TextLayoutCache
import com.moyu.reader.book.TextPageSource
import com.moyu.reader.book.TextReadingSettings
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.moyu.reader.page.ComicFolderPage
import com.moyu.reader.page.ComicFolders
import com.moyu.reader.page.ImagePageSource
import com.moyu.reader.page.PageSource
import com.moyu.reader.page.PageSourceException
import com.moyu.reader.pdf.PageSize
import com.moyu.reader.pdf.PdfSession
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LibraryException(message: String) : IOException(message)

class LibraryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val dao = ReaderDatabase.getInstance(appContext).libraryDao()
    private val preferences = PreferencesRepository(appContext)
    private val booksDirectory = File(appContext.filesDir, "books")
    private val coversDirectory = File(appContext.filesDir, "covers")
    private val pdfTextDirectory = File(appContext.filesDir, "pdf-text")
    private val pdfParseDirectory = File(appContext.cacheDir, "pdf-parse")
    private val pdfTextCache = PdfTextCache(pdfTextDirectory)
    private val textLayoutDirectory = File(appContext.filesDir, "text-layout")
    private val textLayoutCache = TextLayoutCache(textLayoutDirectory)
    private val textDocumentDirectory = File(appContext.filesDir, "text-documents")
    private val textDocuments = TextDocumentCache(textDocumentDirectory)
    // Parsing is the fixed cost of every visit: a long EPUB, TXT or PDF text export would be
    // decoded again even when its page map is cached. Keep the book that was just read parsed,
    // so reopening it goes straight to layout (or straight to an adopted map).
    private val parsedDocuments = LruCache<String, TextDocument>(1)

    // PDFBox's CMap tables are only needed for PDFs. Loading them at process start made a pure
    // text session pay for a library it never uses, so the first PDF opens it on demand.
    private val pdfBoxResources by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { PDFBoxResourceLoader.init(appContext) }
        Unit
    }

    init {
        // Application-scoped observation starts on cold launch even before settings is opened.
        BackupRepository.getInstance(appContext)
        // PDFBox spills parsed documents to scratch files; a process killed mid-parse leaves them
        // behind, and at launch nothing can be reading them yet.
        runCatching { pdfParseDirectory.deleteRecursively() }
    }

    val groups: Flow<List<GroupEntity>> = dao.observeGroups().catch { error ->
        if (error is SQLiteException) throw LibraryException("读取分组失败，请稍后重试。")
        throw error
    }

    val books: Flow<List<BookEntity>> = dao.observeBooks().catch { error ->
        if (error is SQLiteException) {
            throw LibraryException("读取书架失败，请检查设备存储后重试。")
        }
        throw error
    }

    fun bookmarks(bookId: String): Flow<List<Int>> = dao.observeBookmarks(bookId)
        .catch { error ->
            if (error is SQLiteException) {
                throw LibraryException("读取书签失败，请稍后重试。")
            }
            throw error
        }

    suspend fun importFile(uri: Uri, kind: Int = KIND_COMIC): BookEntity = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            try {
                importLocked(uri, kind)
            } catch (error: CancellationException) {
                throw error
            } catch (error: LibraryException) {
                throw error
            } catch (error: PageSourceException) {
                throw LibraryException(error.message ?: "文件无效或已损坏，无法导入。")
            } catch (_: SQLiteFullException) {
                throw LibraryException("存储空间不足，无法保存书架，请清理空间后重试。")
            } catch (_: SQLiteException) {
                throw LibraryException("无法保存书架，请检查设备存储后重试。")
            } catch (_: SecurityException) {
                throw LibraryException("无法读取所选文件，请重新选择文件并授予访问权限。")
            } catch (_: IOException) {
                throw LibraryException("导入失败，请检查文件及设备存储空间。")
            } catch (_: OutOfMemoryError) {
                throw LibraryException("内存不足，无法导入此文件，请关闭其他文档后重试。")
            } catch (_: RuntimeException) {
                throw LibraryException("无法读取这个文件，请确认它未加密、未损坏。")
            }
        }
    }

    suspend fun importFolder(treeUri: Uri): BookEntity = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            try {
                importFolderLocked(treeUri)
            } catch (error: CancellationException) {
                throw error
            } catch (error: LibraryException) {
                throw error
            } catch (error: PageSourceException) {
                throw LibraryException(error.message ?: "文件夹无效或无法读取。")
            } catch (_: SQLiteFullException) {
                throw LibraryException("存储空间不足，无法保存书架，请清理空间后重试。")
            } catch (_: SQLiteException) {
                throw LibraryException("无法保存书架，请检查设备存储后重试。")
            } catch (_: SecurityException) {
                throw LibraryException("无法读取所选文件夹，请重新选择并授予访问权限。")
            } catch (_: IOException) {
                throw LibraryException("导入失败，请检查文件夹及设备存储空间。")
            } catch (_: OutOfMemoryError) {
                throw LibraryException("内存不足，无法导入此文件夹，请关闭其他文档后重试。")
            } catch (_: RuntimeException) {
                throw LibraryException("无法读取这个文件夹，请确认里面的图片完整、可访问。")
            }
        }
    }

    /** Re-links a folder book after it was moved, without ever registering a second book. */
    suspend fun relinkFolder(book: BookEntity, treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            try {
                if (book.format != FORMAT_FOLDER || !takePersistable(treeUri)) return@withLock false
                val folder = ComicFolders.list(resolver, treeUri)
                if (folder.pages.isEmpty() || folderFingerprint(folder.pages) != book.id) {
                    releaseGrantIfUnused(treeUri.toString())
                    return@withLock false
                }
                val existing = dao.getBook(book.id) ?: return@withLock false
                val previousUri = existing.sourceUri
                databaseOperation { dao.attachReference(existing.id, treeUri.toString()) }
                if (previousUri != null && previousUri != treeUri.toString()) releaseGrantIfUnused(previousUri)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PageSourceException) {
                throw LibraryException(error.message ?: "重新关联文件夹失败，请稍后再试。")
            } catch (error: LibraryException) {
                throw error
            } catch (_: SecurityException) {
                throw LibraryException("无法读取所选文件夹，请重新选择并授予访问权限。")
            } catch (_: IOException) {
                throw LibraryException("重新关联文件夹失败，请检查文件后重试。")
            } catch (_: RuntimeException) {
                throw LibraryException("所选文件夹不可用，请重新选择。")
            }
        }
    }

    suspend fun getBook(id: String): BookEntity? = withContext(Dispatchers.IO) {
        databaseOperation { dao.getBook(id) }
    }

    suspend fun saveProgress(
        id: String,
        page: Int,
        offset: Int,
        mode: String,
        readView: Int = READ_VIEW_PDF,
        textPage: Int = 0,
        textPageCount: Int = 0,
        textOffset: Int = 0,
        textProgress: Int = 0,
        textAnchors: Int = 0,
    ) {
        withContext(Dispatchers.IO) {
            databaseOperation {
                dao.saveProgress(id, page, offset, mode, readView, textPage, textPageCount,
                    textOffset, textProgress, textAnchors)
            }
        }
    }

    /** Character-anchored bookmarks for pure text books; PDF and comics keep page marks. */
    fun textBookmarks(bookId: String): Flow<List<Int>> = dao.textBookmarks(bookId)

    suspend fun toggleTextBookmark(bookId: String, offset: Int) = withContext(Dispatchers.IO) {
        databaseOperation { dao.toggleTextBookmark(bookId, offset) }
    }

    /** One-time conversion of a pure-text book's page marks to character anchors. */
    suspend fun migrateTextBookmarks(book: BookEntity, totalChars: Int) = withContext(Dispatchers.IO) {
        if (book.textAnchors != 0 || book.format == FORMAT_PDF) return@withContext
        val pages = databaseOperation { dao.legacyBookmarkPages(book.id) }
        // A stale flag with no page marks means the book already uses character anchors. Clearing
        // the text marks here would delete every bookmark the reader has been adding since.
        if (pages.isEmpty()) {
            databaseOperation { dao.markTextAnchors(book.id) }
            return@withContext
        }
        val count = book.pageCount.coerceAtLeast(1)
        val offsets = pages.map { page ->
            (page.toLong() * totalChars / count).toInt().coerceIn(0, (totalChars - 1).coerceAtLeast(0))
        }
        databaseOperation { dao.adoptTextBookmarks(book.id, offsets) }
    }

    /**
     * Text books paginate for the window they are opened in, so the reader reports the page range it
     * actually built. Keeping the row in step makes library percentages, bookmarks and the saved
     * page index valid on this device.
     */
    suspend fun adoptPageCount(id: String, pageCount: Int): Boolean = withContext(Dispatchers.IO) {
        databaseOperation { dao.adoptPageCount(id, pageCount) }
    }

    suspend fun toggleBookmark(id: String, page: Int) {
        withContext(Dispatchers.IO) {
            databaseOperation { dao.toggleBookmark(id, page) }
        }
    }

    suspend fun createGroup(name: String, kind: Int = KIND_COMIC): Unit = withContext(Dispatchers.IO) {
        databaseOperation { dao.createGroup(name, kind) }
    }

    suspend fun renameGroup(id: String, name: String): Unit = withContext(Dispatchers.IO) {
        databaseOperation { dao.renameGroup(id, name) }
    }

    suspend fun deleteGroup(id: String): Unit = withContext(Dispatchers.IO) {
        databaseOperation { dao.deleteGroup(id) }
    }

    suspend fun moveBooks(ids: List<String>, groupId: String?): Unit = withContext(Dispatchers.IO) {
        databaseOperation { dao.moveBooks(ids, groupId) }
    }

    suspend fun reorderBooks(ids: List<String>): Unit = withContext(Dispatchers.IO) {
        databaseOperation { dao.reorderBooks(ids) }
    }

    suspend fun reorderGroups(ids: List<String>): Unit = withContext(Dispatchers.IO) {
        databaseOperation { dao.reorderGroups(ids) }
    }

    suspend fun deleteBook(id: String) { deleteBooks(listOf(id)) }

    suspend fun deleteBooks(ids: List<String>) {
        withContext(Dispatchers.IO) {
            fileMutex.withLock {
                databaseOperation { MetadataRules.ids(ids) }
                // Private copies are the only files the app may remove; originals are never touched.
                val files = ids.flatMap {
                    listOf(
                        privateFile(booksDirectory, it, "pdf"),
                        privateFile(booksDirectory, it, "zip"),
                        privateFile(coversDirectory, it, "jpg"),
                        privateFile(pdfTextDirectory, it, "pdftext"),
                    )
                }
                val references = ids.mapNotNull { databaseOperation { dao.getBook(it) } }
                    .mapNotNull { it.sourceUri }.distinct()
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    databaseOperation { dao.deleteBooks(ids) }
                    var removed = true
                    files.forEach { if (!deleteIfPossible(it)) removed = false }
                    // Cached page maps are disposable: a leftover file only wastes a few kilobytes.
                    ids.forEach { textLayoutCache.delete(it) }
                    ids.forEach { textDocuments.delete(it) }
                    ids.forEach { parsedDocuments.remove(it) }
                    references.forEach { releaseGrantIfUnused(it) }
                    preferences.clearLastReadingBooks(ids.toSet())
                    if (!removed) {
                        throw LibraryException("已移出书架，但本地副本未能完全删除，请检查存储后重试。")
                    }
                }
            }
        }
    }

    fun bookFile(book: BookEntity): File = privateFile(
        booksDirectory,
        book.id,
        if (book.format == FORMAT_ARCHIVE) "zip" else "pdf",
    )

    fun coverFile(book: BookEntity): File = privateFile(coversDirectory, book.id, "jpg")

    suspend fun isReadable(book: BookEntity): Boolean = withContext(Dispatchers.IO) { accessible(book) }

    /** Startup probe for originals that were moved or deleted after they were linked. */
    suspend fun unavailableReferences(): Set<String> = withContext(Dispatchers.IO) {
        databaseOperation { dao.allBooks() }
            .asSequence()
            .filter { it.isReference }
            .filterNot { accessible(it) }
            .mapTo(mutableSetOf()) { it.id }
    }

    suspend fun openBook(
        book: BookEntity,
        text: TextReadingSettings = TextReadingSettings(),
        textPage: PageSize = TextPageSource.CANONICAL_PAGE,
        textDensity: Float = TextPageSource.CANONICAL_DENSITY,
        startOffset: Int = 0,
    ): PageSource = withContext(Dispatchers.IO) {
        val sourceUri = book.sourceUri
        if (book.format == FORMAT_FOLDER) {
            val folderUri = sourceUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?: throw LibraryException("原文件夹链接已丢失，请重新选择文件夹。")
            return@withContext ImagePageSource.openFolder(resolver, folderUri)
        }
        if (!book.isReference || sourceUri.isNullOrBlank()) {
            return@withContext openPrivateSource(bookFile(book), book.format, text, textPage, textDensity, book.id, startOffset)
        }
        val descriptor = try {
            resolver.openFileDescriptor(Uri.parse(sourceUri), "r")
        } catch (_: FileNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw LibraryException("原文件已不可访问，请重新导入这本书。")
        when (book.format) {
            FORMAT_ARCHIVE -> ImagePageSource.open(descriptor)
            FORMAT_TEXT, FORMAT_EPUB -> {
                val parsed = parsedDocuments.get(book.id)
                    ?: textDocuments.read(book.id)?.also { parsedDocuments.put(book.id, it) }
                if (parsed != null) {
                    try { descriptor.close() } catch (_: IOException) { }
                    TextPageSource.reflow(parsed, text, textPage, textDensity, textLayoutCache, book.id, startOffset)
                } else {
                    TextPageSource.open(
                        descriptor,
                        book.format,
                        text,
                        textPage,
                        textDensity,
                        textLayoutCache,
                        book.id,
                        startOffset,
                    ).also { rememberDocument(book.id, it) }
                }
            }
            else -> {
                ensurePdfBox()
                PdfSession.open(descriptor)
            }
        }
    }

    /** Loads PDFBox resources once, synchronously, before any PDF parser touches them. */
    private fun ensurePdfBox() = pdfBoxResources

    /** Drops the in-memory text document after leaving the reader; the disk cache stays. */
    fun releaseParsedDocument(id: String?) {
        if (id != null) parsedDocuments.remove(id)
    }

    /** Releases reconstructible caches when the system is short of memory or the app is hidden. */
    fun trimMemory() {
        parsedDocuments.evictAll()
    }

    /** Re-lays out an already parsed book, reusing its stored page map when the typesetting matches. */
    suspend fun reflowText(
        book: BookEntity,
        document: TextDocument,
        text: TextReadingSettings,
        textPage: PageSize,
        textDensity: Float,
        startOffset: Int = 0,
    ): PageSource = withContext(Dispatchers.IO) {
        TextPageSource.reflow(document, text, textPage, textDensity, textLayoutCache, book.id, startOffset)
    }

    /**
     * Probes a PDF's text layer once and remembers the outcome. Both a rejected document and a
     * failure that will repeat for this file are cached, so the PDF is parsed at most once; only
     * an environmental failure stays unknown and may retry.
     */
    suspend fun preparePdfText(book: BookEntity): Int = withContext(Dispatchers.IO) {
        if (book.format != FORMAT_PDF || book.kind != KIND_EBOOK) return@withContext TEXT_UNSUPPORTED
        // The header answers the whole question; loading every paragraph would only waste IO.
        val cached = pdfTextCache.readHeader(book.id)
        val state = if (cached != null) {
            if (cached.usable) TEXT_READY else TEXT_UNSUPPORTED
        } else {
            extractAndStorePdfText(book)
        }
        databaseOperation { dao.setTextState(book.id, state) }
        state
    }

    /** Forgets a stored verdict so a reader who expects text can ask for a fresh parse. */
    suspend fun forgetPdfText(book: BookEntity): Int = withContext(Dispatchers.IO) {
        pdfTextCache.delete(book.id)
        databaseOperation { dao.setTextState(book.id, TEXT_UNKNOWN) }
        TEXT_UNKNOWN
    }

    /** Page anchors of a prepared text version: they bridge PDF pages and character offsets. */
    suspend fun pdfTextAnchors(book: BookEntity): IntArray? = withContext(Dispatchers.IO) {
        pdfTextCache.readHeader(book.id)?.takeIf { it.usable }?.pageCharStarts
    }

    /** Explains why a PDF has no text version, or null when it has a usable one. */
    suspend fun pdfTextNotice(book: BookEntity): String? = withContext(Dispatchers.IO) {
        pdfTextCache.readHeader(book.id)?.explanation()
    }

    /** Opens a PDF as flowing text; the caller probes it first, so a missing cache is a bug. */
    suspend fun openPdfText(
        book: BookEntity,
        text: TextReadingSettings,
        textPage: PageSize,
        textDensity: Float,
        startOffset: Int = 0,
    ): PageSource = withContext(Dispatchers.IO) {
        parsedDocuments.get(book.id)?.let { parsed ->
            return@withContext TextPageSource.reflow(parsed, text, textPage, textDensity, textLayoutCache, book.id, startOffset)
        }
        textDocuments.read(book.id)?.let { parsed ->
            parsedDocuments.put(book.id, parsed)
            return@withContext TextPageSource.reflow(parsed, text, textPage, textDensity, textLayoutCache, book.id, startOffset)
        }
        val entry = pdfTextCache.read(book.id) ?: throw LibraryException(PdfTextLayout.Problem.EMPTY.message)
        (entry.problem?.message ?: entry.note)?.let { throw LibraryException(it) }
        val document = PdfTextBook.document(entry)
        parsedDocuments.put(book.id, document)
        textDocuments.write(book.id, document)
        TextPageSource.reflow(document, text, textPage, textDensity, textLayoutCache, book.id, startOffset)
    }

    /**
     * Runs the parse once and stores its outcome. A failure the file itself causes is remembered
     * with its explanation: otherwise every visit would pay for the same doomed parse.
     */
    private suspend fun extractAndStorePdfText(book: BookEntity): Int {
        val result = try {
            extractPdfText(book)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: TextBookException) {
            if (error.retryable) return TEXT_UNKNOWN
            pdfTextCache.writeFailure(book.id, error.message ?: DEFAULT_PDF_TEXT_FAILURE)
            return TEXT_UNSUPPORTED
        } catch (_: OutOfMemoryError) {
            // The same file on the same phone runs out of memory again; explain it once.
            pdfTextCache.writeFailure(book.id, "内存不足，无法解析这个 PDF 的文字，请用原版阅读。")
            return TEXT_UNSUPPORTED
        } catch (_: LinkageError) {
            // PDFBox itself failed to load (missing class or resources): stay on the original pages.
            pdfTextCache.writeFailure(book.id, DEFAULT_PDF_TEXT_FAILURE)
            return TEXT_UNSUPPORTED
        } catch (_: Exception) {
            return TEXT_UNKNOWN
        }
        pdfTextCache.write(book.id, PdfTextCache.Entry.of(result))
        return if (result.problem == null) TEXT_READY else TEXT_UNSUPPORTED
    }

    private suspend fun extractPdfText(book: BookEntity): PdfTextLayout.Result {
        // A partial text version would silently cut the book short, so oversized documents are
        // refused outright and stay readable in the original view.
        if (book.pageCount > MAX_PDF_TEXT_PAGES) {
            throw TextBookException("这个 PDF 页数太多，暂不支持文字版，请用原版阅读。")
        }
        if (pdfSize(book) > MAX_PDF_TEXT_BYTES) {
            throw TextBookException("这个 PDF 太大，暂不支持文字版，请用原版阅读。")
        }
        val pages = openPdfStream(book).use { input ->
            ensurePdfBox()
            PdfTextExtractor.read(input, pdfParseDirectory, book.pageCount.coerceAtLeast(1))
        }
        return PdfTextLayout.reflow(pages)
    }

    private fun pdfSize(book: BookEntity): Long {
        val uri = book.sourceUri
        if (book.isReference && !uri.isNullOrBlank()) {
            return try {
                resolver.openFileDescriptor(Uri.parse(uri), "r")?.use { it.statSize } ?: 0L
            } catch (_: FileNotFoundException) {
                0L
            } catch (_: SecurityException) {
                0L
            } catch (_: IOException) {
                0L
            }
        }
        return bookFile(book).length()
    }

    /** PDFBox needs a stream; originals stay where they are and private copies are read directly. */
    private fun openPdfStream(book: BookEntity): InputStream {
        val uri = book.sourceUri
        if (book.isReference && !uri.isNullOrBlank()) {
            return try {
                resolver.openInputStream(Uri.parse(uri)) ?: throw missingOriginal()
            } catch (_: FileNotFoundException) {
                throw missingOriginal()
            } catch (_: SecurityException) {
                throw LibraryException("无法读取原文件，请重新导入这本书。")
            } catch (_: IOException) {
                throw LibraryException("原文件读取失败，请检查网络或存储后重试。")
            }
        }
        return try {
            FileInputStream(bookFile(book))
        } catch (_: FileNotFoundException) {
            throw LibraryException("本地副本已丢失，请重新导入这本书。")
        }
    }

    private fun missingOriginal() = LibraryException("原文件已不可访问，请重新导入这本书。")

    private fun readable(book: BookEntity): Boolean {
        if (!book.isReference) return bookFile(book).isFile
        val uri = book.sourceUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        return resolver.persistedUriPermissions.any { it.isReadPermission && it.uri == uri }
    }

    /** Grants outlive the document they point at, so opening it is the only honest check. */
    private fun accessible(book: BookEntity): Boolean {
        if (!readable(book)) return false
        if (!book.isReference) return true
        val uri = book.sourceUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        if (book.format == FORMAT_FOLDER) return folderExists(uri)
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: FileNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun folderExists(uri: Uri): Boolean = try {
        val rootId = DocumentsContract.getTreeDocumentId(uri)
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, rootId)
        resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private suspend fun importLocked(uri: Uri, kind: Int): BookEntity {
        currentCoroutineContext().ensureActive()
        MetadataRules.kind(kind)
        ensureDirectory(booksDirectory)
        ensureDirectory(coversDirectory)
        val name = displayName(uri)
        if (uri.scheme == "content") {
            try {
                tryReferenceImport(uri, name, kind)?.let { return it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                releaseGrantIfUnused(uri.toString())
                throw error
            }
            // The reference path declined this provider; do not keep a grant nothing will use.
            releaseGrantIfUnused(uri.toString())
        }
        return importCopy(uri, name, kind)
    }

    /**
     * Documents picked through SAF stay where the user put them; only their cover is copied.
     * Providers that cannot grant lasting, seekable access fall back to a private copy.
     */
    private suspend fun tryReferenceImport(uri: Uri, name: String, kind: Int): BookEntity? {
        if (!takePersistable(uri)) return null
        val probe = fingerprint(uri, kind)
        requireSupportedKind(kind, probe.format)
        val id = probe.digest
        val existing = dao.getBook(id)
        if (existing != null && readable(existing) && coverFile(existing).isFile) {
            // Re-importing the same EPUB is also how an older shelf refresh gets its real cover.
            if (existing.format == FORMAT_EPUB) {
                runCatching { writeEpubCover({ openSource(uri) }, coverFile(existing)) }
            }
            if (existing.sourceUri != uri.toString()) releaseGrantIfUnused(uri.toString())
            return existing
        }
        var coverPart: File? = null
        try {
            if (existing == null || !coverFile(existing).isFile) {
                coverPart = storageOperation { File.createTempFile("cover-", ".part", coversDirectory) }
            }
            val pageCount = try {
                useSession({ openReferenceSession(uri, probe.format, bookId = probe.digest) }) { session ->
                    coverPart?.let { destination ->
                        val extracted = probe.format == FORMAT_EPUB &&
                            writeEpubCover({ openSource(uri) }, destination)
                        if (!extracted) renderCover(session, destination)
                    }
                    session.pageCount
                }
            } catch (_: PageSourceException) {
                // Not seekable or not readable here; the copy path reports the actual problem.
                return null
            }
            val previousUri = existing?.sourceUri
            val book = existing ?: BookEntity(
                id = id,
                title = comicTitle(name),
                fileName = name,
                pageCount = pageCount,
                importedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                sourceUri = uri.toString(),
                storageMode = STORAGE_REFERENCE,
                format = probe.format,
                kind = kind,
            )
            // Text books paginate for the device they are read on, so their count is no file property.
            if (pageCount != book.pageCount && !isTextFormat(book.format)) {
                throw LibraryException("页数与原记录不一致，无法关联这份文件。")
            }
            currentCoroutineContext().ensureActive()
            return withContext(NonCancellable) {
                var ownsCover = false
                var published = false
                try {
                    val cover = coverFile(book)
                    if (coverPart != null && !cover.exists()) {
                        storageOperation { Files.move(coverPart.toPath(), cover.toPath()) }
                        ownsCover = true
                    }
                    val result = if (existing == null) dao.publishImportedBook(book) else {
                        dao.attachReference(existing.id, uri.toString())
                        dao.getBook(existing.id) ?: existing
                    }
                    published = true
                    if (previousUri != null && previousUri != uri.toString()) releaseGrantIfUnused(previousUri)
                    // A former private copy is redundant once the row reads the original document.
                    if (existing != null && !existing.isReference) deleteIfPossible(bookFile(existing))
                    result
                } finally {
                    if (!published && ownsCover) deleteIfPossible(coverFile(book))
                }
            }
        } finally {
            coverPart?.let { deleteIfPossible(it) }
        }
    }

    private suspend fun importFolderLocked(treeUri: Uri): BookEntity {
        currentCoroutineContext().ensureActive()
        ensureDirectory(booksDirectory)
        ensureDirectory(coversDirectory)
        if (!takePersistable(treeUri)) {
            throw LibraryException("无法保存文件夹访问权限，请换一个文件夹重试。")
        }
        try {
            val folder = ComicFolders.list(resolver, treeUri)
            if (folder.pages.isEmpty()) {
                throw LibraryException("这个文件夹里没有图片，漫画需要 JPG、PNG、WebP 等图片文件。")
            }
            val id = folderFingerprint(folder.pages)
            val existing = dao.getBook(id)
            if (existing != null && readable(existing) && coverFile(existing).isFile) {
                if (existing.sourceUri != treeUri.toString()) releaseGrantIfUnused(treeUri.toString())
                return existing
            }
            var coverPart: File? = null
            try {
                if (existing == null || !coverFile(existing).isFile) {
                    coverPart = storageOperation { File.createTempFile("cover-", ".part", coversDirectory) }
                }
                val pageCount = useSession({ ImagePageSource.openFolder(resolver, folder) }) { session ->
                    coverPart?.let { renderCover(session, it) }
                    session.pageCount
                }
                val previousUri = existing?.sourceUri
                val book = existing ?: BookEntity(
                    id = id,
                    title = folder.name.trim().ifBlank { "未命名" },
                    fileName = folder.name,
                    pageCount = pageCount,
                    importedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    sourceUri = treeUri.toString(),
                    storageMode = STORAGE_REFERENCE,
                    format = FORMAT_FOLDER,
                    kind = KIND_COMIC,
                )
                if (pageCount != book.pageCount) {
                    throw LibraryException("页数与原记录不一致，无法关联这个文件夹。")
                }
                currentCoroutineContext().ensureActive()
                return withContext(NonCancellable) {
                    var ownsCover = false
                    var published = false
                    try {
                        val cover = coverFile(book)
                        if (coverPart != null && !cover.exists()) {
                            storageOperation { Files.move(coverPart.toPath(), cover.toPath()) }
                            ownsCover = true
                        }
                        val result = if (existing == null) dao.publishImportedBook(book) else {
                            dao.attachReference(existing.id, treeUri.toString())
                            dao.getBook(existing.id) ?: existing
                        }
                        published = true
                        if (previousUri != null && previousUri != treeUri.toString()) releaseGrantIfUnused(previousUri)
                        result
                    } finally {
                        if (!published && ownsCover) deleteIfPossible(coverFile(book))
                    }
                }
            } finally {
                coverPart?.let { deleteIfPossible(it) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            try {
                releaseGrantIfUnused(treeUri.toString())
            } catch (_: Exception) {
                // Keep the original import failure.
            }
            throw error
        }
    }

    private suspend fun importCopy(uri: Uri, name: String, kind: Int): BookEntity {
        val part = storageOperation { File.createTempFile("import-", ".part", booksDirectory) }
        var coverPart: File? = null
        try {
            val probe = copySource(uri, part, kind)
            requireSupportedKind(kind, probe.format)
            val existing = dao.getBook(probe.digest)
            if (existing != null && bookFile(existing).isFile && coverFile(existing).isFile) {
                if (existing.format == FORMAT_EPUB) {
                    runCatching { writeEpubCover(part, coverFile(existing)) }
                }
                return existing
            }
            val coverTemp = storageOperation {
                File.createTempFile("cover-", ".part", coversDirectory)
            }
            coverPart = coverTemp
            val pageCount = validateAndCreateCover(part, coverTemp, probe.format, probe.digest)
            val now = System.currentTimeMillis()
            val book = BookEntity(
                id = probe.digest,
                title = comicTitle(name),
                fileName = name,
                pageCount = pageCount,
                importedAt = now,
                updatedAt = now,
                format = probe.format,
                kind = kind,
            )
            val destination = bookFile(book)
            val cover = coverFile(book)
            if (destination.exists() && (!destination.isFile || hashFile(destination) != book.id)) {
                throw LibraryException("本地副本校验失败，已保留原有文件，无法完成导入。")
            }
            if (cover.exists() && !cover.isFile) {
                throw LibraryException("封面存储不可用，无法完成导入。")
            }
            currentCoroutineContext().ensureActive()
            // Publishing files and their row is one non-cancellable ownership transfer.
            return withContext(NonCancellable) {
                var ownsSource = false
                var ownsCover = false
                var published = false
                try {
                    if (!destination.exists()) {
                        storageOperation { Files.move(part.toPath(), destination.toPath()) }
                        ownsSource = true
                    }
                    if (!cover.exists()) {
                        storageOperation { Files.move(coverTemp.toPath(), cover.toPath()) }
                        ownsCover = true
                    }
                    val result = dao.publishImportedBook(book)
                    published = true
                    result
                } finally {
                    if (!published) {
                        if (ownsCover) deleteIfPossible(cover)
                        if (ownsSource) deleteIfPossible(destination)
                    }
                }
            }
        } finally {
            deleteIfPossible(part)
            coverPart?.let { deleteIfPossible(it) }
        }
    }

    private fun takePersistable(uri: Uri): Boolean = try {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private suspend fun releaseGrantIfUnused(uri: String) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return
        if (databaseOperation { dao.countReferences(uri) } > 0) return
        try {
            resolver.releasePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The grant is already gone; nothing else to release.
        } catch (_: IllegalArgumentException) {
            // The provider no longer exists.
        }
    }

    private suspend fun openReferenceSession(
        uri: Uri,
        format: Int,
        text: TextReadingSettings = TextReadingSettings(),
        textPage: PageSize = TextPageSource.CANONICAL_PAGE,
        textDensity: Float = TextPageSource.CANONICAL_DENSITY,
        bookId: String? = null,
    ): PageSource {
        val descriptor = try {
            resolver.openFileDescriptor(uri, "r")
        } catch (_: FileNotFoundException) {
            throw LibraryException("所选文件不存在或暂时不可用，请重新选择。")
        } catch (_: SecurityException) {
            throw LibraryException("无法读取所选文件，请重新选择文件并授予访问权限。")
        } catch (_: IOException) {
            throw LibraryException("文件读取中断，请检查文件或云端网络连接后重试。")
        } catch (_: IllegalArgumentException) {
            throw LibraryException("所选文件来源不可用，请从文件选择器重新选择。")
        } ?: throw LibraryException("所选文件不存在或暂时不可用，请重新选择。")
        return when (format) {
            FORMAT_ARCHIVE -> ImagePageSource.open(descriptor)
            FORMAT_TEXT, FORMAT_EPUB -> TextPageSource.open(
                descriptor, format, text, textPage, textDensity, textLayoutCache, bookId,
            ).also { rememberDocument(bookId, it) }
            else -> {
                ensurePdfBox()
                PdfSession.open(descriptor)
            }
        }
    }

    private suspend fun openPrivateSource(
        file: File,
        format: Int,
        text: TextReadingSettings = TextReadingSettings(),
        textPage: PageSize = TextPageSource.CANONICAL_PAGE,
        textDensity: Float = TextPageSource.CANONICAL_DENSITY,
        bookId: String? = null,
        startOffset: Int = 0,
    ): PageSource = when (format) {
        FORMAT_ARCHIVE -> ImagePageSource.open(file)
        FORMAT_TEXT, FORMAT_EPUB -> {
            val parsed = bookId?.let { id ->
                parsedDocuments.get(id) ?: textDocuments.read(id)?.also { parsedDocuments.put(id, it) }
            }
            if (parsed != null && file.isFile) {
                TextPageSource.reflow(parsed, text, textPage, textDensity, textLayoutCache, bookId, startOffset)
            } else {
                TextPageSource.open(file, format, text, textPage, textDensity, textLayoutCache, bookId, startOffset)
                    .also { rememberDocument(bookId, it) }
            }
        }
        else -> {
            ensurePdfBox()
            PdfSession.open(file)
        }
    }

    /** A parsed book is immutable and keyed by its content hash, so it can be reused safely. */
    private fun rememberDocument(bookId: String?, source: PageSource) {
        val id = bookId ?: return
        val document = (source as? TextPageSource)?.document ?: return
        parsedDocuments.put(id, document)
        textDocuments.write(id, document)
    }

    private suspend fun copySource(uri: Uri, destination: File, kind: Int): Probe {
        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = ByteArray(1024)
        var prefixLength = 0
        var result: Probe? = null
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        openSource(uri).use { input ->
            storageOperation {
                FileOutputStream(destination).use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = readSource(input, buffer)
                        currentCoroutineContext().ensureActive()
                        if (count < 0) break
                        if (count == 0) continue
                        if (prefixLength < prefix.size) {
                            val length = min(count, prefix.size - prefixLength)
                            buffer.copyInto(prefix, prefixLength, 0, length)
                            prefixLength += length
                            if (prefixLength == prefix.size && detectFormat(prefix, prefixLength) == null) {
                                throw unsupportedFile(kind)
                            }
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        currentCoroutineContext().ensureActive()
                    }
                    if (prefixLength == 0) {
                        throw LibraryException(EMPTY_FILE)
                    }
                    var format = detectFormat(prefix, prefixLength) ?: throw unsupportedFile(kind)
                    output.fd.sync()
                    currentCoroutineContext().ensureActive()
                    if (format == FORMAT_ARCHIVE) format = zipKind(destination)
                    result = Probe(hex(digest.digest()), format)
                }
            }
        }
        return result ?: throw LibraryException("导入失败，请重试。")
    }

    private suspend fun fingerprint(uri: Uri, kind: Int): Probe {
        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = ByteArray(1024)
        var prefixLength = 0
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        openSource(uri).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = readSource(input, buffer)
                currentCoroutineContext().ensureActive()
                if (count < 0) break
                if (count == 0) continue
                if (prefixLength < prefix.size) {
                    val length = min(count, prefix.size - prefixLength)
                    buffer.copyInto(prefix, prefixLength, 0, length)
                    prefixLength += length
                    if (prefixLength == prefix.size && detectFormat(prefix, prefixLength) == null) {
                        throw unsupportedFile(kind)
                    }
                }
                digest.update(buffer, 0, count)
            }
        }
        if (prefixLength == 0) throw LibraryException(EMPTY_FILE)
        var format = detectFormat(prefix, prefixLength) ?: throw unsupportedFile(kind)
        if (format == FORMAT_ARCHIVE) format = zipKindFromStream(uri)
        return Probe(hex(digest.digest()), format)
    }

    /** Text layouts are rebuilt for every device, so their page count is not a property of the file. */
    private fun isTextFormat(format: Int) = format == FORMAT_TEXT || format == FORMAT_EPUB

    /** Zips are either a comic archive or an EPUB; the package marker decides which. */
    private fun zipKind(file: File): Int = try {
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            var current: ZipEntry? = null
            val next = {
                if (entries.hasMoreElements()) {
                    current = entries.nextElement()
                    current!!.name
                } else null
            }
            val head = { current?.let { entry -> zip.getInputStream(entry).use(EpubSniffer::head) } }
            if (EpubSniffer.looksLikeEpub(next, head)) FORMAT_EPUB else FORMAT_ARCHIVE
        }
    } catch (_: Exception) {
        FORMAT_ARCHIVE
    }

    private fun zipKindFromStream(uri: Uri): Int = try {
        ZipInputStream(BufferedInputStream(openSource(uri), 64 * 1024)).use { zip ->
            val next = { zip.nextEntry?.name }
            val head = { EpubSniffer.head(zip) }
            if (EpubSniffer.looksLikeEpub(next, head)) FORMAT_EPUB else FORMAT_ARCHIVE
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        FORMAT_ARCHIVE
    }

    private fun openSource(uri: Uri): InputStream = try {
        resolver.openInputStream(uri)
            ?: throw LibraryException("所选文件不存在或暂时不可用，请重新选择。")
    } catch (error: LibraryException) {
        throw error
    } catch (_: FileNotFoundException) {
        throw LibraryException("所选文件不存在或尚未下载，请检查文件及网络连接。")
    } catch (_: SecurityException) {
        throw LibraryException("无法读取所选文件，请重新选择文件并授予访问权限。")
    } catch (_: IOException) {
        throw LibraryException("无法读取所选文件，请检查文件及网络连接。")
    } catch (_: IllegalArgumentException) {
        throw LibraryException("所选文件来源不可用，请从文件选择器重新选择。")
    }

    private fun readSource(input: InputStream, buffer: ByteArray): Int = try {
        input.read(buffer)
    } catch (_: IOException) {
        throw LibraryException("文件读取中断，请检查文件或云端网络连接后重试。")
    }

    private suspend fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        try {
            file.inputStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    currentCoroutineContext().ensureActive()
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        } catch (_: IOException) {
            throw LibraryException("无法校验已有本地副本，请检查设备存储后重试。")
        }
        return hex(digest.digest())
    }

    private suspend fun validateAndCreateCover(
        source: File,
        destination: File,
        format: Int,
        bookId: String? = null,
    ): Int = useSession({ openPrivateSource(source, format, bookId = bookId) }) { session ->
        val extracted = format == FORMAT_EPUB && writeEpubCover(source, destination)
        if (!extracted) renderCover(session, destination)
        session.pageCount
    }

    private suspend fun <T> useSession(open: suspend () -> PageSource, block: suspend (PageSource) -> T): T {
        val session = open()
        var completed = false
        try {
            val result = block(session)
            completed = true
            return result
        } finally {
            try {
                session.close()
            } catch (error: PageSourceException) {
                if (completed) throw error
            }
        }
    }

    private suspend fun renderCover(session: PageSource, destination: File) {
        val size = session.pageSize(0)
        val width = min(360, (540.0 * size.width / size.height).toInt().coerceAtLeast(1))
        val height = ceil(width.toDouble() * size.height / size.width)
            .coerceIn(1.0, 540.0).toInt()
        val bitmap = session.renderTile(0, width, 0, 0, width, height)
        try {
            storageOperation {
                FileOutputStream(destination).use { output ->
                    currentCoroutineContext().ensureActive()
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
                        throw LibraryException("无法生成封面，请检查存储空间后重试。")
                    }
                    output.fd.sync()
                    currentCoroutineContext().ensureActive()
                }
            }
        } finally {
            // This private, uncached cover is never handed to the UI.
            bitmap.recycle()
        }
    }

    /**
     * EPUB ships its real artwork in the archive; using it for the shelf is both more faithful and
     * cheaper than laying out a text page just to screenshot it. Returns false when the package has
     * no usable declaration, in which case the caller keeps the rendered first page.
     */
    private suspend fun writeEpubCover(source: File, destination: File): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                EpubCover.read(source)?.let { writeCoverImage(it, destination) } ?: false
            }.getOrDefault(false)
        }

    private suspend fun writeEpubCover(open: () -> InputStream, destination: File): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                EpubCover.read(open)?.let { writeCoverImage(it, destination) } ?: false
            }.getOrDefault(false)
        }

    private fun writeCoverImage(bytes: ByteArray, destination: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        // The shelf draws covers around 150dp; 640px stays sharp without parking a poster in RAM.
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return false
        val directory = destination.parentFile ?: return false
        val temp = File.createTempFile("cover-", ".part", directory)
        return try {
            val written = storageOperation {
                FileOutputStream(temp).use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
                        false
                    } else {
                        output.fd.sync()
                        true
                    }
                }
            }
            if (!written) {
                temp.delete()
                false
            } else {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                true
            }
        } catch (error: Exception) {
            temp.delete()
            throw error
        } finally {
            bitmap.recycle()
        }
    }

    private fun displayName(uri: Uri): String {
        val raw = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                        cursor.getString(column)
                    } else {
                        null
                    }
                }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return raw
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.filterNot { it.isISOControl() }
            ?.trim()
            ?.take(255)
            ?.takeIf { it.isNotBlank() }
            ?: "未命名"
    }

    private fun comicTitle(name: String): String =
        name.substringBeforeLast('.', name).trim().ifBlank { "未命名" }

    private fun folderFingerprint(pages: List<ComicFolderPage>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(FOLDER_FINGERPRINT_PREFIX)
        pages.forEach { page ->
            digest.update(page.path.toByteArray(StandardCharsets.UTF_8))
            digest.update(SEPARATOR)
            digest.update(page.size.toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(SEPARATOR)
        }
        return hex(digest.digest())
    }

    private fun ensureDirectory(directory: File) {
        storageOperation {
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                throw LibraryException("无法创建应用存储目录，请检查存储空间后重试。")
            }
        }
    }

    private fun privateFile(directory: File, id: String, extension: String): File {
        if (!BOOK_ID.matches(id)) throw LibraryException("书籍标识无效，请重新导入。")
        return File(directory, "$id.$extension")
    }

    private fun deleteIfPossible(file: File): Boolean = try {
        Files.deleteIfExists(file.toPath())
        true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private inline fun <T> storageOperation(block: () -> T): T = try {
        block()
    } catch (error: LibraryException) {
        throw error
    } catch (_: IOException) {
        throw LibraryException("无法写入本地存储，请检查可用空间后重试。")
    } catch (_: SecurityException) {
        throw LibraryException("应用存储不可用，请检查设备存储后重试。")
    }

    private suspend fun <T> databaseOperation(block: suspend () -> T): T = try {
        block()
    } catch (_: SQLiteFullException) {
        throw LibraryException("存储空间不足，无法保存书架，请清理空间后重试。")
    } catch (_: SQLiteException) {
        throw LibraryException("书架数据操作失败，请检查设备存储后重试。")
    } catch (error: IllegalArgumentException) {
        throw LibraryException(error.message ?: "书架数据无效。")
    }

    private fun requireSupportedKind(kind: Int, format: Int) {
        when (kind) {
            KIND_EBOOK -> if (format != FORMAT_PDF && format != FORMAT_TEXT && format != FORMAT_EPUB) {
                throw LibraryException("电子书书架只支持 PDF、EPUB 和 TXT，请确认文件格式。")
            }
            else -> if (format == FORMAT_TEXT || format == FORMAT_EPUB) {
                throw LibraryException("EPUB 和 TXT 属于电子书，请切换到「电子书」书架导入。")
            }
        }
    }

    private fun detectFormat(bytes: ByteArray, length: Int): Int? = when {
        hasZipHeader(bytes, length) -> FORMAT_ARCHIVE
        hasPdfHeader(bytes, length) -> FORMAT_PDF
        TextEncoding.looksLikeText(bytes, length) -> FORMAT_TEXT
        else -> null
    }

    private fun unsupportedFile(kind: Int) = LibraryException(
        if (kind == KIND_EBOOK) "这个文件不是电子书格式。电子书支持 PDF、EPUB 和 TXT。"
        else "这个文件不是漫画格式。漫画支持 PDF，以及装有图片的 ZIP/CBZ 压缩包。",
    )

    private fun hasPdfHeader(bytes: ByteArray, length: Int): Boolean {
        if (length < PDF_HEADER.size) return false
        return (0..length - PDF_HEADER.size).any { start ->
            PDF_HEADER.indices.all { offset -> bytes[start + offset] == PDF_HEADER[offset] }
        }
    }

    private fun hasZipHeader(bytes: ByteArray, length: Int): Boolean {
        if (length < 4) return false
        if (bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) return false
        return (bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) ||
            (bytes[2] == 0x05.toByte() && bytes[3] == 0x06.toByte()) ||
            (bytes[2] == 0x07.toByte() && bytes[3] == 0x08.toByte())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { byte ->
        HEX[byte.toInt().ushr(4) and 0x0f].toString() + HEX[byte.toInt() and 0x0f]
    }

    private class Probe(val digest: String, val format: Int)

    private companion object {
        val fileMutex = Mutex()
        val BOOK_ID = Regex("[0-9a-f]{64}")
        val PDF_HEADER = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)
        val FOLDER_FINGERPRINT_PREFIX = "moyu-folder-v1\n".toByteArray(StandardCharsets.UTF_8)
        val SEPARATOR = byteArrayOf(0)
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val HEX = "0123456789abcdef"
        const val EMPTY_FILE = "这个文件是空的，读不到任何内容。"

        /** Parsing a bigger PDF on a phone would cost far more memory than the feature is worth. */
        const val MAX_PDF_TEXT_BYTES = 80L * 1024 * 1024
        const val MAX_PDF_TEXT_PAGES = 2000
        const val DEFAULT_PDF_TEXT_FAILURE = "这个 PDF 暂时无法读取文字，请用原版阅读。"
    }
}
