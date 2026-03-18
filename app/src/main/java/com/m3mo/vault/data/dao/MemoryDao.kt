package com.m3mo.vault.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.m3mo.vault.data.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * MemoryDao - Data Access Object for memories
 *
 * Implements:
 * - Basic CRUD operations
 * - Optimistic locking for concurrent safety
 * - Batch operations for efficiency
 * - Reactive queries with Flow
 * - Specialized queries for decay/search
 */
@Dao
interface MemoryDao {

    // ========== Insert Operations ==========

    /**
     * Insert a single memory
     * @return Row ID of inserted memory
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(memory: MemoryEntity): Long

    /**
     * Insert multiple memories
     * @return List of row IDs
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(memories: List<MemoryEntity>): List<Long>

    // ========== Update Operations (with Optimistic Locking) ==========

    /**
     * Update a memory with optimistic locking
     * Only succeeds if version matches
     * @return Number of rows updated (0 = conflict, 1 = success)
     */
    @Query("""
        UPDATE memories 
        SET content = :content,
            normalizedContent = :normalizedContent,
            type = :type,
            state = :state,
            weight = :weight,
            isPinned = :isPinned,
            confidence = :confidence,
            isCompleted = :isCompleted,
            accessCount = :accessCount,
            embedding = :embedding,
            source = :source,
            tags = :tags,
            updatedAt = :updatedAt,
            lastAccessedAt = :lastAccessedAt,
            version = :newVersion,
            schemaVersion = :schemaVersion
        WHERE id = :id AND version = :expectedVersion
    """)
    suspend fun updateWithVersion(
        id: Long,
        content: String,
        normalizedContent: String,
        type: String,
        state: String,
        weight: Int,
        isPinned: Boolean,
        confidence: Int?,
        isCompleted: Boolean,
        accessCount: Int,
        embedding: ByteArray?,
        source: String,
        tags: String,
        updatedAt: Long,
        lastAccessedAt: Long,
        expectedVersion: Long,
        newVersion: Long,
        schemaVersion: Int
    ): Int

    /**
     * Simple update without version check (use with caution)
     * For internal/batch operations where conflict is acceptable
     */
    @Update
    suspend fun updateSimple(memory: MemoryEntity): Int

    /**
     * Update only the weight field (for decay operations)
     * Uses optimistic locking
     * @return Number of rows updated
     */
    @Query("""
        UPDATE memories 
        SET weight = :newWeight, 
            updatedAt = :updatedAt, 
            version = version + 1
        WHERE id = :id AND version = :expectedVersion
    """)
    suspend fun updateWeight(
        id: Long,
        newWeight: Int,
        updatedAt: Long,
        expectedVersion: Long
    ): Int

    /**
     * Update state (for state transitions)
     */
    @Query("""
        UPDATE memories 
        SET state = :newState, 
            updatedAt = :updatedAt, 
            version = version + 1
        WHERE id = :id AND version = :expectedVersion
    """)
    suspend fun updateState(
        id: Long,
        newState: String,
        updatedAt: Long,
        expectedVersion: Long
    ): Int

    /**
     * Batch update weights (for decay run)
     * Returns count of successfully updated rows
     */
    @Query("""
        UPDATE memories 
        SET weight = :newWeight, 
            state = :newState,
            updatedAt = :updatedAt, 
            version = version + 1
        WHERE id = :id
    """)
    suspend fun updateWeightAndState(
        id: Long,
        newWeight: Int,
        newState: String,
        updatedAt: Long
    ): Int

    // ========== Delete Operations ==========

    /**
     * Soft delete - mark as TOMBSTONED
     */
    @Query("""
        UPDATE memories 
        SET state = 'TOMBSTONED', 
            updatedAt = :updatedAt, 
            version = version + 1
        WHERE id = :id
    """)
    suspend fun softDelete(id: Long, updatedAt: Long): Int

    /**
     * Soft delete multiple memories
     */
    @Query("""
        UPDATE memories 
        SET state = 'TOMBSTONED', 
            updatedAt = :updatedAt, 
            version = version + 1
        WHERE id IN (:ids)
    """)
    suspend fun softDeleteBatch(ids: List<Long>, updatedAt: Long): Int

    /**
     * Hard delete - permanently remove
     */
    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun hardDelete(id: Long): Int

    /**
     * Hard delete multiple memories
     */
    @Query("DELETE FROM memories WHERE id IN (:ids)")
    suspend fun hardDeleteBatch(ids: List<Long>): Int

    /**
     * Delete all tombstoned memories older than threshold
     */
    @Query("DELETE FROM memories WHERE state = 'TOMBSTONED' AND updatedAt < :threshold")
    suspend fun deleteTombstonedOlderThan(threshold: Long): Int

    // ========== Query Operations ==========

    /**
     * Get memory by ID
     */
    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getById(id: Long): MemoryEntity?

    /**
     * Get memory by ID as Flow (reactive)
     */
    @Query("SELECT * FROM memories WHERE id = :id")
    fun observeById(id: Long): Flow<MemoryEntity?>

    /**
     * Get all memories (excluding tombstoned)
     */
    @Query("SELECT * FROM memories WHERE state != 'TOMBSTONED' ORDER BY createdAt DESC")
    suspend fun getAll(): List<MemoryEntity>

    /**
     * Get all memories as Flow
     */
    @Query("SELECT * FROM memories WHERE state != 'TOMBSTONED' ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    /**
     * Get memories by state
     */
    @Query("SELECT * FROM memories WHERE state IN (:states) ORDER BY weight DESC, createdAt DESC LIMIT :limit")
    suspend fun getByStates(states: List<String>, limit: Int): List<MemoryEntity>

    /**
     * Get memories by type
     */
    @Query("SELECT * FROM memories WHERE type = :type AND state != 'TOMBSTONED' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getByType(type: String, limit: Int): List<MemoryEntity>

    /**
     * Get pinned memories
     */
    @Query("SELECT * FROM memories WHERE isPinned = 1 AND state != 'TOMBSTONED' ORDER BY createdAt DESC")
    suspend fun getPinned(): List<MemoryEntity>

    /**
     * Observe pinned memories
     */
    @Query("SELECT * FROM memories WHERE isPinned = 1 AND state != 'TOMBSTONED' ORDER BY createdAt DESC")
    fun observePinned(): Flow<List<MemoryEntity>>

    /**
     * Get recent memories
     */
    @Query("SELECT * FROM memories WHERE state != 'TOMBSTONED' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MemoryEntity>

    /**
     * Get memories for decay (eligible for weight reduction)
     * - Not pinned
     * - In optimizable states
     * - Weight > minimum
     */
    @Query("""
        SELECT * FROM memories 
        WHERE isPinned = 0 
        AND state IN ('STORED', 'OPTIMIZED') 
        AND weight > 1
        ORDER BY lastAccessedAt ASC
        LIMIT :limit
    """)
    suspend fun getDecayEligible(limit: Int): List<MemoryEntity>

    /**
     * Get memories needing archival (weight at minimum)
     */
    @Query("""
        SELECT * FROM memories 
        WHERE isPinned = 0 
        AND state = 'OPTIMIZED' 
        AND weight <= 1
        LIMIT :limit
    """)
    suspend fun getArchiveCandidates(limit: Int): List<MemoryEntity>

    /**
     * Search candidates - memories in searchable states
     * Filtering by type and tags should be done in memory or with FTS
     */
    @Query("""
        SELECT * FROM memories 
        WHERE state IN (:states)
        ORDER BY weight DESC, lastAccessedAt DESC
        LIMIT :limit
    """)
    suspend fun getSearchCandidates(states: List<String>, limit: Int): List<MemoryEntity>

    /**
     * Get recent memories by states (for fuzzy search fallback)
     * Ordered by updatedAt DESC to get most recent first
     */
    @Query("""
        SELECT * FROM memories 
        WHERE state IN (:states)
        ORDER BY updatedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentByStates(states: List<String>, limit: Int): List<MemoryEntity>

    /**
     * Get incomplete tasks
     */
    @Query("""
        SELECT * FROM memories 
        WHERE type = 'TASK' 
        AND isCompleted = 0 
        AND state != 'TOMBSTONED'
        ORDER BY weight DESC, createdAt DESC
    """)
    suspend fun getIncompleteTasks(): List<MemoryEntity>

    /**
     * Observe incomplete tasks
     */
    @Query("""
        SELECT * FROM memories 
        WHERE type = 'TASK' 
        AND isCompleted = 0 
        AND state != 'TOMBSTONED'
        ORDER BY weight DESC, createdAt DESC
    """)
    fun observeIncompleteTasks(): Flow<List<MemoryEntity>>

    // ========== Aggregation Queries ==========

    /**
     * Count memories by state
     */
    @Query("SELECT COUNT(*) FROM memories WHERE state = :state")
    suspend fun countByState(state: String): Int

    /**
     * Count all non-tombstoned memories
     */
    @Query("SELECT COUNT(*) FROM memories WHERE state != 'TOMBSTONED'")
    suspend fun countActive(): Int

    /**
     * Get memory counts by type
     */
    @Query("""
        SELECT type, COUNT(*) as count 
        FROM memories 
        WHERE state != 'TOMBSTONED' 
        GROUP BY type
    """)
    suspend fun countByType(): List<TypeCount>

    /**
     * Get average weight
     */
    @Query("SELECT AVG(weight) FROM memories WHERE state IN ('STORED', 'OPTIMIZED')")
    suspend fun getAverageWeight(): Float?

    /**
     * Get total access count
     */
    @Query("SELECT SUM(accessCount) FROM memories WHERE state != 'TOMBSTONED'")
    suspend fun getTotalAccessCount(): Long?

    // ========== Maintenance Queries ==========

    /**
     * Check if memory exists
     */
    @Query("SELECT EXISTS(SELECT 1 FROM memories WHERE id = :id)")
    suspend fun exists(id: Long): Boolean

    /**
     * Get current version of a memory
     */
    @Query("SELECT version FROM memories WHERE id = :id")
    suspend fun getVersion(id: Long): Long?

    /**
     * Clear all data (for testing/reset)
     */
    @Query("DELETE FROM memories")
    suspend fun deleteAll()

    /**
     * Get memories created between timestamps (for weekly review)
     */
    @Query("""
        SELECT * FROM memories 
        WHERE createdAt >= :startTime 
        AND createdAt <= :endTime 
        AND state != 'TOMBSTONED'
        ORDER BY createdAt DESC
    """)
    suspend fun getCreatedBetween(startTime: Long, endTime: Long): List<MemoryEntity>

    /**
     * Get memories accessed between timestamps
     */
    @Query("""
        SELECT * FROM memories 
        WHERE lastAccessedAt >= :startTime 
        AND lastAccessedAt <= :endTime 
        AND state != 'TOMBSTONED'
        ORDER BY lastAccessedAt DESC
    """)
    suspend fun getAccessedBetween(startTime: Long, endTime: Long): List<MemoryEntity>

    // ========== FTS (Full-Text Search) Operations ==========

    /**
     * Search using FTS4 full-text index
     * 
     * This is 100x faster than Kotlin string matching because:
     * - Uses inverted index
     * - Written in C
     * - Doesn't load all content into RAM
     *
     * @param query FTS query string (supports AND, OR, prefix*)
     * @return List of matching memory IDs
     */
    @Query("""
        SELECT rowid FROM memories_fts 
        WHERE memories_fts MATCH :query
        LIMIT :limit
    """)
    suspend fun searchFts(query: String, limit: Int = 100): List<Long>

    /**
     * Search FTS and return full entities
     */
    @Query("""
        SELECT m.* FROM memories m
        INNER JOIN memories_fts fts ON m.id = fts.rowid
        WHERE memories_fts MATCH :query
        AND m.state NOT IN ('TOMBSTONED', 'ARCHIVED')
        ORDER BY m.weight DESC, m.lastAccessedAt DESC
        LIMIT :limit
    """)
    suspend fun searchFtsFull(query: String, limit: Int = 100): List<MemoryEntity>

    /**
     * Search FTS with prefix matching (for autocomplete)
     */
    @Query("""
        SELECT m.* FROM memories m
        INNER JOIN memories_fts fts ON m.id = fts.rowid
        WHERE memories_fts MATCH :query || '*'
        AND m.state NOT IN ('TOMBSTONED', 'ARCHIVED')
        ORDER BY m.weight DESC
        LIMIT :limit
    """)
    suspend fun searchFtsPrefix(query: String, limit: Int = 50): List<MemoryEntity>

    /**
     * Count FTS matches (for deciding whether to do fuzzy search)
     */
    @Query("""
        SELECT COUNT(*) FROM memories_fts 
        WHERE memories_fts MATCH :query
    """)
    suspend fun countFtsMatches(query: String): Int
}

/**
 * Helper class for type count aggregation
 */
data class TypeCount(
    val type: String,
    val count: Int
)
