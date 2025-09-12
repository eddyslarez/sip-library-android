package com.eddyslarez.siplibrary.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Modelos para el sistema de asistente SIP
 * 
 * @author Eddys Larez
 */

@Parcelize
enum class AssistantMode : Parcelable {
    DISABLED,           // Asistente desactivado
    CONTACTS_ONLY,      // Solo permitir contactos registrados
    BLACKLIST_FILTER    // Filtrar por lista negra
}

@Parcelize
enum class AssistantAction : Parcelable {
    REJECT_IMMEDIATELY, // Rechazar inmediatamente sin que suene
    SEND_TO_ASSISTANT   // Desviar al número del asistente
}

@Parcelize
data class AssistantConfig(
    val accountKey: String,
    val isEnabled: Boolean = false,
    val mode: AssistantMode = AssistantMode.DISABLED,
    val action: AssistantAction = AssistantAction.REJECT_IMMEDIATELY,
    val assistantNumber: String = "",
    val enabledAt: Long = 0L,
    val lastModified: Long = System.currentTimeMillis()
) : Parcelable {
    
    fun isActive(): Boolean = isEnabled && mode != AssistantMode.DISABLED
    
    fun shouldProcessCall(): Boolean = isActive() && assistantNumber.isNotEmpty()
}

@Parcelize
data class BlacklistEntry(
    val id: String,
    val phoneNumber: String,
    val displayName: String? = null,
    val reason: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
) : Parcelable

@Parcelize
data class AssistantCallLog(
    val id: String,
    val accountKey: String,
    val callerNumber: String,
    val callerDisplayName: String? = null,
    val action: AssistantAction,
    val reason: String, // "not_in_contacts", "blacklisted", etc.
    val assistantNumber: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val wasDeflected: Boolean = false,
    val deflectionSuccess: Boolean = false
) : Parcelable

/**
 * Resultado del procesamiento del asistente
 */
data class AssistantProcessingResult(
    val shouldProcess: Boolean,
    val action: AssistantAction,
    val reason: String,
    val assistantNumber: String? = null,
    val config: AssistantConfig? = null
)

/**
 * Estadísticas del asistente
 */
data class AssistantStatistics(
    val accountKey: String,
    val totalProcessedCalls: Int,
    val rejectedCalls: Int,
    val deflectedCalls: Int,
    val successfulDeflections: Int,
    val failedDeflections: Int,
    val blacklistedCalls: Int,
    val nonContactCalls: Int,
    val lastActivity: Long
)
