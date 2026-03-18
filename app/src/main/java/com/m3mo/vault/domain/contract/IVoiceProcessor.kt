package com.m3mo.vault.domain.contract

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.domain.model.MemoryType
import kotlinx.coroutines.flow.Flow

/**
 * IVoiceProcessor - Voice Input Processing Interface
 *
 * Handles voice-to-memory pipeline:
 * 1. Voice capture (recording)
 * 2. Transcription (speech-to-text)
 * 3. Segmentation (split into logical units)
 * 4. Classification (determine memory types)
 */
interface IVoiceProcessor {

    /**
     * Start voice capture session
     *
     * @return Session ID for tracking
     */
    suspend fun startCapture(): AppResult<VoiceSession, AppError>

    /**
     * Stop voice capture and get transcription
     *
     * @param sessionId Session to stop
     * @return Transcription result
     */
    suspend fun stopCapture(sessionId: String): AppResult<TranscriptionResult, AppError>

    /**
     * Cancel active capture session
     */
    suspend fun cancelCapture(sessionId: String): AppResult<Unit, AppError>

    /**
     * Process audio file
     *
     * @param audioPath Path to audio file
     * @return Transcription result
     */
    suspend fun processAudioFile(audioPath: String): AppResult<TranscriptionResult, AppError>

    /**
     * Segment transcription into logical memory units
     *
     * @param transcription Full transcription text
     * @return List of segments
     */
    suspend fun segment(transcription: String): AppResult<List<VoiceSegment>, AppError>

    /**
     * Classify segment into memory type
     *
     * @param segment Segment to classify
     * @return Classification result
     */
    suspend fun classify(segment: VoiceSegment): AppResult<MemoryType, AppError>

    /**
     * Full pipeline: transcribe + segment + classify
     *
     * @param sessionId Completed voice session
     * @return List of classified segments ready to become memories
     */
    suspend fun processSession(sessionId: String): AppResult<List<ProcessedSegment>, AppError>

    /**
     * Observe capture state
     */
    fun observeCaptureState(): Flow<CaptureState>

    /**
     * Observe audio levels during capture
     */
    fun observeAudioLevel(): Flow<Float>

    /**
     * Check if voice processing is available
     */
    fun isAvailable(): Boolean

    /**
     * Get processor statistics
     */
    fun getStats(): VoiceProcessorStats
}

/**
 * Voice capture session
 */
data class VoiceSession(
    val id: String,
    val startedAt: Long,
    val state: CaptureState
)

/**
 * Capture state
 */
enum class CaptureState {
    IDLE,
    STARTING,
    RECORDING,
    PROCESSING,
    COMPLETED,
    ERROR,
    CANCELLED
}

/**
 * Transcription result
 */
data class TranscriptionResult(
    val sessionId: String,
    val text: String,
    val confidence: Float,
    val durationSeconds: Int,
    val wordCount: Int,
    val language: String?,
    val processingTimeMs: Long
)

/**
 * Voice segment - logical unit from transcription
 */
data class VoiceSegment(
    val id: Int,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val confidence: Float
)

/**
 * Processed segment ready to become a memory
 */
data class ProcessedSegment(
    val content: String,
    val type: MemoryType,
    val confidence: Float,
    val sourceSessionId: String
)

/**
 * Voice processor statistics
 */
data class VoiceProcessorStats(
    val totalSessions: Int,
    val totalDurationSeconds: Long,
    val averageConfidence: Float,
    val segmentsCreated: Int
)

/**
 * Voice processor configuration
 */
data class VoiceProcessorConfig(
    /**
     * Maximum recording duration in seconds
     */
    val maxDurationSeconds: Int = 300,

    /**
     * Audio sample rate
     */
    val sampleRate: Int = 16000,

    /**
     * Minimum confidence for transcription
     */
    val minConfidence: Float = 0.5f,

    /**
     * Language hint for transcription
     */
    val languageHint: String? = null,

    /**
     * Enable automatic segmentation
     */
    val autoSegment: Boolean = true,

    /**
     * Silence threshold for auto-segmentation (seconds)
     */
    val silenceThresholdSeconds: Float = 1.5f
) {
    companion object {
        fun default(): VoiceProcessorConfig = VoiceProcessorConfig()
    }
}
