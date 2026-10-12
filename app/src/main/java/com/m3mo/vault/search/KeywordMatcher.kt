package com.m3mo.vault.search

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.SearchResult
import com.m3mo.vault.memory.normalize.DefaultNormalizer

/**
 * Provides indexed keyword and prefix searches through the repository.
 * In-memory fuzzy matching supplies a fallback when indexed matches are insufficient.
 */
class KeywordMatcher(
    private val repository: IMemoryRepository,
    private val normalizer: DefaultNormalizer = DefaultNormalizer.instance
) {
    companion object {
        // Threshold to trigger fuzzy search
        private const val FUZZY_TRIGGER_THRESHOLD = 3

        // Maximum memories for fuzzy search (performance limit)
        private const val FUZZY_MAX_CANDIDATES = 500

        // Maximum edit distance for fuzzy matching
        private const val MAX_EDIT_DISTANCE = 2
    }

    /**
     * Search using FTS (primary method)
     *
     * @param query Search query
     * @param limit Maximum results
     * @return Matching results sorted by relevance
     */
    suspend fun search(query: String, limit: Int = 20): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        // Use FTS for fast search
        val ftsResult = repository.searchFts(query, limit)

        return when (ftsResult) {
            is AppResult.Success -> {
                ftsResult.value.map { memory ->
                    val matchedTerms = extractMatchedTerms(query, memory)
                    SearchResult.keyword(
                        memory = memory,
                        score = calculateRelevanceScore(query, memory),
                        matchedTerms = matchedTerms,
                        positions = emptyList() // FTS doesn't return positions
                    )
                }.sortedByDescending { it.score }
            }
            is AppResult.Failure -> emptyList()
        }
    }

    /**
     * Search with prefix matching (for autocomplete)
     *
     * @param prefix Prefix to match
     * @param limit Maximum results
     * @return Matching results
     */
    suspend fun searchPrefix(prefix: String, limit: Int = 10): List<SearchResult> {
        if (prefix.isBlank() || prefix.length < 2) return emptyList()

        val ftsResult = repository.searchFtsPrefix(prefix, limit)

        return when (ftsResult) {
            is AppResult.Success -> {
                ftsResult.value.map { memory ->
                    SearchResult.keyword(
                        memory = memory,
                        score = 0.8f, // Prefix matches get moderate score
                        matchedTerms = listOf(prefix),
                        positions = emptyList()
                    )
                }
            }
            is AppResult.Failure -> emptyList()
        }
    }

    /**
     * Fuzzy search with edit distance tolerance
     *
     * IMPORTANT: This is expensive! Only use when:
     * - FTS returns few results
     * - User explicitly requests fuzzy matching
     *
     * @param query Query with possible typos
     * @param limit Maximum results
     * @return Matching results
     */
    suspend fun searchFuzzy(query: String, limit: Int = 10): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        // First, check if FTS has enough results
        val ftsCountResult = repository.countFtsMatches(query)
        val ftsCount = when (ftsCountResult) {
            is AppResult.Success -> ftsCountResult.value
            is AppResult.Failure -> 0
        }

        // If FTS has enough results, don't do expensive fuzzy search
        if (ftsCount >= FUZZY_TRIGGER_THRESHOLD) {
            return search(query, limit)
        }

        // Get limited recent candidates for fuzzy matching
        val candidatesResult = repository.getRecent(FUZZY_MAX_CANDIDATES)
        val candidates = when (candidatesResult) {
            is AppResult.Success -> candidatesResult.value
            is AppResult.Failure -> return emptyList()
        }

        val queryTerms = normalizer.normalizeAndTokenize(query)
        if (queryTerms.isEmpty()) return emptyList()

        return candidates.mapNotNull { memory ->
            val tokens = normalizer.normalizeAndTokenize(
                memory.normalizedContent.ifBlank { memory.content }
            )

            var totalScore = 0f
            val matchedTerms = mutableListOf<String>()

            for (queryTerm in queryTerms) {
                val bestMatch = tokens.minByOrNull { editDistance(queryTerm, it) }
                val distance = bestMatch?.let { editDistance(queryTerm, it) } ?: Int.MAX_VALUE

                if (distance <= MAX_EDIT_DISTANCE) {
                    val termScore = 1f - (distance.toFloat() / (queryTerm.length + 1))
                    totalScore += termScore
                    if (bestMatch != null) matchedTerms.add(bestMatch)
                }
            }

            if (matchedTerms.isNotEmpty()) {
                val score = totalScore / queryTerms.size
                SearchResult.keyword(
                    memory = memory,
                    score = score.coerceIn(0f, 1f),
                    matchedTerms = matchedTerms,
                    positions = emptyList()
                )
            } else null
        }.sortedByDescending { it.score }.take(limit)
    }

    /**
     * Search by tag (exact match)
     */
    suspend fun searchByTag(tag: String): List<SearchResult> {
        val normalizedTag = normalizer.normalize(tag)

        // Use FTS to search in tags column
        val ftsResult = repository.searchFts(normalizedTag, 100)

        return when (ftsResult) {
            is AppResult.Success -> {
                ftsResult.value
                    .filter { memory ->
                        memory.tags.any { t -> normalizer.normalize(t) == normalizedTag }
                    }
                    .map { memory ->
                        SearchResult.keyword(
                            memory = memory,
                            score = 1.0f,
                            matchedTerms = listOf(tag),
                            positions = emptyList()
                        )
                    }
            }
            is AppResult.Failure -> emptyList()
        }
    }

    /**
     * Smart search: auto-selects best strategy
     *
     * 1. Try FTS exact match
     * 2. If few results, try FTS prefix
     * 3. If still few, try fuzzy (limited)
     */
    suspend fun smartSearch(query: String, limit: Int = 20): List<SearchResult> {
        // Step 1: FTS exact match
        var results = search(query, limit)
        if (results.size >= limit / 2) {
            return results
        }

        // Step 2: FTS prefix match
        val prefixResults = searchPrefix(query, limit - results.size)
        val existingIds = results.map { it.memory.id }.toSet()
        results = results + prefixResults.filter { it.memory.id !in existingIds }

        if (results.size >= limit / 2) {
            return results.take(limit)
        }

        // Step 3: Fuzzy match (limited scope)
        val fuzzyResults = searchFuzzy(query, limit - results.size)
        val allIds = results.map { it.memory.id }.toSet()
        results = results + fuzzyResults.filter { it.memory.id !in allIds }

        return results.take(limit)
    }

    // ========== In-Memory Matching (for provided candidates) ==========

    /**
     * Match against provided candidates (for hybrid search)
     * Use this when candidates are already loaded
     */
    fun matchInMemory(query: String, candidates: List<MemoryItem>): List<SearchResult> {
        if (query.isBlank() || candidates.isEmpty()) return emptyList()

        val queryTerms = normalizer.normalizeAndTokenize(query)
        if (queryTerms.isEmpty()) return emptyList()

        return candidates.mapNotNull { memory ->
            val score = calculateMatchScore(queryTerms, memory)
            if (score > 0) {
                SearchResult.keyword(
                    memory = memory,
                    score = score,
                    matchedTerms = queryTerms.filter { term ->
                        memory.content.lowercase().contains(term)
                    },
                    positions = emptyList()
                )
            } else null
        }.sortedByDescending { it.score }
    }

    // ========== Internal Methods ==========

    /**
     * Calculate relevance score for FTS result
     */
    private fun calculateRelevanceScore(query: String, memory: MemoryItem): Float {
        val queryTerms = normalizer.normalizeAndTokenize(query)
        val content = (memory.normalizedContent.ifBlank { memory.content }).lowercase()

        var score = 0.5f // Base score for FTS match

        // Term frequency bonus
        for (term in queryTerms) {
            val count = content.split(term).size - 1
            score += (count.coerceAtMost(3) * 0.1f)
        }

        // Position bonus (early match = more relevant)
        val firstTermPos = queryTerms.firstOrNull()?.let { content.indexOf(it) } ?: -1
        if (firstTermPos in 0..50) {
            score += 0.1f
        }

        // Weight bonus
        score += (memory.weight / 32f) * 0.1f

        return score.coerceIn(0f, 1f)
    }

    /**
     * Extract which query terms matched
     */
    private fun extractMatchedTerms(query: String, memory: MemoryItem): List<String> {
        val queryTerms = normalizer.normalizeAndTokenize(query)
        val content = memory.content.lowercase()

        return queryTerms.filter { content.contains(it) }
    }

    /**
     * Calculate match score for in-memory matching
     */
    private fun calculateMatchScore(queryTerms: List<String>, memory: MemoryItem): Float {
        val content = (memory.normalizedContent.ifBlank { memory.content }).lowercase()

        var matchedCount = 0
        for (term in queryTerms) {
            if (content.contains(term)) {
                matchedCount++
            }
        }

        if (matchedCount == 0) return 0f

        return matchedCount.toFloat() / queryTerms.size
    }

    /**
     * Calculate Levenshtein edit distance
     * Optimized with early termination
     */
    private fun editDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length

        // Quick reject if length difference is too large
        if (kotlin.math.abs(m - n) > MAX_EDIT_DISTANCE) return Int.MAX_VALUE

        // Use shorter string as columns for memory efficiency
        val (shorter, longer) = if (m < n) s1 to s2 else s2 to s1
        val shortLen = shorter.length
        val longLen = longer.length

        // Only need two rows
        var prevRow = IntArray(shortLen + 1) { it }
        var currRow = IntArray(shortLen + 1)

        for (i in 1..longLen) {
            currRow[0] = i
            var minInRow = currRow[0]

            for (j in 1..shortLen) {
                val cost = if (longer[i - 1] == shorter[j - 1]) 0 else 1
                currRow[j] = minOf(
                    prevRow[j] + 1,      // Deletion
                    currRow[j - 1] + 1,  // Insertion
                    prevRow[j - 1] + cost // Substitution
                )
                minInRow = minOf(minInRow, currRow[j])
            }

            // Early termination if minimum exceeds threshold
            if (minInRow > MAX_EDIT_DISTANCE) return Int.MAX_VALUE

            // Swap rows
            val temp = prevRow
            prevRow = currRow
            currRow = temp
        }

        return prevRow[shortLen]
    }
}
