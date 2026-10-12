package com.m3mo.vault.search

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.contract.ISemanticMatcher
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.SearchMode
import com.m3mo.vault.domain.model.SearchQuery
import com.m3mo.vault.domain.model.SearchResult
import com.m3mo.vault.domain.model.SearchResults
import kotlinx.coroutines.CancellationException

/**
 * Coordinates keyword, TF-IDF, and hybrid search.
 *
 * Hybrid search first selects keyword candidates through KeywordMatcher, then
 * computes TF-IDF scores for those candidates. Reranking cannot recover records
 * excluded by candidate selection.
 *
 * HybridScore = (KeywordScore * 0.4) + (SemanticScore * 0.6)
 *
 * The hybrid path adds bonuses for recent records, high weights, and Pin.
 * Query filters control archived records, types, and tags.
 */
class SearchEngine(
    private val repository: IMemoryRepository,
    private val semanticMatcher: ISemanticMatcher,
    private val keywordMatcher: KeywordMatcher,
    private val clock: Clock,
    private val logger: Logger
) {
    companion object {
        private const val KEYWORD_WEIGHT = 0.4f
        private const val SEMANTIC_WEIGHT = 0.6f
        private const val RECENCY_BONUS = 0.1f
        private const val HIGH_WEIGHT_BONUS = 0.1f
        private const val PINNED_BONUS = 0.05f
        private const val RECENCY_DAYS = 7
        private const val HIGH_WEIGHT_THRESHOLD = 12
        private const val MS_PER_DAY = 24 * 60 * 60 * 1000L
    }

    /**
     * Search memories
     *
     * @param query Search query with parameters
     * @return Search results or error
     */
    suspend fun search(query: SearchQuery): AppResult<SearchResults, AppError> {
        val startTime = clock.nowMillis()

        return try {
            val results = when (query.mode) {
                SearchMode.KEYWORD -> searchKeywordOnly(query)
                SearchMode.SEMANTIC -> searchSemanticOnly(query)
                SearchMode.HYBRID -> searchHybrid(query)
            }

            val filtered = filterResults(results, query)

            val searchTime = clock.nowMillis() - startTime
            logger.d(LogTags.SEARCH, "Search '${query.text}' returned ${filtered.size} results in ${searchTime}ms")

            AppResult.success(SearchResults(
                query = query,
                results = filtered.take(query.limit),
                totalMatches = filtered.size,
                searchTimeMs = searchTime
            ))

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.SEARCH, "Search failed", e)
            AppResult.failure(
                AppError.Internal.UnexpectedError(
                    message = "Search failed",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    /**
     * Quick search using FTS (fastest)
     */
    suspend fun quickSearch(queryText: String, limit: Int = 10): List<SearchResult> {
        return keywordMatcher.search(queryText, limit)
    }

    /**
     * Smart search with auto-fallback
     */
    suspend fun smartSearch(queryText: String, limit: Int = 20): List<SearchResult> {
        return keywordMatcher.smartSearch(queryText, limit)
    }

    /**
     * Find similar memories
     */
    suspend fun findSimilar(memory: MemoryItem, limit: Int = 10): List<SearchResult> {
        val keywords = memory.content.split(" ")
            .filter { it.length > 3 }
            .take(5)
            .joinToString(" ")

        val candidatesResult = repository.searchFts(keywords, limit * 3)
        val candidates = when (candidatesResult) {
            is AppResult.Success -> candidatesResult.value.filter { it.id != memory.id }
            is AppResult.Failure -> {
                val recentResult = repository.getRecent(limit * 3)
                when (recentResult) {
                    is AppResult.Success -> recentResult.value.filter { it.id != memory.id }
                    is AppResult.Failure -> return emptyList()
                }
            }
        }

        return semanticMatcher.findSimilar(memory, candidates, limit)
    }

    /**
     * Search by tag using FTS
     */
    suspend fun searchByTag(tag: String): AppResult<List<SearchResult>, AppError> {
        val results = keywordMatcher.searchByTag(tag)
        return AppResult.success(results)
    }


    /**
     * Keyword-only search (uses FTS)
     */
    private suspend fun searchKeywordOnly(query: SearchQuery): List<SearchResult> {
        val keywordResults = keywordMatcher.search(query.text, query.limit * 2)

        return keywordResults
            .map { result -> applyBonuses(result) }
            .filter { it.score >= query.minScore }
            .sortedByDescending { it.score }
    }

    /**
     * Semantic-only search
     */
    private suspend fun searchSemanticOnly(query: SearchQuery): List<SearchResult> {
        val candidatesResult = repository.searchFts(query.text, query.limit * 3)
        val candidates = when (candidatesResult) {
            is AppResult.Success -> candidatesResult.value
            is AppResult.Failure -> {
                val recentResult = repository.getRecent(query.limit * 3)
                when (recentResult) {
                    is AppResult.Success -> recentResult.value
                    is AppResult.Failure -> return emptyList()
                }
            }
        }

        if (candidates.isEmpty()) return emptyList()

        val semanticQuery = SearchQuery(
            text = query.text,
            limit = query.limit,
            minScore = query.minScore,
            mode = SearchMode.SEMANTIC
        )

        val semanticResults = semanticMatcher.search(semanticQuery, candidates)

        return semanticResults.results
            .map { result -> applyBonuses(result) }
            .filter { it.score >= query.minScore }
            .sortedByDescending { it.score }
    }

    /**
     * Hybrid search (FTS keyword + semantic)
     */
    private suspend fun searchHybrid(query: SearchQuery): List<SearchResult> {
        val keywordResults = keywordMatcher.search(query.text, query.limit * 2)
        val keywordScores = keywordResults.associate { it.memory.id to it.score }

        val candidates = keywordResults.map { it.memory }

        val semanticScores = if (candidates.isNotEmpty()) {
            val semanticQuery = SearchQuery(
                text = query.text,
                limit = query.limit * 2,
                minScore = 0.1f,
                mode = SearchMode.SEMANTIC
            )
            val semanticResults = semanticMatcher.search(semanticQuery, candidates)
            semanticResults.results.associate { it.memory.id to it.score }
        } else {
            emptyMap()
        }

        val allMemoryIds = (keywordScores.keys + semanticScores.keys).toSet()
        val memoryMap = candidates.associateBy { it.id }

        val hybridResults = allMemoryIds.mapNotNull { memoryId ->
            val memory = memoryMap[memoryId] ?: return@mapNotNull null
            val keywordScore = keywordScores[memoryId] ?: 0f
            val semanticScore = semanticScores[memoryId] ?: 0f

            val hybridScore = (keywordScore * KEYWORD_WEIGHT) + (semanticScore * SEMANTIC_WEIGHT)

            if (hybridScore < query.minScore) return@mapNotNull null

            val keywordResult = keywordResults.find { it.memory.id == memoryId }

            SearchResult.hybrid(
                memory = memory,
                semanticScore = semanticScore,
                keywordScore = keywordScore,
                matchedTerms = keywordResult?.matchedTerms ?: emptyList()
            )
        }

        return hybridResults
            .map { result -> applyBonuses(result) }
            .sortedByDescending { it.score }
    }

    /**
     * Apply relevance bonuses based on memory properties
     */
    private fun applyBonuses(result: SearchResult): SearchResult {
        var bonus = 0f
        val memory = result.memory
        val now = clock.nowMillis()

        val daysSinceAccess = (now - memory.lastAccessedAt) / MS_PER_DAY
        if (daysSinceAccess < RECENCY_DAYS) {
            bonus += RECENCY_BONUS
        }

        if (memory.weight >= HIGH_WEIGHT_THRESHOLD) {
            bonus += HIGH_WEIGHT_BONUS
        }

        if (memory.isPinned) {
            bonus += PINNED_BONUS
        }

        val newScore = (result.score + bonus).coerceAtMost(1f)

        return SearchResult(
            memory = memory,
            score = newScore,
            matchType = result.matchType,
            matchPositions = result.matchPositions,
            matchedTerms = result.matchedTerms,
            snippet = result.snippet
        )
    }

    /**
     * Filter results based on query parameters
     */
    private fun filterResults(results: List<SearchResult>, query: SearchQuery): List<SearchResult> {
        var filtered = results

        query.types?.let { types ->
            filtered = filtered.filter { it.memory.type in types }
        }

        query.tags?.let { tags ->
            filtered = filtered.filter { result ->
                result.memory.tags.any { it in tags }
            }
        }

        if (!query.includeArchived) {
            filtered = filtered.filter {
                it.memory.state != com.m3mo.vault.domain.model.ProcessingState.ARCHIVED
            }
        }

        return filtered
    }
}
