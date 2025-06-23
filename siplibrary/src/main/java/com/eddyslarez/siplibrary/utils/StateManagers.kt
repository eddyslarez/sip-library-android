package com.eddyslarez.siplibrary.utils

import com.eddyslarez.siplibrary.data.models.CallState
import com.eddyslarez.siplibrary.data.models.RegistrationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gestores de estado para llamadas y registro
 * 
 * @author Eddys Larez
 */
object CallStateManager {
    private val _callStateFlow = MutableStateFlow(CallState.NONE)
    val callStateFlow: StateFlow<CallState> = _callStateFlow.asStateFlow()

    private val _callerNumberFlow = MutableStateFlow("")
    val callerNumberFlow: StateFlow<String> = _callerNumberFlow.asStateFlow()

    private val _callIdFlow = MutableStateFlow("")
    val callIdFlow: StateFlow<String> = _callIdFlow.asStateFlow()

    private val _isBackgroundFlow = MutableStateFlow(false)
    val isBackgroundFlow: StateFlow<Boolean> = _isBackgroundFlow.asStateFlow()

    fun updateCallState(newState: CallState) {
        _callStateFlow.value = newState
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
}

object RegistrationStateManager {
    private val _registrationStateFlow = MutableStateFlow(RegistrationState.NONE)
    val registrationStateFlow: StateFlow<RegistrationState> = _registrationStateFlow.asStateFlow()

    fun updateCallState(newState: RegistrationState) {
        _registrationStateFlow.value = newState
    }

    fun getCurrentRegistrationState(): RegistrationState = _registrationStateFlow.value
}