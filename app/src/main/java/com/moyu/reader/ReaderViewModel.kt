package com.moyu.reader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.moyu.reader.data.BookEntity
import com.moyu.reader.data.BackupRepository
import com.moyu.reader.data.KIND_COMIC
import com.moyu.reader.data.KIND_EBOOK
import com.moyu.reader.data.LibraryRepository
import com.moyu.reader.data.PreferencesRepository
import com.moyu.reader.data.READER_BRIGHTNESS_FOLLOW_SYSTEM
import com.moyu.reader.data.READ_VIEW_PDF
import com.moyu.reader.data.READ_VIEW_TEXT
import com.moyu.reader.data.ReaderPreferences
import com.moyu.reader.data.SpeechPreferences
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReaderViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    val library = LibraryRepository(application)
    val settings = PreferencesRepository(application)
    val backups = BackupRepository.getInstance(application)
    val backupStatus = backups.status
    val books = library.books.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val groups = library.groups.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val preferences = settings.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderPreferences())
    val brightness = settings.readerBrightness.stateIn(viewModelScope, SharingStarted.Eagerly, READER_BRIGHTNESS_FOLLOW_SYSTEM)
    val speech = settings.speechPreferences.stateIn(viewModelScope, SharingStarted.Eagerly, SpeechPreferences())
    private val activeBook = MutableStateFlow<BookEntity?>(null)
    val reading = activeBook.asStateFlow()
    private val importState = MutableStateFlow(false)
    val importing = importState.asStateFlow()
    private val startupState = MutableStateFlow(true)
    val initializing = startupState.asStateFlow()
    private val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = notices.asSharedFlow()
    private val unavailableIds = MutableStateFlow<Set<String>>(emptySet())
    val unavailable = unavailableIds.asStateFlow()
    private val restoredScreen = savedState.get<String>("screen")
    val screen = savedState.getStateFlow("screen", "loading")
    val libraryKind = savedState.getStateFlow("libraryKind", KIND_COMIC)
    private var progressJob: Job? = null
    private var openJob: Job? = null
    /** The last committed progress write; opening a book waits for it before reading the row. */
    private var saveBarrier: Job? = null

    init {
        viewModelScope.launch {
            try {
                progressMutex.withLock {
                    val stored = settings.preferences.first()
                    val restoredId = when (restoredScreen) {
                        "library", "settings" -> null
                        "reader" -> savedState.get<String>("bookId") ?: stored.lastReadingBookId
                        else -> stored.lastReadingBookId
                    }
                    val restored = restoredId?.let { library.getBook(it) }
                        ?.takeIf { library.isReadable(it) }
                    val lastBook = stored.lastReadingBookId?.let { library.getBook(it) }
                    val initialKind = restored?.kind
                        ?: savedState.get<Int>("libraryKind")
                        ?: lastBook?.kind
                        ?: KIND_COMIC
                    savedState["libraryKind"] = if (initialKind == KIND_EBOOK) KIND_EBOOK else KIND_COMIC
                    activeBook.value = restored
                    savedState["bookId"] = restored?.id
                    savedState["screen"] = when {
                        restored != null -> "reader"
                        restoredScreen == "settings" -> "settings"
                        else -> "library"
                    }
                    if (restoredId != null && restored == null) persistDestination(null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                savedState["screen"] = "library"
                notices.emit("未能恢复上次阅读，已返回书架")
            } finally {
                startupState.value = false
            }
        }
        viewModelScope.launch {
            try {
                val missing = library.unavailableReferences()
                if (missing.isNotEmpty()) unavailableIds.value = missing
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed probe only means the shelf cannot warn ahead of time.
            }
        }
    }

    fun open(book: BookEntity) {
        flushProgress()
        openJob?.cancel()
        openJob = viewModelScope.launch {
            try {
                // A reopen can arrive while the previous save is still queued; read the row only
                // after that write has landed, or the reader would restore the position before it.
                saveBarrier?.join()
                progressMutex.withLock {
                    val latest = library.getBook(book.id)
                    if (latest == null || !library.isReadable(latest)) {
                        latest?.let { unavailableIds.value += it.id }
                        notices.emit("原文件已不可访问，请重新导入这本书")
                        return@withLock
                    }
                    unavailableIds.value -= latest.id
                    activeBook.value = latest
                    savedState["bookId"] = latest.id
                    savedState["libraryKind"] = latest.kind
                    savedState["screen"] = "reader"
                    persistDestination(latest.id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                notices.emit("暂时无法打开这本书，请检查设备存储空间")
            }
        }
    }

    fun clearUnavailable(id: String) {
        unavailableIds.value -= id
    }

    fun setLibraryKind(kind: Int) {
        require(kind == KIND_COMIC || kind == KIND_EBOOK) { "书架类型无效。" }
        savedState["libraryKind"] = kind
    }

    fun showSettings() {
        openJob?.cancel()
        savedState["screen"] = "settings"
    }

    fun backToLibrary() {
        openJob?.cancel()
        val leavingReader = screen.value == "reader"
        flushProgress()
        // The page source still owns the document until its exit animation finishes; dropping the
        // repository copy lets that last reference be collected once the reader is gone.
        if (leavingReader) library.releaseParsedDocument(activeBook.value?.id)
        savedState["screen"] = "library"
        savedState["bookId"] = null
        persistDestination(null)
    }

    /** Lets the process drop reconstructible caches when the system is low or the app is hidden. */
    fun trimMemory() {
        library.trimMemory()
    }

    private fun persistDestination(id: String?) {
        progressWriter.launch(start = CoroutineStart.UNDISPATCHED) {
            progressMutex.withLock {
                try { settings.setLastReadingBook(id) }
                catch (_: IOException) { notices.emit("启动位置未能保存，请检查设备存储空间") }
            }
        }
    }

    fun importDocuments(uris: List<Uri>, kind: Int = KIND_COMIC) {
        if (uris.isEmpty() || importState.value) return
        if (kind != KIND_COMIC && kind != KIND_EBOOK) return
        importState.value = true
        viewModelScope.launch {
            var imported = 0
            var remembered = 0
            var existing = 0
            val hints = mutableListOf<String>()
            val label = if (kind == KIND_EBOOK) "电子书" else "漫画"
            // A repeat or repair returns the stored row with its original timestamp, so its age
            // tells whether this pass actually added a book to the shelf.
            val startedAt = System.currentTimeMillis()
            try {
                for (uri in uris) {
                    try {
                        val outcome = library.importFile(uri, kind)
                        val book = outcome.book
                        when {
                            book.kind != kind -> existing++
                            book.importedAt >= startedAt -> imported++
                            else -> remembered++
                        }
                        outcome.notice?.let(hints::add)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        notices.emit(error.message ?: "这本书暂时无法导入")
                    }
                }
                val parts = buildList {
                    if (imported > 0) add("已导入 $imported 本${label}")
                    if (remembered > 0) add("$remembered 本已在${label}书架中")
                    if (existing > 0) add("$existing 本已在其他书架中")
                }
                // One snackbar, not two: a queued second message looked like a late, unrelated
                // warning. The shelf-mismatch hint belongs with the import result.
                val messages = buildList {
                    if (parts.isNotEmpty()) add(parts.joinToString("，"))
                    addAll(hints.distinct())
                }
                if (messages.isNotEmpty()) notices.emit(messages.joinToString("；"))
            } finally {
                importState.value = false
            }
        }
    }

    fun updatePosition(page: Int, offset: Int, mode: String) {
        if (screen.value != "reader") return
        val book = activeBook.value ?: return
        val next = book.copy(currentPage = page.coerceIn(0, book.pageCount - 1), pageOffset = offset.coerceAtLeast(0), mode = mode)
        if (book.currentPage == next.currentPage && book.pageOffset == next.pageOffset && book.mode == next.mode) return
        activeBook.value = next
        schedulePersist(next)
    }

    /**
     * The text view reports its character anchor and whole-book percentage; pages are window-local
     * in the chapter engine, so page numbers no longer describe the reading spot.
     */
    fun updateTextPosition(offset: Int, progress: Int) {
        if (screen.value != "reader") return
        val book = activeBook.value ?: return
        val nextOffset = offset.coerceAtLeast(0)
        val nextProgress = progress.coerceIn(0, 100)
        // Text progress is a single line anchor now. The page pixel is derived from that anchor
        // when the book reopens, so stale text page offsets are cleared instead of trusted.
        val nextPageOffset = if (book.isTextBook) {
            0
        } else book.pageOffset
        if (book.textOffset == nextOffset && book.textProgress == nextProgress &&
            book.pageOffset == nextPageOffset
        ) return
        val next = book.copy(
            textOffset = nextOffset,
            textProgress = nextProgress,
            pageOffset = nextPageOffset,
        )
        activeBook.value = next
        schedulePersist(next)
    }

    /** Remembers which PDF view is in use, so reopening lands in it and the shelf labels match. */
    fun setReadView(textView: Boolean) {
        val book = activeBook.value ?: return
        if (!book.hasTextView) return
        val view = if (textView) READ_VIEW_TEXT else READ_VIEW_PDF
        if (book.readView == view) return
        val next = book.copy(readView = view)
        activeBook.value = next
        schedulePersist(next)
    }

    private fun schedulePersist(book: BookEntity) {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(300)
            persist(book)
        }
    }

    fun flushProgress() {
        if (screen.value != "reader") return
        val book = activeBook.value ?: return
        progressJob?.cancel()
        persist(book)
    }

    fun onAppStopped() {
        flushProgress()
        progressWriter.launch(start = CoroutineStart.UNDISPATCHED) {
            // Await queued progress without holding its lock during document-provider I/O.
            progressMutex.withLock { }
            val status = backupStatus.value
            if (status.enabled && !status.pendingRestore && !status.busy) {
                try { backups.backupNow() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
            }
        }
    }

    private fun persist(book: BookEntity) {
        // A committed save must outlive Activity finish and the next book's debounce.
        saveBarrier = progressWriter.launch(start = CoroutineStart.UNDISPATCHED) {
            progressMutex.withLock {
                try {
                    library.saveProgress(book.id, book.currentPage, book.pageOffset, book.mode,
                        book.readView, book.textPage, book.textPageCount,
                        book.textOffset, book.textProgress, book.textAnchors)
                } catch (_: IOException) {
                    notices.emit("阅读进度未能保存，请检查设备存储空间")
                }
            }
        }
    }

    fun toggleBookmark(page: Int) {
        val book = activeBook.value ?: return
        viewModelScope.launch {
            try {
                if (book.isTextBook) {
                    library.toggleTextBookmark(book.id, page)
                } else {
                    library.toggleBookmark(book.id, page)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notices.emit(error.message ?: "书签未能保存，请检查设备存储空间")
            }
        }
    }

    fun delete(book: BookEntity) {
        viewModelScope.launch {
            try {
                library.deleteBook(book.id)
                notices.emit("已从书架移除《${book.title}》")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notices.emit("暂时无法移除，请稍后重试")
            }
        }
    }

    fun createGroup(name: String, kind: Int) = libraryAction { library.createGroup(name, kind) }
    fun renameGroup(id: String, name: String) = libraryAction { library.renameGroup(id, name) }
    fun deleteGroup(id: String) = libraryAction { library.deleteGroup(id) }
    fun moveBooks(ids: List<String>, groupId: String?) = libraryAction { library.moveBooks(ids, groupId) }
    suspend fun reorderBooks(ids: List<String>): Boolean = try {
        library.reorderBooks(ids)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        notices.emit(error.message ?: "排序未能保存，请重试")
        false
    }

    suspend fun reorderGroups(ids: List<String>): Boolean = try {
        library.reorderGroups(ids)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        notices.emit(error.message ?: "分组排序未能保存，请重试")
        false
    }
    fun deleteBooks(ids: List<String>) = libraryAction {
        library.deleteBooks(ids)
        unavailableIds.value -= ids.toSet()
        notices.emit("已移除 ${ids.size} 本书，原始文件未改动")
    }

    private fun libraryAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notices.emit(error.message ?: "操作未完成，请检查存储后重试") }
        }
    }

    private fun changeSetting(write: suspend () -> Unit) {
        viewModelScope.launch {
            try { write() }
            catch (_: IOException) { notices.emit("设置未能保存，请检查设备存储空间") }
        }
    }

    fun setTheme(value: String) { changeSetting { settings.setTheme(value) } }
    fun setDefaultMode(value: String) { changeSetting { settings.setDefaultMode(value) } }
    fun setRightToLeft(value: Boolean) { changeSetting { settings.setRightToLeft(value) } }
    fun setKeepScreenOn(value: Boolean) { changeSetting { settings.setKeepScreenOn(value) } }
    fun setBrightness(value: Float) { changeSetting { settings.setReaderBrightness(value) } }
    fun setPrefetchRadius(value: Int) { changeSetting { settings.setPrefetchRadius(value) } }
    fun setTextReading(fontSize: Int? = null, lineSpacing: String? = null, themeId: String? = null) {
        changeSetting { settings.setTextReading(fontSize, lineSpacing, themeId) }
    }
    fun setSpeech(engine: String?, voice: String?, rate: Float) {
        changeSetting { settings.setSpeech(engine, voice, rate) }
    }
    fun setSpeechBackground(enabled: Boolean) {
        changeSetting { settings.setSpeechBackground(enabled) }
    }

    private companion object {
        // One writer at a time, in launch order: a stale debounced save must not land after a newer
        // flush and undo the position the reader just left.
        val progressWriter = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        val progressMutex = Mutex()
    }
}
