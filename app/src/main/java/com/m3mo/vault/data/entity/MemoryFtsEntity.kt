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
    // 字段名必须与 MemoryEntity 完全一致！

    @ColumnInfo(name = "content")
    val content: String,

    // ⚡️ 修复点：从 "normalized_content" 改为 "normalizedContent"
    @ColumnInfo(name = "normalizedContent")
    val normalizedContent: String,

    @ColumnInfo(name = "tags")
    val tags: String,

    @ColumnInfo(name = "type")
    val type: String
)