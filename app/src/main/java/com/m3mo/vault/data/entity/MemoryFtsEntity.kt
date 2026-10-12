package com.m3mo.vault.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

@Entity(tableName = "memories_fts")
@Fts4(
    contentEntity = MemoryEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61
)
data class MemoryFtsEntity(
    // FTS column names must match the content table's column names.

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "normalizedContent")
    val normalizedContent: String,

    @ColumnInfo(name = "tags")
    val tags: String,

    @ColumnInfo(name = "type")
    val type: String
)