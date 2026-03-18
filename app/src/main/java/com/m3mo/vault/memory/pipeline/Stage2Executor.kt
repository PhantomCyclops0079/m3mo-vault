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
        // Check if Stage 2 is enabled
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

        // 🔥 FIX BUG 1: Pipeline Gap
        // Merge fresh memories from Stage 1 (processed) with existing memories from input
        // distinctBy prefers the first element, so we put processed first to prioritize fresh state?
        // Actually, we want the LATEST state.
        // If Stage 1 processed it, it's in processedMemories.
        // If Stage 1 didn't touch it, it's in inputMemories.
        // We use a Map to ensure we have unique memories by ID, preferring the processed ones.
        val candidateMap = context.inputMemories.associateBy { it.id }.toMutableMap()
        context.processedMemories.forEach { candidateMap[it.id] = it }

        // Filter for memories that need embedding:
        // 1. Must be in STORED or OPTIMIZED state
        // 2. Must NOT have an embedding yet OR marked as DIRTY (needs re-embedding)
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

        // Fit the vectorizer on all corpus (incremental fit if possible)
        // Note: Ideally we fit on a larger corpus, but for MVP we fit on the batch
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
                // Generate embedding
                val embedding = semanticMatcher.embed(memory.normalizedContent.ifBlank { memory.content })

                // Update memory with new embedding
                // If it was DIRTY, we might want to reset state to STORED/OPTIMIZED,
                // but here we keep state stable or ensure it's STORED.
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

        // Add results to context so Stage 3 can use them
        context.addProcessedMemories(processedMemories)

        // Find similar memories for potential consolidation
        // We use the newly processed memories + potentially some context from input
        // to make clustering meaningful even in small batches
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

        // Resource control: limit clustering to manageable size
        val maxClusterCandidates = minOf(memories.size, MAX_CLUSTER_CANDIDATES)

        // Prioritize by weight (higher weight = more important to cluster)
        val candidates = memories
            .sortedByDescending { it.weight }
            .take(maxClusterCandidates)

        val clusters = mutableListOf<MemoryCluster>()
        val processed = mutableSetOf<Long>()

        for (memory in candidates) {
            if (memory.id in processed) continue
            if (!context.shouldContinue()) break

            // Limit comparison pool to reduce O(N²) to O(N×K)
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
        // Resource control constants
        private const val MAX_CLUSTER_CANDIDATES = 200  // Max memories to consider for clustering
        private const val MAX_COMPARISONS_PER_MEMORY = 50  // Max comparisons per memory
        private const val MAX_CLUSTER_SIZE = 5  // Max similar items per cluster
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