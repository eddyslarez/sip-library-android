package com.eddyslarez.siplibrary.data.database.dao

import androidx.room.*
import com.eddyslarez.siplibrary.data.database.entities.TranscriptionEntity
import kotlinx.coroutines.flow.Flow
import com.eddyslarez.siplibrary.data.database.entities.TranscriptionSessionEntity
import com.eddyslarez.siplibrary.data.services.transcription.AudioTranscriptionService

/**
 * DAO para sesiones de transcripción
 * 
 * @author Eddys Larez
 */
@Dao
interface TranscriptionSessionDao {
    
    // === OPERACIONES BÁSICAS ===
    
    @Query("SELECT * FROM transcription_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<TranscriptionSessionEntity>>
    
    @Query("SELECT * FROM transcription_sessions WHERE isActive = 1 ORDER BY startTime DESC")
    fun getActiveSessions(): Flow<List<TranscriptionSessionEntity>>
    
    @Query("SELECT * FROM transcription_sessions WHERE isActive = 0 ORDER BY startTime DESC")
    fun getCompletedSessions(): Flow<List<TranscriptionSessionEntity>>
    
    @Query("SELECT * FROM transcription_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): TranscriptionSessionEntity?
    
    @Query("SELECT * FROM transcription_sessions WHERE callId = :callId")
    suspend fun getSessionByCallId(callId: String): TranscriptionSessionEntity?
    
    @Query("SELECT * FROM transcription_sessions WHERE callLogId = :callLogId")
    suspend fun getSessionByCallLogId(callLogId: String): TranscriptionSessionEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: TranscriptionSessionEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<TranscriptionSessionEntity>)
    
    @Update
    suspend fun updateSession(session: TranscriptionSessionEntity)
    
    @Delete
    suspend fun deleteSession(session: TranscriptionSessionEntity)
    
    @Query("DELETE FROM transcription_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)
    
    // === OPERACIONES DE ESTADO ===
    
    @Query("UPDATE transcription_sessions SET isActive = 0, endTime = :endTime, updatedAt = :timestamp WHERE id = :sessionId")
    suspend fun endSession(sessionId: String, endTime: Long, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE transcription_sessions SET isActive = :isActive, updatedAt = :timestamp WHERE id = :sessionId")
    suspend fun setSessionActive(sessionId: String, isActive: Boolean, timestamp: Long = System.currentTimeMillis())
    
    // === ACTUALIZACIÓN DE ESTADÍSTICAS ===
    
    @Query("""
        UPDATE transcription_sessions SET 
        totalTranscriptions = :total,
        finalTranscriptions = :finalCount,
        partialTranscriptions = :partial,
        totalWords = :words,
        averageConfidence = :confidence,
        speechDuration = :speechDuration,
        silenceDuration = :silenceDuration,
        audioFramesProcessed = :audioFrames,
        errorsCount = :errors,
        updatedAt = :timestamp
        WHERE id = :sessionId
    """)
    suspend fun updateSessionStatistics(
        sessionId: String,
        total: Int,
        finalCount: Int,
        partial: Int,
        words: Int,
        confidence: Float,
        speechDuration: Long,
        silenceDuration: Long,
        audioFrames: Long,
        errors: Int,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE transcription_sessions SET 
        averageAudioLevel = :avgLevel,
        peakAudioLevel = :peakLevel,
        averageSnr = :avgSnr,
        clippingDetected = :clipping,
        updatedAt = :timestamp
        WHERE id = :sessionId
    """)
    suspend fun updateSessionAudioQuality(
        sessionId: String,
        avgLevel: Float,
        peakLevel: Float,
        avgSnr: Float,
        clipping: Boolean,
        timestamp: Long = System.currentTimeMillis()
    )
    
    // === FILTROS POR CONFIGURACIÓN ===
    
    @Query("SELECT * FROM transcription_sessions WHERE language = :language ORDER BY startTime DESC")
    fun getSessionsByLanguage(language: String): Flow<List<TranscriptionSessionEntity>>
    
    @Query("SELECT * FROM transcription_sessions WHERE audioSource = :source ORDER BY startTime DESC")
    fun getSessionsByAudioSource(source: AudioTranscriptionService.AudioSource): Flow<List<TranscriptionSessionEntity>>
    
    @Query("SELECT * FROM transcription_sessions WHERE transcriptionProvider = :provider ORDER BY startTime DESC")
    fun getSessionsByProvider(provider: AudioTranscriptionService.TranscriptionProvider): Flow<List<TranscriptionSessionEntity>>
    
    // === FILTROS POR FECHA ===
    
    @Query("SELECT * FROM transcription_sessions WHERE startTime >= :startTime AND startTime <= :endTime ORDER BY startTime DESC")
    fun getSessionsByDateRange(startTime: Long, endTime: Long): Flow<List<TranscriptionSessionEntity>>
    
    @Query("SELECT * FROM transcription_sessions WHERE startTime >= :timestamp ORDER BY startTime DESC")
    fun getSessionsSince(timestamp: Long): Flow<List<TranscriptionSessionEntity>>
    
    @Query("SELECT * FROM transcription_sessions ORDER BY startTime DESC LIMIT :limit")
    fun getRecentSessions(limit: Int = 20): Flow<List<TranscriptionSessionEntity>>
    
    // === ESTADÍSTICAS DE SESIONES ===
    
    @Query("SELECT COUNT(*) FROM transcription_sessions")
    suspend fun getTotalSessionCount(): Int
    
    @Query("SELECT COUNT(*) FROM transcription_sessions WHERE isActive = 1")
    suspend fun getActiveSessionCount(): Int
    
    @Query("SELECT COUNT(*) FROM transcription_sessions WHERE isActive = 0")
    suspend fun getCompletedSessionCount(): Int
    
    @Query("SELECT AVG(CASE WHEN endTime IS NOT NULL THEN endTime - startTime ELSE 0 END) FROM transcription_sessions WHERE endTime IS NOT NULL")
    suspend fun getAverageSessionDuration(): Double?
    
    @Query("SELECT SUM(totalWords) FROM transcription_sessions")
    suspend fun getTotalWordsTranscribed(): Long?
    
    @Query("SELECT AVG(averageConfidence) FROM transcription_sessions WHERE averageConfidence > 0")
    suspend fun getOverallAverageConfidence(): Float?
    
    @Query("SELECT language, COUNT(*) as count FROM transcription_sessions GROUP BY language ORDER BY count DESC")
    suspend fun getSessionCountByLanguage(): List<LanguageSessionCount>
    
    @Query("SELECT audioSource, COUNT(*) as count FROM transcription_sessions GROUP BY audioSource ORDER BY count DESC")
    suspend fun getSessionCountByAudioSource(): List<AudioSourceSessionCount>
    
    // === CONSULTAS DE CALIDAD ===
    
    @Query("SELECT * FROM transcription_sessions WHERE averageConfidence >= :threshold ORDER BY averageConfidence DESC")
    fun getHighQualitySessions(threshold: Float = 0.8f): Flow<List<TranscriptionSessionEntity>>
    
    @Query("SELECT * FROM transcription_sessions WHERE averageConfidence < :threshold ORDER BY startTime DESC")
    fun getLowQualitySessions(threshold: Float = 0.5f): Flow<List<TranscriptionSessionEntity>>
    
    @Query("SELECT * FROM transcription_sessions WHERE clippingDetected = 1 ORDER BY startTime DESC")
    fun getSessionsWithClipping(): Flow<List<TranscriptionSessionEntity>>
    
    @Query("SELECT * FROM transcription_sessions WHERE errorsCount > :threshold ORDER BY errorsCount DESC")
    fun getSessionsWithErrors(threshold: Int = 0): Flow<List<TranscriptionSessionEntity>>
    
    // === ANÁLISIS AVANZADO ===
    
    @Query("""
        SELECT sessionId, 
               COUNT(*) as transcriptionCount,
               SUM(CASE WHEN isFinal = 1 THEN 1 ELSE 0 END) as finalCount,
               AVG(confidence) as avgConfidence,
               SUM(wordCount) as totalWords
        FROM transcriptions 
        WHERE sessionId = :sessionId
        GROUP BY sessionId
    """)
    suspend fun getSessionAnalysis(sessionId: String): SessionAnalysis?
    
    @Query("""
        SELECT DATE(startTime/1000, 'unixepoch') as date,
               COUNT(*) as sessionCount,
               SUM(totalWords) as totalWords,
               AVG(averageConfidence) as avgConfidence
        FROM transcription_sessions 
        WHERE startTime >= :startTime
        GROUP BY DATE(startTime/1000, 'unixepoch')
        ORDER BY date DESC
    """)
    suspend fun getDailyTranscriptionStats(startTime: Long): List<DailyTranscriptionStats>
    
    // === LIMPIEZA ===
    
    @Query("DELETE FROM transcription_sessions WHERE startTime < :threshold")
    suspend fun deleteSessionsOlderThan(threshold: Long)
    
    @Query("DELETE FROM transcription_sessions WHERE isActive = 0 AND endTime < :threshold")
    suspend fun deleteCompletedSessionsOlderThan(threshold: Long)
    
    @Query("DELETE FROM transcription_sessions")
    suspend fun deleteAllSessions()
    
    @Query("DELETE FROM transcription_sessions WHERE callLogId = :callLogId")
    suspend fun deleteSessionsByCallLog(callLogId: String)
    
    // Mantener solo las N sesiones más recientes
    @Query("DELETE FROM transcription_sessions WHERE id NOT IN (SELECT id FROM transcription_sessions ORDER BY startTime DESC LIMIT :limit)")
    suspend fun keepOnlyRecentSessions(limit: Int)
    
    // === OPERACIONES TRANSACCIONALES ===
    
    @Transaction
    suspend fun createSessionWithInitialTranscription(
        session: TranscriptionSessionEntity,
        initialTranscription: TranscriptionEntity
    ) {
        insertSession(session)
        // Necesitaríamos acceso al TranscriptionDao aquí
        // O hacer esto en el Repository
    }
    
    @Transaction
    suspend fun endSessionWithFinalStats(
        sessionId: String,
        endTime: Long,
        finalStats: SessionFinalStats
    ) {
        endSession(sessionId, endTime)
        updateSessionStatistics(
            sessionId = sessionId,
            total = finalStats.totalTranscriptions,
            finalCount = finalStats.finalTranscriptions,
            partial = finalStats.partialTranscriptions,
            words = finalStats.totalWords,
            confidence = finalStats.averageConfidence,
            timestamp = System.currentTimeMillis(),
            speechDuration = finalStats.totalTranscriptions.toLong(),
            silenceDuration = finalStats.totalTranscriptions.toLong(),
            audioFrames = finalStats.totalTranscriptions.toLong(),
            errors = finalStats.totalTranscriptions
        )
    }
}

/**
 * Clases de datos para estadísticas
 */
data class LanguageSessionCount(
    val language: String,
    val count: Int
)

data class AudioSourceSessionCount(
    val audioSource: AudioTranscriptionService.AudioSource,
    val count: Int
)

data class SessionAnalysis(
    val sessionId: String,
    val transcriptionCount: Int,
    val finalCount: Int,
    val avgConfidence: Float,
    val totalWords: Int
)

data class DailyTranscriptionStats(
    val date: String,
    val sessionCount: Int,
    val totalWords: Long,
    val avgConfidence: Float
)

data class SessionFinalStats(
    val totalTranscriptions: Int,
    val finalTranscriptions: Int,
    val partialTranscriptions: Int,
    val totalWords: Int,
    val averageConfidence: Float
)