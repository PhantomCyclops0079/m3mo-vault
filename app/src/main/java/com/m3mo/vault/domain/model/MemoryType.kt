package com.m3mo.vault.domain.model

/**
 * MemoryType - Types of memories in M3mo
 *
 * The system supports three fundamental memory types:
 * - FACT: Declarative knowledge (who, what, where)
 * - EPISODE: Events with temporal context (when something happened)
 * - TASK: Actionable items with completion state
 *
 * This classification enables:
 * - Type-specific extraction strategies
 * - Appropriate decay policies
 * - Tailored UI presentation
 * - Weekly review categorization
 */
enum class MemoryType(
    val displayName: String,
    val emoji: String,
    val defaultWeight: Int
) {
    /**
     * Factual knowledge - things that are true
     * Examples: "My passport number is G12345", "John's birthday is March 15"
     *
     * Characteristics:
     * - Usually stable over time
     * - Can be updated/corrected
     * - Often extracted from declarative statements
     */
    FACT(
        displayName = "Fact",
        emoji = "📝",
        defaultWeight = 5
    ),

    /**
     * Episodic memory - events that happened
     * Examples: "Had dinner with Sarah on Tuesday", "Visited Paris last summer"
     *
     * Characteristics:
     * - Has temporal context (when)
     * - May have location context (where)
     * - Naturally decays over time unless significant
     */
    EPISODE(
        displayName = "Episode",
        emoji = "📅",
        defaultWeight = 3
    ),

    /**
     * Task/Action item - things to do
     * Examples: "Call dentist to schedule appointment", "Buy milk"
     *
     * Characteristics:
     * - Has completion state
     * - May have due date
     * - Feeds into weekly review
     */
    TASK(
        displayName = "Task",
        emoji = "✅",
        defaultWeight = 4
    );

    companion object {
        /**
         * Parse from string (case-insensitive)
         */
        fun fromString(value: String): MemoryType? {
            return entries.find { 
                it.name.equals(value, ignoreCase = true) 
            }
        }

        /**
         * Get default type for unclassified content
         */
        fun default(): MemoryType = FACT
    }
}

/**
 * Extension to check if memory type is actionable (has completion state)
 */
fun MemoryType.isActionable(): Boolean = this == MemoryType.TASK

/**
 * Extension to check if memory type requires completion tracking
 */
fun MemoryType.hasCompletionState(): Boolean = this == MemoryType.TASK

/**
 * Extension to check if memory type has temporal context
 */
fun MemoryType.hasTemporal(): Boolean = this == MemoryType.EPISODE || this == MemoryType.TASK

/**
 * Extension to get appropriate decay rate multiplier
 */
fun MemoryType.decayMultiplier(): Double = when (this) {
    MemoryType.FACT -> 0.8      // Facts decay slower
    MemoryType.EPISODE -> 1.2   // Episodes decay faster
    MemoryType.TASK -> 1.0      // Tasks decay normally
}
