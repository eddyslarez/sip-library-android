package com.eddyslarez.siplibrary.data.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.eddyslarez.siplibrary.data.database.entities.AssistantConfigEntity
import com.eddyslarez.siplibrary.data.models.AssistantMode
import com.eddyslarez.siplibrary.data.models.AssistantAction

/**
 * DAO para configuración del asistente
 * 
 * @author Eddys Larez
 */
@Dao
interface AssistantConfigDao {
    
    // === OPERACIONES BÁSICAS ===
    
    @Query("SELECT * FROM assistant_config ORDER BY accountKey ASC")
    fun getAllConfigs(): Flow<List<AssistantConfigEntity>>
    
    @Query("SELECT * FROM assistant_config WHERE id = :configId")
    suspend fun getConfigById(configId: String): AssistantConfigEntity?
    
    @Query("SELECT * FROM assistant_config WHERE accountId = :accountId")
    suspend fun getConfigByAccountId(accountId: String): AssistantConfigEntity?
    
    @Query("SELECT * FROM assistant_config WHERE accountKey = :accountKey")
    suspend fun getConfigByAccountKey(accountKey: String): AssistantConfigEntity?
    
    @Query("SELECT * FROM assistant_config WHERE accountKey = :accountKey")
    fun getConfigByAccountKeyFlow(accountKey: String): Flow<AssistantConfigEntity?>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: AssistantConfigEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfigs(configs: List<AssistantConfigEntity>)
    
    @Update
    suspend fun updateConfig(config: AssistantConfigEntity)
    
    @Delete
    suspend fun deleteConfig(config: AssistantConfigEntity)
    
    @Query("DELETE FROM assistant_config WHERE id = :configId")
    suspend fun deleteConfigById(configId: String)
    
    @Query("DELETE FROM assistant_config WHERE accountId = :accountId")
    suspend fun deleteConfigByAccountId(accountId: String)
    
    // === OPERACIONES DE ESTADO ===
    
    @Query("SELECT * FROM assistant_config WHERE isEnabled = 1")
    fun getEnabledConfigs(): Flow<List<AssistantConfigEntity>>
    
    @Query("SELECT * FROM assistant_config WHERE isEnabled = 1 AND mode != 'DISABLED'")
    fun getActiveConfigs(): Flow<List<AssistantConfigEntity>>
    
    @Query("SELECT * FROM assistant_config WHERE mode = :mode")
    fun getConfigsByMode(mode: AssistantMode): Flow<List<AssistantConfigEntity>>
    
    @Query("SELECT * FROM assistant_config WHERE action = :action")
    fun getConfigsByAction(action: AssistantAction): Flow<List<AssistantConfigEntity>>
    
    @Query("UPDATE assistant_config SET isEnabled = :enabled, enabledAt = :timestamp, updatedAt = :timestamp WHERE accountKey = :accountKey")
    suspend fun setEnabled(
        accountKey: String, 
        enabled: Boolean, 
        timestamp: Long = System.currentTimeMillis()
    )
    
    @Query("UPDATE assistant_config SET mode = :mode, updatedAt = :timestamp WHERE accountKey = :accountKey")
    suspend fun updateMode(
        accountKey: String, 
        mode: AssistantMode, 
        timestamp: Long = System.currentTimeMillis()
    )
    
    @Query("UPDATE assistant_config SET action = :action, updatedAt = :timestamp WHERE accountKey = :accountKey")
    suspend fun updateAction(
        accountKey: String, 
        action: AssistantAction, 
        timestamp: Long = System.currentTimeMillis()
    )
    
    @Query("UPDATE assistant_config SET assistantNumber = :number, updatedAt = :timestamp WHERE accountKey = :accountKey")
    suspend fun updateAssistantNumber(
        accountKey: String, 
        number: String, 
        timestamp: Long = System.currentTimeMillis()
    )
    
    // === CONSULTAS ESPECÍFICAS ===
    
    @Query("SELECT COUNT(*) FROM assistant_config WHERE isEnabled = 1")
    suspend fun getEnabledConfigCount(): Int
    
    @Query("SELECT COUNT(*) FROM assistant_config WHERE isEnabled = 1 AND mode != 'DISABLED'")
    suspend fun getActiveConfigCount(): Int
    
    @Query("SELECT accountKey FROM assistant_config WHERE isEnabled = 1 AND mode != 'DISABLED'")
    suspend fun getActiveAccountKeys(): List<String>
    
    @Query("SELECT * FROM assistant_config WHERE assistantNumber = :number")
    suspend fun getConfigsByAssistantNumber(number: String): List<AssistantConfigEntity>
    
    // === ESTADÍSTICAS ===
    
    @Query("SELECT COUNT(*) FROM assistant_config")
    suspend fun getTotalConfigCount(): Int
    
    @Query("SELECT mode, COUNT(*) as count FROM assistant_config WHERE isEnabled = 1 GROUP BY mode")
    suspend fun getModeDistribution(): List<ModeCount>
    
    @Query("SELECT action, COUNT(*) as count FROM assistant_config WHERE isEnabled = 1 GROUP BY action")
    suspend fun getActionDistribution(): List<ActionCount>
    
    // === LIMPIEZA ===
    
    @Query("DELETE FROM assistant_config WHERE isEnabled = 0 AND updatedAt < :threshold")
    suspend fun deleteDisabledConfigsOlderThan(threshold: Long)
    
    @Query("UPDATE assistant_config SET isEnabled = 0, updatedAt = :timestamp")
    suspend fun disableAllConfigs(timestamp: Long = System.currentTimeMillis())
}

/**
 * Clases de datos para estadísticas
 */
data class ModeCount(
    val mode: AssistantMode,
    val count: Int
)

data class ActionCount(
    val action: AssistantAction,
    val count: Int
)