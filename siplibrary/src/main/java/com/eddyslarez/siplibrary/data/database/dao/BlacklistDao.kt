package com.eddyslarez.siplibrary.data.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.eddyslarez.siplibrary.data.database.entities.BlacklistEntity

/**
 * DAO para lista negra del asistente
 * 
 * @author Eddys Larez
 */
@Dao
interface BlacklistDao {
    
    // === OPERACIONES BÁSICAS ===
    
    @Query("SELECT * FROM assistant_blacklist WHERE assistantConfigId = :configId AND isActive = 1 ORDER BY phoneNumber ASC")
    fun getActiveBlacklistForConfig(configId: String): Flow<List<BlacklistEntity>>
    
    @Query("SELECT * FROM assistant_blacklist WHERE assistantConfigId = :configId ORDER BY addedAt DESC")
    fun getAllBlacklistForConfig(configId: String): Flow<List<BlacklistEntity>>
    
    @Query("SELECT * FROM assistant_blacklist WHERE id = :entryId")
    suspend fun getBlacklistEntryById(entryId: String): BlacklistEntity?
    
    @Query("SELECT * FROM assistant_blacklist WHERE assistantConfigId = :configId AND phoneNumber = :phoneNumber")
    suspend fun getBlacklistEntryByPhone(configId: String, phoneNumber: String): BlacklistEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlacklistEntry(entry: BlacklistEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlacklistEntries(entries: List<BlacklistEntity>)
    
    @Update
    suspend fun updateBlacklistEntry(entry: BlacklistEntity)
    
    @Delete
    suspend fun deleteBlacklistEntry(entry: BlacklistEntity)
    
    @Query("DELETE FROM assistant_blacklist WHERE id = :entryId")
    suspend fun deleteBlacklistEntryById(entryId: String)
    
    // === OPERACIONES DE BÚSQUEDA ===
    
    @Query("SELECT * FROM assistant_blacklist WHERE assistantConfigId = :configId AND (phoneNumber LIKE '%' || :query || '%' OR displayName LIKE '%' || :query || '%') AND isActive = 1 ORDER BY phoneNumber ASC")
    fun searchBlacklist(configId: String, query: String): Flow<List<BlacklistEntity>>
    
    @Query("SELECT * FROM assistant_blacklist WHERE phoneNumber = :phoneNumber AND isActive = 1")
    suspend fun findActiveEntriesByPhone(phoneNumber: String): List<BlacklistEntity>
    
    // === OPERACIONES DE ESTADO ===
    
    @Query("UPDATE assistant_blacklist SET isActive = :active, updatedAt = :timestamp WHERE id = :entryId")
    suspend fun setActive(
        entryId: String, 
        active: Boolean, 
        timestamp: Long = System.currentTimeMillis()
    )
    
    @Query("UPDATE assistant_blacklist SET isActive = :active, updatedAt = :timestamp WHERE assistantConfigId = :configId AND phoneNumber = :phoneNumber")
    suspend fun setActiveByPhone(
        configId: String, 
        phoneNumber: String, 
        active: Boolean, 
        timestamp: Long = System.currentTimeMillis()
    )
    
    @Query("UPDATE assistant_blacklist SET displayName = :displayName, updatedAt = :timestamp WHERE id = :entryId")
    suspend fun updateDisplayName(
        entryId: String, 
        displayName: String?, 
        timestamp: Long = System.currentTimeMillis()
    )
    
    @Query("UPDATE assistant_blacklist SET reason = :reason, updatedAt = :timestamp WHERE id = :entryId")
    suspend fun updateReason(
        entryId: String, 
        reason: String?, 
        timestamp: Long = System.currentTimeMillis()
    )
    
    // === VERIFICACIONES ===
    
    @Query("SELECT EXISTS(SELECT 1 FROM assistant_blacklist WHERE assistantConfigId = :configId AND phoneNumber = :phoneNumber AND isActive = 1)")
    suspend fun isPhoneNumberBlacklisted(configId: String, phoneNumber: String): Boolean
    
    @Query("SELECT COUNT(*) FROM assistant_blacklist WHERE assistantConfigId = :configId AND isActive = 1")
    suspend fun getActiveBlacklistCount(configId: String): Int
    
    @Query("SELECT COUNT(*) FROM assistant_blacklist WHERE assistantConfigId = :configId")
    suspend fun getTotalBlacklistCount(configId: String): Int
    
    // === ESTADÍSTICAS ===
    
    @Query("SELECT COUNT(*) FROM assistant_blacklist WHERE assistantConfigId = :configId AND addedAt >= :startTime")
    suspend fun getBlacklistCountSince(configId: String, startTime: Long): Int
    
    @Query("SELECT phoneNumber, COUNT(*) as count FROM assistant_blacklist WHERE assistantConfigId = :configId GROUP BY phoneNumber ORDER BY count DESC LIMIT :limit")
    suspend fun getMostBlacklistedNumbers(configId: String, limit: Int = 10): List<PhoneNumberCount>
    
    // === LIMPIEZA ===
    
    @Query("DELETE FROM assistant_blacklist WHERE assistantConfigId = :configId")
    suspend fun deleteAllForConfig(configId: String)
    
    @Query("DELETE FROM assistant_blacklist WHERE assistantConfigId = :configId AND isActive = 0")
    suspend fun deleteInactiveForConfig(configId: String)
    
    @Query("DELETE FROM assistant_blacklist WHERE assistantConfigId = :configId AND addedAt < :threshold")
    suspend fun deleteOldEntriesForConfig(configId: String, threshold: Long)
    
    @Query("UPDATE assistant_blacklist SET isActive = 0, updatedAt = :timestamp WHERE assistantConfigId = :configId")
    suspend fun deactivateAllForConfig(configId: String, timestamp: Long = System.currentTimeMillis())
    
    // === OPERACIONES EN LOTE ===
    
    @Query("SELECT phoneNumber FROM assistant_blacklist WHERE assistantConfigId = :configId AND isActive = 1")
    suspend fun getActivePhoneNumbers(configId: String): List<String>
    
    @Transaction
    suspend fun replaceBlacklistForConfig(configId: String, newEntries: List<BlacklistEntity>) {
        deleteAllForConfig(configId)
        insertBlacklistEntries(newEntries)
    }
}