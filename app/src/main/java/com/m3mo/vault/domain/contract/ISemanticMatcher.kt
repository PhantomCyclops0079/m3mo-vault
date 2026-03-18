package com.m3mo.vault.domain.contract

import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.SearchQuery
import com.m3mo.vault.domain.model.SearchResult
import com.m3mo.vault.domain.model.SearchResults

/**
 * ISemanticMatcher - Semantic Search Interface
 *
 * Provides semantic similarity matching using text embeddings.
 * The implementation is pluggable to support different backends:
 * - TF-IDF (low-end devices)
 * - Word2Vec (mid-range devices)
 * - BERT/Sentence Transformers (high-end devices)
 *
 * This abstraction enables:
 * - Resource-aware algorithm selection
 * - Easy testing with mock implementations
 * - Future upgrades without changing consumers
 */
interface ISemanticMatcher {

    /**
     * Search for semantically similar memories
     *
     * @param query Search query with parameters
     * @param candidates Memories to search within
     * @return Ranked search results
     */
    suspend fun search(
        query: SearchQuery,
        candidates: List<MemoryItem>
    ): SearchResults

    /**
     * Calculate similarity between query and a single memory
     *
     * @param query Query text
     * @param memory Memory to compare
     * @return Similarity score (0.0 to 1.0)
     */
    suspend fun similarity(query: String, memory: MemoryItem): Float

    /**
     * Find memories similar to a given memory
     *
     * @param memory Reference memory
     * @param candidates Pool of memories to search
     * @param limit Maximum results
     * @return Similar memories ranked by similarity
     */
    suspend fun findSimilar(
        memory: MemoryItem,
        candidates: List<MemoryItem>,
        limit: Int = 10
    ): List<SearchResult>

    /**
     * Generate embedding vector for content
     *
     * @param content Text to embed
     * @return Embedding as ByteArray (binary format for BLOB storage)
     */
    suspend fun embed(content: String): ByteArray

    /**
     * Batch embed multiple contents
     *
     * @param contents List of texts to embed
     * @return Map of content to embedding
     */
    suspend fun embedBatch(contents: List<String>): Map<String, ByteArray>

    /**
     * Calculate cosine similarity between two embeddings
     *
     * @param embedding1 First embedding (binary)
     * @param embedding2 Second embedding (binary)
     * @return Cosine similarity (-1.0 to 1.0)
     */
    fun cosineSimilarity(embedding1: ByteArray, embedding2: ByteArray): Float

    /**
     * Get the current matcher type/algorithm
     */
    fun getMatcherType(): SemanticMatcherType

    /**
     * Get matcher statistics
     */
    fun getStats(): MatcherStats
}

/**
 * Types of semantic matchers
 */
enum class SemanticMatcherType {
    /**
     * TF-IDF based matching
     * - Fast, low memory
     * - Good for exact/keyword matches
     * - Best for low-end devices
     */
    TF_IDF,

    /**
     * Word2Vec based matching
     * - Balanced performance
     * - Better semantic understanding
     * - Best for mid-range devices
     */
    WORD2VEC,

    /**
     * BERT/Transformer based matching
     * - Best semantic understanding
     * - Higher resource usage
     * - Best for high-end devices
     */
    TRANSFORMER,

    /**
     * Hybrid combining multiple approaches
     */
    HYBRID,

    /**
     * Mock implementation for testing
     */
    MOCK
}

/**
 * Matcher performance statistics
 */
data class MatcherStats(
    /**
     * Total searches performed
     */
    val totalSearches: Long,

    /**
     * Total embeddings generated
     */
    val totalEmbeddings: Long,

    /**
     * Average search time in milliseconds
     */
    val avgSearchTimeMs: Long,

    /**
     * Average embedding time in milliseconds
     */
    val avgEmbedTimeMs: Long,

    /**
     * Cache hit rate (0.0 to 1.0)
     */
    val cacheHitRate: Float,

    /**
     * Current embedding dimension
     */
    val embeddingDimension: Int
) {
    companion object {
        fun empty(): MatcherStats = MatcherStats(
            totalSearches = 0,
            totalEmbeddings = 0,
            avgSearchTimeMs = 0,
            avgEmbedTimeMs = 0,
            cacheHitRate = 0f,
            embeddingDimension = 0
        )
    }
}

/**
 * Configuration for semantic matcher
 */
data class SemanticMatcherConfig(
    /**
     * Preferred matcher type (may be overridden by resource constraints)
     */
    val preferredType: SemanticMatcherType = SemanticMatcherType.TF_IDF,

    /**
     * Enable embedding cache
     */
    val enableCache: Boolean = true,

    /**
     * Maximum cache size (number of embeddings)
     */
    val maxCacheSize: Int = 1000,

    /**
     * Minimum similarity score to include in results
     */
    val minSimilarityScore: Float = 0.3f,

    /**
     * Enable automatic algorithm fallback on resource pressure
     */
    val enableFallback: Boolean = true,

    /**
     * Maximum time budget for search (milliseconds)
     */
    val searchTimeoutMs: Long = 5000,

    /**
     * Number of top results to return by default
     */
    val defaultTopK: Int = 20
) {
    companion object {
        fun default(): SemanticMatcherConfig = SemanticMatcherConfig()

        fun lowMemory(): SemanticMatcherConfig = SemanticMatcherConfig(
            preferredType = SemanticMatcherType.TF_IDF,
            maxCacheSize = 500,
            enableFallback = true
        )

        fun highQuality(): SemanticMatcherConfig = SemanticMatcherConfig(
            preferredType = SemanticMatcherType.TRANSFORMER,
            maxCacheSize = 2000,
            searchTimeoutMs = 10000
        )
    }
}

/**
 * Factory for creating semantic matchers
 */
interface ISemanticMatcherFactory {
    /**
     * Create matcher with default config
     */
    fun create(): ISemanticMatcher

    /**
     * Create matcher with specific type
     */
    fun create(type: SemanticMatcherType): ISemanticMatcher

    /**
     * Create matcher with custom config
     */
    fun create(config: SemanticMatcherConfig): ISemanticMatcher

    /**
     * Get recommended matcher type for current device
     */
    fun recommendedType(): SemanticMatcherType
}
