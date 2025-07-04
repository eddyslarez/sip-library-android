package com.eddyslarez.siplibrary.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Estados detallados de llamada basados en el flujo SIP real
 * 
 * @author Eddys Larez
 */

@Parcelize
enum class DetailedCallState : Parcelable {
    // Estados iniciales
    IDLE,
    
    // Estados de llamada saliente
    OUTGOING_INIT,          // Se envía INVITE
    OUTGOING_PROGRESS,      // Se recibe 183 Session Progress
    OUTGOING_RINGING,       // Se recibe 180 Ringing
    
    // Estados de llamada entrante
    INCOMING_RECEIVED,      // Se recibe INVITE
    
    // Estados conectados
    CONNECTED,              // Se recibe/envía 200 OK
    STREAMS_RUNNING,        // RTP fluye después de ACK
    
    // Estados de pausa/hold
    PAUSING,                // Iniciando hold
    PAUSED,                 // En hold
    RESUMING,               // Saliendo de hold
    
    // Estados de finalización
    ENDING,                 // Iniciando finalización
    ENDED,                  // Llamada terminada
    
    // Estados de error
    ERROR                   // Error en cualquier punto
}

@Parcelize
enum class CallErrorReason : Parcelable {
    NONE,
    BUSY,                   // 486 Busy Here
    NO_ANSWER,              // 408 Request Timeout
    REJECTED,               // 603 Decline
    TEMPORARILY_UNAVAILABLE, // 480 Temporarily Unavailable
    NOT_FOUND,              // 404 Not Found
    FORBIDDEN,              // 403 Forbidden
    NETWORK_ERROR,          // Problemas de red
    AUTHENTICATION_FAILED,   // 401/407
    SERVER_ERROR,           // 5xx
    UNKNOWN                 // Otros errores
}

@Parcelize
data class CallStateInfo(
    val state: DetailedCallState,
    val previousState: DetailedCallState?,
    val errorReason: CallErrorReason = CallErrorReason.NONE,
    val timestamp: Long,
    val sipCode: Int? = null,
    val sipReason: String? = null,
    val callId: String = "",
    val direction: CallDirections = CallDirections.OUTGOING
) : Parcelable {
    
    fun isOutgoingCall(): Boolean = direction == CallDirections.OUTGOING
    fun isIncomingCall(): Boolean = direction == CallDirections.INCOMING
    fun isConnected(): Boolean = state == DetailedCallState.CONNECTED || state == DetailedCallState.STREAMS_RUNNING
    fun isActive(): Boolean = state != DetailedCallState.IDLE && state != DetailedCallState.ENDED && state != DetailedCallState.ERROR
    fun hasError(): Boolean = state == DetailedCallState.ERROR || errorReason != CallErrorReason.NONE
    fun isOnHold(): Boolean = state == DetailedCallState.PAUSED
    fun isTransitioning(): Boolean = state == DetailedCallState.PAUSING || state == DetailedCallState.RESUMING || state == DetailedCallState.ENDING
}

/**
 * Mapeo de códigos SIP a razones de error
 */
object SipErrorMapper {
    fun mapSipCodeToErrorReason(sipCode: Int): CallErrorReason {
        return when (sipCode) {
            486 -> CallErrorReason.BUSY
            408 -> CallErrorReason.NO_ANSWER
            603 -> CallErrorReason.REJECTED
            480 -> CallErrorReason.TEMPORARILY_UNAVAILABLE
            404 -> CallErrorReason.NOT_FOUND
            403 -> CallErrorReason.FORBIDDEN
            401, 407 -> CallErrorReason.AUTHENTICATION_FAILED
            in 500..599 -> CallErrorReason.SERVER_ERROR
            else -> CallErrorReason.UNKNOWN
        }
    }
    
    fun getErrorDescription(reason: CallErrorReason): String {
        return when (reason) {
            CallErrorReason.NONE -> "Sin error"
            CallErrorReason.BUSY -> "Ocupado"
            CallErrorReason.NO_ANSWER -> "Sin respuesta"
            CallErrorReason.REJECTED -> "Rechazada"
            CallErrorReason.TEMPORARILY_UNAVAILABLE -> "Temporalmente no disponible"
            CallErrorReason.NOT_FOUND -> "Usuario no encontrado"
            CallErrorReason.FORBIDDEN -> "Prohibido"
            CallErrorReason.NETWORK_ERROR -> "Error de red"
            CallErrorReason.AUTHENTICATION_FAILED -> "Error de autenticación"
            CallErrorReason.SERVER_ERROR -> "Error del servidor"
            CallErrorReason.UNKNOWN -> "Error desconocido"
        }
    }
}

/**
 * Validador de transiciones de estado
 */
object CallStateTransitionValidator {
    
    private val validOutgoingTransitions = mapOf(
        DetailedCallState.IDLE to setOf(DetailedCallState.OUTGOING_INIT),
        DetailedCallState.OUTGOING_INIT to setOf(DetailedCallState.OUTGOING_PROGRESS, DetailedCallState.OUTGOING_RINGING, DetailedCallState.ERROR, DetailedCallState.ENDING),
        DetailedCallState.OUTGOING_PROGRESS to setOf(DetailedCallState.OUTGOING_RINGING, DetailedCallState.CONNECTED, DetailedCallState.ERROR, DetailedCallState.ENDING),
        DetailedCallState.OUTGOING_RINGING to setOf(DetailedCallState.CONNECTED, DetailedCallState.ERROR, DetailedCallState.ENDING),
        DetailedCallState.CONNECTED to setOf(DetailedCallState.STREAMS_RUNNING, DetailedCallState.PAUSING, DetailedCallState.ENDING, DetailedCallState.ERROR),
        DetailedCallState.STREAMS_RUNNING to setOf(DetailedCallState.PAUSING, DetailedCallState.ENDING, DetailedCallState.ERROR),
        DetailedCallState.PAUSING to setOf(DetailedCallState.PAUSED, DetailedCallState.ERROR),
        DetailedCallState.PAUSED to setOf(DetailedCallState.RESUMING, DetailedCallState.ENDING, DetailedCallState.ERROR),
        DetailedCallState.RESUMING to setOf(DetailedCallState.STREAMS_RUNNING, DetailedCallState.ERROR),
        DetailedCallState.ENDING to setOf(DetailedCallState.ENDED),
        DetailedCallState.ENDED to setOf(DetailedCallState.IDLE),
        DetailedCallState.ERROR to setOf(DetailedCallState.ENDED, DetailedCallState.IDLE)
    )
    
    private val validIncomingTransitions = mapOf(
        DetailedCallState.IDLE to setOf(DetailedCallState.INCOMING_RECEIVED),
        DetailedCallState.INCOMING_RECEIVED to setOf(DetailedCallState.CONNECTED, DetailedCallState.ERROR, DetailedCallState.ENDING),
        DetailedCallState.CONNECTED to setOf(DetailedCallState.STREAMS_RUNNING, DetailedCallState.PAUSING, DetailedCallState.ENDING, DetailedCallState.ERROR),
        DetailedCallState.STREAMS_RUNNING to setOf(DetailedCallState.PAUSING, DetailedCallState.ENDING, DetailedCallState.ERROR),
        DetailedCallState.PAUSING to setOf(DetailedCallState.PAUSED, DetailedCallState.ERROR),
        DetailedCallState.PAUSED to setOf(DetailedCallState.RESUMING, DetailedCallState.ENDING, DetailedCallState.ERROR),
        DetailedCallState.RESUMING to setOf(DetailedCallState.STREAMS_RUNNING, DetailedCallState.ERROR),
        DetailedCallState.ENDING to setOf(DetailedCallState.ENDED),
        DetailedCallState.ENDED to setOf(DetailedCallState.IDLE),
        DetailedCallState.ERROR to setOf(DetailedCallState.ENDED, DetailedCallState.IDLE)
    )
    
    fun isValidTransition(
        from: DetailedCallState,
        to: DetailedCallState,
        direction: CallDirections
    ): Boolean {
        val validTransitions = if (direction == CallDirections.OUTGOING) {
            validOutgoingTransitions
        } else {
            validIncomingTransitions
        }
        
        return validTransitions[from]?.contains(to) ?: false
    }
    
    fun getValidNextStates(
        currentState: DetailedCallState,
        direction: CallDirections
    ): Set<DetailedCallState> {
        val validTransitions = if (direction == CallDirections.OUTGOING) {
            validOutgoingTransitions
        } else {
            validIncomingTransitions
        }
        
        return validTransitions[currentState] ?: emptySet()
    }
}