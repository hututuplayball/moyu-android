package com.moyu.reader.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONArray

/** Restored history without a private PDF. Never shown as an imported book. */
@Entity(tableName = "pending_books")
data class PendingBookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val pageCount: Int,
    val currentPage: Int,
    val pageOffset: Int,
    val mode: String,
    val importedAt: Long,
    val updatedAt: Long,
    val groupId: String?,
    val sortOrder: Long,
    val bookmarksJson: String,
    @ColumnInfo(defaultValue = "0") val kind: Int = KIND_COMIC,
) {
    fun metadata(): BookMetadata {
        val pages = JSONArray(bookmarksJson)
        return BookMetadata(id, title, pageCount, currentPage, pageOffset, mode,
            importedAt, updatedAt, groupId, sortOrder, List(pages.length()) { pages.getInt(it) },
            kind = kind)
    }

    companion object {
        fun from(value: BookMetadata) = PendingBookEntity(value.id, value.title, value.pageCount,
            value.currentPage, value.pageOffset, value.mode, value.importedAt, value.updatedAt,
            value.groupId, value.sortOrder, JSONArray(value.bookmarks).toString(), value.kind)
    }
}
