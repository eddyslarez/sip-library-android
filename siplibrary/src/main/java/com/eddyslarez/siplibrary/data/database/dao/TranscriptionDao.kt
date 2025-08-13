package com.eddyslarez.siplibrary.data.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.eddyslarez.siplibrary.data.database.entities.TranscriptionEntity
import com.eddyslarez.siplibrary.data.database.entities.TranscriptionSessionEntity
import com.eddyslarez.siplibrary.data.services.transcription.AudioTranscriptionService

/**
 * DAO para transcripciones de audio
 * 
 * @author Eddys Larez
 */
@Dao
interface TranscriptionDao {
    
    // === OPERACIONES BÁSICAS DE TRANSCRIPCIONES ===
    
    @Query("SELECT * FROM transcriptions ORDER BY timestamp DESC")
    fun getAllTranscriptions(): Flow<List<TranscriptionEntity>>
    
    @Query("SELECT * FROM transcriptions WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getTranscriptionsBySession(sessionId: String): Flow<List<TranscriptionEntity>>
    
    @Query("SELECT * FROM transcriptions WHERE callLogId = :callLogId ORDER BY timestamp ASC")
    fun getTranscriptionsByCallLog(callLogId: String): Flow<List<TranscriptionEntity>>
    
    @Query("SELECT * FROM transcriptions WHERE id = :transcriptionId")
    suspend fun getTranscriptionById(transcriptionId: String): TranscriptionEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscription(transcription: TranscriptionEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscriptions(transcriptions: List<TranscriptionEntity>)
    
    @Update
    suspend fun updateTranscription(transcription: TranscriptionEntity)
    
    @Delete
    suspend fun deleteTranscription(transcription: TranscriptionEntity)
    
    @Query("DELETE FROM transcriptions WHERE id = :transcriptionId")
    suspend fun deleteTranscriptionById(transcriptionId: String)
    
    // === FILTROS POR TIPO ===
    
    @Query("SELECT * FROM transcriptions WHERE isFinal = 1 ORDER BY timestamp DESC")
    fun getFinalTranscriptions(): Flow<List<TranscriptionEntity>>
    
    @Query("SELECT * FROM transcriptions WHERE isFinal = 0 ORDER BY timestamp DESC")
    fun getPartialTranscriptions(): Flow<List<TranscriptionEntity>>
    
    @Query("SELECT * FROM transcriptions WHERE speakerLabel = :speaker ORDER BY timestamp DESC")
    fun getTranscriptionsBySpeaker(speaker: String): Flow<List<TranscriptionEntity>>
    
    @Query("SELECT * FROM transcriptions WHERE audioSource = :source ORDER BY timestamp DESC")
    fun getTranscriptionsByAudioSource(source: AudioTranscriptionService.AudioSource): Flow<List<TranscriptionEntity>>
    
    @Query("SELECT * FROM transcriptions WHERE language = :language ORDER BY timestamp DESC")
    fun getTranscriptionsByLanguage(language: String): Flow<List<TranscriptionEntity>>
    
    // === BÚSQUEDA ===
    
    @Query("SELECT * FROM transcriptions WHERE text LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchTranscriptions(query: String): Flow<List<TranscriptionEntity>>
    
    @Query("SELECT * FROM transcriptions WHERE text LIKE '%' || :query || '%' AND sessionId = :sessionId ORDER BY timestamp ASC")
    fun searchTranscriptionsInSession(query: String, sessionId: String): Flow<List<TranscriptionEntity>>
    
    // === FILTROS POR FECHA ===
    
    @Query("SELECT * FROM transcriptions WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    fun getTranscriptionsByDateRange(startTime: Long, endTime: Long): Flow<List<TranscriptionEntity>>
    
    @Query("SELECT * FROM transcriptions WHERE timestamp >= :timestamp ORDER BY timestamp DESC")
    fun getTranscriptionsSince(timestamp: Long): Flow<List<TranscriptionEntity>>
    
    // === ESTADÍSTICAS ===
    
    @Query("SELECT COUNT(*) FROM transcriptions")
    suspend fun getTotalTranscriptionCount(): Int
    
    @Query("SELECT COUNT(*) FROM transcriptions WHERE isFinal = 1")
    suspend fun getFinalTranscriptionCount(): Int
    
    @Query("SELECT COUNT(*) FROM transcriptions WHERE sessionId = :sessionId")
    suspend fun getTranscriptionCountBySession(sessionId: String): Int
    
    @Query("SELECT SUM(wordCount) FROM transcriptions WHERE isFinal = 1")
    suspend fun getTotalWordCount(): Int?
    
    @Query("SELECT AVG(confidence) FROM transcriptions WHERE isFinal = 1")
    suspend fun getAverageConfidence(): Float?
    
    @Query("SELECT SUM(duration) FROM transcriptions WHERE isFinal = 1")
    suspend fun getTotalSpeechDuration(): Long?
    
    @Query("SELECT speakerLabel, COUNT(*) as count FROM transcriptions WHERE speakerLabel IS NOT NULL GROUP BY speakerLabel")
    suspend fun getTranscriptionCountBySpeaker(): List<SpeakerCount>
    
    @Query("SELECT language, COUNT(*) as count FROM transcriptions GROUP BY language ORDER BY count DESC")
    suspend fun getTranscriptionCountByLanguage(): List<LanguageCount>
    
    // === OPERACIONES DE SESIONES ===
    
    @Query("SELECT * FROM transcription_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<TranscriptionSessionEntity>>
    
    @Query("SELECT * FROM transcription_sessions WHERE isActive = 1")
    fun getActiveSessions(): Flow<List<TranscriptionSessionEntity>>
    
    @Query("SELECT * FROM transcription_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): TranscriptionSessionEntity?
    
    @Query("SELECT * FROM transcription_sessions WHERE callLogId = :callLogId")
    suspend fun getSessionByCallLog(callLogId: String): TranscriptionSessionEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: TranscriptionSessionEntity)
    
    @Update
    suspend fun updateSession(session: TranscriptionSessionEntity)
    
    @Query("UPDATE transcription_sessions SET isActive = 0, endTime = :endTime, updatedAt = :timestamp WHERE id = :sessionId")
    suspend fun endSession(sessionId: String, endTime: Long, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE transcription_sessions SET totalTranscriptions = :total, finalTranscriptions = :final, partialTranscriptions = :partial, totalWords = :words, averageConfidence = :confidence, updatedAt = :timestamp WHERE id = :sessionId")
    suspend fun updateSessionStatistics(
        sessionId: String,
        total: Int,
        final: Int,
        partial: Int,
        words: Int,
        confidence: Float,
        timestamp: Long = System.currentTimeMillis()
    )
    
    // === CONSULTAS COMBINADAS ===
    
    @Transaction
    @Query("SELECT * FROM transcription_sessions WHERE id = :sessionId")
    suspend fun getSessionWithTranscriptions(sessionId: String): SessionWithTranscriptions?
    
    @Transaction
    @Query("SELECT * FROM transcription_sessions WHERE callLogId = :callLogId")
    suspend fun getSessionWithTranscriptionsByCallLog(callLogId: String): SessionWithTranscriptions?
    
    // === LIMPIEZA ===
    
    @Query("DELETE FROM transcriptions WHERE sessionId = :sessionId")
    suspend fun deleteTranscriptionsBySession(sessionId: String)
    
    @Query("DELETE FROM transcriptions WHERE callLogId = :callLogId")
    suspend fun deleteTranscriptionsByCallLog(callLogId: String)
    
    @Query("DELETE FROM transcriptions WHERE timestamp < :threshold")
    suspend fun deleteTranscriptionsOlderThan(threshold: Long)
    
    @Query("DELETE FROM transcriptions")
    suspend fun deleteAllTranscriptions()
    
    @Query("DELETE FROM transcription_sessions WHERE startTime < :threshold")
    suspend fun deleteSessionsOlderThan(threshold: Long)
    
    @Query("DELETE FROM transcription_sessions")
    suspend fun deleteAllSessions()
    
    // Mantener solo las N transcripciones más recientes
    @Query("DELETE FROM transcriptions WHERE id NOT IN (SELECT id FROM transcriptions ORDER BY timestamp DESC LIMIT :limit)")
    suspend fun keepOnlyRecentTranscriptions(limit: Int)
    
    // === EXPORTACIÓN ===
    
    @Query("SELECT * FROM transcriptions WHERE sessionId = :sessionId AND isFinal = 1 ORDER BY timestamp ASC")
    suspend fun getFinalTranscriptionsForExport(sessionId: String): List<TranscriptionEntity>
    
    @Query("SELECT t.*, ts.startTime as sessionStartTime FROM transcriptions t JOIN transcription_sessions ts ON t.sessionId = ts.id WHERE t.sessionId = :sessionId ORDER BY t.timestamp ASC")
    suspend fun getTranscriptionsWithSessionInfo(sessionId: String): List<TranscriptionWithSessionInfo>
}

/**
 * Clases de datos para consultas
 */
data class SpeakerCount(
    val speakerLabel: String,
    val count: Int
)

data class LanguageCount(
    val language: String,
    val count: Int
)

data class SessionWithTranscriptions(
    @Embedded val session: TranscriptionSessionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "sessionId"
    )
    val transcriptions: List<TranscriptionEntity>
)

data class TranscriptionWithSessionInfo(
    @Embedded val transcription: TranscriptionEntity,
    val sessionStartTime: Long
) {
    fun getRelativeTimestamp(): Long {
        return transcription.timestamp - sessionStartTime
    }
    
    fun getFormattedRelativeTime(): String {
        val relativeMs = getRelativeTimestamp()
        val minutes = relativeMs / 60000
        val seconds = (relativeMs % 60000) / 1000
        val millis = relativeMs % 1000
        
        return String.format("%02d:%02d.%03d", minutes, seconds, millis)
    }
}