class SipViewModel(
    private val sipLibrary: EddysSipLibrary
) : ViewModel() {

    private val _uiState = MutableStateFlow(SipUiState())
    val uiState: StateFlow<SipUiState> = _uiState.asStateFlow()

    private val _permissionsGranted = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    // OPTIMIZADO: Estados unificados de llamada
    val callState: StateFlow<CallStateInfo> = sipLibrary.getCallStateFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 
            CallStateInfo(
                state = DetailedCallState.IDLE,
                previousState = null,
                timestamp = System.currentTimeMillis()
            )
        )

    val registrationState: StateFlow<RegistrationState> = sipLibrary.getRegistrationStateFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, RegistrationState.NONE)

    // Estados de registro multi-cuenta
    val registrationStates: StateFlow<Map<String, RegistrationState>> = sipLibrary.getRegistrationStatesFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // Historial de estados para debugging
    val callStateHistory: StateFlow<List<CallStateInfo>> = sipLibrary.getCallStateFlow()
        .map { currentState ->
            sipLibrary.getCallStateHistory()
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        setupSipListeners()
        observeCallStates()
    }

    private fun setupSipListeners() {
        // Listener principal para eventos SIP
        sipLibrary.addSipEventListener(object : EddysSipLibrary.SipEventListener {
            override fun onRegistrationStateChanged(state: RegistrationState, username: String, domain: String) {
                Log.d("SipListener", "onRegistrationStateChanged: $username@$domain -> ${state.name}")
                _uiState.update {
                    it.copy(
                        registrationMessage = "Registration: ${state.name} ($username@$domain)",
                        isRegistered = state == RegistrationState.OK
                    )
                }
            }

            // OPTIMIZADO: Listener unificado para estados de llamada
            override fun onCallStateChanged(stateInfo: CallStateInfo) {
                Log.d("SipListener", "onCallStateChanged: ${stateInfo.state.name}")
                
                val message = buildCallMessage(stateInfo)
                _uiState.update {
                    it.copy(
                        callMessage = message,
                        detailedCallMessage = message,
                        lastStateTransition = "${stateInfo.previousState?.name ?: "NONE"} → ${stateInfo.state.name}",
                        hasCallError = stateInfo.hasError(),
                        errorReason = if (stateInfo.hasError()) stateInfo.errorReason.name else null
                    )
                }

                // Manejar estados específicos
                handleStateChange(stateInfo)
            }

            override fun onIncomingCall(callInfo: EddysSipLibrary.IncomingCallInfo) {
                Log.d("SipListener", "onIncomingCall from: ${callInfo.callerNumber}")
                _uiState.update {
                    it.copy(
                        callMessage = "Incoming call from ${callInfo.callerNumber}",
                        incomingCall = callInfo
                    )
                }
            }

            override fun onCallConnected(callInfo: EddysSipLibrary.CallInfo) {
                Log.d("SipListener", "onCallConnected with: ${callInfo.phoneNumber}")
                _uiState.update {
                    it.copy(
                        callMessage = "Call connected with ${callInfo.phoneNumber}",
                        currentCall = callInfo
                    )
                }
            }

            override fun onCallEnded(callInfo: EddysSipLibrary.CallInfo, reason: EddysSipLibrary.CallEndReason) {
                Log.d("SipListener", "onCallEnded: ${reason.name}, callInfo: $callInfo")
                _uiState.update {
                    it.copy(
                        callMessage = "Call ended: ${reason.name}",
                        currentCall = null,
                        incomingCall = null,
                        hasCallError = false,
                        errorReason = null
                    )
                }
            }

            override fun onCallFailed(error: String, callInfo: EddysSipLibrary.CallInfo?) {
                Log.d("SipListener", "onCallFailed: $error, callInfo: $callInfo")
                _uiState.update {
                    it.copy(
                        callMessage = "Call failed: $error",
                        hasCallError = true,
                        errorReason = "FAILED"
                    )
                }
            }
        })

        // Listener específico para llamadas con estados detallados
        sipLibrary.setCallListener(object : EddysSipLibrary.CallListener {
            override fun onCallInitiated(callInfo: EddysSipLibrary.CallInfo) {
                Log.d("CallListener", "onCallInitiated to: ${callInfo.phoneNumber}")
                _uiState.update {
                    it.copy(
                        callMessage = "Calling ${callInfo.phoneNumber}...",
                        currentCall = callInfo
                    )
                }
            }

            override fun onCallRinging(callInfo: EddysSipLibrary.CallInfo) {
                Log.d("CallListener", "onCallRinging: ${callInfo.phoneNumber}")
                _uiState.update {
                    it.copy(
                        callMessage = "Ringing ${callInfo.phoneNumber}..."
                    )
                }
            }

            override fun onCallConnected(callInfo: EddysSipLibrary.CallInfo) {
                Log.d("CallListener", "onCallConnected: ${callInfo.phoneNumber}")
            }

            override fun onCallHeld(callInfo: EddysSipLibrary.CallInfo) {
                Log.d("CallListener", "onCallHeld")
                _uiState.update {
                    it.copy(
                        callMessage = "Call on hold",
                        currentCall = callInfo
                    )
                }
            }

            override fun onCallResumed(callInfo: EddysSipLibrary.CallInfo) {
                Log.d("CallListener", "onCallResumed")
                _uiState.update {
                    it.copy(
                        callMessage = "Call resumed",
                        currentCall = callInfo
                    )
                }
            }

            override fun onCallEnded(callInfo: EddysSipLibrary.CallInfo, reason: EddysSipLibrary.CallEndReason) {
                Log.d("CallListener", "onCallEnded: ${reason.name}")
            }

            override fun onCallTransferred(callInfo: EddysSipLibrary.CallInfo, transferTo: String) {
                Log.d("CallListener", "onCallTransferred to: $transferTo")
            }

            override fun onMuteStateChanged(isMuted: Boolean, callInfo: EddysSipLibrary.CallInfo) {
                Log.d("CallListener", "onMuteStateChanged: $isMuted")
                _uiState.update {
                    it.copy(
                        currentCall = callInfo
                    )
                }
            }

            // OPTIMIZADO: Listener unificado para estados detallados específicos de llamada
            override fun onCallStateChanged(stateInfo: CallStateInfo) {
                Log.d("CallListener", "onCallStateChanged: ${stateInfo.state}")
                
                // Aquí puedes manejar lógica específica de UI para cada estado
                when (stateInfo.state) {
                    DetailedCallState.OUTGOING_INIT -> {
                        _uiState.update { it.copy(callMessage = "Iniciando llamada...") }
                    }
                    DetailedCallState.OUTGOING_PROGRESS -> {
                        _uiState.update { it.copy(callMessage = "Estableciendo conexión...") }
                    }
                    DetailedCallState.OUTGOING_RINGING -> {
                        _uiState.update { it.copy(callMessage = "Sonando...") }
                    }
                    DetailedCallState.INCOMING_RECEIVED -> {
                        _uiState.update { it.copy(callMessage = "Llamada entrante") }
                    }
                    DetailedCallState.CONNECTED -> {
                        _uiState.update { it.copy(callMessage = "Conectado") }
                    }
                    DetailedCallState.STREAMS_RUNNING -> {
                        _uiState.update { it.copy(callMessage = "Audio activo") }
                    }
                    DetailedCallState.PAUSING -> {
                        _uiState.update { it.copy(callMessage = "Pausando llamada...") }
                    }
                    DetailedCallState.PAUSED -> {
                        _uiState.update { it.copy(callMessage = "Llamada en espera") }
                    }
                    DetailedCallState.RESUMING -> {
                        _uiState.update { it.copy(callMessage = "Reanudando llamada...") }
                    }
                    DetailedCallState.ENDING -> {
                        _uiState.update { it.copy(callMessage = "Finalizando llamada...") }
                    }
                    DetailedCallState.ENDED -> {
                        _uiState.update { it.copy(callMessage = "Llamada finalizada") }
                    }
                    DetailedCallState.ERROR -> {
                        val errorMsg = SipErrorMapper.getErrorDescription(stateInfo.errorReason)
                        _uiState.update { 
                            it.copy(
                                callMessage = "Error: $errorMsg",
                                hasCallError = true,
                                errorReason = stateInfo.errorReason.name
                            ) 
                        }
                    }
                    else -> {}
                }
            }
        })
    }

    // OPTIMIZADO: Observar estados unificados para lógica adicional
    private fun observeCallStates() {
        viewModelScope.launch {
            callState.collect { stateInfo ->
                // Lógica adicional basada en estados
                when (stateInfo.state) {
                    DetailedCallState.STREAMS_RUNNING -> {
                        // Iniciar timer de duración de llamada
                        startCallDurationTimer()
                    }
                    DetailedCallState.ENDED, DetailedCallState.ERROR -> {
                        // Detener timer de duración
                        stopCallDurationTimer()
                    }
                    else -> {}
                }
            }
        }

        // Observar estados de registro multi-cuenta
        viewModelScope.launch {
            registrationStates.collect { states ->
                val registeredCount = states.values.count { it == RegistrationState.OK }
                val totalCount = states.size
                
                _uiState.update {
                    it.copy(
                        multiAccountStatus = "Cuentas registradas: $registeredCount/$totalCount",
                        allAccountsRegistered = registeredCount == totalCount && totalCount > 0
                    )
                }
            }
        }
    }

    // OPTIMIZADO: Construir mensaje del estado
    private fun buildCallMessage(stateInfo: CallStateInfo): String {
        val baseMessage = when (stateInfo.state) {
            DetailedCallState.IDLE -> "Sin llamadas"
            DetailedCallState.OUTGOING_INIT -> "Iniciando llamada saliente"
            DetailedCallState.OUTGOING_PROGRESS -> "Progreso de llamada (${stateInfo.sipCode})"
            DetailedCallState.OUTGOING_RINGING -> "Teléfono sonando"
            DetailedCallState.INCOMING_RECEIVED -> "Llamada entrante recibida"
            DetailedCallState.CONNECTED -> "Llamada conectada"
            DetailedCallState.STREAMS_RUNNING -> "Audio en curso"
            DetailedCallState.PAUSING -> "Pausando..."
            DetailedCallState.PAUSED -> "En espera"
            DetailedCallState.RESUMING -> "Reanudando..."
            DetailedCallState.ENDING -> "Finalizando..."
            DetailedCallState.ENDED -> "Llamada terminada"
            DetailedCallState.ERROR -> "Error: ${SipErrorMapper.getErrorDescription(stateInfo.errorReason)}"
        }

        return if (stateInfo.sipCode != null) {
            "$baseMessage (${stateInfo.sipCode})"
        } else {
            baseMessage
        }
    }

    // OPTIMIZADO: Manejar cambios de estado específicos
    private fun handleStateChange(stateInfo: CallStateInfo) {
        when (stateInfo.state) {
            DetailedCallState.ERROR -> {
                // Manejar errores específicos
                when (stateInfo.errorReason) {
                    CallErrorReason.BUSY -> {
                        _uiState.update { it.copy(callMessage = "Línea ocupada") }
                    }
                    CallErrorReason.NO_ANSWER -> {
                        _uiState.update { it.copy(callMessage = "Sin respuesta") }
                    }
                    CallErrorReason.REJECTED -> {
                        _uiState.update { it.copy(callMessage = "Llamada rechazada") }
                    }
                    CallErrorReason.NETWORK_ERROR -> {
                        _uiState.update { it.copy(callMessage = "Error de red") }
                    }
                    else -> {
                        _uiState.update { it.copy(callMessage = "Error desconocido") }
                    }
                }
            }
            DetailedCallState.OUTGOING_RINGING -> {
                // Iniciar sonido de ringback si es necesario
                Log.d("SipViewModel", "Call is ringing - could start ringback tone")
            }
            DetailedCallState.STREAMS_RUNNING -> {
                // Audio está fluyendo - actualizar UI
                Log.d("SipViewModel", "Audio streams are running")
            }
            else -> {}
        }
    }

    // Timer de duración de llamada
    private var callDurationTimer: Job? = null
    private val _callDuration = MutableStateFlow(0L)
    val callDuration: StateFlow<Long> = _callDuration.asStateFlow()

    private fun startCallDurationTimer() {
        stopCallDurationTimer()
        callDurationTimer = viewModelScope.launch {
            var duration = 0L
            while (isActive) {
                _callDuration.value = duration
                delay(1000)
                duration += 1000
            }
        }
    }

    private fun stopCallDurationTimer() {
        callDurationTimer?.cancel()
        callDurationTimer = null
        _callDuration.value = 0L
    }

    // OPTIMIZADO: Métodos para obtener información
    fun getCurrentCallState(): CallStateInfo {
        return sipLibrary.getCurrentCallState()
    }

    fun getCallStateHistory(): List<CallStateInfo> {
        return sipLibrary.getCallStateHistory()
    }

    fun clearCallStateHistory() {
        sipLibrary.clearCallStateHistory()
    }

    fun getSystemDiagnostic(): String {
        return buildString {
            appendLine("=== SYSTEM DIAGNOSTIC ===")
            appendLine(sipLibrary.diagnoseListeners())
            appendLine("\n=== CALL STATE HISTORY ===")
            getCallStateHistory().takeLast(10).forEach { state ->
                appendLine("${state.timestamp}: ${state.previousState} -> ${state.state}")
                if (state.hasError()) {
                    appendLine("  Error: ${state.errorReason} (${state.sipCode})")
                }
            }
        }
    }

    // Métodos existentes...
    fun onPermissionsGranted() {
        _permissionsGranted.value = true
    }

    fun onPermissionsDenied() {
        _permissionsGranted.value = false
        _uiState.update { it.copy(
            registrationMessage = "Permissions required for SIP functionality"
        )}
    }

    fun registerAccount(username: String, password: String, domain: String, pushToken: String) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(
                    registrationMessage = "Registering...",
                    isRegistering = true
                )}

                sipLibrary.registerAccount(
                    username = username,
                    password = password,
                    domain = domain.ifEmpty { null },
                    pushToken = pushToken.ifEmpty { null }
                )

                _uiState.update { it.copy(
                    registeredUsername = username,
                    registeredDomain = domain,
                    isRegistering = false
                )}
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    registrationMessage = "Registration failed: ${e.message}",
                    isRegistering = false
                )}
            }
        }
    }

    fun makeCall(phoneNumber: String) {
        viewModelScope.launch {
            try {
                sipLibrary.makeCall(phoneNumber)
                _uiState.update { it.copy(
                    dialedNumber = phoneNumber
                )}
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    callMessage = "Failed to make call: ${e.message}"
                )}
            }
        }
    }

    fun acceptCall() {
        viewModelScope.launch {
            sipLibrary.acceptCall()
        }
    }

    fun declineCall() {
        viewModelScope.launch {
            sipLibrary.declineCall()
        }
    }

    fun endCall() {
        viewModelScope.launch {
            sipLibrary.endCall()
        }
    }

    fun holdCall() {
        viewModelScope.launch {
            sipLibrary.holdCall()
        }
    }

    fun resumeCall() {
        viewModelScope.launch {
            sipLibrary.resumeCall()
        }
    }

    fun toggleMute() {
        viewModelScope.launch {
            sipLibrary.toggleMute()
        }
    }

    fun sendDtmf(digit: Char) {
        viewModelScope.launch {
            val success = sipLibrary.sendDtmf(digit)
            _uiState.update { it.copy(
                callMessage = if (success) "DTMF sent: $digit" else "Failed to send DTMF: $digit"
            )}
        }
    }

    fun updateDialedNumber(number: String) {
        _uiState.update { it.copy(dialedNumber = number) }
    }

    fun clearDialedNumber() {
        _uiState.update { it.copy(dialedNumber = "") }
    }

    class Factory(private val sipLibrary: EddysSipLibrary) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SipViewModel::class.java)) {
                return SipViewModel(sipLibrary) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

// ACTUALIZADO: Estado de UI con nuevos campos
data class SipUiState(
    val registrationMessage: String = "Not registered",
    val callMessage: String = "No active calls",
    val dialedNumber: String = "",
    val registeredUsername: String = "",
    val registeredDomain: String = "",
    val isRegistered: Boolean = false,
    val isRegistering: Boolean = false,
    val currentCall: EddysSipLibrary.CallInfo? = null,
    val incomingCall: EddysSipLibrary.IncomingCallInfo? = null,
    
    // Campos para estados
    val detailedCallMessage: String = "Idle",
    val lastStateTransition: String = "",
    val hasCallError: Boolean = false,
    val errorReason: String? = null,
    val multiAccountStatus: String = "No accounts",
    val allAccountsRegistered: Boolean = false
)

// OPTIMIZADO: Extension functions para estados
fun DetailedCallState.isCallActive(): Boolean {
    return this in listOf(
        DetailedCallState.OUTGOING_INIT,
        DetailedCallState.OUTGOING_PROGRESS,
        DetailedCallState.OUTGOING_RINGING,
        DetailedCallState.INCOMING_RECEIVED,
        DetailedCallState.CONNECTED,
        DetailedCallState.STREAMS_RUNNING,
        DetailedCallState.PAUSING,
        DetailedCallState.PAUSED,
        DetailedCallState.RESUMING
    )
}

fun DetailedCallState.getDisplayText(): String {
    return when (this) {
        DetailedCallState.IDLE -> "Sin llamadas"
        DetailedCallState.OUTGOING_INIT -> "Iniciando..."
        DetailedCallState.OUTGOING_PROGRESS -> "Conectando..."
        DetailedCallState.OUTGOING_RINGING -> "Sonando..."
        DetailedCallState.INCOMING_RECEIVED -> "Llamada entrante"
        DetailedCallState.CONNECTED -> "Conectado"
        DetailedCallState.STREAMS_RUNNING -> "En llamada"
        DetailedCallState.PAUSING -> "Pausando..."
        DetailedCallState.PAUSED -> "En espera"
        DetailedCallState.RESUMING -> "Reanudando..."
        DetailedCallState.ENDING -> "Finalizando..."
        DetailedCallState.ENDED -> "Finalizada"
        DetailedCallState.ERROR -> "Error"
    }
}

// OBSOLETO: Mantenido para compatibilidad
@Deprecated("Use DetailedCallState.getDisplayText() instead")
fun CallState.getDisplayText(): String {
    return when (this) {
        CallState.NONE -> "No Call"
        CallState.INCOMING -> "Incoming Call"
        CallState.OUTGOING -> "Outgoing Call"
        CallState.CALLING -> "Calling..."
        CallState.RINGING -> "Ringing..."
        CallState.CONNECTED -> "Connected"
        CallState.HOLDING -> "On Hold"
        CallState.ACCEPTING -> "Accepting..."
        CallState.ENDING -> "Ending..."
        CallState.ENDED -> "Call Ended"
        CallState.DECLINED -> "Declined"
        CallState.ERROR -> "Error"
        CallState.IDLE -> "Idle"
        CallState.DIALING -> "Dialing..."
        CallState.PAUSED -> "Call Paused"
        CallState.FAILED -> "Call Failed"
        CallState.CANCELLED -> "Call Cancelled"
        CallState.DECLINING -> "Declining Call..."
        CallState.RESUMING -> "Resuming Call..."
        CallState.INITIATING -> "Initiating Call..."
    }
}