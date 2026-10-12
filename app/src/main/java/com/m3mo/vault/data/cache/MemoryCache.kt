package com.m3mo.vault.data.cache

import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.domain.model.MemoryItem
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * MemoryCache - In-memory cache for hot memories (L1 Cache)
 *
 * Features:
 * - O(1) LRU eviction using LinkedHashMap with accessOrder=true
 * - Thread-safe with ReentrantReadWriteLock
 * - TTL-based expiration
 * - Automatic pinned memory caching
 * - Statistics tracking
 *
 * Architecture:
 * ```
 * Request → [L1 Cache (MemoryCache)] → [L2 Database (Room)]
 *               ↓ miss                    ↓
 *           [Fetch from DB]          [Return & Cache]
 * ```
 *
 * Performance improvement:
 * - Previous: O(N log N) eviction with sortedBy
 * - Now: O(1) eviction with LinkedHashMap
 */
class MemoryCache(
    private val policy: CachePolicy = CachePolicy.STANDARD,
    private val clock: Clock
) {
    /**
     * Cache entry with metadata
     */
    private data class CacheEntry(
        val item: MemoryItem,
        val cachedAt: Long
    ) {
        fun isExpired(now: Long, ttlMillis: Long): Boolean {
            return (now - cachedAt) > ttlMillis
        }
    }

    /**
     * LRU cache using LinkedHashMap with accessOrder = true
     * When accessOrder is true, iterating starts from least-recently-accessed
     */
    private val cache: LinkedHashMap<Long, CacheEntry> = object : LinkedHashMap<Long, CacheEntry>(
        policy.maxSize,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CacheEntry>?): Boolean {
            if (eldest != null && size > policy.maxSize) {
                val entry = eldest.value
                if (policy.alwaysCachePinned && entry.item.isPinned) {
                    return false
                }
                evictionCount.incrementAndGet()
                return true
            }
            return false
        }
    }

    private val lock = ReentrantReadWriteLock()

    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)

    /**
     * Get item from cache
     * Returns null if not cached or expired
     */
    fun get(id: Long): MemoryItem? {
        if (policy.isDisabled) {
            missCount.incrementAndGet()
            return null
        }

        val now = clock.nowMillis()

        return lock.read {
            val entry = cache[id]
            when {
                entry == null -> {
                    missCount.incrementAndGet()
                    null
                }
                entry.isExpired(now, policy.ttlMillis) -> {
                    missCount.incrementAndGet()
                    null
                }
                else -> {
                    hitCount.incrementAndGet()
                    entry.item
                }
            }
        }.also { result ->
            if (result == null) {
                lock.write {
                    val entry = cache[id]
                    if (entry != null && entry.isExpired(now, policy.ttlMillis)) {
                        cache.remove(id)
                    }
                }
            }
        }
    }

    /**
     * Get multiple items from cache
     * Returns map of found items (missing items not included)
     */
    fun getAll(ids: List<Long>): Map<Long, MemoryItem> {
        if (policy.isDisabled) {
            missCount.addAndGet(ids.size.toLong())
            return emptyMap()
        }

        val result = mutableMapOf<Long, MemoryItem>()
        val now = clock.nowMillis()
        val expiredIds = mutableListOf<Long>()

        lock.read {
            for (id in ids) {
                val entry = cache[id]
                when {
                    entry == null -> missCount.incrementAndGet()
                    entry.isExpired(now, policy.ttlMillis) -> {
                        expiredIds.add(id)
                        missCount.incrementAndGet()
                    }
                    else -> {
                        hitCount.incrementAndGet()
                        result[id] = entry.item
                    }
                }
            }
        }

        if (expiredIds.isNotEmpty()) {
            lock.write {
                expiredIds.forEach { cache.remove(it) }
            }
        }

        return result
    }

    /**
     * Put item in cache
     */
    fun put(item: MemoryItem) {
        if (policy.isDisabled) return

        val now = clock.nowMillis()
        val entry = CacheEntry(item = item, cachedAt = now)

        lock.write {
            cache[item.id] = entry
        }
    }

    /**
     * Put multiple items in cache
     */
    fun putAll(items: List<MemoryItem>) {
        if (policy.isDisabled) return

        val now = clock.nowMillis()

        lock.write {
            for (item in items) {
                val entry = CacheEntry(item = item, cachedAt = now)
                cache[item.id] = entry
            }
        }
    }

    /**
     * Update item in cache (if present)
     * Returns true if item was in cache and updated
     */
    fun update(item: MemoryItem): Boolean {
        if (policy.isDisabled) return false

        val now = clock.nowMillis()

        return lock.write {
            if (cache.containsKey(item.id)) {
                cache[item.id] = CacheEntry(item = item, cachedAt = now)
                true
            } else {
                false
            }
        }
    }

    /**
     * Remove item from cache
     */
    fun remove(id: Long): MemoryItem? {
        return lock.write {
            cache.remove(id)?.item
        }
    }

    /**
     * Remove multiple items from cache
     */
    fun removeAll(ids: List<Long>) {
        lock.write {
            for (id in ids) {
                cache.remove(id)
            }
        }
    }

    /**
     * Clear entire cache
     */
    fun clear() {
        lock.write {
            cache.clear()
        }
    }

    /**
     * Check if item is in cache (and not expired)
     */
    fun contains(id: Long): Boolean {
        if (policy.isDisabled) return false

        val now = clock.nowMillis()

        return lock.read {
            val entry = cache[id]
            entry != null && !entry.isExpired(now, policy.ttlMillis)
        }
    }

    /**
     * Get current cache size
     */
    val size: Int
        get() = lock.read { cache.size }

    /**
     * Get cache statistics
     */
    fun getStats(): CacheStats {
        return lock.read {
            CacheStats(
                size = cache.size,
                maxSize = policy.maxSize,
                hitCount = hitCount.get(),
                missCount = missCount.get(),
                evictionCount = evictionCount.get(),
                estimatedMemoryBytes = estimateMemoryUsage()
            )
        }
    }

    /**
     * Reset statistics
     */
    fun resetStats() {
        hitCount.set(0)
        missCount.set(0)
        evictionCount.set(0)
    }

    /**
     * Estimate memory usage of cache
     * Rough estimation: ~500 bytes per entry average
     */
    private fun estimateMemoryUsage(): Long {
        return cache.size * 500L
    }

    /**
     * Clean up expired entries (call periodically)
     */
    fun cleanupExpired(): Int {
        val now = clock.nowMillis()
        var removed = 0

        lock.write {
            val iterator = cache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.isExpired(now, policy.ttlMillis)) {
                    iterator.remove()
                    removed++
                }
            }
        }

        return removed
    }
}
