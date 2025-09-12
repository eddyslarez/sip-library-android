package com.eddyslarez.siplibrary.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import com.eddyslarez.siplibrary.data.models.AssistantAction

/**
 * Entidad para historial de llamadas procesadas por el asistente
 * 
 * @author Eddys Larez
 */
@Entity(
    tableName = "assistant_call_logs",
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
        Index(value = ["callerNumber"]),
        Index(value = ["timestamp"]),
        Index(value = ["action"]),
        Index(value = ["wasDeflected"])
    ]
)
data class AssistantCallLogEntity(
    @PrimaryKey
    val id: String,
    val assistantConfigId: String,
    val accountKey: String, // Para referencia rápida
    val callerNumber: String,
    val callerDisplayName: String? = null,
    val action: AssistantAction,
    val reason: String, // "not_in_contacts", "blacklisted", "manual_rule"
    val assistantNumber: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val wasDeflected: Boolean = false,
    val deflectionSuccess: Boolean = false,
    val errorMessage: String? = null,
    val processingTimeMs: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
) {
    
    fun toAssistantCallLog(): com.eddyslarez.siplibrary.data.models.AssistantCallLog {
        return com.eddyslarez.siplibrary.data.models.AssistantCallLog(
            id = id,
            accountKey = accountKey,
            callerNumber = callerNumber,
            callerDisplayName = callerDisplayName,
            action = action,
            reason = reason,
            assistantNumber = assistantNumber,
            timestamp = timestamp,
            wasDeflected = wasDeflected,
            deflectionSuccess = deflectionSuccess
        )
    }
    
    fun isSuccessful(): Boolean {
        return when (action) {
            AssistantAction.REJECT_IMMEDIATELY -> true // Siempre exitoso
            AssistantAction.SEND_TO_ASSISTANT -> wasDeflected && deflectionSuccess
        }
    }
}