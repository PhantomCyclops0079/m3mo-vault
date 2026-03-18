package com.m3mo.vault.behavior

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.memory.decay.SmartDecayStrategy

class MemoryBooster(
    private val repository: IMemoryRepository,
    private val decayStrategy: SmartDecayStrategy, // 保持注入，未来可能用到
    private val clock: Clock,
    private val logger: Logger
) {
    companion object {
        const val BOOST_VIEW = 1
        const val BOOST_SEARCH_CLICK = 2
        const val BOOST_EDIT = 3
        const val BOOST_SHARE = 2
        const val BOOST_REVIEW_KEEP = 2
        const val MAX_WEIGHT = 16 // ⚡ 修复点：补充最大权重定义

        private const val BOOST_COOLDOWN_MS = 5 * 60 * 1000L
    }

    private val recentBoosts = mutableMapOf<Long, Long>()

    suspend fun onMemoryViewed(memoryId: Long): AppResult<MemoryItem, AppError> = boostMemory(memoryId, BoostAction.VIEW)
    suspend fun onSearchResultClicked(memoryId: Long): AppResult<MemoryItem, AppError> = boostMemory(memoryId, BoostAction.SEARCH_CLICK)
    suspend fun onMemoryEdited(memoryId: Long): AppResult<MemoryItem, AppError> = boostMemory(memoryId, BoostAction.EDIT)
    suspend fun onMemoryPinned(memoryId: Long): AppResult<MemoryItem, AppError> = boostMemory(memoryId, BoostAction.PIN)
    suspend fun onMemoryShared(memoryId: Long): AppResult<MemoryItem, AppError> = boostMemory(memoryId, BoostAction.SHARE)
    suspend fun onReviewKeep(memoryId: Long): AppResult<MemoryItem, AppError> = boostMemory(memoryId, BoostAction.REVIEW_KEEP)

    suspend fun boostBatch(memoryIds: List<Long>, action: BoostAction): AppResult<Int, AppError> {
        var successCount = 0
        for (id in memoryIds) {
            if (boostMemory(id, action) is AppResult.Success) successCount++
        }
        logger.d(LogTags.DECAY, "Batch boost: $successCount/${memoryIds.size} memories boosted")
        return AppResult.success(successCount)
    }

    suspend fun recordAccess(memoryId: Long): AppResult<MemoryItem, AppError> {
        return when (val memoryResult = repository.getById(memoryId)) {
            is AppResult.Success -> {
                val memory = memoryResult.value
                // ⚡ 修复点：使用标准的 copy 方法实现不可变更新
                val updated = memory.copy(
                    lastAccessedAt = clock.nowMillis(),
                    accessCount = memory.accessCount + 1,
                    updatedAt = clock.nowMillis(),
                    version = memory.version + 1
                )
                repository.update(updated)
            }
            is AppResult.Failure -> AppResult.failure(memoryResult.error)
        }
    }

    private suspend fun boostMemory(memoryId: Long, action: BoostAction): AppResult<MemoryItem, AppError> {
        if (action != BoostAction.PIN && !canBoost(memoryId)) {
            logger.d(LogTags.DECAY, "Boost cooldown active for memory $memoryId")
            return recordAccess(memoryId)
        }

        return when (val memoryResult = repository.getById(memoryId)) {
            is AppResult.Success -> {
                val memory = memoryResult.value

                // ⚡ 修复点：重写加权逻辑，适配新的 MemoryItem 和乐观锁
                val boosted = when (action) {
                    BoostAction.PIN -> {
                        memory.copy(
                            weight = MAX_WEIGHT,
                            isPinned = true,
                            lastAccessedAt = clock.nowMillis(),
                            accessCount = memory.accessCount + 1,
                            updatedAt = clock.nowMillis(),
                            version = memory.version + 1
                        )
                    }
                    else -> {
                        val newWeight = (memory.weight + action.boostAmount).coerceAtMost(MAX_WEIGHT)
                        memory.copy(
                            weight = newWeight,
                            lastAccessedAt = clock.nowMillis(),
                            accessCount = memory.accessCount + 1,
                            updatedAt = clock.nowMillis(),
                            version = memory.version + 1
                        )
                    }
                }

                when (val updateResult = repository.update(boosted)) {
                    is AppResult.Success -> {
                        recordBoostTime(memoryId)
                        logger.d(LogTags.DECAY, "Memory $memoryId boosted: ${action.name} (+${action.boostAmount})")
                        AppResult.success(boosted)
                    }
                    is AppResult.Failure -> AppResult.failure(updateResult.error)
                }
            }
            is AppResult.Failure -> AppResult.failure(memoryResult.error)
        }
    }

    private fun canBoost(memoryId: Long): Boolean {
        val lastBoost = recentBoosts[memoryId] ?: return true
        val elapsed = clock.nowMillis() - lastBoost
        return elapsed >= BOOST_COOLDOWN_MS
    }

    private fun recordBoostTime(memoryId: Long) {
        recentBoosts[memoryId] = clock.nowMillis()
        val cutoff = clock.nowMillis() - (60 * 60 * 1000L)
        recentBoosts.entries.removeIf { it.value < cutoff }
    }
}

enum class BoostAction(val boostAmount: Int) {
    VIEW(MemoryBooster.BOOST_VIEW),
    SEARCH_CLICK(MemoryBooster.BOOST_SEARCH_CLICK),
    EDIT(MemoryBooster.BOOST_EDIT),
    PIN(MemoryBooster.MAX_WEIGHT),
    SHARE(MemoryBooster.BOOST_SHARE),
    REVIEW_KEEP(MemoryBooster.BOOST_REVIEW_KEEP)
}