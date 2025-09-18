package com.eddyslarez.siplibrary.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import com.eddyslarez.siplibrary.data.models.AssistantMode
import com.eddyslarez.siplibrary.data.models.AssistantAction

/**
 * Entidad para configuración del asistente por cuenta SIP
 * 
 * @author Eddys Larez
 */
@Entity(
    tableName = "assistant_config",
    foreignKeys = [
        ForeignKey(
            entity = SipAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["accountId"], unique = true),
        Index(value = ["isEnabled"]),
        Index(value = ["mode"])
    ]
)
data class AssistantConfigEntity(
    @PrimaryKey
    val id: String,
    val accountId: String,
    val accountKey: String,
    val isEnabled: Boolean = false,
    val mode: AssistantMode = AssistantMode.DISABLED,
    val action: AssistantAction = AssistantAction.REJECT_IMMEDIATELY,
    val assistantNumber: String = "",
    val enabledAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    
    fun isActive(): Boolean = isEnabled && mode != AssistantMode.DISABLED
    
    fun shouldProcessCall(): Boolean = isActive() && 
        (action == AssistantAction.REJECT_IMMEDIATELY || 
         (action == AssistantAction.SEND_TO_ASSISTANT && assistantNumber.isNotEmpty()))
    
    fun toAssistantConfig(): com.eddyslarez.siplibrary.data.models.AssistantConfig {
        return com.eddyslarez.siplibrary.data.models.AssistantConfig(
            accountKey = accountKey,
            isEnabled = isEnabled,
            mode = mode,
            action = action,
            assistantNumber = assistantNumber,
            enabledAt = enabledAt,
            lastModified = updatedAt
        )
    }
}