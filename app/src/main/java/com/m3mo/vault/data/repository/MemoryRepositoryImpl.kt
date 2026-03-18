package com.m3mo.vault.data.repository

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.data.cache.MemoryCache
import com.m3mo.vault.data.dao.MemoryDao
import com.m3mo.vault.data.mapper.toDomain
import com.m3mo.vault.data.mapper.toDomainList
import com.m3mo.vault.data.mapper.toEntity
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.MemoryType
import com.m3mo.vault.domain.model.ProcessingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * MemoryRepositoryImpl - Implementation of IMemoryRepository
 *
 * Features:
 * - L1 cache for hot data
 * - Optimistic locking for concurrent safety
 * - Batch operations for efficiency
 * - Reactive Flow support
 * - Proper CancellationException handling for coroutines
 */
class MemoryRepositoryImpl(
    private val memoryDao: MemoryDao,
    private val cache: MemoryCache,
    private val clock: Clock,
    private val logger: Logger
) : IMemoryRepository {

    // ========== Single Item Operations ==========

    override suspend fun getById(id: Long): AppResult<MemoryItem, AppError> {
        // Check cache first
        cache.get(id)?.let {
            return AppResult.success(it)
        }

        // Cache miss - fetch from database
        return try {
            val entity = memoryDao.getById(id)
            if (entity == null) {
                AppResult.failure(
                    AppError.Business.NotFound(entity = "Memory", id = id)
                )
            } else {
                val item = entity.toDomain()
                cache.put(item)
                AppResult.success(item)
            }
        } catch (e: CancellationException) {
            throw e  // Must rethrow for proper coroutine cancellation
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to get memory: $id", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "getById",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun insert(memory: MemoryItem): AppResult<MemoryItem, AppError> {
        return try {
            val entity = memory.toEntity()
            memoryDao.insert(entity)
            cache.put(memory)
            logger.d(LogTags.DATA, "Inserted memory: ${memory.id}")
            AppResult.success(memory)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to insert memory: ${memory.id}", e)
            AppResult.failure(
                AppError.Database.WriteError(
                    operation = "insert",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun update(memory: MemoryItem): AppResult<MemoryItem, AppError> {
        return try {
            val entity = memory.toEntity()
            
            // Use optimistic locking - check version
            val rowsUpdated = memoryDao.updateWithVersion(
                id = entity.id,
                content = entity.content,
                normalizedContent = entity.normalizedContent,
                type = entity.type,
                state = entity.state,
                weight = entity.weight,
                isPinned = entity.isPinned,
                confidence = entity.confidence,
                isCompleted = entity.isCompleted,
                accessCount = entity.accessCount,
                embedding = entity.embedding,
                source = entity.source,
                tags = entity.tags,
                updatedAt = entity.updatedAt,
                lastAccessedAt = entity.lastAccessedAt,
                expectedVersion = memory.version - 1,
                newVersion = memory.version,
                schemaVersion = entity.schemaVersion
            )

            if (rowsUpdated == 0) {
                val currentVersion = memoryDao.getVersion(memory.id)
                logger.w(LogTags.DATA, "Optimistic lock conflict: memory ${memory.id}")
                return AppResult.failure(
                    AppError.Business.OptimisticLockConflict(
                        entity = "Memory",
                        id = memory.id,
                        expectedVersion = memory.version - 1,
                        actualVersion = currentVersion ?: -1
                    )
                )
            }

            cache.update(memory)
            logger.d(LogTags.DATA, "Updated memory: ${memory.id} (v${memory.version})")
            AppResult.success(memory)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to update memory: ${memory.id}", e)
            AppResult.failure(
                AppError.Database.WriteError(
                    operation = "update",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun upsert(memory: MemoryItem): AppResult<MemoryItem, AppError> {
        return try {
            val exists = memoryDao.exists(memory.id)
            if (exists) update(memory) else insert(memory)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to upsert memory: ${memory.id}", e)
            AppResult.failure(
                AppError.Database.WriteError(
                    operation = "upsert",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun delete(id: Long): AppResult<Unit, AppError> {
        return try {
            val now = clock.nowMillis()
            memoryDao.softDelete(id, now)
            cache.remove(id)
            logger.d(LogTags.DATA, "Soft deleted memory: $id")
            AppResult.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to delete memory: $id", e)
            AppResult.failure(
                AppError.Database.WriteError(
                    operation = "delete",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun hardDelete(id: Long): AppResult<Unit, AppError> {
        return try {
            memoryDao.hardDelete(id)
            cache.remove(id)
            logger.d(LogTags.DATA, "Hard deleted memory: $id")
            AppResult.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to hard delete memory: $id", e)
            AppResult.failure(
                AppError.Database.WriteError(
                    operation = "hardDelete",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    // ========== Batch Operations ==========

    override suspend fun insertBatch(memories: List<MemoryItem>): AppResult<List<MemoryItem>, AppError> {
        if (memories.isEmpty()) return AppResult.success(emptyList())

        return try {
            val entities = memories.map { it.toEntity() }
            memoryDao.insertAll(entities)
            cache.putAll(memories)
            logger.d(LogTags.DATA, "Inserted ${memories.size} memories")
            AppResult.success(memories)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to insert batch", e)
            AppResult.failure(
                AppError.Database.WriteError(
                    operation = "insertBatch",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun updateBatch(memories: List<MemoryItem>): AppResult<Int, AppError> {
        if (memories.isEmpty()) return AppResult.success(0)

        return try {
            var successCount = 0
            for (memory in memories) {
                val entity = memory.toEntity()
                val result = memoryDao.updateSimple(entity)
                if (result > 0) {
                    successCount++
                    cache.update(memory)
                }
            }
            logger.d(LogTags.DATA, "Batch updated $successCount/${memories.size} memories")
            AppResult.success(successCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to batch update", e)
            AppResult.failure(
                AppError.Database.WriteError(
                    operation = "updateBatch",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun deleteBatch(ids: List<Long>): AppResult<Int, AppError> {
        if (ids.isEmpty()) return AppResult.success(0)

        return try {
            val now = clock.nowMillis()
            val count = memoryDao.softDeleteBatch(ids, now)
            cache.removeAll(ids)
            logger.d(LogTags.DATA, "Soft deleted $count memories")
            AppResult.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to batch delete", e)
            AppResult.failure(
                AppError.Database.WriteError(
                    operation = "deleteBatch",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    // ========== Query Operations ==========

    override suspend fun getAll(limit: Int, offset: Int): AppResult<List<MemoryItem>, AppError> {
        return try {
            val entities = memoryDao.getAll()
            val items = entities.toDomainList()
                .drop(offset)
                .take(limit)
            AppResult.success(items)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to get all memories", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "getAll",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun getByType(type: MemoryType, limit: Int): AppResult<List<MemoryItem>, AppError> {
        return try {
            val entities = memoryDao.getByType(type.name, limit)
            AppResult.success(entities.toDomainList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to get memories by type", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "getByType",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun getByState(state: ProcessingState, limit: Int): AppResult<List<MemoryItem>, AppError> {
        return try {
            val entities = memoryDao.getByStates(listOf(state.name), limit)
            AppResult.success(entities.toDomainList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to get memories by state", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "getByState",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun getByStates(states: Set<ProcessingState>, limit: Int): AppResult<List<MemoryItem>, AppError> {
        return try {
            val stateStrings = states.map { it.name }
            val entities = memoryDao.getByStates(stateStrings, limit)
            AppResult.success(entities.toDomainList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to get memories by states", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "getByStates",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun getByTimeRange(startTime: Long, endTime: Long): AppResult<List<MemoryItem>, AppError> {
        return try {
            val entities = memoryDao.getCreatedBetween(startTime, endTime)
            AppResult.success(entities.toDomainList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to get memories by time range", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "getByTimeRange",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun getPinned(): AppResult<List<MemoryItem>, AppError> {
        return try {
            val entities = memoryDao.getPinned()
            val items = entities.toDomainList()
            cache.putAll(items)
            AppResult.success(items)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to get pinned memories", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "getPinned",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun getRecent(limit: Int): AppResult<List<MemoryItem>, AppError> {
        return try {
            val searchableStates = listOf(
                ProcessingState.STORED.name,
                ProcessingState.OPTIMIZED.name
            )
            val entities = memoryDao.getRecentByStates(searchableStates, limit)
            AppResult.success(entities.toDomainList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to get recent memories", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "getRecent",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun getIncompleteTasks(): AppResult<List<MemoryItem>, AppError> {
        return try {
            val entities = memoryDao.getIncompleteTasks()
            AppResult.success(entities.toDomainList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to get incomplete tasks", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "getIncompleteTasks",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun getCompletedTasks(startTime: Long, endTime: Long): AppResult<List<MemoryItem>, AppError> {
        return try {
            val entities = memoryDao.getCreatedBetween(startTime, endTime)
            val completedTasks = entities.toDomainList()
                .filter { it.type == MemoryType.TASK && it.isCompleted }
            AppResult.success(completedTasks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to get completed tasks", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "getCompletedTasks",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun getDecayEligible(limit: Int): AppResult<List<MemoryItem>, AppError> {
        return try {
            val entities = memoryDao.getDecayEligible(limit)
            AppResult.success(entities.toDomainList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to get decay eligible memories", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "getDecayEligible",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun getNeedingEmbedding(limit: Int): AppResult<List<MemoryItem>, AppError> {
        return try {
            val searchableStates = listOf(
                ProcessingState.STORED.name,
                ProcessingState.OPTIMIZED.name
            )
            val entities = memoryDao.getSearchCandidates(searchableStates, limit * 2)
            val needingEmbedding = entities.toDomainList()
                .filter { it.embedding == null }
                .take(limit)
            AppResult.success(needingEmbedding)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to get memories needing embedding", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "getNeedingEmbedding",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    // ========== Reactive Streams ==========

    override fun observeAll(): Flow<List<MemoryItem>> {
        return memoryDao.observeAll().map { entities ->
            entities.toDomainList()
        }
    }

    override fun observeById(id: Long): Flow<MemoryItem?> {
        return memoryDao.observeById(id).map { entity ->
            entity?.toDomain()
        }
    }

    override fun observeByType(type: MemoryType): Flow<List<MemoryItem>> {
        return memoryDao.observeAll().map { entities ->
            entities.toDomainList().filter { it.type == type }
        }
    }

    override fun observeIncompleteTasks(): Flow<List<MemoryItem>> {
        return memoryDao.observeIncompleteTasks().map { entities ->
            entities.toDomainList()
        }
    }

    override fun observePinned(): Flow<List<MemoryItem>> {
        return memoryDao.observePinned().map { entities ->
            entities.toDomainList()
        }
    }

    override fun observeCount(): Flow<Int> {
        return memoryDao.observeAll().map { it.size }
    }

    // ========== Statistics ==========

    override suspend fun count(): AppResult<Int, AppError> {
        return try {
            val count = memoryDao.countActive()
            AppResult.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to count memories", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "count",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun countByType(type: MemoryType): AppResult<Int, AppError> {
        return try {
            val counts = memoryDao.countByType()
            val count = counts.find { it.type == type.name }?.count ?: 0
            AppResult.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to count by type", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "countByType",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun countByState(state: ProcessingState): AppResult<Int, AppError> {
        return try {
            val count = memoryDao.countByState(state.name)
            AppResult.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to count by state", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "countByState",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun exists(id: Long): AppResult<Boolean, AppError> {
        return try {
            val exists = cache.contains(id) || memoryDao.exists(id)
            AppResult.success(exists)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to check existence", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "exists",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    // ========== Search Support ==========

    override suspend fun getSearchable(): AppResult<List<MemoryItem>, AppError> {
        return try {
            val searchableStates = listOf(
                ProcessingState.STORED.name,
                ProcessingState.OPTIMIZED.name
            )
            val entities = memoryDao.getSearchCandidates(searchableStates, 10000)
            AppResult.success(entities.toDomainList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to get searchable memories", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "getSearchable",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun searchByKeyword(query: String, limit: Int): AppResult<List<MemoryItem>, AppError> {
        // Deprecated: Use searchFts() for better performance
        return searchFts(query, limit)
    }

    override suspend fun searchFts(query: String, limit: Int): AppResult<List<MemoryItem>, AppError> {
        return try {
            // Sanitize query for FTS (escape special characters)
            val sanitizedQuery = sanitizeFtsQuery(query)
            if (sanitizedQuery.isBlank()) {
                return AppResult.success(emptyList())
            }

            val entities = memoryDao.searchFtsFull(sanitizedQuery, limit)
            AppResult.success(entities.toDomainList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "FTS search failed for '$query'", e)
            // Fallback to basic search on FTS error
            fallbackSearch(query, limit)
        }
    }

    override suspend fun searchFtsPrefix(query: String, limit: Int): AppResult<List<MemoryItem>, AppError> {
        return try {
            val sanitizedQuery = sanitizeFtsQuery(query)
            if (sanitizedQuery.isBlank()) {
                return AppResult.success(emptyList())
            }

            val entities = memoryDao.searchFtsPrefix(sanitizedQuery, limit)
            AppResult.success(entities.toDomainList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "FTS prefix search failed", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "searchFtsPrefix",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun countFtsMatches(query: String): AppResult<Int, AppError> {
        return try {
            val sanitizedQuery = sanitizeFtsQuery(query)
            if (sanitizedQuery.isBlank()) {
                return AppResult.success(0)
            }

            val count = memoryDao.countFtsMatches(sanitizedQuery)
            AppResult.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(LogTags.DATA, "FTS count failed", e)
            AppResult.success(0) // Return 0 on error to trigger fuzzy search
        }
    }

    /**
     * Sanitize query for FTS4 syntax
     * - Remove special characters that have meaning in FTS
     * - Convert to lowercase
     */
    private fun sanitizeFtsQuery(query: String): String {
        return query
            .lowercase()
            .trim()
            // Remove FTS special characters: " * - OR AND NEAR
            .replace(Regex("[\"*\\-()]"), " ")
            // Collapse multiple spaces
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Fallback search when FTS fails (e.g., malformed query)
     */
    private suspend fun fallbackSearch(query: String, limit: Int): AppResult<List<MemoryItem>, AppError> {
        return try {
            val normalizedQuery = query.lowercase().trim()
            val searchableStates = listOf(
                ProcessingState.STORED.name,
                ProcessingState.OPTIMIZED.name
            )
            // Limit fallback to recent memories only
            val entities = memoryDao.getSearchCandidates(searchableStates, 500)
            val results = entities.toDomainList()
                .filter { it.normalizedContent.contains(normalizedQuery) }
                .take(limit)
            AppResult.success(results)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "fallbackSearch",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    // ========== Maintenance ==========

    override suspend fun cleanupTombstoned(cutoffTime: Long): AppResult<Int, AppError> {
        return try {
            val count = memoryDao.deleteTombstonedOlderThan(cutoffTime)
            logger.i(LogTags.DATA, "Cleaned up $count tombstoned memories")
            AppResult.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to cleanup tombstoned", e)
            AppResult.failure(
                AppError.Database.WriteError(
                    operation = "cleanupTombstoned",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun vacuum(): AppResult<Unit, AppError> {
        return try {
            // Room doesn't expose VACUUM directly
            // This would need to be done via RoomDatabase.query("VACUUM")
            logger.i(LogTags.DATA, "Vacuum requested (not implemented)")
            AppResult.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to vacuum", e)
            AppResult.failure(
                AppError.Database.WriteError(
                    operation = "vacuum",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    override suspend fun getDatabaseSize(): AppResult<Long, AppError> {
        return try {
            // Would need access to database file to get actual size
            AppResult.success(0L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DATA, "Failed to get database size", e)
            AppResult.failure(
                AppError.Database.ReadError(
                    operation = "getDatabaseSize",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    // ========== Cache Management (Not in interface) ==========

    /**
     * Clear cache (for testing or memory pressure)
     */
    fun clearCache() {
        cache.clear()
        logger.d(LogTags.DATA, "Cache cleared")
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats() = cache.getStats()
}
