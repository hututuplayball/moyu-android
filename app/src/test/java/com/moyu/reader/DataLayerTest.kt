package com.moyu.reader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataLayerTest {
    private lateinit var database: ReaderDatabase
    private lateinit var dao: LibraryDao
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val d = "d".repeat(64)

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ReaderDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = database.libraryDao()
    }

    @After fun tearDown() { database.close() }

    @Test fun migratesRealV1SqliteFileWithoutLosingBooksOrBookmarks() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "real-v1-migration-test.db"
        context.deleteDatabase(name)
        // The actual v1 Room table definitions, including its cascading bookmark foreign key/index.
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE books (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                "fileName TEXT NOT NULL, pageCount INTEGER NOT NULL, currentPage INTEGER NOT NULL, " +
                "pageOffset INTEGER NOT NULL, mode TEXT NOT NULL, importedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE bookmarks (bookId TEXT NOT NULL, page INTEGER NOT NULL, " +
                "PRIMARY KEY(bookId, page), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_bookmarks_bookId ON bookmarks(bookId)")
            listOf(Triple(a, "Zulu", 100L), Triple(b, "Beta", 200L), Triple(c, "alpha", 200L)).forEach { (id, title, imported) ->
                old.execSQL("INSERT INTO books VALUES (?, ?, ?, 20, 5, 77, 'horizontal', ?, ?)",
                    arrayOf(id, title, "$title.pdf", imported, if (id == a) 9000L else imported))
            }
            old.execSQL("INSERT INTO bookmarks VALUES (?, 8)", arrayOf(a))
            old.version = 1
        }
        val migrated = Room.databaseBuilder(context, ReaderDatabase::class.java, name)
            .addMigrations(ReaderDatabase.MIGRATION_1_2, ReaderDatabase.MIGRATION_2_3, ReaderDatabase.MIGRATION_3_4,
                ReaderDatabase.MIGRATION_4_5, ReaderDatabase.MIGRATION_5_6, ReaderDatabase.MIGRATION_6_7,
                ReaderDatabase.MIGRATION_7_8, ReaderDatabase.MIGRATION_8_9)
            .allowMainThreadQueries().build()
        try {
            val rows = migrated.libraryDao().allBooks()
            assertEquals(listOf(c, b, a), rows.map { it.id })
            assertEquals(listOf(0L, 1L, 2L), rows.map { it.sortOrder })
            assertTrue(rows.all { it.groupId == null && it.currentPage == 5 && it.pageOffset == 77 && it.mode == "horizontal" })
            assertTrue(rows.all { !it.isReference && it.sourceUri == null })
            assertTrue(rows.all { it.format == FORMAT_PDF })
            assertTrue(rows.all { it.kind == KIND_COMIC })
            // A migrated PDF starts without a probed text layer, so it opens as the original.
            assertTrue(rows.all { it.textState == TEXT_UNKNOWN })
            // And its shelf label is the PDF's own page count, not a text layout it never built.
            assertTrue(rows.all { it.readView == READ_VIEW_PDF && it.textPage == 0 && it.textPageCount == 0 })
            assertEquals("Zulu.pdf", rows.last().fileName)
            assertEquals(9000L, rows.last().updatedAt)
            assertEquals(listOf(BookmarkEntity(a, 8)), migrated.libraryDao().allBookmarks())
            migrated.libraryDao().saveProgress(a, 12, 99, "vertical")
            assertEquals(listOf(c, b, a), migrated.libraryDao().allBooks().map { it.id })
            assertEquals(3L, migrated.libraryDao().publishImportedBook(book(d)).sortOrder)
            assertTrue(migrated.libraryDao().allGroups().isEmpty())
            assertTrue(migrated.libraryDao().allPendingBooks().isEmpty())
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun subsetAndWholeShelfReorderPreserveMembershipAndUnlistedSlots() = runBlocking {
        listOf(a, b, c, d).forEach { dao.publishImportedBook(book(it)) }
        dao.createGroup("Series")
        val group = dao.allGroups().single()
        dao.moveBooks(listOf(a, c), group.id)
        val unlisted = listOf(dao.getBook(b), dao.getBook(d))
        dao.reorderBooks(listOf(c, a))
        assertEquals(listOf(c, b, a, d), dao.allBooks().map { it.id })
        assertEquals(unlisted, listOf(dao.getBook(b), dao.getBook(d)))
        val memberships = dao.allBooks().associate { it.id to it.groupId }
        dao.reorderBooks(listOf(d, a, b, c))
        assertEquals(listOf(d, a, b, c), dao.allBooks().map { it.id })
        assertEquals(memberships, dao.allBooks().associate { it.id to it.groupId })
        rejected { dao.reorderBooks(listOf(a, a)) }
        rejected { dao.moveBooks(listOf(a, "f".repeat(64)), null) }
        rejected { dao.moveBooks(listOf(a), "missing-group") }
        assertEquals(group.id, dao.getBook(a)?.groupId)
        assertEquals(listOf(d, a, b, c), dao.allBooks().map { it.id })
        dao.saveProgress(c, 5, 3, "vertical")
        assertEquals(listOf(d, a, b, c), dao.observeBooks().first().map { it.id })
    }

    @Test fun shelfLabelFollowsTheLayoutTheBookWasLastReadIn() = runBlocking {
        dao.publishImportedBook(book(a).copy(kind = KIND_EBOOK))
        dao.setTextState(a, TEXT_READY)
        // The text view paginated this window into 52 pages and stopped on the 27th.
        dao.saveProgress(a, 12, 0, "horizontal", READ_VIEW_TEXT, 26, 52)
        val flow = dao.getBook(a)!!
        assertTrue(flow.showsTextPages)
        assertEquals(52, flow.labeledPageCount)
        assertEquals(27, flow.labeledPage)
        // Reading the original pages again labels the book with the PDF's own pages.
        dao.saveProgress(a, 12, 0, "horizontal", READ_VIEW_PDF)
        val pages = dao.getBook(a)!!
        assertFalse(pages.showsTextPages)
        assertEquals(20, pages.labeledPageCount)
        assertEquals(13, pages.labeledPage)
        // A text page outside the reported range is clamped instead of stored as is.
        dao.saveProgress(a, 12, 0, "horizontal", READ_VIEW_TEXT, 70, 52)
        assertEquals(51, dao.getBook(a)!!.textPage)
        // A book without a text layer keeps PDF pages even if a stale text view is reported.
        dao.publishImportedBook(book(b).copy(kind = KIND_EBOOK))
        dao.saveProgress(b, 0, 0, "vertical", READ_VIEW_TEXT, 26, 52)
        assertEquals(READ_VIEW_PDF, dao.getBook(b)!!.readView)
        assertEquals(0, dao.getBook(b)!!.textPageCount)
    }

    @Test fun shelvesKeepGroupsSeparateAndRejectCrossShelfMoves() = runBlocking {
        dao.createGroup("Reading", KIND_COMIC)
        dao.createGroup("Reading", KIND_EBOOK)
        assertEquals(2, dao.allGroups().count { it.name == "Reading" })
        val comicGroup = dao.allGroups().single { it.kind == KIND_COMIC }
        val ebookGroup = dao.allGroups().single { it.kind == KIND_EBOOK }
        dao.publishImportedBook(book(a).copy(kind = KIND_EBOOK))
        rejected { dao.moveBooks(listOf(a), comicGroup.id) }
        dao.moveBooks(listOf(a), ebookGroup.id)
        assertEquals(ebookGroup.id, dao.getBook(a)?.groupId)
    }

    @Test fun groupDeletionUngroupsImportedAndPendingWithoutChangingSlotsOrBookmarks() = runBlocking {
        val group = GroupEntity("series", "Series", 0, 100)
        dao.mergeSnapshot(MetadataSnapshot(100, listOf(group), listOf(record(a, groupId = group.id), record(b, order = 1, groupId = group.id))))
        dao.publishImportedBook(book(a))
        dao.deleteGroup(group.id)
        assertTrue(dao.allGroups().isEmpty())
        assertNull(dao.getBook(a)?.groupId)
        assertNull(dao.getPendingBook(b)?.groupId)
        assertEquals(0L, dao.getBook(a)?.sortOrder)
        assertEquals(1L, dao.getPendingBook(b)?.sortOrder)
        assertEquals(listOf(BookmarkEntity(a, 2), BookmarkEntity(a, 7)), dao.allBookmarks())
        assertTrue(dao.getBook(a)!!.updatedAt > 100)
        dao.deleteBooks(listOf(a, b))
        assertTrue(dao.allBooks().isEmpty())
        assertTrue(dao.allPendingBooks().isEmpty())
        assertTrue(dao.allBookmarks().isEmpty())
    }

    @Test fun groupReorderCompactsSlotsAndDuplicateNamesAreRejected() = runBlocking {
        dao.mergeSnapshot(MetadataSnapshot(10, listOf(GroupEntity("g1", "One", 0, 10), GroupEntity("g2", "Two", 1, 10)), emptyList()))
        dao.reorderGroups(listOf("g2", "g1"))
        assertEquals(listOf("g2", "g1"), dao.allGroups().map { it.id })
        assertEquals(listOf(0L, 1L), dao.allGroups().map { it.sortOrder })
        try {
            dao.createGroup("One")
            fail("duplicate group name must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertEquals("已有同名分组。", expected.message)
        }
        assertEquals(2, dao.allGroups().size)
        dao.renameGroup("g2", "Three")
        assertEquals(listOf("Three", "One"), dao.allGroups().map { it.name })
        try {
            dao.renameGroup("g2", "one")
            fail("renaming onto an existing name must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertEquals("已有同名分组。", expected.message)
        }
    }

    @Test fun pendingHistorySurvivesDatabaseReopenAndRepeatedBackupsUntilMatchingImport() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "pending-reopen-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, ReaderDatabase::class.java, name).allowMainThreadQueries().build()
        var persistent = open()
        val original = MetadataSnapshot(100, listOf(GroupEntity("series", "Series", 0, 100)),
            listOf(record(a, groupId = "series"), record(b, order = 1)), ReaderPreferences(lastReadingBookId = a), 100)
        try {
            persistent.libraryDao().mergeSnapshot(original)
            persistent.close()
            persistent = open()
            val reopened = persistent.libraryDao()
            assertTrue(reopened.allBooks().isEmpty())
            val savedAgain = BackupCodec.decode(BackupCodec.encode(reopened.snapshot(200, original.preferences, 100)))
            assertEquals(original.books, savedAgain.books)
            reopened.mergeSnapshot(savedAgain)
            val imported = reopened.publishImportedBook(book(a))
            assertEquals(5, imported.currentPage)
            assertEquals(77, imported.pageOffset)
            assertEquals("horizontal", imported.mode)
            assertEquals("series", imported.groupId)
            assertEquals(0L, imported.sortOrder)
            assertEquals(100L, imported.updatedAt)
            assertNull(reopened.getPendingBook(a))
            assertNotNull(reopened.getPendingBook(b))
            assertEquals(listOf(BookmarkEntity(a, 2), BookmarkEntity(a, 7)), reopened.allBookmarks())
            val combined = reopened.snapshot(300, original.preferences, 100)
            assertEquals(setOf(a, b), combined.books.map { it.id }.toSet())
            assertEquals(2L, reopened.publishImportedBook(book(c)).sortOrder)
            reopened.saveProgress(a, 9, 12, "vertical")
            val beforeDuplicate = reopened.getBook(a)
            assertEquals(beforeDuplicate, reopened.publishImportedBook(book(a)))
            assertEquals(beforeDuplicate, reopened.getBook(a))
        } finally {
            persistent.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun textBooksAdoptTheDevicePageCountAndRescaleMarks() = runBlocking {
        dao.publishImportedBook(book(a).copy(format = FORMAT_EPUB, kind = KIND_EBOOK,
            pageCount = 12, currentPage = 9, pageOffset = 40))
        dao.toggleBookmark(a, 3)
        dao.toggleBookmark(a, 10)
        // The reader's layout has fewer pages than the import: saved page and marks must follow it.
        assertTrue(dao.adoptPageCount(a, 7))
        val adopted = dao.getBook(a)!!
        assertEquals(7, adopted.pageCount)
        assertEquals(6, adopted.currentPage)
        assertEquals(0, adopted.pageOffset)
        // A mark follows the passage it anchors, not the page number it happened to sit on.
        assertEquals(listOf(BookmarkEntity(a, 1), BookmarkEntity(a, 5)), dao.allBookmarks())
        // Growing keeps the saved page, and an unchanged count is not a write at all.
        val before = dao.getBook(a)
        assertTrue(dao.adoptPageCount(a, 20))
        assertEquals(before?.currentPage, dao.getBook(a)?.currentPage)
        assertEquals(listOf(BookmarkEntity(a, 2), BookmarkEntity(a, 14)), dao.allBookmarks())
        assertFalse(dao.adoptPageCount(a, 20))
        assertEquals(listOf(BookmarkEntity(a, 2), BookmarkEntity(a, 14)), dao.allBookmarks())
        rejected { dao.adoptPageCount(a, 0) }
        assertEquals(20, dao.getBook(a)?.pageCount)
    }

    @Test fun pdfTextStateIsRecordedOnceAndOnlyValidValuesPass() = runBlocking {
        dao.publishImportedBook(book(a).copy(kind = KIND_EBOOK))
        val fresh = dao.getBook(a)!!
        assertEquals(TEXT_UNKNOWN, fresh.textState)
        // Until the probe succeeds the reader offers only the original pages.
        assertFalse(fresh.hasTextView)
        assertTrue(dao.setTextState(a, TEXT_READY))
        val ready = dao.getBook(a)!!
        assertEquals(TEXT_READY, ready.textState)
        assertTrue(ready.hasTextView)
        // Re-probing the same PDF must not rewrite the row time and again.
        assertFalse(dao.setTextState(a, TEXT_READY))
        assertTrue(dao.setTextState(a, TEXT_UNSUPPORTED))
        assertFalse(dao.getBook(a)!!.hasTextView)
        rejected { dao.setTextState(a, TEXT_UNSUPPORTED + 5) }
        assertFalse(dao.setTextState(b, TEXT_READY))
    }

    @Test fun restoreAcceptsADifferentTextPageCountButClampsItsPosition() = runBlocking {
        dao.publishImportedBook(book(a).copy(format = FORMAT_TEXT, kind = KIND_EBOOK, pageCount = 12))
        // The same text book imported on another device reports a different page range.
        dao.mergeSnapshot(MetadataSnapshot(500, emptyList(),
            listOf(record(a, updatedAt = 500).copy(currentPage = 15, pageOffset = 77, bookmarks = listOf(3, 15)))))
        val restored = dao.getBook(a)!!
        assertEquals(12, restored.pageCount)
        assertEquals(11, restored.currentPage)
        assertEquals(0, restored.pageOffset)
        assertEquals(listOf(BookmarkEntity(a, 3)), dao.allBookmarks())
    }

    @Test fun restorePreservesNewerLocalProgressBookmarksGroupsAndPendingRecords() = runBlocking {
        dao.mergeSnapshot(MetadataSnapshot(100, listOf(GroupEntity("series", "Older", 0, 100)),
            listOf(record(a, groupId = "series"), record(b, order = 1))))
        dao.publishImportedBook(book(a))
        dao.saveProgress(a, 9, 4, "vertical")
        dao.toggleBookmark(a, 6)
        dao.renameGroup("series", "Newer local name")
        val local = dao.getBook(a)
        val marks = dao.allBookmarks()
        dao.mergeSnapshot(MetadataSnapshot(400, listOf(GroupEntity("series", "Stale name", 0, 90)),
            listOf(record(a, updatedAt = 90, groupId = "series"), record(b, updatedAt = 90, order = 1))))
        assertEquals(local, dao.getBook(a))
        assertEquals(marks, dao.allBookmarks())
        assertEquals("Newer local name", dao.getGroup("series")?.name)
        assertEquals(100L, dao.getPendingBook(b)?.updatedAt)
        dao.mergeSnapshot(MetadataSnapshot(500, emptyList(), listOf(record(b, updatedAt = 500, order = 1).copy(currentPage = 8))))
        assertEquals(8, dao.getPendingBook(b)?.currentPage)
    }

    @Test fun invalidSnapshotCannotPartiallyMutateLibrary() = runBlocking {
        dao.publishImportedBook(book(a).copy(updatedAt = 10))
        val before = dao.getBook(a)
        rejected {
            dao.mergeSnapshot(MetadataSnapshot(100, listOf(GroupEntity("added", "New group", 0, 100)),
                listOf(record(a).copy(pageCount = 25), record(b, order = 1))))
        }
        assertEquals(before, dao.getBook(a))
        assertTrue(dao.allGroups().isEmpty())
        assertTrue(dao.allPendingBooks().isEmpty())
        rejected {
            dao.mergeSnapshot(MetadataSnapshot(100, emptyList(), listOf(record(b).copy(groupId = "nonexistent"))))
        }
        assertFalse(dao.allBooks().isEmpty())
    }

    @Test fun acceptedSkewedRestoreCanAdvanceProgressBookmarksAndPreferencesAndBeBackedUpAgain() = runBlocking {
        val future = System.currentTimeMillis() + MetadataRules.MAX_CLOCK_SKEW_MS / 2
        val preferences = PreferencesRepository(MemoryPreferencesStore())
        val original = MetadataSnapshot(future, listOf(GroupEntity("series", "Series", 0, future)),
            listOf(record(a, future, groupId = "series")), ReaderPreferences(theme = "dark"), future)
        dao.mergeSnapshot(BackupCodec.decode(BackupCodec.encode(original)))
        preferences.mergeRestored(original.preferences, original.preferencesUpdatedAt)
        dao.publishImportedBook(book(a))
        dao.saveProgress(a, 9, MetadataRules.MAX_OFFSET, "vertical")
        dao.toggleBookmark(a, 6)
        dao.renameGroup("series", "Renamed")
        preferences.setTheme("light")
        preferences.setLastReadingBook(a)
        val prefs = preferences.metadata.first()
        assertEquals(future + 2, prefs.updatedAt)
        assertEquals(future + 2, dao.getBook(a)!!.updatedAt)
        val snapshot = dao.snapshot(System.currentTimeMillis(), prefs.value, prefs.updatedAt)
        assertEquals(snapshot, BackupCodec.decode(BackupCodec.encode(snapshot)))
    }

    @Test fun prefetchRadiusPersistsOnDeviceWithoutTouchingTheBackupTimestamp() = runBlocking {
        val preferences = PreferencesRepository(MemoryPreferencesStore())
        assertEquals(DEFAULT_PREFETCH_RADIUS, preferences.preferences.first().prefetchRadius)
        assertEquals(DEFAULT_CUSTOM_PREFETCH_RADIUS, preferences.preferences.first().lastCustomPrefetchRadius)
        val before = preferences.metadata.first()
        preferences.setPrefetchRadius(MAX_PREFETCH_RADIUS)
        assertEquals(MAX_PREFETCH_RADIUS, preferences.preferences.first().prefetchRadius)
        assertEquals(MAX_PREFETCH_RADIUS, preferences.preferences.first().lastCustomPrefetchRadius)
        // How many pages a phone can warm is device-local, so it must not look like a settings edit.
        assertEquals(before.updatedAt, preferences.metadata.first().updatedAt)
        rejected { preferences.setPrefetchRadius(MIN_PREFETCH_RADIUS - 1) }
        rejected { preferences.setPrefetchRadius(MAX_PREFETCH_RADIUS + 1) }
        assertEquals(MAX_PREFETCH_RADIUS, preferences.preferences.first().prefetchRadius)
        // A preset is not a custom pick, so the stepper still reopens at the deeper value.
        preferences.setPrefetchRadius(DEFAULT_PREFETCH_RADIUS)
        assertEquals(DEFAULT_PREFETCH_RADIUS, preferences.preferences.first().prefetchRadius)
        assertEquals(MAX_PREFETCH_RADIUS, preferences.preferences.first().lastCustomPrefetchRadius)
    }

    @Test fun farFutureRestoreCannotMutateBooksGroupsPendingBookmarksOrPreferences() = runBlocking {
        val preferences = PreferencesRepository(MemoryPreferencesStore())
        preferences.setTheme("dark")
        dao.mergeSnapshot(MetadataSnapshot(100, listOf(GroupEntity("series", "Series", 0, 100)),
            listOf(record(a, groupId = "series"), record(b, order = 1))))
        dao.publishImportedBook(book(a))
        val before = dao.snapshot(200, ReaderPreferences(), 0)
        val beforePreferences = preferences.metadata.first()
        rejected {
            dao.mergeSnapshot(MetadataSnapshot(300, listOf(GroupEntity("added", "New", 1, 300)),
                listOf(record(a, 300, groupId = "added"), record(b, MetadataRules.MAX_TIME), record(c, order = 2))))
        }
        rejected { preferences.mergeRestored(ReaderPreferences(theme = "light"), MetadataRules.MAX_TIME) }
        assertEquals(before, dao.snapshot(200, ReaderPreferences(), 0))
        assertEquals(beforePreferences, preferences.metadata.first())
        assertEquals(listOf(BookmarkEntity(a, 2), BookmarkEntity(a, 7)), dao.allBookmarks())
    }

    @Test fun appendCompactsImportedAndPendingBookOrdersAndGroupOrdersSeparately() = runBlocking {
        val groups = listOf(GroupEntity("first", "First", 7, 100),
            GroupEntity("last", "Last", MetadataRules.MAX_ORDER, 100))
        val middlePending = "e".repeat(64)
        dao.mergeSnapshot(MetadataSnapshot(100, groups, listOf(
            record(a, order = MetadataRules.MAX_ORDER - 3),
            record(middlePending, order = MetadataRules.MAX_ORDER - 2),
            record(b, order = MetadataRules.MAX_ORDER - 1), record(c, order = MetadataRules.MAX_ORDER))))
        dao.publishImportedBook(book(a))
        dao.publishImportedBook(book(b))
        assertEquals(MetadataRules.MAX_ORDER, dao.getPendingBook(c)!!.sortOrder)
        dao.publishImportedBook(book(d))
        val snapshot = dao.snapshot(200, ReaderPreferences(), 0)
        assertEquals(listOf(a, middlePending, b, c, d), snapshot.books.map { it.id })
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), snapshot.books.map { it.sortOrder })
        assertEquals(listOf(middlePending, c), dao.allPendingBooks().map { it.id })
        assertEquals(groups, dao.allGroups()) // Book compaction cannot change group order.
        dao.createGroup("Appended")
        assertEquals(listOf("First", "Last", "Appended"), dao.allGroups().map { it.name })
        assertEquals(listOf(0L, 1L, 2L), dao.allGroups().map { it.sortOrder })
        assertEquals(snapshot.books, dao.snapshot(200, ReaderPreferences(), 0).books)
        assertEquals(3L, dao.publishImportedBook(book(c)).sortOrder)
        assertEquals(listOf(a, b, c, d), dao.allBooks().map { it.id })
        assertEquals(listOf(BookmarkEntity(a, 2), BookmarkEntity(a, 7), BookmarkEntity(b, 2),
            BookmarkEntity(b, 7), BookmarkEntity(c, 2), BookmarkEntity(c, 7)), dao.allBookmarks())
    }

    @Test fun mergeOrderCollisionAtMaximumCompactsRatherThanRejectingValidOrdinals() = runBlocking {
        dao.mergeSnapshot(MetadataSnapshot(100, emptyList(), listOf(record(a, order = MetadataRules.MAX_ORDER))))
        dao.publishImportedBook(book(a))
        dao.mergeSnapshot(MetadataSnapshot(200, emptyList(), listOf(record(b, order = MetadataRules.MAX_ORDER))))
        val snapshot = dao.snapshot(300, ReaderPreferences(), 0)
        assertEquals(listOf(a, b), snapshot.books.map { it.id })
        assertEquals(listOf(0L, 1L), snapshot.books.map { it.sortOrder })
        assertEquals(snapshot, BackupCodec.decode(BackupCodec.encode(snapshot)))
        assertEquals(2L, dao.publishImportedBook(book(c)).sortOrder)
    }

    @Test fun individuallyEncodableSnapshotsWhoseUnionExceedsByteLimitRollBackEveryTable() = runBlocking {
        val title = "阅".repeat(255)
        val existing = listOf(record(a, groupId = "series").copy(title = title)) + List(4_499) { index ->
            record((index + 1).toString(16).padStart(64, '0'), order = (index + 1).toLong()).copy(title = title)
        }
        val initial = MetadataSnapshot(100, listOf(GroupEntity("series", "Original", 0, 100)), existing)
        assertTrue(BackupCodec.encode(initial).size < BackupCodec.MAX_BYTES)
        dao.mergeSnapshot(initial)
        dao.publishImportedBook(book(a))
        val before = dao.snapshot(200, ReaderPreferences(), 0)
        val beforeBook = dao.getBook(a)
        val beforeMarks = dao.allBookmarks()
        val additions = listOf(record(a, updatedAt = 300, groupId = "added").copy(bookmarks = listOf(1))) +
            List(4_500) { index ->
                record((index + 5_000).toString(16).padStart(64, '0'), order = index.toLong()).copy(title = title)
            }
        val incoming = MetadataSnapshot(300, listOf(GroupEntity("series", "Changed", 0, 300),
            GroupEntity("added", "Added", 1, 300)), additions)
        assertTrue(BackupCodec.encode(incoming).size < BackupCodec.MAX_BYTES)
        assertTrue(existing.size + additions.size - 1 < MetadataRules.MAX_BOOKS)
        rejected { dao.mergeSnapshot(incoming) }
        assertEquals(before, dao.snapshot(200, ReaderPreferences(), 0))
        assertEquals(beforeBook, dao.getBook(a))
        assertEquals(beforeMarks, dao.allBookmarks())
        assertNull(dao.getGroup("added"))
    }

    @Test fun rawBackupConfigurationReadPropagatesIoFailureAndCanReobserveSavedValues() = runBlocking {
        var reads = 0
        val saved = preferencesOf(
            booleanPreferencesKey("backup_enabled") to false,
            longPreferencesKey("last_metadata_backup_at") to 42L,
            booleanPreferencesKey("backup_pending_restore") to true,
        )
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                if (reads++ == 0) throw IOException("temporarily unavailable")
                emit(saved)
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw AssertionError("Reading configuration must not reset DataStore")
        }
        val preferences = PreferencesRepository(store)
        try { preferences.readBackupConfiguration(); fail("Expected raw IOException") } catch (_: IOException) { }
        assertEquals(BackupConfiguration(enabled = false, lastBackupAt = 42, pendingRestore = true),
            preferences.readBackupConfiguration())
        assertEquals(2, reads)
    }

    @Test fun backupMetadataErrorsAndCancellationAreNotConvertedToDefaults() = runBlocking {
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("unavailable") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw AssertionError("No writes expected")
        }
        try { PreferencesRepository(store).metadata.first(); fail("Expected raw IOException") } catch (_: IOException) { }
        val cancelled = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw CancellationException("cancelled") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw AssertionError("No writes expected")
        }
        try {
            PreferencesRepository(cancelled).readBackupConfiguration()
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    private class MemoryPreferencesStore : DataStore<Preferences> {
        private val values = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(values.value).also { values.value = it }
    }

    private fun book(id: String) = BookEntity(id, "Book", "private-original.pdf", 20, importedAt = 10, updatedAt = 10)
    private fun record(id: String, updatedAt: Long = 100, order: Long = 0, groupId: String? = null) =
        BookMetadata(id, "Restored title", 20, 5, 77, "horizontal", 10, updatedAt, groupId, order, listOf(2, 7))

    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected invalid metadata to be rejected") } catch (_: IllegalArgumentException) { }
    }
}
