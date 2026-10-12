package com.m3mo.vault.memory.semantic

import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.contract.ISemanticMatcher
import com.m3mo.vault.domain.contract.MatcherStats
import com.m3mo.vault.domain.contract.SemanticMatcherType
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.SearchQuery
import com.m3mo.vault.domain.model.SearchResult
import com.m3mo.vault.domain.model.SearchResults
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Implements ISemanticMatcher using TF-IDF vectors and cosine similarity.
 * Caches serialized vectors for reuse.
 */
class TfIdfSemanticMatcher(
    private val vectorizer: TfIdfVectorizer = TfIdfVectorizer(),
    private val config: TfIdfMatcherConfig = TfIdfMatcherConfig.default(),
    private val logger: Logger
) : ISemanticMatcher {

    // Embedding cache: content-based key → embedding (ByteArray)
    // Key strategy: short texts use raw content, long texts use "H:" + hashCode
    private val embeddingCache = ConcurrentHashMap<String, ByteArray>()

    // Statistics
    private val searchCount = AtomicLong(0)
    private val embedCount = AtomicLong(0)
    private var totalSearchTimeMs = AtomicLong(0)
    private var totalEmbedTimeMs = AtomicLong(0)
    private var cacheHits = AtomicLong(0)
    private var cacheMisses = AtomicLong(0)

    override suspend fun search(query: SearchQuery, candidates: List<MemoryItem>): SearchResults {
        val startTime = System.currentTimeMillis()
        searchCount.incrementAndGet()

        if (candidates.isEmpty()) {
            return SearchResults(
                query = query,
                results = emptyList(),
                totalMatches = 0,
                searchTimeMs = 0
            )
        }

        // Ensure vectorizer is fitted
        ensureFitted(candidates)

        // Transform query
        val queryVector = vectorizer.transform(query.text)
        if (queryVector.isEmpty()) {
            return SearchResults(
                query = query,
                results = emptyList(),
                totalMatches = 0,
                searchTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // Calculate similarities
        val results = candidates.mapNotNull { memory ->
            val memoryVector = getOrComputeVector(memory)
            if (memoryVector.isEmpty()) return@mapNotNull null

            val similarity = vectorizer.cosineSimilarity(queryVector, memoryVector)
            if (similarity < config.minSimilarity) return@mapNotNull null

            SearchResult.semantic(
                memory = memory,
                score = similarity
            ).withSnippet()
        }
            .sortedByDescending { it.score }
            .take(query.limit)

        val searchTime = System.currentTimeMillis() - startTime
        totalSearchTimeMs.addAndGet(searchTime)

        logger.d(LogTags.SEMANTIC, "Search '${query.text}' returned ${results.size} results in ${searchTime}ms")

        return SearchResults(
            query = query,
            results = results,
            totalMatches = results.size,
            searchTimeMs = searchTime
        )
    }

    override suspend fun similarity(query: String, memory: MemoryItem): Float {
        ensureFitted(listOf(memory))

        val queryVector = vectorizer.transform(query)
        val memoryVector = getOrComputeVector(memory)

        return vectorizer.cosineSimilarity(queryVector, memoryVector)
    }

    override suspend fun findSimilar(
        memory: MemoryItem,
        candidates: List<MemoryItem>,
        limit: Int
    ): List<SearchResult> {
        if (candidates.isEmpty()) return emptyList()

        ensureFitted(candidates + memory)

        val referenceVector = getOrComputeVector(memory)
        if (referenceVector.isEmpty()) return emptyList()

        return candidates
            .filter { it.id != memory.id }  // Exclude self
            .mapNotNull { candidate ->
                val candidateVector = getOrComputeVector(candidate)
                if (candidateVector.isEmpty()) return@mapNotNull null

                val similarity = vectorizer.cosineSimilarity(referenceVector, candidateVector)
                if (similarity < config.minSimilarity) return@mapNotNull null

                SearchResult.semantic(
                    memory = candidate,
                    score = similarity
                )
            }
            .sortedByDescending { it.score }
            .take(limit)
    }

    override suspend fun embed(content: String): ByteArray {
        val startTime = System.currentTimeMillis()
        embedCount.incrementAndGet()

        // Cache key strategy to minimize collision risk:
        // - Short texts (<= 64 chars): use text itself as key (no collision possible)
        // - Long texts: use hashCode (extremely low collision probability ~1/2^32)
        // Trade-off: slightly more memory for short texts, but zero collision risk
        val cacheKey = computeCacheKey(content)
        embeddingCache[cacheKey]?.let {
            cacheHits.incrementAndGet()
            return it
        }
        cacheMisses.incrementAndGet()

        // Compute embedding
        val vector = vectorizer.transform(content)
        // Direct ByteArray - no Base64 overhead (33% size reduction vs Base64 String)
        val bytes = EmbeddingSerializer.serializeToBytes(vector)

        // Cache if enabled
        if (config.enableCache && embeddingCache.size < config.maxCacheSize) {
            embeddingCache[cacheKey] = bytes
        }

        val embedTime = System.currentTimeMillis() - startTime
        totalEmbedTimeMs.addAndGet(embedTime)

        return bytes
    }

    /**
     * Compute cache key with collision-resistant strategy
     * 
     * For short texts: use the text itself (zero collision risk)
     * For long texts: use "H:" + hashCode (very low collision probability)
     * 
     * Note: hashCode collision probability is ~1/2^32, acceptable for cache
     * but documented here for transparency.
     */
    private fun computeCacheKey(content: String): String {
        return if (content.length <= SHORT_TEXT_THRESHOLD) {
            content  // No collision possible for short texts
        } else {
            "H:${content.hashCode()}"  // Prefix to distinguish from raw text
        }
    }

    override suspend fun embedBatch(contents: List<String>): Map<String, ByteArray> {
        return contents.associateWith { embed(it) }
    }

    override fun cosineSimilarity(embedding1: ByteArray, embedding2: ByteArray): Float {
        val vec1 = EmbeddingSerializer.deserializeFromBytes(embedding1)
        val vec2 = EmbeddingSerializer.deserializeFromBytes(embedding2)
        return vectorizer.cosineSimilarity(vec1, vec2)
    }

    override fun getMatcherType(): SemanticMatcherType = SemanticMatcherType.TF_IDF

    override fun getStats(): MatcherStats {
        val totalSearches = searchCount.get()
        val totalEmbeds = embedCount.get()
        val hits = cacheHits.get()
        val misses = cacheMisses.get()

        return MatcherStats(
            totalSearches = totalSearches,
            totalEmbeddings = totalEmbeds,
            avgSearchTimeMs = if (totalSearches > 0) totalSearchTimeMs.get() / totalSearches else 0,
            avgEmbedTimeMs = if (totalEmbeds > 0) totalEmbedTimeMs.get() / totalEmbeds else 0,
            cacheHitRate = if (hits + misses > 0) hits.toFloat() / (hits + misses) else 0f,
            embeddingDimension = vectorizer.vocabSize
        )
    }

    /**
     * Fit vectorizer on corpus if not already done
     */
    fun fit(memories: List<MemoryItem>) {
        val documents = memories.map { it.normalizedContent.ifBlank { it.content } }
        vectorizer.fit(documents)
        logger.d(LogTags.SEMANTIC, "Fitted vectorizer on ${documents.size} documents, vocab size: ${vectorizer.vocabSize}")
    }

    /**
     * Clear embedding cache
     */
    fun clearCache() {
        embeddingCache.clear()
        cacheHits.set(0)
        cacheMisses.set(0)
    }

    /**
     * Get current cache size
     */
    val cacheSize: Int get() = embeddingCache.size

    // ========== Internal Methods ==========

    private fun ensureFitted(memories: List<MemoryItem>) {
        if (vectorizer.vocabSize == 0) {
            fit(memories)
        }
    }

    private fun getOrComputeVector(memory: MemoryItem): Map<Int, Float> {
        val content = memory.normalizedContent.ifBlank { memory.content }

        // Try to use cached embedding (now ByteArray)
        memory.embedding?.let { cached ->
            return EmbeddingSerializer.deserializeFromBytes(cached)
        }

        // Compute fresh
        return vectorizer.transform(content)
    }

    companion object {
        // Texts shorter than this use full content as cache key (zero collision)
        // Texts longer use hashCode (very low collision probability)
        private const val SHORT_TEXT_THRESHOLD = 64
    }
}

/**
 * Configuration for TF-IDF matcher
 */
data class TfIdfMatcherConfig(
    val minSimilarity: Float = 0.1f,
    val enableCache: Boolean = true,
    val maxCacheSize: Int = 1000
) {
    companion object {
        fun default(): TfIdfMatcherConfig = TfIdfMatcherConfig()

        fun lowMemory(): TfIdfMatcherConfig = TfIdfMatcherConfig(
            maxCacheSize = 500
        )
    }
}
