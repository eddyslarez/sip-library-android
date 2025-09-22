package com.eddyslarez.siplibrary.data.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.eddyslarez.siplibrary.data.database.entities.SipAccountEntity
import com.eddyslarez.siplibrary.data.models.RegistrationState

/**
 * DAO para cuentas SIP
 * 
 * @author Eddys Larez
 */
@Dao
interface SipAccountDao {
    
    // === OPERACIONES BÁSICAS ===
    
    @Query("SELECT * FROM sip_accounts ORDER BY isDefault DESC, displayName ASC")
    fun getAllAccounts(): Flow<List<SipAccountEntity>>
    
    @Query("SELECT * FROM sip_accounts WHERE isActive = 1 ORDER BY isDefault DESC, displayName ASC")
    fun getActiveAccounts(): Flow<List<SipAccountEntity>>
    
    @Query("SELECT * FROM sip_accounts WHERE id = :accountId")
    suspend fun getAccountById(accountId: String): SipAccountEntity?
    
    @Query("SELECT * FROM sip_accounts WHERE username = :username AND domain = :domain")
    suspend fun getAccountByCredentials(username: String, domain: String): SipAccountEntity?
    
    @Query("SELECT * FROM sip_accounts WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultAccount(): SipAccountEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: SipAccountEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccounts(accounts: List<SipAccountEntity>)
    
    @Update
    suspend fun updateAccount(account: SipAccountEntity)
    
    @Delete
    suspend fun deleteAccount(account: SipAccountEntity)
    
    @Query("DELETE FROM sip_accounts WHERE id = :accountId")
    suspend fun deleteAccountById(accountId: String)
    
    // === OPERACIONES DE REGISTRO ===
    
    @Query("SELECT * FROM sip_accounts WHERE registrationState = :state")
    fun getAccountsByRegistrationState(state: RegistrationState): Flow<List<SipAccountEntity>>
    
    @Query("SELECT * FROM sip_accounts WHERE registrationState = 'OK' AND isActive = 1")
    fun getRegisteredAccounts(): Flow<List<SipAccountEntity>>
    
    @Query("UPDATE sip_accounts SET registrationState = :state, lastRegistrationTime = :timestamp, updatedAt = :timestamp WHERE id = :accountId")
    suspend fun updateRegistrationState(accountId: String, state: RegistrationState, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE sip_accounts SET registrationState = :state, registrationExpiry = :expiry, lastRegistrationTime = :timestamp, updatedAt = :timestamp WHERE id = :accountId")
    suspend fun updateRegistrationWithExpiry(accountId: String, state: RegistrationState, expiry: Long, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE sip_accounts SET lastErrorMessage = :errorMessage, updatedAt = :timestamp WHERE id = :accountId")
    suspend fun updateLastError(accountId: String, errorMessage: String?, timestamp: Long = System.currentTimeMillis())
    
    // === OPERACIONES DE PUSH ===
    
    @Query("UPDATE sip_accounts SET pushToken = :token, pushProvider = :provider, updatedAt = :timestamp WHERE id = :accountId")
    suspend fun updatePushInfo(accountId: String, token: String?, provider: String?, timestamp: Long = System.currentTimeMillis())
    
    @Query("SELECT * FROM sip_accounts WHERE pushToken IS NOT NULL AND pushToken != '' AND enablePush = 1")
    fun getAccountsWithPush(): Flow<List<SipAccountEntity>>
    
    // === OPERACIONES DE ESTADÍSTICAS ===
    
    @Query("UPDATE sip_accounts SET totalCalls = totalCalls + 1, lastCallTime = :timestamp, updatedAt = :timestamp WHERE id = :accountId")
    suspend fun incrementTotalCalls(accountId: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE sip_accounts SET successfulCalls = successfulCalls + 1, updatedAt = :timestamp WHERE id = :accountId")
    suspend fun incrementSuccessfulCalls(accountId: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE sip_accounts SET failedCalls = failedCalls + 1, updatedAt = :timestamp WHERE id = :accountId")
    suspend fun incrementFailedCalls(accountId: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE sip_accounts SET connectionQuality = :quality, averageLatency = :latency, updatedAt = :timestamp WHERE id = :accountId")
    suspend fun updateConnectionQuality(accountId: String, quality: Float, latency: Int, timestamp: Long = System.currentTimeMillis())
    
    // === OPERACIONES DE CONFIGURACIÓN ===
    
    @Query("UPDATE sip_accounts SET isDefault = 0")
    suspend fun clearDefaultAccount()
    
    @Query("UPDATE sip_accounts SET isDefault = 1, updatedAt = :timestamp WHERE id = :accountId")
    suspend fun setDefaultAccount(accountId: String, timestamp: Long = System.currentTimeMillis())
    
    @Transaction
    suspend fun setAsDefaultAccount(accountId: String) {
        clearDefaultAccount()
        setDefaultAccount(accountId)
    }

    @Query("SELECT COUNT(*) FROM sip_accounts")
    suspend fun getAccountCount(): Int

    @Query("UPDATE sip_accounts SET isActive = :isActive, updatedAt = :timestamp WHERE id = :accountId")
    suspend fun setAccountActive(accountId: String, isActive: Boolean, timestamp: Long = System.currentTimeMillis())
    
    // === CONSULTAS AVANZADAS ===
    
    @Query("SELECT COUNT(*) FROM sip_accounts WHERE isActive = 1")
    suspend fun getActiveAccountCount(): Int
    
    @Query("SELECT COUNT(*) FROM sip_accounts WHERE registrationState = 'OK'")
    suspend fun getRegisteredAccountCount(): Int
    
    @Query("SELECT * FROM sip_accounts WHERE registrationExpiry > 0 AND registrationExpiry < :currentTime")
    suspend fun getExpiredAccounts(currentTime: Long = System.currentTimeMillis()): List<SipAccountEntity>
    
    @Query("SELECT * FROM sip_accounts WHERE lastRegistrationTime < :threshold AND registrationState = 'OK'")
    suspend fun getAccountsNeedingRenewal(threshold: Long): List<SipAccountEntity>
    
    @Query("SELECT domain, COUNT(*) as count FROM sip_accounts WHERE isActive = 1 GROUP BY domain")
    suspend fun getAccountCountByDomain(): List<DomainCount>
    
    // === LIMPIEZA ===
    
    @Query("DELETE FROM sip_accounts WHERE isActive = 0 AND updatedAt < :threshold")
    suspend fun deleteInactiveAccountsOlderThan(threshold: Long)
    
    @Query("UPDATE sip_accounts SET registrationState = 'NONE', lastErrorMessage = NULL WHERE registrationState != 'NONE'")
    suspend fun resetAllRegistrationStates()
}

/**
 * Clase de datos para conteo por dominio
 */
data class DomainCount(
    val domain: String,
    val count: Int
)