package com.m3mo.vault.behavior

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.contract.IResourceGovernor
import com.m3mo.vault.domain.contract.ISemanticMatcher
import com.m3mo.vault.domain.contract.ResourceCost
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.MemoryType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ConsolidationSuggester - Suggests Memory Consolidation
 */
class ConsolidationSuggester(
    private val repository: IMemoryRepository,
    private val semanticMatcher: ISemanticMatcher,
    private val governor: IResourceGovernor,
    private val clock: Clock,
    private val logger: Logger
) {
    companion object {
        const val DUPLICATE_THRESHOLD = 0.95f
        const val MERGE_THRESHOLD = 0.80f
        const val LINK_THRESHOLD = 0.60f
        private const val MAX_SUGGESTIONS = 20
        private const val MAX_CANDIDATES = 500
    }

    suspend fun getSuggestions(limit: Int = MAX_SUGGESTIONS): AppResult<List<ConsolidationSuggestion>, AppError> =
        withContext(Dispatchers.Default) {
            try {
                if (!governor.canProceed(ResourceCost(memoryMb = 50))) {
                    logger.w(LogTags.SEARCH, "Consolidation skipped: Insufficient resources")
                    return@withContext AppResult.failure(
                        AppError.Internal.UnexpectedError(
                            message = "Review generation delayed",
                            technicalMessage = "Insufficient resources: Memory budget exceeded"
                        ))
                }

                val memories = when (val memoriesResult = repository.getSearchable()) {
                    is AppResult.Success -> memoriesResult.value.take(MAX_CANDIDATES)
                    is AppResult.Failure -> return@withContext AppResult.failure(memoriesResult.error)
                }

                if (memories.size < 2) return@withContext AppResult.success(emptyList())

                val suggestions = mutableListOf<ConsolidationSuggestion>()
                val processed = mutableSetOf<Long>()

                for (memory in memories) {
                    if (memory.id in processed) continue

                    val similar = semanticMatcher.findSimilar(
                        memory = memory,
                        candidates = memories.filter { it.id != memory.id && it.id !in processed },
                        limit = 5
                    )

                    for (result in similar) {
                        val type = classifySuggestion(result.score)
                        if (type != null) {
                            suggestions.add(
                                ConsolidationSuggestion(
                                    primary = memory,
                                    secondary = result.memory,
                                    type = type,
                                    similarity = result.score,
                                    reason = generateReason(memory, result.memory, type)
                                )
                            )
                            if (type == ConsolidationType.DUPLICATE) processed.add(result.memory.id)
                        }
                    }
                    if (suggestions.size >= limit) break
                }

                logger.d(LogTags.SEARCH, "Found ${suggestions.size} consolidation suggestions")
                AppResult.success(suggestions.sortedByDescending { it.similarity }.take(limit))

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(LogTags.SEARCH, "Failed to get consolidation suggestions", e)
                AppResult.failure(AppError.Internal.UnexpectedError("Failed to analyze memories", e.message ?: "Unknown error"))
            }
        }

    suspend fun findRelated(memoryId: Long, limit: Int = 10): AppResult<List<RelatedMemory>, AppError> {
        val memory = when (val memoryResult = repository.getById(memoryId)) {
            is AppResult.Success -> memoryResult.value
            is AppResult.Failure -> return AppResult.failure(memoryResult.error)
        }

        val candidates = when (val candidatesResult = repository.getSearchable()) {
            is AppResult.Success -> candidatesResult.value.filter { it.id != memoryId }
            is AppResult.Failure -> return AppResult.failure(candidatesResult.error)
        }

        val similar = semanticMatcher.findSimilar(memory, candidates, limit)
        val related = similar.map { result ->
            RelatedMemory(
                memory = result.memory,
                similarity = result.score,
                relationship = determineRelationship(memory, result.memory, result.score)
            )
        }

        return AppResult.success(related)
    }

    suspend fun findDuplicates(content: String): AppResult<List<MemoryItem>, AppError> {
        val candidates = when (val candidatesResult = repository.getSearchable()) {
            is AppResult.Success -> candidatesResult.value
            is AppResult.Failure -> return AppResult.failure(candidatesResult.error)
        }

        if (candidates.isEmpty()) return AppResult.success(emptyList())

        val tempMemory = candidates.first().copy(
            id = -1,
            content = content,
            normalizedContent = "",
            embedding = null
        )

        val similar = semanticMatcher.findSimilar(tempMemory, candidates, 5)
        val duplicates = similar
            .filter { it.score >= MERGE_THRESHOLD }
            .map { it.memory }

        return AppResult.success(duplicates)
    }

    suspend fun merge(primaryId: Long, secondaryId: Long, mergedContent: String): AppResult<MemoryItem, AppError> {
        val primary = when (val primaryResult = repository.getById(primaryId)) {
            is AppResult.Success -> primaryResult.value
            is AppResult.Failure -> return AppResult.failure(primaryResult.error)
        }

        val secondary = when (val secondaryResult = repository.getById(secondaryId)) {
            is AppResult.Success -> secondaryResult.value
            is AppResult.Failure -> return AppResult.failure(secondaryResult.error)
        }

        val merged = primary.copy(
            content = mergedContent,
            normalizedContent = "",
            weight = maxOf(primary.weight, secondary.weight),
            accessCount = primary.accessCount + secondary.accessCount,
            tags = (primary.tags + secondary.tags).distinct(),
            isPinned = primary.isPinned || secondary.isPinned,
            updatedAt = clock.nowMillis(),
            version = primary.version + 1,
            embedding = null
        )

        val updateResult = repository.update(merged)
        if (updateResult is AppResult.Failure) return AppResult.failure(updateResult.error)

        repository.update(secondary.copy(
            state = com.m3mo.vault.domain.model.ProcessingState.TOMBSTONED,
            updatedAt = clock.nowMillis(),
            version = secondary.version + 1
        ))

        logger.i(LogTags.DATA, "Merged memories $primaryId and $secondaryId")
        return AppResult.success(merged)
    }

    suspend fun applySuggestion(suggestion: ConsolidationSuggestion, action: ConsolidationAction): AppResult<Unit, AppError> {
        return when (action) {
            ConsolidationAction.KEEP_PRIMARY -> {
                val result = repository.update(suggestion.secondary.copy(
                    state = com.m3mo.vault.domain.model.ProcessingState.TOMBSTONED,
                    updatedAt = clock.nowMillis(),
                    version = suggestion.secondary.version + 1
                ))
                if (result is AppResult.Failure) AppResult.failure(result.error) else AppResult.success(Unit)
            }
            ConsolidationAction.KEEP_SECONDARY -> {
                val result = repository.update(suggestion.primary.copy(
                    state = com.m3mo.vault.domain.model.ProcessingState.TOMBSTONED,
                    updatedAt = clock.nowMillis(),
                    version = suggestion.primary.version + 1
                ))
                if (result is AppResult.Failure) AppResult.failure(result.error) else AppResult.success(Unit)
            }
            ConsolidationAction.IGNORE -> AppResult.success(Unit)
        }
    }

    private fun classifySuggestion(similarity: Float): ConsolidationType? {
        return when {
            similarity >= DUPLICATE_THRESHOLD -> ConsolidationType.DUPLICATE
            similarity >= MERGE_THRESHOLD -> ConsolidationType.MERGE
            similarity >= LINK_THRESHOLD -> ConsolidationType.LINK
            else -> null
        }
    }

    private fun generateReason(primary: MemoryItem, secondary: MemoryItem, type: ConsolidationType): String {
        return when (type) {
            ConsolidationType.DUPLICATE -> "These memories appear to be duplicates"
            ConsolidationType.MERGE -> if (primary.type == secondary.type) "Similar ${primary.type.name.lowercase()} entries could be combined" else "Related content could be merged"
            ConsolidationType.LINK -> "These memories cover related topics"
        }
    }

    private fun determineRelationship(primary: MemoryItem, secondary: MemoryItem, similarity: Float): RelationshipType {
        if (primary.type == MemoryType.FACT && secondary.type == MemoryType.FACT && similarity > MERGE_THRESHOLD) {
            return if (primary.createdAt > secondary.createdAt) RelationshipType.UPDATES else RelationshipType.UPDATED_BY
        }
        if (primary.type == secondary.type) return RelationshipType.SIMILAR
        return RelationshipType.RELATED
    }
}

data class ConsolidationSuggestion(
    val primary: MemoryItem,
    val secondary: MemoryItem,
    val type: ConsolidationType,
    val similarity: Float,
    val reason: String
)

enum class ConsolidationType { DUPLICATE, MERGE, LINK }
enum class ConsolidationAction { KEEP_PRIMARY, KEEP_SECONDARY, IGNORE }
data class RelatedMemory(val memory: MemoryItem, val similarity: Float, val relationship: RelationshipType)
enum class RelationshipType { SIMILAR, RELATED, UPDATES, UPDATED_BY }