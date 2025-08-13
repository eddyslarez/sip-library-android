package com.eddyslarez.siplibrary.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import com.eddyslarez.siplibrary.data.services.transcription.AudioTranscriptionService

/**
 * Entidad para almacenar transcripciones de llamadas
 * 
 * @author Eddys Larez
 */
@Entity(
    tableName = "transcriptions",
    foreignKeys = [
        ForeignKey(
            entity = CallLogEntity::class,
            parentColumns = ["id"],
            childColumns = ["callLogId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["callLogId"]),
        Index(value = ["sessionId"]),
        Index(value = ["timestamp"]),
        Index(value = ["isFinal"]),
        Index(value = ["speakerLabel"])
    ]
)
data class TranscriptionEntity(
    @PrimaryKey
    val id: String,
    val sessionId: String,
    val callLogId: String,
    val text: String,
    val confidence: Float,
    val isFinal: Boolean,
    val timestamp: Long,
    val duration: Long,
    val audioSource: AudioTranscriptionService.AudioSource,
    val language: String,
    val speakerLabel: String? = null, // "local", "remote", o null
    val wordCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun getFormattedTimestamp(): String {
        val date = java.util.Date(timestamp)
        return java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(date)
    }
    
    fun getWordList(): List<String> {
        return text.split("\\s+".toRegex()).filter { it.isNotBlank() }
    }
}

/**
 * Entidad para sesiones de transcripción
 */
@Entity(
    tableName = "transcription_sessions",
    foreignKeys = [
        ForeignKey(
            entity = CallLogEntity::class,
            parentColumns = ["id"],
            childColumns = ["callLogId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["callLogId"]),
        Index(value = ["startTime"]),
        Index(value = ["isActive"])
    ]
)
data class TranscriptionSessionEntity(
    @PrimaryKey
    val id: String,
    val callLogId: String,
    val callId: String,
    val startTime: Long,
    val endTime: Long? = null,
    val isActive: Boolean = true,
    val language: String,
    val audioSource: AudioTranscriptionService.AudioSource,
    val transcriptionProvider: AudioTranscriptionService.TranscriptionProvider,
    
    // Configuración
    val enablePartialResults: Boolean = true,
    val confidenceThreshold: Float = 0.5f,
    val enableProfanityFilter: Boolean = false,
    val enablePunctuation: Boolean = true,
    
    // Estadísticas de sesión
    val totalTranscriptions: Int = 0,
    val finalTranscriptions: Int = 0,
    val partialTranscriptions: Int = 0,
    val totalWords: Int = 0,
    val averageConfidence: Float = 0f,
    val speechDuration: Long = 0L,
    val silenceDuration: Long = 0L,
    val audioFramesProcessed: Long = 0L,
    val errorsCount: Int = 0,
    
    // Calidad de audio
    val averageAudioLevel: Float = 0f,
    val peakAudioLevel: Float = 0f,
    val averageSnr: Float = 0f,
    val clippingDetected: Boolean = false,
    
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun getDuration(): Long {
        return (endTime ?: System.currentTimeMillis()) - startTime
    }
    
    fun getFormattedDuration(): String {
        val durationMs = getDuration()
        val minutes = durationMs / 60000
        val seconds = (durationMs % 60000) / 1000
        val millis = durationMs % 1000
        
        return String.format("%02d:%02d.%03d", minutes, seconds, millis)
    }
    
    fun getWordsPerMinute(): Float {
        val durationMinutes = getDuration() / 60000f
        return if (durationMinutes > 0) totalWords / durationMinutes else 0f
    }
    
    fun getSpeechToSilenceRatio(): Float {
        val totalDuration = getDuration()
        return if (totalDuration > 0) speechDuration.toFloat() / totalDuration else 0f
    }
}