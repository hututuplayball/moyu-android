package com.moyu.reader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.moyu.reader.data.MetadataRules.nextTimestamp
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM books ORDER BY sortOrder ASC, importedAt DESC, title COLLATE NOCASE ASC, id ASC")
    abstract fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM groups ORDER BY sortOrder ASC, id ASC")
    abstract fun observeGroups(): Flow<List<GroupEntity>>

    @Query("SELECT page FROM bookmarks WHERE bookId = :bookId ORDER BY page ASC")
    abstract fun observeBookmarks(bookId: String): Flow<List<Int>>

    // Room invalidates this flow for every metadata table, even when row counts stay unchanged.
    @Query("SELECT (SELECT COUNT(*) FROM books) + (SELECT COUNT(*) FROM bookmarks) + " +
        "(SELECT COUNT(*) FROM groups) + (SELECT COUNT(*) FROM pending_books)")
    abstract fun observeBackupChanges(): Flow<Long>

    @Query("SELECT (SELECT COUNT(*) FROM books) + (SELECT COUNT(*) FROM groups) + " +
        "(SELECT COUNT(*) FROM pending_books)")
    abstract suspend fun metadataCount(): Int

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    abstract suspend fun getBook(id: String): BookEntity?

    @Query("SELECT COUNT(*) FROM books WHERE sourceUri = :sourceUri")
    abstract suspend fun countReferences(sourceUri: String): Int

    @Query("SELECT * FROM books ORDER BY sortOrder ASC, importedAt DESC, title COLLATE NOCASE ASC, id ASC")
    abstract suspend fun allBooks(): List<BookEntity>

    @Query("SELECT * FROM groups ORDER BY sortOrder ASC, id ASC")
    abstract suspend fun allGroups(): List<GroupEntity>

    @Query("SELECT * FROM bookmarks ORDER BY bookId ASC, page ASC")
    abstract suspend fun allBookmarks(): List<BookmarkEntity>

    @Query("SELECT * FROM pending_books ORDER BY sortOrder ASC, id ASC")
    abstract suspend fun allPendingBooks(): List<PendingBookEntity>

    @Query("SELECT * FROM pending_books WHERE id = :id LIMIT 1")
    abstract suspend fun getPendingBook(id: String): PendingBookEntity?

    @Query("SELECT * FROM groups WHERE id = :id LIMIT 1")
    abstract suspend fun getGroup(id: String): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertBook(book: BookEntity): Long

    @Update
    protected abstract suspend fun updateBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putGroup(group: GroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putPending(book: PendingBookEntity)

    @Query("DELETE FROM pending_books WHERE id = :id")
    protected abstract suspend fun removePending(id: String)

    @Query("DELETE FROM groups WHERE id = :id")
    protected abstract suspend fun removeGroup(id: String)

    @Query("UPDATE books SET storageMode = :mode, sourceUri = :sourceUri, updatedAt = :updatedAt WHERE id = :id")
    protected abstract suspend fun updateStorage(id: String, mode: Int, sourceUri: String?, updatedAt: Long)

    @Query("UPDATE books SET textState = :state, updatedAt = :updatedAt WHERE id = :id")
    protected abstract suspend fun updateTextState(id: String, state: Int, updatedAt: Long)

    @Query("DELETE FROM books WHERE id = :id")
    protected abstract suspend fun removeBook(id: String)

    @Query("SELECT MAX(sortOrder) FROM (SELECT sortOrder FROM books UNION ALL SELECT sortOrder FROM pending_books)")
    protected abstract suspend fun maxBookOrder(): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE bookId = :id AND page = :page)")
    protected abstract suspend fun hasBookmark(id: String, page: Int): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE bookId = :id AND page = :page")
    protected abstract suspend fun removeBookmark(id: String, page: Int)

    @Query("DELETE FROM bookmarks WHERE bookId = :id")
    protected abstract suspend fun clearBookmarks(id: String)

    @Query("SELECT page FROM bookmarks WHERE bookId = :id ORDER BY page ASC")
    protected abstract suspend fun bookmarkPages(id: String): List<Int>

    @Query("SELECT offset FROM text_bookmarks WHERE bookId = :id ORDER BY offset ASC")
    abstract fun textBookmarks(id: String): Flow<List<Int>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTextBookmark(bookmark: TextBookmarkEntity)

    @Query("DELETE FROM text_bookmarks WHERE bookId = :id AND offset = :offset")
    protected abstract suspend fun removeTextBookmark(id: String, offset: Int)

    @Query("DELETE FROM text_bookmarks WHERE bookId = :id")
    protected abstract suspend fun clearTextBookmarks(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM text_bookmarks WHERE bookId = :id AND offset = :offset)")
    protected abstract suspend fun hasTextBookmark(id: String, offset: Int): Boolean

    @Query("SELECT COUNT(*) FROM text_bookmarks")
    protected abstract suspend fun textBookmarkCount(): Int

    @Transaction
    open suspend fun toggleTextBookmark(id: String, offset: Int) {
        MetadataRules.bookId(id)
        if (offset < 0) return
        val book = getBook(id) ?: return
        val adding = !hasTextBookmark(id, offset)
        if (!adding) removeTextBookmark(id, offset) else {
            require(textBookmarkCount() < MetadataRules.MAX_BOOKMARKS) { "书签数量已达上限。" }
            insertTextBookmark(TextBookmarkEntity(id, offset))
        }
        updateBook(book.copy(updatedAt = nextTimestamp(book.updatedAt)))
        if (adding) requireBackupCapacity()
    }

    /** Converts page marks to character anchors once; existing character marks are kept. */
    @Transaction
    open suspend fun adoptTextBookmarks(id: String, offsets: List<Int>) {
        MetadataRules.bookId(id)
        val book = getBook(id) ?: return
        clearBookmarks(id)
        // A reader may already have added character marks after the migration flag was lost.
        // Converting the old page marks must add anchors, never delete newer ones.
        offsets.distinct().take(MetadataRules.MAX_BOOKMARKS).forEach {
            insertTextBookmark(TextBookmarkEntity(id, it.coerceAtLeast(0)))
        }
        if (book.textAnchors == 0) {
            updateBook(book.copy(textAnchors = 1, updatedAt = nextTimestamp(book.updatedAt)))
        }
    }

    @Transaction
    open suspend fun markTextAnchors(id: String) {
        MetadataRules.bookId(id)
        val book = getBook(id) ?: return
        if (book.textAnchors == 0) updateBook(book.copy(textAnchors = 1, updatedAt = nextTimestamp(book.updatedAt)))
    }

    /** Legacy page marks of a pure-text book; empty for PDFs and comics. */
    suspend fun legacyBookmarkPages(id: String): List<Int> = bookmarkPages(id)

    @Transaction
    open suspend fun saveProgress(
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
        MetadataRules.bookId(id)
        MetadataRules.mode(mode)
        require(readView == READ_VIEW_PDF || readView == READ_VIEW_TEXT) { "阅读版本无效。" }
        val book = getBook(id) ?: return
        val boundedPage = page.coerceIn(0, (book.pageCount - 1).coerceAtLeast(0))
        val boundedOffset = if (boundedPage == page) offset.coerceIn(0, MetadataRules.MAX_OFFSET) else 0
        // Text pages only describe a PDF that can actually flow; anything else keeps the PDF pages.
        val view = if (book.hasTextView) readView else READ_VIEW_PDF
        val boundedTextCount = when {
            view != READ_VIEW_TEXT -> book.textPageCount
            else -> textPageCount.coerceIn(0, MetadataRules.MAX_PAGE_COUNT)
        }
        val boundedTextPage = if (boundedTextCount > 0) textPage.coerceIn(0, boundedTextCount - 1) else 0
        val boundedTextOffset = textOffset.coerceIn(0, Int.MAX_VALUE)
        val boundedTextProgress = textProgress.coerceIn(0, 100)
        // A stale in-memory row must never downgrade the one-time conversion flag; otherwise the
        // next open would migrate again and could clear the reader's character bookmarks.
        val anchors = maxOf(book.textAnchors, textAnchors.coerceIn(0, 1))
        if (book.currentPage == boundedPage && book.pageOffset == boundedOffset && book.mode == mode &&
            book.readView == view && book.textPage == boundedTextPage && book.textPageCount == boundedTextCount &&
            book.textOffset == boundedTextOffset && book.textProgress == boundedTextProgress &&
            book.textAnchors == anchors
        ) return
        updateBook(book.copy(currentPage = boundedPage, pageOffset = boundedOffset, mode = mode,
            readView = view, textPage = boundedTextPage, textPageCount = boundedTextCount,
            textOffset = boundedTextOffset, textProgress = boundedTextProgress, textAnchors = anchors,
            updatedAt = nextTimestamp(book.updatedAt)))
    }

    @Transaction
    open suspend fun toggleBookmark(id: String, page: Int) {
        MetadataRules.bookId(id)
        val book = getBook(id) ?: return
        if (page !in 0 until book.pageCount) return
        val adding = !hasBookmark(id, page)
        if (!adding) removeBookmark(id, page) else {
            require(allBookmarks().size + allPendingBooks().sumOf { it.metadata().bookmarks.size } < MetadataRules.MAX_BOOKMARKS) {
                "书签数量已达上限。"
            }
            insertBookmark(BookmarkEntity(id, page))
        }
        // Bookmarks belong to the book's merge unit and must advance its revision too.
        updateBook(book.copy(updatedAt = nextTimestamp(book.updatedAt)))
        if (adding) requireBackupCapacity()
    }

    /**
     * Text books paginate for the window they are read in, so their stored page count follows the
     * page source the reader actually built. Saved pages and bookmarks are moved into the new range,
     * otherwise the row would fail metadata validation.
     */
    @Transaction
    open suspend fun adoptPageCount(id: String, pageCount: Int): Boolean {
        MetadataRules.bookId(id)
        require(pageCount in 1..MetadataRules.MAX_PAGE_COUNT) { "页数无效。" }
        val book = getBook(id) ?: return false
        if (book.pageCount == pageCount) return false
        val page = book.currentPage.coerceIn(0, pageCount - 1)
        val offset = if (page == book.currentPage) book.pageOffset else 0
        rescaleBookmarks(id, from = book.pageCount, to = pageCount)
        updateBook(book.copy(pageCount = pageCount, currentPage = page, pageOffset = offset,
            updatedAt = nextTimestamp(book.updatedAt)))
        return true
    }

    /** A mark anchors a passage, not a number: rescaling keeps it in front of the same text. */
    private suspend fun rescaleBookmarks(id: String, from: Int, to: Int) {
        val marks = allBookmarks().filter { it.bookId == id }.map { it.page }
        if (marks.isEmpty() || from <= 0) return
        clearBookmarks(id)
        marks.map { (it.toLong() * to / from).toInt().coerceIn(0, to - 1) }
            .distinct()
            .forEach { insertBookmark(BookmarkEntity(id, it)) }
    }

    /** Records whether a PDF can be read as flowing text; unknown means the probe has to run. */
    @Transaction
    open suspend fun setTextState(id: String, state: Int): Boolean {
        MetadataRules.bookId(id)
        MetadataRules.textState(state)
        val book = getBook(id) ?: return false
        if (book.textState == state) return false
        updateTextState(id, state, nextTimestamp(book.updatedAt))
        return true
    }

    @Transaction
    open suspend fun attachReference(id: String, sourceUri: String) {
        MetadataRules.bookId(id)
        require(sourceUri.isNotBlank() && sourceUri.length <= 4096) { "原文件标识无效。" }
        val book = getBook(id) ?: throw IllegalArgumentException("书籍不存在。")
        if (book.isReference && book.sourceUri == sourceUri) return
        updateStorage(id, STORAGE_REFERENCE, sourceUri, nextTimestamp(book.updatedAt))
    }

    @Transaction
    open suspend fun createGroup(name: String, kind: Int = KIND_COMIC) {
        MetadataRules.kind(kind)
        val cleaned = name.trim()
        MetadataRules.name(cleaned)
        val groups = allGroups()
        require(groups.size < MetadataRules.MAX_GROUPS) { "分组数量已达上限。" }
        requireUniqueName(groups, cleaned, exceptId = null, kind = kind)
        val maxOrder = groups.maxOfOrNull { it.sortOrder } ?: -1
        val order = if (maxOrder >= MetadataRules.MAX_ORDER) {
            groups.forEachIndexed { index, group ->
                if (group.sortOrder != index.toLong()) {
                    putGroup(group.copy(sortOrder = index.toLong(), updatedAt = nextTimestamp(group.updatedAt)))
                }
            }
            groups.size.toLong()
        } else maxOrder + 1
        putGroup(GroupEntity(UUID.randomUUID().toString(), cleaned, order, kind = kind))
        requireBackupCapacity()
    }

    @Transaction
    open suspend fun renameGroup(id: String, name: String) {
        MetadataRules.groupId(id)
        val cleaned = name.trim()
        MetadataRules.name(cleaned)
        val group = getGroup(id) ?: throw IllegalArgumentException("分组不存在。")
        if (group.name != cleaned) {
            requireUniqueName(allGroups(), cleaned, exceptId = id, kind = group.kind)
            putGroup(group.copy(name = cleaned, updatedAt = nextTimestamp(group.updatedAt)))
            requireBackupCapacity()
        }
    }

    /** Two groups sharing a name in the same shelf look like a failed dissolve. */
    private fun requireUniqueName(groups: List<GroupEntity>, name: String, exceptId: String?, kind: Int) {
        require(groups.none {
            it.id != exceptId && it.kind == kind && it.name.equals(name, ignoreCase = true)
        }) { "已有同名分组。" }
    }

    @Transaction
    open suspend fun deleteGroup(id: String) {
        MetadataRules.groupId(id)
        require(getGroup(id) != null) { "分组不存在。" }
        allBooks().filter { it.groupId == id }.forEach {
            updateBook(it.copy(groupId = null, updatedAt = nextTimestamp(it.updatedAt)))
        }
        allPendingBooks().filter { it.groupId == id }.forEach {
            putPending(it.copy(groupId = null, updatedAt = nextTimestamp(it.updatedAt)))
        }
        removeGroup(id)
    }

    @Transaction
    open suspend fun moveBooks(ids: List<String>, groupId: String?) {
        MetadataRules.ids(ids)
        val target = groupId?.let {
            MetadataRules.groupId(it)
            getGroup(it) ?: throw IllegalArgumentException("目标分组不存在。")
        }
        // Resolve the entire batch before writing; stale or fabricated IDs cannot partly succeed.
        val books = ids.map { getBook(it) ?: throw IllegalArgumentException("书籍不存在。") }
        if (target != null) {
            require(books.all { it.kind == target.kind }) { "书籍类型与目标分组不一致。" }
        }
        val changed = books.filter { it.groupId != groupId }
        changed.forEach {
            updateBook(it.copy(groupId = groupId, updatedAt = nextTimestamp(it.updatedAt)))
        }
        if (changed.isNotEmpty()) requireBackupCapacity()
    }

    @Transaction
    open suspend fun reorderBooks(ids: List<String>) {
        MetadataRules.ids(ids)
        val books = ids.map { getBook(it) ?: throw IllegalArgumentException("书籍不存在。") }
        val slots = books.map { it.sortOrder }.sorted()
        require(slots.distinct().size == slots.size) { "排序位置冲突，请先重新载入书架。" }
        books.forEachIndexed { index, book ->
            if (book.sortOrder != slots[index]) {
                updateBook(book.copy(sortOrder = slots[index], updatedAt = nextTimestamp(book.updatedAt)))
            }
        }
    }

    @Transaction
    open suspend fun reorderGroups(ids: List<String>) {
        require(ids.size <= MetadataRules.MAX_GROUPS && ids.distinct().size == ids.size) { "分组列表无效。" }
        val groups = ids.map { getGroup(it) ?: throw IllegalArgumentException("分组不存在。") }
        val slots = groups.map { it.sortOrder }.sorted()
        require(slots.distinct().size == slots.size) { "排序位置冲突，请先重新载入书架。" }
        groups.forEachIndexed { index, group ->
            if (group.sortOrder != slots[index]) {
                putGroup(group.copy(sortOrder = slots[index], updatedAt = nextTimestamp(group.updatedAt)))
            }
        }
    }

    @Transaction
    open suspend fun deleteBook(id: String) { deleteBooks(listOf(id)) }

    @Transaction
    open suspend fun deleteBooks(ids: List<String>) {
        MetadataRules.ids(ids)
        ids.forEach { removeBook(it); removePending(it) }
    }

    /** Called after validated private files are published. Also usable without PdfRenderer in tests. */
    @Transaction
    open suspend fun publishImportedBook(book: BookEntity): BookEntity {
        MetadataRules.bookId(book.id)
        MetadataRules.name(book.title)
        MetadataRules.kind(book.kind)
        require(book.pageCount in 1..MetadataRules.MAX_PAGE_COUNT) { "页数无效。" }
        getBook(book.id)?.let { return it } // Repair/duplicate imports never reset reading history.
        val pending = getPendingBook(book.id)
        require(allBooks().size + allPendingBooks().size < MetadataRules.MAX_BOOKS || pending != null) {
            "书籍数量已达上限。"
        }
        val result = if (pending != null) {
            val metadata = pending.metadata().also(MetadataRules::book)
            val page = metadata.currentPage.coerceAtMost(book.pageCount - 1)
            book.copy(title = metadata.title, currentPage = page,
                pageOffset = if (page == metadata.currentPage) metadata.pageOffset else 0,
                mode = metadata.mode, importedAt = metadata.importedAt, updatedAt = metadata.updatedAt,
                groupId = metadata.groupId?.takeIf { getGroup(it)?.kind == metadata.kind },
                sortOrder = metadata.sortOrder, kind = metadata.kind)
        } else {
            if ((maxBookOrder() ?: -1) >= MetadataRules.MAX_ORDER) compactBookOrders()
            book.copy(groupId = null, sortOrder = (maxBookOrder() ?: -1) + 1)
        }
        check(insertBook(result) != -1L) { "无法发布书籍。" }
        pending?.metadata()?.bookmarks?.filter { it in 0 until book.pageCount }?.forEach {
            insertBookmark(BookmarkEntity(book.id, it))
        }
        removePending(book.id)
        requireBackupCapacity()
        return result
    }

    @Transaction
    open suspend fun snapshot(createdAt: Long, preferences: ReaderPreferences, preferencesUpdatedAt: Long): MetadataSnapshot {
        val marks = allBookmarks().groupBy { it.bookId }
        val imported = allBooks().map { BookMetadata.from(it, marks[it.id].orEmpty().map { mark -> mark.page }) }
        val importedIds = imported.map { it.id }.toSet()
        val pending = allPendingBooks().filter { it.id !in importedIds }.map { it.metadata() }
        return MetadataSnapshot(createdAt, allGroups(), (imported + pending).sortedWith(
            compareBy<BookMetadata> { it.sortOrder }.thenBy { it.id }), preferences, preferencesUpdatedAt)
    }

    @Transaction
    open suspend fun mergeSnapshot(snapshot: MetadataSnapshot): Int {
        BackupCodec.validateForRestore(snapshot) // No writes until the complete external snapshot is valid.
        val localBooks = allBooks().associateBy { it.id }
        val localPending = allPendingBooks().associateBy { it.id }
        val incoming = snapshot.books.filter { record ->
            val timestamp = localBooks[record.id]?.updatedAt ?: localPending[record.id]?.updatedAt
            timestamp == null || record.updatedAt > timestamp
        }
        incoming.forEach {
            val local = localBooks[it.id]
            require(local == null || isTextBook(local) || local.pageCount == it.pageCount) {
                "备份页数与已导入文件不一致。"
            }
        }
        snapshot.groups.forEach {
            val local = getGroup(it.id)
            if (local == null || it.updatedAt > local.updatedAt) putGroup(it)
        }
        val replacedIds = incoming.map { it.id }.toSet()
        val occupied = (localBooks.values.filter { it.id !in replacedIds }.map { it.sortOrder } +
            localPending.values.filter { it.id !in replacedIds }.map { it.sortOrder }).toMutableSet()
        var nextOrder = maxOf(maxBookOrder() ?: -1, snapshot.books.maxOfOrNull { it.sortOrder } ?: -1) + 1
        incoming.sortedWith(compareBy<BookMetadata> { it.sortOrder }.thenBy { it.id }).forEach { value ->
            val order = if (occupied.add(value.sortOrder)) value.sortOrder else {
                // Temporary slots may exceed MAX_ORDER, but never Long's range: both snapshots
                // are bounded. Compact the resulting union before validation/transaction commit.
                val slot = nextOrder++
                occupied.add(slot)
                slot
            }
            val record = value.copy(sortOrder = order)
            val local = localBooks[record.id]
            if (local == null) putPending(PendingBookEntity.from(record)) else {
                // A text layout's page range depends on the device, so restored pages are clamped.
                val page = record.currentPage.coerceIn(0, (local.pageCount - 1).coerceAtLeast(0))
                val offset = if (page == record.currentPage) record.pageOffset else 0
                updateBook(local.copy(title = record.title, currentPage = page,
                    pageOffset = offset, mode = record.mode, importedAt = record.importedAt,
                    updatedAt = record.updatedAt, groupId = record.groupId, sortOrder = record.sortOrder,
                    kind = record.kind))
                clearBookmarks(record.id)
                record.bookmarks.filter { it in 0 until local.pageCount }
                    .forEach { insertBookmark(BookmarkEntity(record.id, it)) }
                removePending(record.id)
            }
        }
        if ((maxBookOrder() ?: -1) > MetadataRules.MAX_ORDER) compactBookOrders()
        // Includes the encoded union, not just record counts. Any failure rolls back ALL tables.
        BackupCodec.requireCapacity(this.snapshot(snapshot.createdAt, snapshot.preferences, snapshot.preferencesUpdatedAt))
        return snapshot.books.size
    }

    private suspend fun requireBackupCapacity() {
        // The codec reserves the largest possible preferences, independent of DataStore writes.
        BackupCodec.requireCapacity(snapshot(System.currentTimeMillis(), ReaderPreferences(), 0))
    }

    private suspend fun compactBookOrders() {
        val books = allBooks()
        val pending = allPendingBooks()
        // Stable sorting preserves each table's existing tie order, including hidden pending history.
        val slots = (books.map { it.id to it.sortOrder } + pending.map { it.id to it.sortOrder })
            .sortedBy { it.second }.mapIndexed { index, entry -> entry.first to index.toLong() }.toMap()
        books.forEach {
            val order = slots.getValue(it.id)
            if (it.sortOrder != order) updateBook(it.copy(sortOrder = order, updatedAt = nextTimestamp(it.updatedAt)))
        }
        pending.forEach {
            val order = slots.getValue(it.id)
            if (it.sortOrder != order) putPending(it.copy(sortOrder = order, updatedAt = nextTimestamp(it.updatedAt)))
        }
    }

    /** Text layouts are rebuilt for every device, so two copies may legitimately differ in pages. */
    private fun isTextBook(book: BookEntity) = book.format == FORMAT_TEXT || book.format == FORMAT_EPUB
}
