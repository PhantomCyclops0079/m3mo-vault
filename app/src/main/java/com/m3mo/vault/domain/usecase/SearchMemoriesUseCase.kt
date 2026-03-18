package com.m3mo.vault.domain.usecase

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.core.event.EventBus
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.contract.ISemanticMatcher
import com.m3mo.vault.domain.event.MemoryAccessedEvent
import com.m3mo.vault.domain.event.AccessType
import com.m3mo.vault.domain.event.SearchPerformedEvent
import com.m3mo.vault.domain.model.ProcessingState
import com.m3mo.vault.domain.model.SearchMode
import com.m3mo.vault.domain.model.SearchQuery
import com.m3mo.vault.domain.model.SearchResults

/**
 * SearchMemoriesUseCase - Semantic search for memories
 *
 * Supports three search modes:
 * - SEMANTIC: Embedding-based similarity search
 * - KEYWORD: Traditional text search
 * - HYBRID: Combined approach (default)
 */
class SearchMemoriesUseCase(
    private val repository: IMemoryRepository,
    private val semanticMatcher: ISemanticMatcher,
    private val eventBus: EventBus,
    private val clock: Clock
) {
    /**
     * Execute search with full query options
     */
    suspend fun execute(query: SearchQuery): AppResult<SearchResults, AppError> {
        if (query.text.isBlank()) {
            return AppResult.success(
                SearchResults(
                    query = query,
                    results = emptyList(),
                    totalMatches = 0,
                    searchTimeMs = 0
                )
            )
        }

        val startTime = clock.nowMillis()

        // Get searchable memories
        val candidatesResult = repository.getByStates(
            states = if (query.includeArchived) {
                ProcessingState.searchable()
            } else {
                setOf(ProcessingState.STORED, ProcessingState.OPTIMIZED)
            },
            limit = 1000 // Max candidates for search
        )

        return candidatesResult.suspendFlatMap { candidates ->
            // Filter by type if specified
            val filtered = if (query.types != null) {
                candidates.filter { it.type in query.types }
            } else {
                candidates
            }

            // Filter by tags if specified
            val tagFiltered = if (query.tags != null) {
                filtered.filter { memory ->
                    memory.tags.any { it in query.tags }
                }
            } else {
                filtered
            }

            // Execute search
            val results = semanticMatcher.search(query, tagFiltered)
            val searchTimeMs = clock.nowMillis() - startTime

            // Publish event
            eventBus.publish(
                SearchPerformedEvent(
                    query = query.text,
                    resultCount = results.results.size,
                    searchTimeMs = searchTimeMs
                )
            )

            // Mark top results as accessed
            results.results.take(5).forEach { result ->
                eventBus.publish(
                    MemoryAccessedEvent(
                        memoryId = result.memory.id,
                        accessType = AccessType.SEARCH_RESULT
                    )
                )
            }

            AppResult.success(results.copy(searchTimeMs = searchTimeMs))
        }
    }

    /**
     * Simple text search
     */
    suspend fun search(text: String, limit: Int = 20): AppResult<SearchResults, AppError> {
        return execute(SearchQuery.simple(text).copy(limit = limit))
    }

    /**
     * Semantic-only search
     */
    suspend fun semanticSearch(text: String, limit: Int = 20): AppResult<SearchResults, AppError> {
        return execute(
            SearchQuery(
                text = text,
                limit = limit,
                mode = SearchMode.SEMANTIC
            )
        )
    }

    /**
     * Keyword-only search
     */
    suspend fun keywordSearch(text: String, limit: Int = 20): AppResult<SearchResults, AppError> {
        return execute(
            SearchQuery(
                text = text,
                limit = limit,
                mode = SearchMode.KEYWORD
            )
        )
    }

    /**
     * Record that user clicked on a search result
     */
    suspend fun recordResultClick(memoryId: Long) {
        eventBus.publish(
            MemoryAccessedEvent(
                memoryId = memoryId,
                accessType = AccessType.SEARCH_CLICK
            )
        )
    }
}
