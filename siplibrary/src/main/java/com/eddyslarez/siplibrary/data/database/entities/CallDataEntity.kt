package com.eddyslarez.siplibrary.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import com.eddyslarez.siplibrary.data.models.CallDirections
import com.eddyslarez.siplibrary.data.models.CallState

/**
 * Entidad para datos de llamadas activas
 * 
 * @author Eddys Larez
 */
@Entity(
    tableName = "call_data",
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
        Index(value = ["callId"], unique = true),
        Index(value = ["currentState"]),
        Index(value = ["isActive"])
    ]
)
data class CallDataEntity(
    @PrimaryKey
    val callId: String,
    val accountId: String,
    val fromNumber: String,
    val toNumber: String,
    val direction: CallDirections,
    val currentState: CallState,
    val startTime: Long,
    val connectTime: Long? = null,
    val endTime: Long? = null,
    val isActive: Boolean = true,
    
    // Tags SIP
    val fromTag: String? = null,
    val toTag: String? = null,
    val inviteFromTag: String = "",
    val inviteToTag: String = "",
    
    // Información de contacto remoto
    val remoteContactUri: String? = null,
    val remoteDisplayName: String = "",
    val remoteUserAgent: String? = null,
    
    // SDP y WebRTC
    val localSdp: String = "",
    val remoteSdp: String = "",
    val iceUfrag: String? = null,
    val icePwd: String? = null,
    val dtlsFingerprint: String? = null,
    
    // Headers SIP
    val viaHeader: String = "",
    val inviteViaBranch: String = "",
    val lastCSeqValue: Int = 0,
    
    // Estado de la llamada
    val isOnHold: Boolean = false,
    val isMuted: Boolean = false,
    val isRecording: Boolean = false,
    
    // Mensajes originales
    val originalInviteMessage: String = "",
    val originalCallInviteMessage: String = "",
    
    // Información de calidad
    val audioCodec: String? = null,
    val videoCodec: String? = null,
    val bandwidth: Int = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val bytesTransferred: Long = 0,
    
    // Metadatos
    val md5Hash: String = "",
    val sipName: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun getDuration(): Long {
        return if (endTime != null && connectTime != null) {
            endTime - connectTime
        } else if (connectTime != null) {
            System.currentTimeMillis() - connectTime
        } else {
            0L
        }
    }
    
    fun getRemoteParty(): String {
        return when (direction) {
            CallDirections.OUTGOING -> toNumber
            CallDirections.INCOMING -> fromNumber
        }
    }
    
    fun getLocalParty(): String {
        return when (direction) {
            CallDirections.OUTGOING -> fromNumber
            CallDirections.INCOMING -> toNumber
        }
    }
    
    fun isConnected(): Boolean {
        return currentState == CallState.CONNECTED || currentState == CallState.STREAMS_RUNNING
    }
}