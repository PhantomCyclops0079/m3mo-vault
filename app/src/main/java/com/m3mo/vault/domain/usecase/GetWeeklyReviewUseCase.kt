package com.m3mo.vault.domain.usecase

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.core.event.EventBus
import com.m3mo.vault.domain.contract.IWeeklyReviewEngine
import com.m3mo.vault.domain.event.WeeklyReviewReadyEvent
import com.m3mo.vault.domain.event.WeeklyReviewStartedEvent
import com.m3mo.vault.domain.model.WeeklyReview

/**
 * GetWeeklyReviewUseCase - Generates weekly review content
 */
class GetWeeklyReviewUseCase(
    private val reviewEngine: IWeeklyReviewEngine,
    private val eventBus: EventBus
) {
    /**
     * Get review for current week
     */
    suspend fun getCurrentWeek(): AppResult<WeeklyReview, AppError> {
        val (weekStart, weekEnd) = reviewEngine.getWeekBoundaries(System.currentTimeMillis())

        eventBus.publish(
            WeeklyReviewStartedEvent(weekStart = weekStart, weekEnd = weekEnd)
        )

        return reviewEngine.generateCurrentWeekReview().suspendOnSuccess { review ->
            eventBus.publish(
                WeeklyReviewReadyEvent(
                    weekStart = weekStart,
                    weekEnd = weekEnd,
                    memoriesIncluded = review.totalMemories
                )
            )
        }
    }

    /**
     * Get review for previous week
     */
    suspend fun getPreviousWeek(): AppResult<WeeklyReview, AppError> {
        return reviewEngine.generatePreviousWeekReview()
    }

    /**
     * Get review for specific week
     */
    suspend fun getForWeek(weekStart: Long): AppResult<WeeklyReview, AppError> {
        val (start, end) = reviewEngine.getWeekBoundaries(weekStart)
        return reviewEngine.generateReview(start, end)
    }

    /**
     * Check if weekly review notification should be shown
     */
    fun isReviewDue(): Boolean {
        return reviewEngine.isReviewDue()
    }
}
