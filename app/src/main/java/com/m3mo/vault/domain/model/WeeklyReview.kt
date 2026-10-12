package com.m3mo.vault.domain.model

/**
 * WeeklyReview - Aggregated data for weekly review ritual
 *
 * The weekly review is a core feature that helps users:
 * - See what tasks were completed
 * - Review significant episodes
 * - Reinforce important memories
 * - Clear out low-value items
 */
data class WeeklyReview(
    /**
     * Review period start (Monday 00:00)
     */
    val weekStart: Long,

    /**
     * Review period end (Sunday 23:59)
     */
    val weekEnd: Long,

    /**
     * Tasks completed this week
     */
    val completedTasks: List<MemoryItem>,

    /**
     * Tasks still pending
     */
    val pendingTasks: List<MemoryItem>,

    /**
     * Significant episodes this week
     */
    val episodes: List<MemoryItem>,

    /**
     * New facts added this week
     */
    val newFacts: List<MemoryItem>,

    /**
     * Memories that decayed significantly (candidates for review)
     */
    val decayedMemories: List<MemoryItem>,

    /**
     * Memories accessed frequently this week
     */
    val frequentlyAccessed: List<MemoryItem>,

    /**
     * Statistics for the week
     */
    val stats: WeeklyStats,

    /**
     * When this review was generated
     */
    val generatedAt: Long
) {
    companion object {
        fun empty(weekStart: Long, weekEnd: Long): WeeklyReview {
            return WeeklyReview(
                weekStart = weekStart,
                weekEnd = weekEnd,
                completedTasks = emptyList(),
                pendingTasks = emptyList(),
                episodes = emptyList(),
                newFacts = emptyList(),
                decayedMemories = emptyList(),
                frequentlyAccessed = emptyList(),
                stats = WeeklyStats.empty(),
                generatedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * Check if review has any content
     */
    fun isEmpty(): Boolean {
        return completedTasks.isEmpty() &&
                pendingTasks.isEmpty() &&
                episodes.isEmpty() &&
                newFacts.isEmpty()
    }

    /**
     * Total memories in this review
     */
    val totalMemories: Int get() = 
        completedTasks.size + 
        pendingTasks.size + 
        episodes.size + 
        newFacts.size

    /**
     * Week number in the year
     */
    val weekNumber: Int get() {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = weekStart
        return calendar.get(java.util.Calendar.WEEK_OF_YEAR)
    }
}

/**
 * Weekly statistics
 */
data class WeeklyStats(
    /**
     * Total memories created this week
     */
    val memoriesCreated: Int,

    /**
     * Total memories accessed this week
     */
    val memoriesAccessed: Int,

    /**
     * Total searches performed
     */
    val searchesPerformed: Int,

    /**
     * Average search relevance score
     */
    val avgSearchScore: Float,

    /**
     * Tasks completed count
     */
    val tasksCompleted: Int,

    /**
     * Tasks created count
     */
    val tasksCreated: Int,

    /**
     * Completion rate (tasks completed / total tasks)
     */
    val completionRate: Float,

    /**
     * Memories archived by SmartDecay
     */
    val memoriesArchived: Int,

    /**
     * Total voice input duration (seconds)
     */
    val voiceInputSeconds: Int
) {
    companion object {
        fun empty(): WeeklyStats {
            return WeeklyStats(
                memoriesCreated = 0,
                memoriesAccessed = 0,
                searchesPerformed = 0,
                avgSearchScore = 0f,
                tasksCompleted = 0,
                tasksCreated = 0,
                completionRate = 0f,
                memoriesArchived = 0,
                voiceInputSeconds = 0
            )
        }
    }

    /**
     * Productivity score (0-100)
     */
    val productivityScore: Int get() {
        val taskScore = (completionRate * 40).toInt()
        val activityScore = ((memoriesCreated + memoriesAccessed).coerceAtMost(50) * 0.6).toInt()
        val searchScore = (searchesPerformed.coerceAtMost(30) * 0.5).toInt()
        return (taskScore + activityScore + searchScore).coerceAtMost(100)
    }
}

/**
 * Review section for UI rendering
 */
data class ReviewSection(
    val title: String,
    val subtitle: String?,
    val items: List<MemoryItem>,
    val type: ReviewSectionType,
    val isExpanded: Boolean = true
)

enum class ReviewSectionType {
    COMPLETED_TASKS,
    PENDING_TASKS,
    EPISODES,
    NEW_FACTS,
    DECAYED,
    FREQUENT
}

/**
 * Convert review to UI sections
 */
fun WeeklyReview.toSections(): List<ReviewSection> {
    return listOfNotNull(
        if (completedTasks.isNotEmpty()) ReviewSection(
            title = "Completed Tasks",
            subtitle = "${completedTasks.size} tasks done",
            items = completedTasks,
            type = ReviewSectionType.COMPLETED_TASKS
        ) else null,

        if (pendingTasks.isNotEmpty()) ReviewSection(
            title = "Pending Tasks",
            subtitle = "${pendingTasks.size} still to do",
            items = pendingTasks,
            type = ReviewSectionType.PENDING_TASKS
        ) else null,

        if (episodes.isNotEmpty()) ReviewSection(
            title = "This Week's Events",
            subtitle = "${episodes.size} moments captured",
            items = episodes,
            type = ReviewSectionType.EPISODES
        ) else null,

        if (newFacts.isNotEmpty()) ReviewSection(
            title = "New Facts",
            subtitle = "${newFacts.size} things learned",
            items = newFacts,
            type = ReviewSectionType.NEW_FACTS
        ) else null,

        if (decayedMemories.isNotEmpty()) ReviewSection(
            title = "Fading Memories",
            subtitle = "Pin to keep, or let go",
            items = decayedMemories,
            type = ReviewSectionType.DECAYED
        ) else null,

        if (frequentlyAccessed.isNotEmpty()) ReviewSection(
            title = "Most Accessed",
            subtitle = "Your go-to memories",
            items = frequentlyAccessed,
            type = ReviewSectionType.FREQUENT
        ) else null
    )
}
