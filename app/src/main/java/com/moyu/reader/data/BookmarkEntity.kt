package com.moyu.reader.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "bookmarks",
    primaryKeys = ["bookId", "page"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["bookId"])],
)
data class BookmarkEntity(
    val bookId: String,
    val page: Int,
)

/** Text bookmarks anchor a character, not a window-local page, so they survive reflow and chapters. */
@Entity(
    tableName = "text_bookmarks",
    primaryKeys = ["bookId", "offset"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["bookId"])],
)
data class TextBookmarkEntity(
    val bookId: String,
    val offset: Int,
)
