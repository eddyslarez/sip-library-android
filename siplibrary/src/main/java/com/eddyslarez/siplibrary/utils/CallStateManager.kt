package com.eddyslarez.siplibrary.utils

import com.eddyslarez.siplibrary.data.models.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock

/**
 * Gestor avanzado de estados de llamada con prevención de duplicados
 * 
 * @author Eddys Larez
 */
class AdvancedCallStateManager {
    
    private val _callStateFlow = MutableStateFlow(
        CallStateInfo(
            state = DetailedCallState.IDLE,
            previousState = null,
            timestamp = Clock.System.now().toEpochMilliseconds()
        )
    )
    val callStateFlow: StateFlow<CallStateInfo> = _callStateFlow.asStateFlow()
    
    private val _callHistoryFlow = MutableStateFlow<List<CallStateInfo>>(emptyList())
    val callHistoryFlow: StateFlow<List<CallStateInfo>> = _callHistoryFlow.asStateFlow()
    
    private var currentCallId: String = ""
    private var currentDirection: CallDirections = CallDirections.OUTGOING
    
    /**
     * Actualiza el estado de la llamada con validación y prevención de duplicados
     */
    fun updateCallState(
        newState: DetailedCallState,
        callId: String = currentCallId,
        direction: CallDirections = currentDirection,
        sipCode: Int? = null,
        sipReason: String? = null,
        errorReason: CallErrorReason = CallErrorReason.NONE
    ): Boolean {
        val currentStateInfo = _callStateFlow.value
        
        // Prevenir duplicados del mismo estado
        if (currentStateInfo.state == newState && 
            currentStateInfo.callId == callId &&
            currentStateInfo.errorReason == errorReason) {
            log.d(tag = "AdvancedCallStateManager") { 
                "Duplicate state transition prevented: $newState for call $callId" 
            }
            return false
        }
        
        // Validar transición
        if (!CallStateTransitionValidator.isValidTransition(
                currentStateInfo.state, 
                newState, 
                direction
            )) {
            log.w(tag = "AdvancedCallStateManager") { 
                "Invalid state transition: ${currentStateInfo.state} -> $newState for $direction call" 
            }
            return false
        }
        
        // Crear nueva información de estado
        val newStateInfo = CallStateInfo(
            state = newState,
            previousState = currentStateInfo.state,
            errorReason = errorReason,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            sipCode = sipCode,
            sipReason = sipReason,
            callId = callId,
            direction = direction
        )
        
        // Actualizar estado actual
        _callStateFlow.value = newStateInfo
        
        // Añadir al historial
        val currentHistory = _callHistoryFlow.value.toMutableList()
        currentHistory.add(newStateInfo)
        
        // Mantener solo los últimos 50 estados para evitar memory leaks
        if (currentHistory.size > 50) {
            currentHistory.removeAt(0)
        }
        
        _callHistoryFlow.value = currentHistory
        
        // Actualizar información de llamada actual
        if (newState != DetailedCallState.IDLE && newState != DetailedCallState.ENDED) {
            currentCallId = callId
            currentDirection = direction
        } else if (newState == DetailedCallState.ENDED || newState == DetailedCallState.IDLE) {
            currentCallId = ""
        }
        
        log.d(tag = "AdvancedCallStateManager") { 
            "State transition: ${currentStateInfo.state} -> $newState for call $callId (${direction.name})" 
        }
        
        return true
    }
    
    /**
     * Métodos específicos para transiciones de llamada saliente
     */
    fun startOutgoingCall(callId: String, phoneNumber: String) {
        updateCallState(
            newState = DetailedCallState.OUTGOING_INIT,
            callId = callId,
            direction = CallDirections.OUTGOING
        )
    }
    
    fun outgoingCallProgress(callId: String, sipCode: Int = 183) {
        updateCallState(
            newState = DetailedCallState.OUTGOING_PROGRESS,
            callId = callId,
            sipCode = sipCode,
            sipReason = "Session Progress"
        )
    }
    
    fun outgoingCallRinging(callId: String, sipCode: Int = 180) {
        updateCallState(
            newState = DetailedCallState.OUTGOING_RINGING,
            callId = callId,
            sipCode = sipCode,
            sipReason = "Ringing"
        )
    }
    
    /**
     * Métodos específicos para transiciones de llamada entrante
     */
    fun incomingCallReceived(callId: String, callerNumber: String) {
        updateCallState(
            newState = DetailedCallState.INCOMING_RECEIVED,
            callId = callId,
            direction = CallDirections.INCOMING
        )
    }
    
    /**
     * Métodos para estados conectados
     */
    fun callConnected(callId: String, sipCode: Int = 200) {
        updateCallState(
            newState = DetailedCallState.CONNECTED,
            callId = callId,
            sipCode = sipCode,
            sipReason = "OK"
        )
    }
    
    fun streamsRunning(callId: String) {
        updateCallState(
            newState = DetailedCallState.STREAMS_RUNNING,
            callId = callId
        )
    }
    
    /**
     * Métodos para hold/resume
     */
    fun startHold(callId: String) {
        updateCallState(
            newState = DetailedCallState.PAUSING,
            callId = callId
        )
    }
    
    fun callOnHold(callId: String) {
        updateCallState(
            newState = DetailedCallState.PAUSED,
            callId = callId
        )
    }
    
    fun startResume(callId: String) {
        updateCallState(
            newState = DetailedCallState.RESUMING,
            callId = callId
        )
    }
    
    /**
     * Métodos para finalización
     */
    fun startEnding(callId: String) {
        updateCallState(
            newState = DetailedCallState.ENDING,
            callId = callId
        )
    }
    
    fun callEnded(callId: String, sipCode: Int? = null, sipReason: String? = null) {
        updateCallState(
            newState = DetailedCallState.ENDED,
            callId = callId,
            sipCode = sipCode,
            sipReason = sipReason
        )
    }
    
    /**
     * Métodos para errores
     */
    fun callError(
        callId: String, 
        sipCode: Int? = null, 
        sipReason: String? = null,
        errorReason: CallErrorReason = CallErrorReason.UNKNOWN
    ) {
        val mappedError = if (sipCode != null) {
            SipErrorMapper.mapSipCodeToErrorReason(sipCode)
        } else {
            errorReason
        }
        
        updateCallState(
            newState = DetailedCallState.ERROR,
            callId = callId,
            sipCode = sipCode,
            sipReason = sipReason,
            errorReason = mappedError
        )
    }
    
    /**
     * Resetear a estado idle
     */
    fun resetToIdle() {
        updateCallState(
            newState = DetailedCallState.IDLE,
            callId = "",
            direction = CallDirections.OUTGOING
        )
    }
    
    /**
     * Métodos de consulta
     */
    fun getCurrentState(): CallStateInfo = _callStateFlow.value
    fun getCurrentCallId(): String = currentCallId
    fun isCallActive(): Boolean = _callStateFlow.value.isActive()
    fun isCallConnected(): Boolean = _callStateFlow.value.isConnected()
    fun hasError(): Boolean = _callStateFlow.value.hasError()
    
    /**
     * Obtener historial de estados para debugging
     */
    fun getStateHistory(): List<CallStateInfo> = _callHistoryFlow.value
    
    /**
     * Limpiar historial
     */
    fun clearHistory() {
        _callHistoryFlow.value = emptyList()
    }
    
    /**
     * Diagnóstico del estado actual
     */
    fun getDiagnosticInfo(): String {
        val current = getCurrentState()
        val history = getStateHistory()
        
        return buildString {
            appendLine("=== CALL STATE DIAGNOSTIC ===")
            appendLine("Current State: ${current.state}")
            appendLine("Previous State: ${current.previousState}")
            appendLine("Call ID: ${current.callId}")
            appendLine("Direction: ${current.direction}")
            appendLine("Error Reason: ${current.errorReason}")
            appendLine("SIP Code: ${current.sipCode}")
            appendLine("SIP Reason: ${current.sipReason}")
            appendLine("Timestamp: ${current.timestamp}")
            appendLine("Is Active: ${current.isActive()}")
            appendLine("Is Connected: ${current.isConnected()}")
            appendLine("Has Error: ${current.hasError()}")
            appendLine("History Count: ${history.size}")
            
            if (history.isNotEmpty()) {
                appendLine("\n--- Recent State History ---")
                history.takeLast(5).forEach { state ->
                    appendLine("${state.timestamp}: ${state.previousState} -> ${state.state}")
                }
            }
        }
    }
}

/**
 * Instancia global del gestor de estados
 */
object CallStateManager {
    private val advancedManager = AdvancedCallStateManager()
    
    // Compatibilidad con la API existente
    private val _callStateFlow = MutableStateFlow(CallState.NONE)
    val callStateFlow: StateFlow<CallState> = _callStateFlow.asStateFlow()
    
    private val _callerNumberFlow = MutableStateFlow("")
    val callerNumberFlow: StateFlow<String> = _callerNumberFlow.asStateFlow()
    
    private val _callIdFlow = MutableStateFlow("")
    val callIdFlow: StateFlow<String> = _callIdFlow.asStateFlow()
    
    private val _isBackgroundFlow = MutableStateFlow(false)
    val isBackgroundFlow: StateFlow<Boolean> = _isBackgroundFlow.asStateFlow()
    
    // Exponer el gestor avanzado
    val advanced: AdvancedCallStateManager = advancedManager
    
    // Métodos de compatibilidad
    fun updateCallState(newState: CallState) {
        _callStateFlow.value = newState
        
        // Mapear a estados detallados
        val detailedState = mapLegacyToDetailedState(newState)
        if (detailedState != null) {
            advancedManager.updateCallState(detailedState)
        }
    }
    
    fun callerNumber(number: String) {
        _callerNumberFlow.value = number
    }
    
    fun callId(id: String) {
        _callIdFlow.value = id
    }
    
    fun setBackground() {
        _isBackgroundFlow.value = true
    }
    
    fun setForeground() {
        _isBackgroundFlow.value = false
    }
    
    fun setAppClosed() {
        _isBackgroundFlow.value = true
    }
    
    fun getCurrentCallState(): CallState = _callStateFlow.value
    fun getCurrentCallerNumber(): String = _callerNumberFlow.value
    fun getCurrentCallId(): String = _callIdFlow.value
    
    /**
     * Mapeo de estados legacy a estados detallados
     */
    private fun mapLegacyToDetailedState(legacyState: CallState): DetailedCallState? {
        return when (legacyState) {
            CallState.NONE, CallState.IDLE -> DetailedCallState.IDLE
            CallState.CALLING, CallState.INITIATING -> DetailedCallState.OUTGOING_INIT
            CallState.OUTGOING -> DetailedCallState.OUTGOING_RINGING
            CallState.RINGING -> DetailedCallState.OUTGOING_RINGING
            CallState.INCOMING -> DetailedCallState.INCOMING_RECEIVED
            CallState.CONNECTED -> DetailedCallState.CONNECTED
            CallState.HOLDING -> DetailedCallState.PAUSED
            CallState.ENDING -> DetailedCallState.ENDING
            CallState.ENDED -> DetailedCallState.ENDED
            CallState.ERROR, CallState.FAILED -> DetailedCallState.ERROR
            else -> null
        }
    }
    
    /**
     * Mapeo de estados detallados a legacy para compatibilidad
     */
    fun mapDetailedToLegacyState(detailedState: DetailedCallState): CallState {
        return when (detailedState) {
            DetailedCallState.IDLE -> CallState.IDLE
            DetailedCallState.OUTGOING_INIT -> CallState.CALLING
            DetailedCallState.OUTGOING_PROGRESS -> CallState.OUTGOING
            DetailedCallState.OUTGOING_RINGING -> CallState.RINGING
            DetailedCallState.INCOMING_RECEIVED -> CallState.INCOMING
            DetailedCallState.CONNECTED -> CallState.CONNECTED
            DetailedCallState.STREAMS_RUNNING -> CallState.CONNECTED
            DetailedCallState.PAUSING -> CallState.HOLDING
            DetailedCallState.PAUSED -> CallState.HOLDING
            DetailedCallState.RESUMING -> CallState.RESUMING
            DetailedCallState.ENDING -> CallState.ENDING
            DetailedCallState.ENDED -> CallState.ENDED
            DetailedCallState.ERROR -> CallState.ERROR
        }
    }
}