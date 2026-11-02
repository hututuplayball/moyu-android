package com.moyu.reader.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Single-level collection: deliberately has no parent group. */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Long,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    /** Groups live inside one shelf; a comic group never accepts an ebook. */
    @ColumnInfo(defaultValue = "0") val kind: Int = KIND_COMIC,
)
