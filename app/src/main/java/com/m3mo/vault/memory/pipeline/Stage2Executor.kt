package com.m3mo.vault.memory.pipeline

import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.ProcessingState
import com.m3mo.vault.memory.semantic.TfIdfSemanticMatcher
import kotlinx.coroutines.CancellationException

/**
 * Stage2Executor - Vectorization and Clustering (Adaptive)
 *
 * Stage 2 of the optimization pipeline:
 * - TF-IDF vectorization
 * - Embedding generation
 * - Similar memory clustering
 *
 * This stage is ADAPTIVE and may be skipped on resource-constrained devices.
 * The decision is based on:
 * - Available memory
 * - Battery level
 * - Time budget
 */
class Stage2Executor(
    private val semanticMatcher: TfIdfSemanticMatcher,
    private val logger: Logger
) {
    /**
     * Execute Stage 2 on pipeline context
     */
    suspend fun execute(context: PipelineContext): StageResult {
        if (!context.config.enableStage2) {
            logger.d(LogTags.PIPELINE, "Stage 2: Skipped (disabled in config)")
            return StageResult.success(
                stage = PipelineStage.STAGE_2_VECTORIZE,
                inputCount = 0,
                outputCount = 0,
                durationMs = 0
            )
        }

        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<PipelineError>()

        val candidateMap = context.inputMemories.associateBy { it.id }.toMutableMap()
        context.processedMemories.forEach { candidateMap[it.id] = it }

        val inputMemories = candidateMap.values.filter { memory ->
            val isReadyState = memory.state == ProcessingState.STORED || memory.state == ProcessingState.OPTIMIZED
            val needsEmbedding = memory.embedding == null || memory.state == ProcessingState.DIRTY
            isReadyState && needsEmbedding
        }

        if (inputMemories.isEmpty()) {
            logger.d(LogTags.PIPELINE, "Stage 2: No memories need embedding")
            return StageResult.success(
                stage = PipelineStage.STAGE_2_VECTORIZE,
                inputCount = 0,
                outputCount = 0,
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        logger.d(LogTags.PIPELINE, "Stage 2: Vectorizing ${inputMemories.size} memories")

        try {
            semanticMatcher.fit(inputMemories)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.PIPELINE, "Stage 2: Failed to fit vectorizer", e)
            return StageResult.failure(
                stage = PipelineStage.STAGE_2_VECTORIZE,
                inputCount = inputMemories.size,
                durationMs = System.currentTimeMillis() - startTime,
                errors = listOf(PipelineError(
                    stage = PipelineStage.STAGE_2_VECTORIZE,
                    memoryId = null,
                    message = "Vectorizer fit failed: ${e.message}",
                    cause = e
                ))
            )
        }

        val processedMemories = mutableListOf<MemoryItem>()

        for (memory in inputMemories) {
            if (!context.shouldContinue()) {
                logger.d(LogTags.PIPELINE, "Stage 2: Cancelled or budget exhausted")
                break
            }

            try {
                val embedding = semanticMatcher.embed(memory.normalizedContent.ifBlank { memory.content })

                val processed = memory.withEmbedding(embedding)
                    .let { if (it.state == ProcessingState.DIRTY) it.withState(ProcessingState.STORED) else it }

                processedMemories.add(processed)
                context.budget.recordItem()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (context.config.continueOnError) {
                    errors.add(PipelineError(
                        stage = PipelineStage.STAGE_2_VECTORIZE,
                        memoryId = memory.id,
                        message = "Embedding failed: ${e.message}",
                        cause = e
                    ))
                    logger.w(LogTags.PIPELINE, "Stage 2: Error embedding ${memory.id}: ${e.message}")
                } else {
                    throw e
                }
            }
        }

        context.addProcessedMemories(processedMemories)

        val clusters = findClusters(processedMemories, context)

        val duration = System.currentTimeMillis() - startTime
        logger.d(
            LogTags.PIPELINE,
            "Stage 2: Completed ${processedMemories.size} embeddings, ${clusters.size} clusters in ${duration}ms"
        )

        return StageResult(
            stage = PipelineStage.STAGE_2_VECTORIZE,
            inputCount = inputMemories.size,
            outputCount = processedMemories.size,
            durationMs = duration,
            success = errors.isEmpty(),
            errors = errors,
            metrics = mapOf(
                "clustersFound" to clusters.size,
                "vocabSize" to semanticMatcher.getStats().embeddingDimension
            )
        )
    }

    /**
     * Find clusters of similar memories
     */
    private suspend fun findClusters(
        memories: List<MemoryItem>,
        context: PipelineContext
    ): List<MemoryCluster> {
        if (memories.size < 2) return emptyList()

        val maxClusterCandidates = minOf(memories.size, MAX_CLUSTER_CANDIDATES)

        val candidates = memories
            .sortedByDescending { it.weight }
            .take(maxClusterCandidates)

        val clusters = mutableListOf<MemoryCluster>()
        val processed = mutableSetOf<Long>()

        for (memory in candidates) {
            if (memory.id in processed) continue
            if (!context.shouldContinue()) break

            val comparisonPool = candidates
                .filter { it.id !in processed && it.id != memory.id }
                .take(MAX_COMPARISONS_PER_MEMORY)

            if (comparisonPool.isEmpty()) {
                processed.add(memory.id)
                continue
            }

            val similar = semanticMatcher.findSimilar(
                memory = memory,
                candidates = comparisonPool,
                limit = MAX_CLUSTER_SIZE
            )

            if (similar.isNotEmpty()) {
                val clusterMembers = listOf(memory.id) + similar.map { it.memory.id }
                clusters.add(MemoryCluster(
                    centroidId = memory.id,
                    memberIds = clusterMembers,
                    avgSimilarity = similar.map { it.score }.average().toFloat()
                ))
                processed.addAll(clusterMembers)
            } else {
                processed.add(memory.id)
            }
        }

        return clusters
    }

    companion object {
        private const val MAX_CLUSTER_CANDIDATES = 200
        private const val MAX_COMPARISONS_PER_MEMORY = 50
        private const val MAX_CLUSTER_SIZE = 5
    }
}

/**
 * Represents a cluster of similar memories
 */
data class MemoryCluster(
    val centroidId: Long,
    val memberIds: List<Long>,
    val avgSimilarity: Float
) {
    val size: Int get() = memberIds.size
}