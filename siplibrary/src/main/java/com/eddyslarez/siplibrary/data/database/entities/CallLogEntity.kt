package com.eddyslarez.siplibrary.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import com.eddyslarez.siplibrary.data.models.CallDirections
import com.eddyslarez.siplibrary.data.models.CallTypes

/**
 * Entidad para historial de llamadas
 * 
 * @author Eddys Larez
 */
@Entity(
    tableName = "call_logs",
    foreignKeys = [
        ForeignKey(
            entity = SipAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["startTime"]),
        Index(value = ["phoneNumber"]),
        Index(value = ["callType"]),
        Index(value = ["direction"]),
        Index(value = ["isRead"])
    ]
)
data class CallLogEntity(
    @PrimaryKey
    val id: String,
    val accountId: String,
    val callId: String,
    val phoneNumber: String,
    val displayName: String? = null,
    val direction: CallDirections,
    val callType: CallTypes,
    val startTime: Long,
    val endTime: Long? = null,
    val duration: Int = 0, // en segundos
    val isRead: Boolean = false,
    val notes: String? = null,
    
    // Información técnica
    val sipCode: Int? = null,
    val sipReason: String? = null,
    val localAddress: String? = null,
    val remoteAddress: String? = null,
    val userAgent: String? = null,
    
    // Calidad de llamada
    val audioQuality: Float = 0.0f,
    val networkLatency: Int = 0,
    val packetLoss: Float = 0.0f,
    val jitter: Int = 0,
    
    // Metadatos
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun getFormattedDuration(): String {
        val hours = duration / 3600
        val minutes = (duration % 3600) / 60
        val seconds = duration % 60
        
        return when {
            hours > 0 -> String.format("%02d:%02d:%02d", hours, minutes, seconds)
            else -> String.format("%02d:%02d", minutes, seconds)
        }
    }
    
    fun isMissedCall(): Boolean = callType == CallTypes.MISSED
    fun isSuccessfulCall(): Boolean = callType == CallTypes.SUCCESS
    fun isIncomingCall(): Boolean = direction == CallDirections.INCOMING
    fun isOutgoingCall(): Boolean = direction == CallDirections.OUTGOING
}