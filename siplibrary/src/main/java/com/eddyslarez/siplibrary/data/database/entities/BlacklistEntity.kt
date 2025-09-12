package com.eddyslarez.siplibrary.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Entidad para lista negra del asistente
 * 
 * @author Eddys Larez
 */
@Entity(
    tableName = "assistant_blacklist",
    foreignKeys = [
        ForeignKey(
            entity = AssistantConfigEntity::class,
            parentColumns = ["id"],
            childColumns = ["assistantConfigId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["assistantConfigId"]),
        Index(value = ["phoneNumber"]),
        Index(value = ["isActive"]),
        Index(value = ["assistantConfigId", "phoneNumber"], unique = true)
    ]
)
data class BlacklistEntity(
    @PrimaryKey
    val id: String,
    val assistantConfigId: String,
    val phoneNumber: String,
    val displayName: String? = null,
    val reason: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    
    fun toBlacklistEntry(): com.eddyslarez.siplibrary.data.models.BlacklistEntry {
        return com.eddyslarez.siplibrary.data.models.BlacklistEntry(
            id = id,
            phoneNumber = phoneNumber,
            displayName = displayName,
            reason = reason,
            addedAt = addedAt,
            isActive = isActive
        )
    }
}