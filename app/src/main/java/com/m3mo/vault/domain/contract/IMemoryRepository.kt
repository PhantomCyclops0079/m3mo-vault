package com.m3mo.vault.domain.contract

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.MemoryType
import com.m3mo.vault.domain.model.ProcessingState
import kotlinx.coroutines.flow.Flow

/**
 * IMemoryRepository - Memory persistence interface
 *
 * Defines the contract for memory storage and retrieval.
 * Implementation uses Room database with WAL layer for writes
 * and L1 cache for hot data.
 *
 * Design principles:
 * - All operations return AppResult for consistent error handling
 * - Flow-based for reactive UI updates
 * - Optimistic locking support for concurrent access
 * - Batch operations for efficiency
 */
interface IMemoryRepository {

    // ========== Single Item Operations ==========

    /**
     * Get memory by ID
     *
     * @param id Memory ID
     * @return Memory if found, NotFound error otherwise
     */
    suspend fun getById(id: Long): AppResult<MemoryItem, AppError>

    /**
     * Insert new memory
     *
     * @param memory Memory to insert
     * @return Inserted memory with generated ID
     */
    suspend fun insert(memory: MemoryItem): AppResult<MemoryItem, AppError>

    /**
     * Update existing memory
     *
     * IMPORTANT: Implementation must use optimistic locking!
     * - Check WHERE id = :id AND version = :memory.version
     * - If no rows affected, return AppError.Business.OptimisticLockConflict
     * - On success, the returned MemoryItem has version incremented
     *
     * @param memory Updated memory (must have valid ID and current version)
     * @return Updated memory with new version, or OptimisticLockConflict error
     */
    suspend fun update(memory: MemoryItem): AppResult<MemoryItem, AppError>

    /**
     * Upsert (insert or update) memory
     *
     * @param memory Memory to upsert
     * @return Upserted memory
     */
    suspend fun upsert(memory: MemoryItem): AppResult<MemoryItem, AppError>

    /**
     * Delete memory by ID (sets to TOMBSTONED state)
     *
     * @param id Memory ID to delete
     * @return Success or NotFound error
     */
    suspend fun delete(id: Long): AppResult<Unit, AppError>

    /**
     * Hard delete memory (permanent removal)
     * Use with caution - for GC cleanup only
     */
    suspend fun hardDelete(id: Long): AppResult<Unit, AppError>

    // ========== Batch Operations ==========

    /**
     * Insert multiple memories
     *
     * @param memories List of memories to insert
     * @return List of inserted memories
     */
    suspend fun insertBatch(memories: List<MemoryItem>): AppResult<List<MemoryItem>, AppError>

    /**
     * Update multiple memories
     *
     * Note: For batch updates (like SmartDecay), version conflicts are
     * handled gracefully - conflicting items are skipped and counted
     * in the return value. No error is thrown for partial success.
     *
     * @param memories List of memories to update
     * @return Number of successfully updated rows (may be less than input size)
     */
    suspend fun updateBatch(memories: List<MemoryItem>): AppResult<Int, AppError>

    /**
     * Delete multiple memories by IDs
     *
     * @param ids Memory IDs to delete
     * @return Number of deleted rows
     */
    suspend fun deleteBatch(ids: List<Long>): AppResult<Int, AppError>

    // ========== Query Operations ==========

    /**
     * Get all memories (paginated)
     *
     * @param limit Maximum results
     * @param offset Starting position
     * @return List of memories
     */
    suspend fun getAll(limit: Int = 100, offset: Int = 0): AppResult<List<MemoryItem>, AppError>

    /**
     * Get memories by type
     *
     * @param type Memory type filter
     * @param limit Maximum results
     * @return List of memories
     */
    suspend fun getByType(type: MemoryType, limit: Int = 100): AppResult<List<MemoryItem>, AppError>

    /**
     * Get memories by state
     *
     * @param state Processing state filter
     * @param limit Maximum results
     * @return List of memories
     */
    suspend fun getByState(state: ProcessingState, limit: Int = 100): AppResult<List<MemoryItem>, AppError>

    /**
     * Get memories in multiple states
     *
     * @param states Set of states to include
     * @param limit Maximum results
     * @return List of memories
     */
    suspend fun getByStates(states: Set<ProcessingState>, limit: Int = 100): AppResult<List<MemoryItem>, AppError>

    /**
     * Get memories created in time range
     *
     * @param startTime Start of range (inclusive)
     * @param endTime End of range (exclusive)
     * @return List of memories
     */
    suspend fun getByTimeRange(startTime: Long, endTime: Long): AppResult<List<MemoryItem>, AppError>

    /**
     * Get pinned memories
     *
     * @return List of pinned memories
     */
    suspend fun getPinned(): AppResult<List<MemoryItem>, AppError>

    /**
     * Get most recent memories (for fuzzy search fallback)
     *
     * @param limit Maximum results
     * @return List of recent memories sorted by updatedAt desc
     */
    suspend fun getRecent(limit: Int = 100): AppResult<List<MemoryItem>, AppError>

    /**
     * Get incomplete tasks
     *
     * @return List of incomplete task memories
     */
    suspend fun getIncompleteTasks(): AppResult<List<MemoryItem>, AppError>

    /**
     * Get completed tasks in time range
     */
    suspend fun getCompletedTasks(startTime: Long, endTime: Long): AppResult<List<MemoryItem>, AppError>

    /**
     * Get memories eligible for decay processing
     *
     * @param limit Maximum to process
     * @return List of eligible memories
     */
    suspend fun getDecayEligible(limit: Int = 500): AppResult<List<MemoryItem>, AppError>

    /**
     * Get memories needing embedding
     *
     * @param limit Maximum to process
     * @return List of memories without embeddings
     */
    suspend fun getNeedingEmbedding(limit: Int = 100): AppResult<List<MemoryItem>, AppError>

    // ========== Reactive Streams ==========

    /**
     * Observe all memories
     *
     * @return Flow of all memories (updates on changes)
     */
    fun observeAll(): Flow<List<MemoryItem>>

    /**
     * Observe memory by ID
     *
     * @param id Memory ID
     * @return Flow of memory updates
     */
    fun observeById(id: Long): Flow<MemoryItem?>

    /**
     * Observe memories by type
     *
     * @param type Memory type
     * @return Flow of memories of this type
     */
    fun observeByType(type: MemoryType): Flow<List<MemoryItem>>

    /**
     * Observe incomplete tasks
     *
     * @return Flow of incomplete tasks
     */
    fun observeIncompleteTasks(): Flow<List<MemoryItem>>

    /**
     * Observe pinned memories
     *
     * @return Flow of pinned memories
     */
    fun observePinned(): Flow<List<MemoryItem>>

    /**
     * Observe memory count
     *
     * @return Flow of total memory count
     */
    fun observeCount(): Flow<Int>

    // ========== Statistics ==========

    /**
     * Get total memory count
     */
    suspend fun count(): AppResult<Int, AppError>

    /**
     * Get count by type
     */
    suspend fun countByType(type: MemoryType): AppResult<Int, AppError>

    /**
     * Get count by state
     */
    suspend fun countByState(state: ProcessingState): AppResult<Int, AppError>

    /**
     * Check if memory exists
     */
    suspend fun exists(id: Long): AppResult<Boolean, AppError>

    // ========== Search Support ==========

    /**
     * Get all searchable memories (for building search index)
     */
    suspend fun getSearchable(): AppResult<List<MemoryItem>, AppError>

    /**
     * Full-text search using SQLite FTS4 (100x faster than string matching)
     *
     * @param query Search query (supports AND, OR, prefix*)
     * @param limit Maximum results
     * @return List of matching memories
     */
    suspend fun searchFts(query: String, limit: Int = 100): AppResult<List<MemoryItem>, AppError>

    /**
     * Full-text search with prefix matching (for autocomplete)
     */
    suspend fun searchFtsPrefix(query: String, limit: Int = 50): AppResult<List<MemoryItem>, AppError>

    /**
     * Count FTS matches (to decide if fuzzy search is needed)
     */
    suspend fun countFtsMatches(query: String): AppResult<Int, AppError>

    /**
     * Full-text search (keyword based)
     *
     * @param query Search query
     * @param limit Maximum results
     * @return List of matching memories
     * 
     * @deprecated Use searchFts() instead for better performance
     */
    suspend fun searchByKeyword(query: String, limit: Int = 20): AppResult<List<MemoryItem>, AppError>

    // ========== Maintenance ==========

    /**
     * Permanently remove tombstoned memories older than cutoff
     *
     * @param cutoffTime Only remove if tombstoned before this time
     * @return Number of rows removed
     */
    suspend fun cleanupTombstoned(cutoffTime: Long): AppResult<Int, AppError>

    /**
     * Vacuum database (reclaim space)
     */
    suspend fun vacuum(): AppResult<Unit, AppError>

    /**
     * Get database size in bytes
     */
    suspend fun getDatabaseSize(): AppResult<Long, AppError>
}
