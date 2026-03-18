package com.m3mo.vault.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * MemoryEntity - Room database entity for memories
 *
 * Maps directly to MemoryItem domain model.
 * Includes version field for optimistic locking.
 *
 * Indexes:
 * - state: For filtering by processing state
 * - type: For filtering by memory type
 * - weight: For sorting by importance
 * - createdAt: For time-based queries
 * - lastAccessedAt: For decay calculations
 * - isPinned: For quick pinned queries
 */
@Entity(
    tableName = "memories",
    indices = [
        Index(value = ["state"]),
        Index(value = ["type"]),
        Index(value = ["weight"]),
        Index(value = ["createdAt"]),
        Index(value = ["lastAccessedAt"]),
        Index(value = ["isPinned"]),
        Index(value = ["state", "weight"]),  // Composite for decay queries
        Index(value = ["state", "type"])     // Composite for filtered searches
    ]
)
data class MemoryEntity(
    /**
     * Unique identifier (Snowflake ID)
     */
    @PrimaryKey
    val id: Long,

    /**
     * Raw content as entered by user
     */
    @ColumnInfo(name = "content")
    val content: String,

    /**
     * Normalized content for search/matching
     * Lowercased, trimmed, whitespace normalized
     */
    @ColumnInfo(name = "normalizedContent")
    val normalizedContent: String,

    /**
     * Memory type: FACT, EPISODE, TASK
     */
    @ColumnInfo(name = "type")
    val type: String,

    /**
     * Processing state: NEW, QUEUED, STORED, etc.
     */
    @ColumnInfo(name = "state")
    val state: String,

    /**
     * Weight for SmartDecay (1-16)
     */
    @ColumnInfo(name = "weight")
    val weight: Int,

    /**
     * Whether memory is pinned (immune to decay)
     */
    @ColumnInfo(name = "isPinned")
    val isPinned: Boolean,

    /**
     * User confidence rating (1-5, null if not rated)
     */
    @ColumnInfo(name = "confidence")
    val confidence: Int?,

    /**
     * Task completion state (only for TASK type)
     */
    @ColumnInfo(name = "isCompleted")
    val isCompleted: Boolean,

    /**
     * Number of times this memory was accessed
     */
    @ColumnInfo(name = "accessCount")
    val accessCount: Int,

    /**
     * Embedding vector for semantic search (JSON serialized)
     */
    @ColumnInfo(name = "embedding")
    val embedding: ByteArray?,

    /**
     * Source of memory: TEXT, VOICE, IMPORT, etc.
     */
    @ColumnInfo(name = "source")
    val source: String,

    /**
     * Tags as JSON array string
     */
    @ColumnInfo(name = "tags")
    val tags: String,

    /**
     * Creation timestamp (epoch millis)
     */
    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    /**
     * Last update timestamp (epoch millis)
     */
    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,

    /**
     * Last access timestamp (epoch millis)
     */
    @ColumnInfo(name = "lastAccessedAt")
    val lastAccessedAt: Long,

    /**
     * Version for optimistic locking
     * Incremented on every update
     */
    @ColumnInfo(name = "version")
    val version: Long,

    /**
     * Schema version for migrations
     */
    @ColumnInfo(name = "schemaVersion")
    val schemaVersion: Int
)
