package com.eddyslarez.siplibrary.data.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.eddyslarez.siplibrary.data.database.entities.AssistantCallLogEntity
import com.eddyslarez.siplibrary.data.models.AssistantAction

/**
 * DAO para historial de llamadas del asistente
 * 
 * @author Eddys Larez
 */
@Dao
interface AssistantCallLogDao {
    
    // === OPERACIONES BÁSICAS ===
    
    @Query("SELECT * FROM assistant_call_logs WHERE assistantConfigId = :configId ORDER BY timestamp DESC")
    fun getCallLogsForConfig(configId: String): Flow<List<AssistantCallLogEntity>>
    
    @Query("SELECT * FROM assistant_call_logs WHERE accountKey = :accountKey ORDER BY timestamp DESC")
    fun getCallLogsForAccount(accountKey: String): Flow<List<AssistantCallLogEntity>>
    
    @Query("SELECT * FROM assistant_call_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentCallLogs(limit: Int = 50): Flow<List<AssistantCallLogEntity>>
    
    @Query("SELECT * FROM assistant_call_logs WHERE id = :logId")
    suspend fun getCallLogById(logId: String): AssistantCallLogEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallLog(callLog: AssistantCallLogEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallLogs(callLogs: List<AssistantCallLogEntity>)
    
    @Update
    suspend fun updateCallLog(callLog: AssistantCallLogEntity)
    
    @Delete
    suspend fun deleteCallLog(callLog: AssistantCallLogEntity)
    
    @Query("DELETE FROM assistant_call_logs WHERE id = :logId")
    suspend fun deleteCallLogById(logId: String)
    
    // === FILTROS POR ACCIÓN ===

    @Query("SELECT * FROM assistant_call_logs WHERE assistantConfigId = :configId AND action = :action ORDER BY timestamp DESC")
    fun getCallLogsByAction(configId: String, action: AssistantAction): Flow<List<AssistantCallLogEntity>>

    @Query("SELECT * FROM assistant_call_logs WHERE assistantConfigId = :configId AND action = 'REJECT_IMMEDIATELY' ORDER BY timestamp DESC")
    fun getRejectedCalls(configId: String): Flow<List<AssistantCallLogEntity>>
    
    @Query("SELECT * FROM assistant_call_logs WHERE assistantConfigId = :configId AND action = 'SEND_TO_ASSISTANT' ORDER BY timestamp DESC")
    fun getDeflectedCalls(configId: String): Flow<List<AssistantCallLogEntity>>
    
    @Query("SELECT * FROM assistant_call_logs WHERE assistantConfigId = :configId AND wasDeflected = 1 AND deflectionSuccess = 1 ORDER BY timestamp DESC")
    fun getSuccessfulDeflections(configId: String): Flow<List<AssistantCallLogEntity>>
    
    @Query("SELECT * FROM assistant_call_logs WHERE assistantConfigId = :configId AND wasDeflected = 1 AND deflectionSuccess = 0 ORDER BY timestamp DESC")
    fun getFailedDeflections(configId: String): Flow<List<AssistantCallLogEntity>>
    
    // === FILTROS POR RAZÓN ===
    
    @Query("SELECT * FROM assistant_call_logs WHERE assistantConfigId = :configId AND reason = :reason ORDER BY timestamp DESC")
    fun getCallLogsByReason(configId: String, reason: String): Flow<List<AssistantCallLogEntity>>
    
    @Query("SELECT * FROM assistant_call_logs WHERE assistantConfigId = :configId AND reason = 'blacklisted' ORDER BY timestamp DESC")
    fun getBlacklistedCalls(configId: String): Flow<List<AssistantCallLogEntity>>
    
    @Query("SELECT * FROM assistant_call_logs WHERE assistantConfigId = :configId AND reason = 'not_in_contacts' ORDER BY timestamp DESC")
    fun getNonContactCalls(configId: String): Flow<List<AssistantCallLogEntity>>
    
    // === BÚSQUEDA ===
    
    @Query("SELECT * FROM assistant_call_logs WHERE assistantConfigId = :configId AND (callerNumber LIKE '%' || :query || '%' OR callerDisplayName LIKE '%' || :query || '%') ORDER BY timestamp DESC")
    fun searchCallLogs(configId: String, query: String): Flow<List<AssistantCallLogEntity>>
    
    @Query("SELECT * FROM assistant_call_logs WHERE callerNumber = :phoneNumber ORDER BY timestamp DESC")
    fun getCallLogsForPhoneNumber(phoneNumber: String): Flow<List<AssistantCallLogEntity>>
    
    // === FILTROS POR FECHA ===
    
    @Query("SELECT * FROM assistant_call_logs WHERE assistantConfigId = :configId AND timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    fun getCallLogsByDateRange(
        configId: String, 
        startTime: Long, 
        endTime: Long
    ): Flow<List<AssistantCallLogEntity>>
    
    @Query("SELECT * FROM assistant_call_logs WHERE assistantConfigId = :configId AND timestamp >= :timestamp ORDER BY timestamp DESC")
    fun getCallLogsSince(configId: String, timestamp: Long): Flow<List<AssistantCallLogEntity>>
    
    @Query("SELECT * FROM assistant_call_logs WHERE assistantConfigId = :configId AND DATE(timestamp/1000, 'unixepoch') = DATE(:timestamp/1000, 'unixepoch') ORDER BY timestamp DESC")
    fun getCallLogsForDay(configId: String, timestamp: Long): Flow<List<AssistantCallLogEntity>>
    
    // === ESTADÍSTICAS ===
    
    @Query("SELECT COUNT(*) FROM assistant_call_logs WHERE assistantConfigId = :configId")
    suspend fun getTotalCallLogCount(configId: String): Int
    
    @Query("SELECT COUNT(*) FROM assistant_call_logs WHERE assistantConfigId = :configId AND action = :action")
    suspend fun getCallLogCountByAction(configId: String, action: AssistantAction): Int
    
    @Query("SELECT COUNT(*) FROM assistant_call_logs WHERE assistantConfigId = :configId AND reason = :reason")
    suspend fun getCallLogCountByReason(configId: String, reason: String): Int
    
    @Query("SELECT COUNT(*) FROM assistant_call_logs WHERE assistantConfigId = :configId AND wasDeflected = 1 AND deflectionSuccess = 1")
    suspend fun getSuccessfulDeflectionCount(configId: String): Int
    
    @Query("SELECT COUNT(*) FROM assistant_call_logs WHERE assistantConfigId = :configId AND wasDeflected = 1 AND deflectionSuccess = 0")
    suspend fun getFailedDeflectionCount(configId: String): Int
    
    @Query("SELECT AVG(processingTimeMs) FROM assistant_call_logs WHERE assistantConfigId = :configId AND processingTimeMs > 0")
    suspend fun getAverageProcessingTime(configId: String): Double?

    @Query("SELECT callerNumber AS phoneNumber, COUNT(*) as count FROM assistant_call_logs WHERE assistantConfigId = :configId GROUP BY callerNumber ORDER BY count DESC LIMIT :limit")
    suspend fun getMostProcessedNumbers(configId: String, limit: Int = 10): List<PhoneNumberCount>

    @Query(
        """
    SELECT 
        COUNT(*) AS totalCalls,
        SUM(CASE WHEN action = 'REJECT_IMMEDIATELY' THEN 1 ELSE 0 END) AS rejectedCalls,
        SUM(CASE WHEN action = 'SEND_TO_ASSISTANT' THEN 1 ELSE 0 END) AS deflectedCalls,
        SUM(CASE WHEN wasDeflected = 1 AND deflectionSuccess = 1 THEN 1 ELSE 0 END) AS successfulDeflections,
        SUM(CASE WHEN wasDeflected = 1 AND deflectionSuccess = 0 THEN 1 ELSE 0 END) AS failedDeflections,
        SUM(CASE WHEN reason = 'blacklisted' THEN 1 ELSE 0 END) AS blacklistedCalls,
        SUM(CASE WHEN reason = 'not_in_contacts' THEN 1 ELSE 0 END) AS nonContactCalls,
        MAX(timestamp) AS lastActivity
    FROM assistant_call_logs 
    WHERE assistantConfigId = :configId
    """
    )
    suspend fun getStatisticsForConfig(configId: String): AssistantStatisticsRaw?


    // === LIMPIEZA ===
    
    @Query("DELETE FROM assistant_call_logs WHERE assistantConfigId = :configId")
    suspend fun deleteAllForConfig(configId: String)
    
    @Query("DELETE FROM assistant_call_logs WHERE assistantConfigId = :configId AND timestamp < :threshold")
    suspend fun deleteOldCallLogsForConfig(configId: String, threshold: Long)
    
    @Query("DELETE FROM assistant_call_logs WHERE timestamp < :threshold")
    suspend fun deleteOldCallLogs(threshold: Long)
    
    // Mantener solo los N registros más recientes por configuración
    @Query("""
        DELETE FROM assistant_call_logs 
        WHERE assistantConfigId = :configId 
        AND id NOT IN (
            SELECT id FROM assistant_call_logs 
            WHERE assistantConfigId = :configId 
            ORDER BY timestamp DESC 
            LIMIT :limit
        )
    """)
    suspend fun keepOnlyRecentCallLogsForConfig(configId: String, limit: Int)
}

/**
 * Clase de datos para estadísticas raw de la base de datos
 */
data class AssistantStatisticsRaw(
    val totalCalls: Int,
    val rejectedCalls: Int,
    val deflectedCalls: Int,
    val successfulDeflections: Int,
    val failedDeflections: Int,
    val blacklistedCalls: Int,
    val nonContactCalls: Int,
    val lastActivity: Long
)