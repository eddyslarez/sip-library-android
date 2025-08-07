package com.eddyslarez.siplibrary.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * Entidad para contactos
 * 
 * @author Eddys Larez
 */
@Entity(
    tableName = "contacts",
    indices = [
        Index(value = ["phoneNumber"], unique = true),
        Index(value = ["displayName"]),
        Index(value = ["isFavorite"]),
        Index(value = ["isBlocked"])
    ]
)
data class ContactEntity(
    @PrimaryKey
    val id: String,
    val phoneNumber: String,
    val displayName: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val company: String? = null,
    val notes: String? = null,
    val avatarUrl: String? = null,
    val isFavorite: Boolean = false,
    val isBlocked: Boolean = false,
    val ringtoneUri: String? = null,
    
    // Estadísticas
    val totalCalls: Int = 0,
    val lastCallTime: Long = 0L,
    val totalCallDuration: Long = 0L,
    val missedCalls: Int = 0,
    
    // Metadatos
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long = 0L,
    val source: String = "manual" // manual, system, import
) {
    fun getFullName(): String {
        return when {
            firstName != null && lastName != null -> "$firstName $lastName"
            firstName != null -> firstName
            lastName != null -> lastName
            else -> displayName
        }
    }
    
    fun getInitials(): String {
        val name = getFullName()
        return name.split(" ")
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .take(2)
            .joinToString("")
            .ifEmpty { name.take(2).uppercase() }
    }
}