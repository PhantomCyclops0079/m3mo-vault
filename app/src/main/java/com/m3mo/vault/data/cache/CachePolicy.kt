package com.m3mo.vault.data.cache

/**
 * CachePolicy - Configuration for memory cache behavior
 *
 * Controls:
 * - Maximum cache size
 * - TTL (time-to-live)
 * - Eviction strategy
 * - What gets cached automatically
 */
data class CachePolicy(
    /**
     * Maximum number of items in cache
     * Default: 100 (balance between memory and hit rate)
     */
    val maxSize: Int = DEFAULT_MAX_SIZE,

    /**
     * Time-to-live for cached items (millis)
     * Items older than this are considered stale
     * Default: 5 minutes
     */
    val ttlMillis: Long = DEFAULT_TTL_MILLIS,

    /**
     * Whether to cache search results automatically
     */
    val cacheSearchResults: Boolean = true,

    /**
     * Whether to cache recently created memories
     */
    val cacheRecentlyCreated: Boolean = true,

    /**
     * Whether to cache pinned memories always
     */
    val alwaysCachePinned: Boolean = true,

    /**
     * Minimum weight threshold for auto-caching
     * Memories with weight >= this are more likely to be cached
     */
    val minWeightForAutoCache: Int = 8,

    /**
     * Maximum memory usage before aggressive eviction (bytes)
     * When exceeded, cache will shrink aggressively
     */
    val maxMemoryBytes: Long = DEFAULT_MAX_MEMORY_BYTES
) {
    companion object {
        const val DEFAULT_MAX_SIZE = 100
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L  // 5 minutes
        const val DEFAULT_MAX_MEMORY_BYTES = 10 * 1024 * 1024L  // 10 MB

        /**
         * Minimal cache for low-memory devices
         */
        val MINIMAL = CachePolicy(
            maxSize = 20,
            ttlMillis = 2 * 60 * 1000L,
            cacheSearchResults = false,
            maxMemoryBytes = 2 * 1024 * 1024L
        )

        /**
         * Standard cache for normal devices
         */
        val STANDARD = CachePolicy()

        /**
         * Aggressive cache for high-memory devices
         */
        val AGGRESSIVE = CachePolicy(
            maxSize = 500,
            ttlMillis = 15 * 60 * 1000L,
            maxMemoryBytes = 50 * 1024 * 1024L
        )

        /**
         * No caching (for testing or debugging)
         */
        val DISABLED = CachePolicy(
            maxSize = 0,
            ttlMillis = 0,
            cacheSearchResults = false,
            cacheRecentlyCreated = false,
            alwaysCachePinned = false
        )
    }

    /**
     * Check if caching is effectively disabled
     */
    val isDisabled: Boolean
        get() = maxSize <= 0 || ttlMillis <= 0

    /**
     * Create a copy with adjusted size based on available memory
     */
    fun adjustForMemory(availableMemoryMb: Int): CachePolicy {
        return when {
            availableMemoryMb < 512 -> copy(
                maxSize = (maxSize * 0.3).toInt().coerceAtLeast(10),
                maxMemoryBytes = maxMemoryBytes / 4
            )
            availableMemoryMb < 1024 -> copy(
                maxSize = (maxSize * 0.5).toInt(),
                maxMemoryBytes = maxMemoryBytes / 2
            )
            availableMemoryMb < 2048 -> this
            else -> copy(
                maxSize = (maxSize * 1.5).toInt(),
                maxMemoryBytes = maxMemoryBytes * 2
            )
        }
    }
}

/**
 * Cache statistics for monitoring
 */
data class CacheStats(
    val size: Int,
    val maxSize: Int,
    val hitCount: Long,
    val missCount: Long,
    val evictionCount: Long,
    val estimatedMemoryBytes: Long
) {
    /**
     * Cache hit rate (0.0 to 1.0)
     */
    val hitRate: Float
        get() {
            val total = hitCount + missCount
            return if (total > 0) hitCount.toFloat() / total else 0f
        }

    /**
     * Fill rate (0.0 to 1.0)
     */
    val fillRate: Float
        get() = if (maxSize > 0) size.toFloat() / maxSize else 0f
}
