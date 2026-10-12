package com.m3mo.vault.domain.usecase

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.core.event.EventBus
import com.m3mo.vault.domain.contract.IVoiceProcessor
import com.m3mo.vault.domain.contract.ProcessedSegment
import com.m3mo.vault.domain.contract.VoiceSession
import com.m3mo.vault.domain.event.SegmentsExtractedEvent
import com.m3mo.vault.domain.event.ExtractedSegment
import com.m3mo.vault.domain.event.VoiceInputCompletedEvent
import com.m3mo.vault.domain.event.VoiceInputStartedEvent
import com.m3mo.vault.domain.model.MemoryItem

/**
 * ProcessVoiceInputUseCase - Handles voice-to-memory pipeline
 *
 * Pipeline:
 * 1. Start voice capture
 * 2. Transcribe audio
 * 3. Segment into logical units
 * 4. Classify each segment
 * 5. Create memories
 */
class ProcessVoiceInputUseCase(
    private val voiceProcessor: IVoiceProcessor,
    private val createMemoryUseCase: CreateMemoryUseCase,
    private val eventBus: EventBus
) {
    /**
     * Start voice capture session
     */
    suspend fun startCapture(): AppResult<VoiceSession, AppError> {
        return voiceProcessor.startCapture().suspendOnSuccess { session ->
            eventBus.publish(
                VoiceInputStartedEvent(sessionId = session.id)
            )
        }
    }

    /**
     * Stop capture and process to memories
     */
    suspend fun stopAndProcess(sessionId: String): AppResult<List<MemoryItem>, AppError> {
        val transcriptionResult = voiceProcessor.stopCapture(sessionId)

        return transcriptionResult.suspendFlatMap { transcription ->
            eventBus.publish(
                VoiceInputCompletedEvent(
                    sessionId = sessionId,
                    transcription = transcription.text,
                    durationSeconds = transcription.durationSeconds,
                    confidence = transcription.confidence
                )
            )

            voiceProcessor.processSession(sessionId).suspendFlatMap { segments ->
                eventBus.publish(
                    SegmentsExtractedEvent(
                        sessionId = sessionId,
                        segments = segments.map {
                            ExtractedSegment(
                                content = it.content,
                                type = it.type,
                                confidence = it.confidence
                            )
                        }
                    )
                )

                createMemoriesFromSegments(segments)
            }
        }
    }

    /**
     * Cancel active capture
     */
    suspend fun cancelCapture(sessionId: String): AppResult<Unit, AppError> {
        return voiceProcessor.cancelCapture(sessionId)
    }

    /**
     * Process existing audio file
     */
    suspend fun processAudioFile(audioPath: String): AppResult<List<MemoryItem>, AppError> {
        return voiceProcessor.processAudioFile(audioPath).suspendFlatMap { transcription ->
            val segments = voiceProcessor.segment(transcription.text)

            segments.suspendFlatMap { segmentList ->
                val classified = segmentList.map { segment ->
                    val typeResult = voiceProcessor.classify(segment)
                    ProcessedSegment(
                        content = segment.text,
                        type = typeResult.fold(
                            onSuccess = { it },
                            onFailure = { com.m3mo.vault.domain.model.MemoryType.FACT }
                        ),
                        confidence = segment.confidence,
                        sourceSessionId = ""
                    )
                }

                createMemoriesFromSegments(classified)
            }
        }
    }

    private suspend fun createMemoriesFromSegments(
        segments: List<ProcessedSegment>
    ): AppResult<List<MemoryItem>, AppError> {
        if (segments.isEmpty()) {
            return AppResult.success(emptyList())
        }

        val requests = segments.map { segment ->
            CreateMemoryRequest(
                content = segment.content,
                type = segment.type,
                source = com.m3mo.vault.domain.model.MemorySource.VOICE
            )
        }

        return createMemoryUseCase.executeBatch(requests)
    }

    /**
     * Check if voice processing is available
     */
    fun isAvailable(): Boolean = voiceProcessor.isAvailable()
}
