package com.m3mo.vault.domain.model

/**
 * SearchResult - Represents a memory matched by search
 *
 * Contains the matched memory plus relevance scoring and
 * match metadata for highlighting and ranking.
 */
data class SearchResult(
    /**
     * The matched memory
     */
    val memory: MemoryItem,

    /**
     * Relevance score (0.0 to 1.0)
     * Higher = more relevant to the query
     */
    val score: Float,

    /**
     * Type of match that produced this result
     */
    val matchType: MatchType,

    /**
     * Positions in content where matches occurred
     * Used for highlighting in UI
     */
    val matchPositions: List<IntRange>,

    /**
     * Matched keywords/terms (for keyword search)
     */
    val matchedTerms: List<String>,

    /**
     * Snippet of content around the match
     */
    val snippet: String?
) {
    companion object {
        /**
         * Create a semantic search result
         */
        fun semantic(
            memory: MemoryItem,
            score: Float
        ): SearchResult {
            return SearchResult(
                memory = memory,
                score = score,
                matchType = MatchType.SEMANTIC,
                matchPositions = emptyList(),
                matchedTerms = emptyList(),
                snippet = null
            )
        }

        /**
         * Create a keyword search result
         */
        fun keyword(
            memory: MemoryItem,
            score: Float,
            matchedTerms: List<String>,
            positions: List<IntRange>
        ): SearchResult {
            return SearchResult(
                memory = memory,
                score = score,
                matchType = MatchType.KEYWORD,
                matchPositions = positions,
                matchedTerms = matchedTerms,
                snippet = null
            )
        }

        /**
         * Create a hybrid search result
         */
        fun hybrid(
            memory: MemoryItem,
            semanticScore: Float,
            keywordScore: Float,
            matchedTerms: List<String>
        ): SearchResult {
            val combinedScore = (semanticScore * 0.7f) + (keywordScore * 0.3f)
            return SearchResult(
                memory = memory,
                score = combinedScore,
                matchType = MatchType.HYBRID,
                matchPositions = emptyList(),
                matchedTerms = matchedTerms,
                snippet = null
            )
        }
    }

    /**
     * Generate a snippet around the first match
     */
    fun withSnippet(maxLength: Int = 100): SearchResult {
        if (matchPositions.isEmpty()) {
            val snippetText = if (memory.content.length > maxLength) {
                memory.content.take(maxLength - 3) + "..."
            } else {
                memory.content
            }
            return copy(snippet = snippetText)
        }

        val firstMatch = matchPositions.first()
        val start = (firstMatch.first - maxLength / 2).coerceAtLeast(0)
        val end = (start + maxLength).coerceAtMost(memory.content.length)

        val snippetText = buildString {
            if (start > 0) append("...")
            append(memory.content.substring(start, end))
            if (end < memory.content.length) append("...")
        }

        return copy(snippet = snippetText)
    }
}

/**
 * Type of search match
 */
enum class MatchType {
    /**
     * Matched via semantic similarity (embeddings)
     */
    SEMANTIC,

    /**
     * Matched via keyword/text search
     */
    KEYWORD,

    /**
     * Combined semantic + keyword match
     */
    HYBRID,

    /**
     * Exact content match
     */
    EXACT
}

/**
 * Search query representation
 */
data class SearchQuery(
    /**
     * Raw query text
     */
    val text: String,

    /**
     * Maximum results to return
     */
    val limit: Int = 20,

    /**
     * Minimum score threshold (0.0 to 1.0)
     */
    val minScore: Float = 0.3f,

    /**
     * Filter by memory types
     */
    val types: Set<MemoryType>? = null,

    /**
     * Filter by tags
     */
    val tags: Set<String>? = null,

    /**
     * Include archived memories
     */
    val includeArchived: Boolean = false,

    /**
     * Search mode
     */
    val mode: SearchMode = SearchMode.HYBRID
) {
    companion object {
        fun simple(text: String): SearchQuery {
            return SearchQuery(text = text)
        }

        fun semantic(text: String, limit: Int = 20): SearchQuery {
            return SearchQuery(
                text = text,
                limit = limit,
                mode = SearchMode.SEMANTIC
            )
        }

        fun keyword(text: String, limit: Int = 20): SearchQuery {
            return SearchQuery(
                text = text,
                limit = limit,
                mode = SearchMode.KEYWORD
            )
        }
    }
}

/**
 * Search execution mode
 */
enum class SearchMode {
    /**
     * Semantic similarity search using embeddings
     */
    SEMANTIC,

    /**
     * Traditional keyword/text search
     */
    KEYWORD,

    /**
     * Combined semantic + keyword (default)
     */
    HYBRID
}

/**
 * Aggregated search results
 */
data class SearchResults(
    /**
     * Query that produced these results
     */
    val query: SearchQuery,

    /**
     * Matched memories sorted by relevance
     */
    val results: List<SearchResult>,

    /**
     * Total matches found (may be > results.size if limited)
     */
    val totalMatches: Int,

    /**
     * Search execution time in milliseconds
     */
    val searchTimeMs: Long
) {
    val isEmpty: Boolean get() = results.isEmpty()
    val size: Int get() = results.size

    fun topResult(): SearchResult? = results.firstOrNull()
}
