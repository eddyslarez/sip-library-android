package com.eddyslarez.siplibrary.data.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.eddyslarez.siplibrary.data.database.entities.ContactEntity

/**
 * DAO para contactos
 * 
 * @author Eddys Larez
 */
@Dao
interface ContactDao {
    
    // === OPERACIONES BÁSICAS ===
    
    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    fun getAllContacts(): Flow<List<ContactEntity>>
    
    @Query("SELECT * FROM contacts WHERE id = :contactId")
    suspend fun getContactById(contactId: String): ContactEntity?
    
    @Query("SELECT * FROM contacts WHERE phoneNumber = :phoneNumber")
    suspend fun getContactByPhoneNumber(phoneNumber: String): ContactEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContacts(contacts: List<ContactEntity>)
    
    @Update
    suspend fun updateContact(contact: ContactEntity)
    
    @Delete
    suspend fun deleteContact(contact: ContactEntity)
    
    @Query("DELETE FROM contacts WHERE id = :contactId")
    suspend fun deleteContactById(contactId: String)
    
    // === BÚSQUEDA ===
    
    @Query("SELECT * FROM contacts WHERE displayName LIKE '%' || :query || '%' OR phoneNumber LIKE '%' || :query || '%' OR firstName LIKE '%' || :query || '%' OR lastName LIKE '%' || :query || '%' ORDER BY displayName ASC")
    fun searchContacts(query: String): Flow<List<ContactEntity>>
    
    @Query("SELECT * FROM contacts WHERE displayName LIKE :query || '%' OR firstName LIKE :query || '%' OR lastName LIKE :query || '%' ORDER BY displayName ASC LIMIT :limit")
    suspend fun searchContactsStartingWith(query: String, limit: Int = 10): List<ContactEntity>
    
    // === FILTROS ===
    
    @Query("SELECT * FROM contacts WHERE isFavorite = 1 ORDER BY displayName ASC")
    fun getFavoriteContacts(): Flow<List<ContactEntity>>
    
    @Query("SELECT * FROM contacts WHERE isBlocked = 1 ORDER BY displayName ASC")
    fun getBlockedContacts(): Flow<List<ContactEntity>>
    
    @Query("SELECT * FROM contacts WHERE company = :company ORDER BY displayName ASC")
    fun getContactsByCompany(company: String): Flow<List<ContactEntity>>
    
    @Query("SELECT * FROM contacts WHERE source = :source ORDER BY displayName ASC")
    fun getContactsBySource(source: String): Flow<List<ContactEntity>>
    
    // === OPERACIONES DE FAVORITOS ===
    
    @Query("UPDATE contacts SET isFavorite = :isFavorite, updatedAt = :timestamp WHERE id = :contactId")
    suspend fun setFavorite(contactId: String, isFavorite: Boolean, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE contacts SET isFavorite = 1, updatedAt = :timestamp WHERE phoneNumber = :phoneNumber")
    suspend fun setFavoriteByPhoneNumber(phoneNumber: String, timestamp: Long = System.currentTimeMillis())
    
    // === OPERACIONES DE BLOQUEO ===
    
    @Query("UPDATE contacts SET isBlocked = :isBlocked, updatedAt = :timestamp WHERE id = :contactId")
    suspend fun setBlocked(contactId: String, isBlocked: Boolean, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE contacts SET isBlocked = 1, updatedAt = :timestamp WHERE phoneNumber = :phoneNumber")
    suspend fun blockByPhoneNumber(phoneNumber: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("SELECT isBlocked FROM contacts WHERE phoneNumber = :phoneNumber")
    suspend fun isPhoneNumberBlocked(phoneNumber: String): Boolean?
    
    // === ESTADÍSTICAS ===
    
    @Query("UPDATE contacts SET totalCalls = totalCalls + 1, lastCallTime = :timestamp, updatedAt = :timestamp WHERE phoneNumber = :phoneNumber")
    suspend fun incrementCallCount(phoneNumber: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE contacts SET totalCallDuration = totalCallDuration + :duration, updatedAt = :timestamp WHERE phoneNumber = :phoneNumber")
    suspend fun addCallDuration(phoneNumber: String, duration: Long, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE contacts SET missedCalls = missedCalls + 1, updatedAt = :timestamp WHERE phoneNumber = :phoneNumber")
    suspend fun incrementMissedCalls(phoneNumber: String, timestamp: Long = System.currentTimeMillis())
    
    // === CONSULTAS AVANZADAS ===
    
    @Query("SELECT COUNT(*) FROM contacts")
    suspend fun getTotalContactCount(): Int
    
    @Query("SELECT COUNT(*) FROM contacts WHERE isFavorite = 1")
    suspend fun getFavoriteContactCount(): Int
    
    @Query("SELECT COUNT(*) FROM contacts WHERE isBlocked = 1")
    suspend fun getBlockedContactCount(): Int
    
    @Query("SELECT * FROM contacts WHERE totalCalls > 0 ORDER BY totalCalls DESC LIMIT :limit")
    fun getMostCalledContacts(limit: Int = 10): Flow<List<ContactEntity>>
    
    @Query("SELECT * FROM contacts WHERE lastCallTime > 0 ORDER BY lastCallTime DESC LIMIT :limit")
    fun getRecentlyCalledContacts(limit: Int = 10): Flow<List<ContactEntity>>
    
    @Query("SELECT DISTINCT company FROM contacts WHERE company IS NOT NULL AND company != '' ORDER BY company ASC")
    suspend fun getAllCompanies(): List<String>
    
    @Query("SELECT DISTINCT SUBSTR(displayName, 1, 1) as letter FROM contacts ORDER BY letter ASC")
    suspend fun getContactInitials(): List<String>
    
    // === SINCRONIZACIÓN ===
    
    @Query("UPDATE contacts SET syncedAt = :timestamp WHERE id = :contactId")
    suspend fun updateSyncTime(contactId: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("SELECT * FROM contacts WHERE syncedAt < :threshold OR syncedAt = 0")
    suspend fun getContactsNeedingSync(threshold: Long): List<ContactEntity>
    
    @Query("UPDATE contacts SET syncedAt = :timestamp WHERE source = :source")
    suspend fun updateSyncTimeBySource(source: String, timestamp: Long = System.currentTimeMillis())
    
    // === LIMPIEZA ===
    
    @Query("DELETE FROM contacts WHERE source = :source")
    suspend fun deleteContactsBySource(source: String)
    
    @Query("DELETE FROM contacts WHERE updatedAt < :threshold AND totalCalls = 0")
    suspend fun deleteUnusedContactsOlderThan(threshold: Long)
    
    @Query("DELETE FROM contacts")
    suspend fun deleteAllContacts()
    
    // === OPERACIONES DE AVATAR ===
    
    @Query("UPDATE contacts SET avatarUrl = :avatarUrl, updatedAt = :timestamp WHERE id = :contactId")
    suspend fun updateAvatar(contactId: String, avatarUrl: String?, timestamp: Long = System.currentTimeMillis())
    
    // === OPERACIONES DE RINGTONE ===
    
    @Query("UPDATE contacts SET ringtoneUri = :ringtoneUri, updatedAt = :timestamp WHERE id = :contactId")
    suspend fun updateRingtone(contactId: String, ringtoneUri: String?, timestamp: Long = System.currentTimeMillis())
    
    @Query("SELECT ringtoneUri FROM contacts WHERE phoneNumber = :phoneNumber")
    suspend fun getRingtoneForPhoneNumber(phoneNumber: String): String?
    
    // === OPERACIONES DE NOTAS ===
    
    @Query("UPDATE contacts SET notes = :notes, updatedAt = :timestamp WHERE id = :contactId")
    suspend fun updateNotes(contactId: String, notes: String?, timestamp: Long = System.currentTimeMillis())
}