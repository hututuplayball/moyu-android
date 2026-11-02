package com.moyu.reader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Locale

@Database(
    entities = [BookEntity::class, BookmarkEntity::class, TextBookmarkEntity::class, GroupEntity::class, PendingBookEntity::class],
    version = 9,
    exportSchema = false,
)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN groupId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `groups` (id TEXT NOT NULL PRIMARY KEY, " +
                    "name TEXT NOT NULL, sortOrder INTEGER NOT NULL, updatedAt INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE IF NOT EXISTS pending_books (id TEXT NOT NULL PRIMARY KEY, " +
                    "title TEXT NOT NULL, pageCount INTEGER NOT NULL, currentPage INTEGER NOT NULL, " +
                    "pageOffset INTEGER NOT NULL, mode TEXT NOT NULL, importedAt INTEGER NOT NULL, " +
                    "updatedAt INTEGER NOT NULL, groupId TEXT, sortOrder INTEGER NOT NULL, bookmarksJson TEXT NOT NULL)")
                // Seed the old import-descending shelf once; reading activity must never reorder it.
                val ids = mutableListOf<String>()
                db.query("SELECT id FROM books ORDER BY importedAt DESC, title COLLATE NOCASE ASC, id ASC").use {
                    while (it.moveToNext()) ids.add(it.getString(0))
                }
                ids.forEachIndexed { index, id ->
                    db.execSQL("UPDATE books SET sortOrder = ? WHERE id = ?", arrayOf(index.toLong(), id))
                }
            }
        }

        /** Old builds could create two groups with the same name, which made a dissolve look unfinished. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val byName = LinkedHashMap<String, MutableList<String>>()
                db.query("SELECT id, name FROM groups ORDER BY sortOrder ASC, id ASC").use { cursor ->
                    while (cursor.moveToNext()) {
                        val key = cursor.getString(1).trim().lowercase(Locale.ROOT)
                        byName.getOrPut(key) { mutableListOf() }.add(cursor.getString(0))
                    }
                }
                byName.values.filter { it.size > 1 }.forEach { ids ->
                    val keeper = ids.first()
                    ids.drop(1).forEach { duplicate ->
                        db.execSQL("UPDATE books SET groupId = ? WHERE groupId = ?", arrayOf(keeper, duplicate))
                        db.execSQL("UPDATE pending_books SET groupId = ? WHERE groupId = ?", arrayOf(keeper, duplicate))
                        db.execSQL("DELETE FROM groups WHERE id = ?", arrayOf(duplicate))
                    }
                }
            }
        }

        /** Existing books keep their private copies; new imports may reference the original document. */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN sourceUri TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN storageMode INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Every existing row is a PDF; new imports may be archives or image folders. */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN format INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Existing shelves stay comics; the ebook shelf starts empty until files are imported there. */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE groups ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_books ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Existing books start out unknown; a PDF's text layer is probed the first time it is read. */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN textState INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Existing books were read as original pages, so their shelf labels keep meaning that. */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN readView INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN textPage INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN textPageCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Text books move to character anchors; legacy page positions stay readable through the defaults. */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN textOffset INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN textProgress INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN textAnchors INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `text_bookmarks` (" +
                    "bookId TEXT NOT NULL, offset INTEGER NOT NULL, " +
                    "PRIMARY KEY(bookId, offset), " +
                    "FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_text_bookmarks_bookId ON text_bookmarks(bookId)")
            }
        }

        @Volatile
        private var instance: ReaderDatabase? = null

        fun getInstance(context: Context): ReaderDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ReaderDatabase::class.java,
                "moyu_library.db",
            ).addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8, MIGRATION_8_9,
            ).build().also { instance = it }
        }
    }
}
