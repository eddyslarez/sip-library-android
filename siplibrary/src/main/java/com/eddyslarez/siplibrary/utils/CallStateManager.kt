package com.eddyslarez.siplibrary.utils

import com.eddyslarez.siplibrary.data.models.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
/**
 * Gestor optimizado de estados de llamada
 * Solo maneja los nuevos estados definidos, sin compatibilidad legacy
 *
 * @author Eddys Larez
 */
object CallStateManager {

    private val _callStateFlow = MutableStateFlow(
        CallStateInfo(
            state = CallState.IDLE,
            previousState = null,
            timestamp = Clock.System.now().toEpochMilliseconds()
        )
    )
    val callStateFlow: StateFlow<CallStateInfo> = _callStateFlow.asStateFlow()

    private val _callHistoryFlow = MutableStateFlow<List<CallStateInfo>>(emptyList())
    val callHistoryFlow: StateFlow<List<CallStateInfo>> = _callHistoryFlow.asStateFlow()

    private var currentCallId: String = ""
    private var currentDirection: CallDirections = CallDirections.OUTGOING
    private var currentCallerNumber: String = ""

    /**
     * Actualiza el estado de la llamada con validación y prevención de duplicados
     */
    internal fun updateCallState(
        newState: CallState,
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
            log.d(tag = "CallStateManager") {
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
            log.w(tag = "CallStateManager") {
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
        if (newState != CallState.IDLE && newState != CallState.ENDED) {
            currentCallId = callId
            currentDirection = direction
        } else if (newState == CallState.ENDED || newState == CallState.IDLE) {
            currentCallId = ""
            currentCallerNumber = ""
        }

        log.d(tag = "CallStateManager") {
            "State transition: ${currentStateInfo.state} -> $newState for call $callId (${direction.name})"
        }

        return true
    }

    // === MÉTODOS PARA TRANSICIONES DE LLAMADA SALIENTE ===

    fun startOutgoingCall(callId: String, phoneNumber: String) {
        currentCallerNumber = phoneNumber
        updateCallState(
            newState = CallState.OUTGOING_INIT,
            callId = callId,
            direction = CallDirections.OUTGOING
        )
    }

    fun outgoingCallProgress(callId: String, sipCode: Int = 183) {
        updateCallState(
            newState = CallState.OUTGOING_PROGRESS,
            callId = callId,
            sipCode = sipCode,
            sipReason = "Session Progress"
        )
    }

    fun outgoingCallRinging(callId: String, sipCode: Int = 180) {
        updateCallState(
            newState = CallState.OUTGOING_RINGING,
            callId = callId,
            sipCode = sipCode,
            sipReason = "Ringing"
        )
    }

    // === MÉTODOS PARA TRANSICIONES DE LLAMADA ENTRANTE ===

    fun incomingCallReceived(callId: String, callerNumber: String) {
        currentCallerNumber = callerNumber
        updateCallState(
            newState = CallState.INCOMING_RECEIVED,
            callId = callId,
            direction = CallDirections.INCOMING
        )
    }

    // === MÉTODOS PARA ESTADOS CONECTADOS ===

    fun callConnected(callId: String, sipCode: Int = 200) {
        updateCallState(
            newState = CallState.CONNECTED,
            callId = callId,
            sipCode = sipCode,
            sipReason = "OK"
        )
    }

    fun streamsRunning(callId: String) {
        updateCallState(
            newState = CallState.STREAMS_RUNNING,
            callId = callId
        )
    }

    // === MÉTODOS PARA HOLD/RESUME ===

    fun startHold(callId: String) {
        updateCallState(
            newState = CallState.PAUSING,
            callId = callId
        )
    }

    fun callOnHold(callId: String) {
        updateCallState(
            newState = CallState.PAUSED,
            callId = callId
        )
    }

    fun startResume(callId: String) {
        updateCallState(
            newState = CallState.RESUMING,
            callId = callId
        )
    }

    // === MÉTODOS PARA FINALIZACIÓN ===

    fun startEnding(callId: String) {
        updateCallState(
            newState = CallState.ENDING,
            callId = callId
        )
    }

    fun callEnded(callId: String, sipCode: Int? = null, sipReason: String? = null) {
        updateCallState(
            newState = CallState.ENDED,
            callId = callId,
            sipCode = sipCode,
            sipReason = sipReason
        )
    }

    // === MÉTODOS PARA ERRORES ===

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
            newState = CallState.ERROR,
            callId = callId,
            sipCode = sipCode,
            sipReason = sipReason,
            errorReason = mappedError
        )
    }

    // === MÉTODOS PARA RESETEO ===

    fun resetToIdle() {
        updateCallState(
            newState = CallState.IDLE,
            callId = "",
            direction = CallDirections.OUTGOING
        )
        currentCallerNumber = ""
    }

    // === MÉTODOS DE CONSULTA ===

    fun getCurrentState(): CallStateInfo = _callStateFlow.value
    fun getCurrentCallId(): String = currentCallId
    fun getCurrentCallerNumber(): String = currentCallerNumber
    fun isCallActive(): Boolean = _callStateFlow.value.isActive()
    fun isCallConnected(): Boolean = _callStateFlow.value.isConnected()
    fun hasError(): Boolean = _callStateFlow.value.hasError()

    fun getStateHistory(): List<CallStateInfo> = _callHistoryFlow.value
    fun clearHistory() {
        _callHistoryFlow.value = emptyList()
    }

    // === MÉTODOS AUXILIARES PARA COMPATIBILIDAD ===

    fun callerNumber(number: String) {
        currentCallerNumber = number
    }

    fun callId(id: String) {
        currentCallId = id
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
            appendLine("Caller Number: $currentCallerNumber")
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
