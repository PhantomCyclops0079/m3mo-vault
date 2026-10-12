package com.m3mo.vault.memory.pipeline

import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.MemoryType
import com.m3mo.vault.domain.model.ProcessingState
import com.m3mo.vault.memory.normalize.DefaultNormalizer
import kotlinx.coroutines.CancellationException

/**
 * Stage1Executor - Normalization and Classification
 *
 * Stage 1 of the optimization pipeline:
 * - Text normalization (Unicode, whitespace, case)
 * - Auto-classification (FACT/EPISODE/TASK)
 * - Keyword extraction
 *
 * This stage prepares memories for efficient search and decay processing.
 */
class Stage1Executor(
    private val normalizer: DefaultNormalizer = DefaultNormalizer.instance,
    private val logger: Logger
) {
    /**
     * Execute Stage 1 on pipeline context
     */
    suspend fun execute(context: PipelineContext): StageResult {
        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<PipelineError>()
        val inputMemories = context.inputMemories.filter {
            it.state == ProcessingState.NEW || it.state == ProcessingState.QUEUED
        }

        logger.d(LogTags.PIPELINE, "Stage 1: Processing ${inputMemories.size} memories")

        val processedMemories = mutableListOf<MemoryItem>()

        for (memory in inputMemories) {
            if (!context.shouldContinue()) {
                logger.d(LogTags.PIPELINE, "Stage 1: Cancelled or budget exhausted")
                break
            }

            try {
                val processed = processMemory(memory)
                processedMemories.add(processed)
                context.budget.recordItem()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (context.config.continueOnError) {
                    errors.add(PipelineError(
                        stage = PipelineStage.STAGE_1_NORMALIZE,
                        memoryId = memory.id,
                        message = "Normalization failed: ${e.message}",
                        cause = e
                    ))
                    logger.w(LogTags.PIPELINE, "Stage 1: Error processing ${memory.id}: ${e.message}")
                } else {
                    throw e
                }
            }
        }

        context.addProcessedMemories(processedMemories)

        val duration = System.currentTimeMillis() - startTime
        logger.d(LogTags.PIPELINE, "Stage 1: Completed ${processedMemories.size} in ${duration}ms")

        return if (errors.isEmpty()) {
            StageResult.success(
                stage = PipelineStage.STAGE_1_NORMALIZE,
                inputCount = inputMemories.size,
                outputCount = processedMemories.size,
                durationMs = duration
            )
        } else {
            StageResult(
                stage = PipelineStage.STAGE_1_NORMALIZE,
                inputCount = inputMemories.size,
                outputCount = processedMemories.size,
                durationMs = duration,
                success = true,
                errors = errors
            )
        }
    }

    /**
     * Process a single memory
     */
    private fun processMemory(memory: MemoryItem): MemoryItem {
        val normalizedContent = normalizer.normalize(memory.content)

        val classifiedType = if (memory.type == MemoryType.FACT) {
            classifyMemory(memory.content)
        } else {
            memory.type
        }

        val tags = if (memory.tags.isEmpty()) {
            normalizer.extractKeywords(memory.content, maxKeywords = 5)
        } else {
            memory.tags
        }

        return memory
            .withNormalizedContent(normalizedContent)
            .withType(classifiedType)
            .withTags(tags)
            .withState(ProcessingState.STORED)
    }

    /**
     * Auto-classify memory based on content analysis
     *
     * Simple rule-based classification:
     * - TASK: Contains action verbs, todo patterns
     * - EPISODE: Contains date/time patterns, "I", past tense
     * - FACT: Default for informational content
     */
    private fun classifyMemory(content: String): MemoryType {
        val lowerContent = content.lowercase()

        if (isTaskContent(lowerContent)) {
            return MemoryType.TASK
        }

        if (isEpisodeContent(lowerContent)) {
            return MemoryType.EPISODE
        }

        return MemoryType.FACT
    }

    private fun isTaskContent(content: String): Boolean {
        val taskPatterns = listOf(
            "todo", "to do", "to-do",
            "need to", "have to", "should",
            "reminder", "don't forget", "remember to",
            "buy", "call", "email", "send", "meet",
            "deadline", "due", "by tomorrow", "by monday",
            "[ ]", "[x]", "- [ ]"
        )

        return taskPatterns.any { content.contains(it) }
    }

    private fun isEpisodeContent(content: String): Boolean {
        val episodePatterns = listOf(
            " i ", "i'm", "i've", "i'll",
            "today", "yesterday", "last week", "last month",
            "met with", "talked to", "went to",
            "happened", "occurred", "experienced",
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
        )

        return episodePatterns.any { content.contains(it) }
    }
}
