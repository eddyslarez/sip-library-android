package com.eddyslarez.siplibrary

import android.app.Application
import android.content.Context
import com.eddyslarez.siplibrary.core.SipCoreManager
import com.eddyslarez.siplibrary.data.models.CallDirections
import com.eddyslarez.siplibrary.data.models.CallLog
import com.eddyslarez.siplibrary.data.models.CallState
import com.eddyslarez.siplibrary.data.models.RegistrationState
import com.eddyslarez.siplibrary.data.services.audio.AudioDevice
import com.eddyslarez.siplibrary.utils.CallStateManager
import com.eddyslarez.siplibrary.utils.RegistrationStateManager
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * EddysSipLibrary - Biblioteca SIP/VoIP para Android (Versión Mejorada Multi-Cuenta)
 *
 * @author Eddys Larez
 * @version 1.2.0
 */
class EddysSipLibrary private constructor() {

    private var sipCoreManager: SipCoreManager? = null
    private var isInitialized = false
    private lateinit var config: SipConfig
    private val listeners = mutableSetOf<SipEventListener>()
    private var registrationListener: RegistrationListener? = null
    private var callListener: CallListener? = null
    private var incomingCallListener: IncomingCallListener? = null

    companion object {
        @Volatile
        private var INSTANCE: EddysSipLibrary? = null
        private const val TAG = "EddysSipLibrary"

        fun getInstance(): EddysSipLibrary {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: EddysSipLibrary().also { INSTANCE = it }
            }
        }
    }

    data class SipConfig(
        val defaultDomain: String = "",
        val webSocketUrl: String = "",
        val userAgent: String = "",
        val enableLogs: Boolean = true,
        val enableAutoReconnect: Boolean = true,
        val pingIntervalMs: Long = 30000L
    )

    /**
     * Listener principal para todos los eventos SIP
     */
    interface SipEventListener {
        fun onRegistrationStateChanged(state: RegistrationState, username: String, domain: String) {}
        fun onCallStateChanged(state: CallState, callInfo: CallInfo?) {}
        fun onIncomingCall(callInfo: IncomingCallInfo) {}
        fun onCallConnected(callInfo: CallInfo) {}
        fun onCallEnded(callInfo: CallInfo, reason: CallEndReason) {}
        fun onCallFailed(error: String, callInfo: CallInfo?) {}
        fun onDtmfReceived(digit: Char, callInfo: CallInfo) {}
        fun onAudioDeviceChanged(device: AudioDevice) {}
        fun onNetworkStateChanged(isConnected: Boolean) {}
    }

    /**
     * Listener específico para estados de registro
     */
    interface RegistrationListener {
        fun onRegistrationSuccessful(username: String, domain: String)
        fun onRegistrationFailed(username: String, domain: String, error: String)
        fun onUnregistered(username: String, domain: String)
        fun onRegistrationExpiring(username: String, domain: String, expiresIn: Long)
    }

    /**
     * Listener específico para estados de llamada
     */
    interface CallListener {
        fun onCallInitiated(callInfo: CallInfo)
        fun onCallRinging(callInfo: CallInfo)
        fun onCallConnected(callInfo: CallInfo)
        fun onCallHeld(callInfo: CallInfo)
        fun onCallResumed(callInfo: CallInfo)
        fun onCallEnded(callInfo: CallInfo, reason: CallEndReason)
        fun onCallTransferred(callInfo: CallInfo, transferTo: String)
        fun onMuteStateChanged(isMuted: Boolean, callInfo: CallInfo)
    }

    /**
     * Listener específico para llamadas entrantes
     */
    interface IncomingCallListener {
        fun onIncomingCall(callInfo: IncomingCallInfo)
        fun onIncomingCallCancelled(callInfo: IncomingCallInfo)
        fun onIncomingCallTimeout(callInfo: IncomingCallInfo)
    }

    /**
     * Información de llamada
     */
    data class CallInfo(
        val callId: String,
        val phoneNumber: String,
        val displayName: String?,
        val direction: CallDirection,
        val startTime: Long,
        val duration: Long = 0,
        val isOnHold: Boolean = false,
        val isMuted: Boolean = false,
        val localAccount: String,
        val codec: String? = null
    )

    /**
     * Información de llamada entrante
     */
    data class IncomingCallInfo(
        val callId: String,
        val callerNumber: String,
        val callerName: String?,
        val targetAccount: String,
        val timestamp: Long,
        val headers: Map<String, String> = emptyMap()
    )

    /**
     * Dirección de la llamada
     */
    enum class CallDirection {
        INCOMING, OUTGOING
    }

    /**
     * Razones de finalización de llamada
     */
    enum class CallEndReason {
        NORMAL_HANGUP,
        BUSY,
        NO_ANSWER,
        REJECTED,
        NETWORK_ERROR,
        CANCELLED,
        TIMEOUT,
        ERROR
    }

    fun initialize(
        application: Application,
        config: SipConfig = SipConfig()
    ) {
        if (isInitialized) {
            log.w(tag = TAG) { "Library already initialized" }
            return
        }

        try {
            log.d(tag = TAG) { "Initializing EddysSipLibrary v1.2.0 Multi-Account by Eddys Larez" }

            this.config = config
            sipCoreManager = SipCoreManager.createInstance(application, config)
            sipCoreManager?.initialize()

            // Configurar listeners internos
            setupInternalListeners()

            isInitialized = true
            log.d(tag = TAG) { "EddysSipLibrary initialized successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing library: ${e.message}" }
            throw SipLibraryException("Failed to initialize library", e)
        }
    }

    private fun setupInternalListeners() {
        sipCoreManager?.let { manager ->

            log.d(tag = TAG) { "Setting up internal listeners" }

            // Configurar callback para eventos principales
            manager.setCallbacks(object : SipCallbacks {
                override fun onCallTerminated() {
                    log.d(tag = TAG) { "Internal callback: Call terminated" }
                    val callInfo = getCurrentCallInfo()
                    notifyCallEnded(callInfo, CallEndReason.NORMAL_HANGUP)
                }

                override fun onCallStateChanged(state: CallState) {
                    log.d(tag = TAG) { "Internal callback: Call state changed to $state" }
                    val callInfo = getCurrentCallInfo()
                    notifyCallStateChanged(state, callInfo)

                    when (state) {
                        CallState.CONNECTED -> callInfo?.let { notifyCallConnected(it) }
                        CallState.RINGING, CallState.OUTGOING -> callInfo?.let { notifyCallRinging(it) }
                        CallState.INCOMING -> handleIncomingCall()
                        CallState.ENDED -> callInfo?.let { notifyCallEnded(it, CallEndReason.NORMAL_HANGUP) }
                        CallState.ERROR -> callInfo?.let { notifyCallFailed("Call failed", it) }
                        else -> {}
                    }
                }

                override fun onRegistrationStateChanged(state: RegistrationState) {
                    log.d(tag = TAG) { "Internal callback: Registration state changed to $state" }
                    // Este se maneja en onAccountRegistrationStateChanged
                }

                override fun onAccountRegistrationStateChanged(username: String, domain: String, state: RegistrationState) {
                    log.d(tag = TAG) { "Internal callback: Account registration state changed for $username@$domain to $state" }
                    notifyRegistrationStateChanged(state, username, domain)
                }

                override fun onIncomingCall(callerNumber: String, callerName: String?) {
                    log.d(tag = TAG) { "Internal callback: Incoming call from $callerNumber" }
                    val callInfo = IncomingCallInfo(
                        callId = generateCallId(),
                        callerNumber = callerNumber,
                        callerName = callerName,
                        targetAccount = manager.getCurrentUsername() ?: "",
                        timestamp = System.currentTimeMillis()
                    )
                    notifyIncomingCall(callInfo)
                }

                override fun onCallConnected() {
                    log.d(tag = TAG) { "Internal callback: Call connected" }
                    getCurrentCallInfo()?.let { notifyCallConnected(it) }
                }

                override fun onCallFailed(error: String) {
                    log.d(tag = TAG) { "Internal callback: Call failed - $error" }
                    val callInfo = getCurrentCallInfo()
                    notifyCallFailed(error, callInfo)
                }
            })

            log.d(tag = TAG) { "Internal listeners configured successfully" }
        }
    }


    // === MÉTODOS PARA CONFIGURAR LISTENERS ===

    /**
     * Añade un listener general para eventos SIP
     */
    fun addSipEventListener(listener: SipEventListener) {
        listeners.add(listener)
        log.d(tag = TAG) { "SipEventListener added. Total listeners: ${listeners.size}" }
    }

    /**
     * Remueve un listener general
     */
    fun removeSipEventListener(listener: SipEventListener) {
        listeners.remove(listener)
        log.d(tag = TAG) { "SipEventListener removed. Total listeners: ${listeners.size}" }
    }

    /**
     * Configura un listener específico para registro
     */
    fun setRegistrationListener(listener: RegistrationListener?) {
        this.registrationListener = listener
        log.d(tag = TAG) { "RegistrationListener configured" }
    }

    /**
     * Configura un listener específico para llamadas
     */
    fun setCallListener(listener: CallListener?) {
        this.callListener = listener
        log.d(tag = TAG) { "CallListener configured" }
    }

    /**
     * Configura un listener específico para llamadas entrantes
     */
    fun setIncomingCallListener(listener: IncomingCallListener?) {
        this.incomingCallListener = listener
        log.d(tag = TAG) { "IncomingCallListener configured" }
    }

    // === MÉTODOS DE NOTIFICACIÓN INTERNA ===

    private fun notifyRegistrationStateChanged(state: RegistrationState, username: String, domain: String) {
        log.d(tag = TAG) { "Notifying registration state change: $state for $username@$domain to ${listeners.size} listeners" }

        // CORREGIDO: Ejecutar en hilo principal para UI
        CoroutineScope(Dispatchers.Main).launch {
            listeners.forEach { listener ->
                try {
                    listener.onRegistrationStateChanged(state, username, domain)
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in listener onRegistrationStateChanged: ${e.message}" }
                }
            }

            registrationListener?.let { listener ->
                try {
                    when (state) {
                        RegistrationState.OK -> listener.onRegistrationSuccessful(username, domain)
                        RegistrationState.FAILED -> listener.onRegistrationFailed(username, domain, "Registration failed")
                        RegistrationState.NONE, RegistrationState.CLEARED -> listener.onUnregistered(username, domain)
                        else -> {}
                    }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in RegistrationListener: ${e.message}" }
                }
            }
        }
    }

    private fun notifyCallStateChanged(state: CallState, callInfo: CallInfo?) {
        log.d(tag = TAG) { "Notifying call state change: $state to ${listeners.size} listeners" }

        // CORREGIDO: Ejecutar en hilo principal para UI
        CoroutineScope(Dispatchers.Main).launch {
            listeners.forEach { listener ->
                try {
                    listener.onCallStateChanged(state, callInfo)
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in listener onCallStateChanged: ${e.message}" }
                }
            }
        }
    }


    private fun notifyCallConnected(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call connected to ${listeners.size} listeners" }

        listeners.forEach { listener ->
            try {
                listener.onCallConnected(callInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in listener onCallConnected: ${e.message}" }
            }
        }

        try {
            callListener?.onCallConnected(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallConnected: ${e.message}" }
        }
    }

    private fun notifyCallRinging(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call ringing" }

        try {
            callListener?.onCallRinging(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallRinging: ${e.message}" }
        }
    }

    private fun notifyCallEnded(callInfo: CallInfo?, reason: CallEndReason) {
        callInfo?.let { info ->
            log.d(tag = TAG) { "Notifying call ended to ${listeners.size} listeners" }

            listeners.forEach { listener ->
                try {
                    listener.onCallEnded(info, reason)
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in listener onCallEnded: ${e.message}" }
                }
            }

            try {
                callListener?.onCallEnded(info, reason)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in CallListener onCallEnded: ${e.message}" }
            }
        }
    }

    private fun notifyIncomingCall(callInfo: IncomingCallInfo) {
        log.d(tag = TAG) { "Notifying incoming call to ${listeners.size} listeners" }

        listeners.forEach { listener ->
            try {
                listener.onIncomingCall(callInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in listener onIncomingCall: ${e.message}" }
            }
        }

        try {
            incomingCallListener?.onIncomingCall(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in IncomingCallListener: ${e.message}" }
        }
    }

    private fun notifyCallFailed(error: String, callInfo: CallInfo?) {
        log.d(tag = TAG) { "Notifying call failed to ${listeners.size} listeners" }

        listeners.forEach { listener ->
            try {
                listener.onCallFailed(error, callInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in listener onCallFailed: ${e.message}" }
            }
        }
    }

    private fun handleIncomingCall() {
        // Implementar lógica para manejar llamada entrante
        val callerNumber = extractCallerNumber()
        val callerName = extractCallerName()

        val callInfo = IncomingCallInfo(
            callId = generateCallId(),
            callerNumber = callerNumber,
            callerName = callerName,
            targetAccount = sipCoreManager?.getCurrentUsername() ?: "",
            timestamp = System.currentTimeMillis()
        )

        notifyIncomingCall(callInfo)
    }

    // === MÉTODOS AUXILIARES ===

    private fun getCurrentCallInfo(): CallInfo? {
        val manager = sipCoreManager ?: return null
        val account = manager.currentAccountInfo ?: return null
        val callData = account.currentCallData ?: return null

        return callData.isOnHold?.let {
            CallInfo(
                callId = callData.callId,
                phoneNumber = if (callData.direction == CallDirections.INCOMING) callData.from else callData.to,
                displayName = null, // Extraer del SIP si está disponible
                direction = if (callData.direction == CallDirections.INCOMING) CallDirection.INCOMING else CallDirection.OUTGOING,
                startTime = manager.callStartTimeMillis,
                duration = if (manager.callStartTimeMillis > 0) System.currentTimeMillis() - manager.callStartTimeMillis else 0,
                isOnHold = it,
                isMuted = manager.webRtcManager.isMuted(),
                localAccount = account.username,
                codec = null // Extraer del SDP si está disponible
            )
        }
    }

    private fun extractCallerNumber(): String {
        // Implementar extracción del número del caller desde el mensaje SIP
        return sipCoreManager?.currentAccountInfo?.currentCallData?.from ?: ""
    }

    private fun extractCallerName(): String? {
        // Implementar extracción del nombre del caller desde el mensaje SIP
        return null
    }

    private fun generateCallId(): String {
        return "call_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }

    // === MÉTODOS PÚBLICOS DE LA API ===

    /**
     * Registra una cuenta SIP
     */
    fun registerAccount(
        username: String,
        password: String,
        domain: String? = null,
        pushToken: String? = null,
        pushProvider: String = "fcm"
    ) {
        checkInitialized()

        val finalDomain = domain ?: sipCoreManager?.getDefaultDomain() ?: "mcn.ru"
        val finalToken = pushToken ?: ""

        log.d(tag = TAG) { "Registering account: $username@$finalDomain" }

        sipCoreManager?.register(
            username = username,
            password = password,
            domain = finalDomain,
            provider = pushProvider,
            token = finalToken
        )
    }

    /**
     * Desregistra una cuenta SIP específica
     */
    fun unregisterAccount(username: String, domain: String) {
        checkInitialized()
        log.d(tag = TAG) { "Unregistering account: $username@$domain" }
        sipCoreManager?.unregister(username, domain)
    }

    /**
     * Desregistra todas las cuentas
     */
    fun unregisterAllAccounts() {
        checkInitialized()
        log.d(tag = TAG) { "Unregistering all accounts" }
        sipCoreManager?.unregisterAllAccounts()
    }

    /**
     * NUEVO: Obtiene el estado de registro para una cuenta específica
     */
    fun getRegistrationState(username: String, domain: String): RegistrationState {
        checkInitialized()
        val accountKey = "$username@$domain"
        return sipCoreManager?.getRegistrationState(accountKey) ?: RegistrationState.NONE
    }

    /**
     * NUEVO: Obtiene todos los estados de registro
     */
    fun getAllRegistrationStates(): Map<String, RegistrationState> {
        checkInitialized()
        return sipCoreManager?.getAllRegistrationStates() ?: emptyMap()
    }

    /**
     * NUEVO: Flow para observar estados de registro de todas las cuentas
     */
    fun getRegistrationStatesFlow(): Flow<Map<String, RegistrationState>> {
        checkInitialized()
        return sipCoreManager?.registrationStatesFlow ?: flowOf(emptyMap())
    }

    /**
     * Cambia el dispositivo de audio (entrada o salida) durante una llamada.
     */
    fun changeAudioDevice(device: AudioDevice) {
        checkInitialized()
        sipCoreManager?.changeAudioDevice(device)
    }

    /**
     * Refresca la lista de dispositivos de audio disponibles.
     */
    fun refreshAudioDevices() {
        checkInitialized()
        sipCoreManager?.refreshAudioDevices()
    }

    /**
     * Devuelve el par de dispositivos de audio actuales (input, output).
     */
    fun getCurrentAudioDevices(): Pair<AudioDevice?, AudioDevice?> {
        checkInitialized()
        return sipCoreManager?.getCurrentDevices() ?: Pair(null, null)
    }

    /**
     * Devuelve todos los dispositivos de audio disponibles (inputs, outputs).
     */
    fun getAvailableAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        checkInitialized()
        return sipCoreManager?.getAudioDevices() ?: Pair(emptyList(), emptyList())
    }

    fun makeCall(
        phoneNumber: String,
        username: String? = null,
        domain: String? = null
    ) {
        checkInitialized()

        val finalUsername = username ?: sipCoreManager?.getCurrentUsername()
        val finalDomain = domain ?: sipCoreManager?.getDefaultDomain() ?: ""

        if (finalUsername == null) {
            throw SipLibraryException("No registered account available for calling")
        }

        log.d(tag = TAG) { "Making call to $phoneNumber from $finalUsername@$finalDomain" }

        sipCoreManager?.makeCall(phoneNumber, finalUsername, finalDomain)

        // Notificar inicio de llamada
        val callInfo = CallInfo(
            callId = generateCallId(),
            phoneNumber = phoneNumber,
            displayName = null,
            direction = CallDirection.OUTGOING,
            startTime = System.currentTimeMillis(),
            localAccount = finalUsername
        )

        try {
            callListener?.onCallInitiated(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallInitiated: ${e.message}" }
        }
    }

    fun acceptCall() {
        checkInitialized()
        log.d(tag = TAG) { "Accepting call" }
        sipCoreManager?.acceptCall()
    }

    fun declineCall() {
        checkInitialized()
        log.d(tag = TAG) { "Declining call" }
        sipCoreManager?.declineCall()

        // Notificar llamada rechazada
        getCurrentCallInfo()?.let { callInfo ->
            val reason = CallEndReason.REJECTED
            notifyCallEnded(callInfo, reason)
        }
    }

    fun endCall() {
        checkInitialized()
        log.d(tag = TAG) { "Ending call" }

        val callInfo = getCurrentCallInfo()
        sipCoreManager?.endCall()

        callInfo?.let { info ->
            notifyCallEnded(info, CallEndReason.NORMAL_HANGUP)
        }
    }

    fun holdCall() {
        checkInitialized()
        log.d(tag = TAG) { "Holding call" }
        sipCoreManager?.holdCall()

        getCurrentCallInfo()?.let { callInfo ->
            val heldCallInfo = callInfo.copy(isOnHold = true)
            try {
                callListener?.onCallHeld(heldCallInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in CallListener onCallHeld: ${e.message}" }
            }
        }
    }

    fun resumeCall() {
        checkInitialized()
        log.d(tag = TAG) { "Resuming call" }
        sipCoreManager?.resumeCall()

        getCurrentCallInfo()?.let { callInfo ->
            val resumedCallInfo = callInfo.copy(isOnHold = false)
            try {
                callListener?.onCallResumed(resumedCallInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in CallListener onCallResumed: ${e.message}" }
            }
        }
    }

    fun toggleMute() {
        checkInitialized()
        log.d(tag = TAG) { "Toggling mute" }
        sipCoreManager?.mute()

        getCurrentCallInfo()?.let { callInfo ->
            val isMuted = sipCoreManager?.webRtcManager?.isMuted() ?: false
            val mutedCallInfo = callInfo.copy(isMuted = isMuted)
            try {
                callListener?.onMuteStateChanged(isMuted, mutedCallInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in CallListener onMuteStateChanged: ${e.message}" }
            }
        }
    }

    // === MÉTODOS DE INFORMACIÓN ===

    fun getCurrentCallState(): CallState {
        checkInitialized()
        return sipCoreManager?.callState ?: CallState.NONE
    }

    /**
     * OBSOLETO: Use getRegistrationStatesFlow() o getAllRegistrationStates() en su lugar
     */
    @Deprecated("Use getRegistrationStatesFlow() para obtener estados de todas las cuentas")
    fun getRegistrationState(): RegistrationState {
        checkInitialized()
        return sipCoreManager?.getRegistrationState() ?: RegistrationState.NONE
    }

    fun hasActiveCall(): Boolean {
        checkInitialized()
        return sipCoreManager?.currentCall() ?: false
    }

    fun getCurrentCallInfos(): CallInfo? {
        checkInitialized()
        return getCurrentCallInfo()
    }

    // === FLOWS PARA COMPOSE/COROUTINES ===

    fun getCallStateFlow(): Flow<CallState> {
        checkInitialized()
        return CallStateManager.callStateFlow
    }

    /**
     * OBSOLETO: Use getRegistrationStatesFlow() en su lugar
     */
    @Deprecated("Use getRegistrationStatesFlow() para obtener estados multi-cuenta")
    fun getRegistrationStateFlow(): Flow<RegistrationState> {
        checkInitialized()
        return RegistrationStateManager.registrationStateFlow
    }

    private fun checkInitialized() {
        if (!isInitialized || sipCoreManager == null) {
            throw SipLibraryException("Library not initialized. Call initialize() first.")
        }
    }
    fun diagnoseListeners(): String {
        return buildString {
            appendLine("=== LISTENERS DIAGNOSTIC ===")
            appendLine("Library initialized: $isInitialized")
            appendLine("SipCoreManager: ${sipCoreManager != null}")
            appendLine("General listeners count: ${listeners.size}")
            appendLine("Registration listener: ${registrationListener != null}")
            appendLine("Call listener: ${callListener != null}")
            appendLine("Incoming call listener: ${incomingCallListener != null}")

            appendLine("\n--- Current States ---")
            appendLine("Current call state: ${getCurrentCallState()}")
            appendLine("Registration states: ${getAllRegistrationStates()}")

            appendLine("\n--- Active Accounts ---")
            sipCoreManager?.let { manager ->
                appendLine("Current account: ${manager.getCurrentUsername()}")
                appendLine("Core manager healthy: ${manager.isSipCoreManagerHealthy()}")
            }
        }
    }

    fun dispose() {
        if (isInitialized) {
            log.d(tag = TAG) { "Disposing EddysSipLibrary" }
            sipCoreManager?.dispose()
            sipCoreManager = null
            listeners.clear()
            registrationListener = null
            callListener = null
            incomingCallListener = null
            isInitialized = false
            log.d(tag = TAG) { "EddysSipLibrary disposed" }
        }
    }

    // === INTERFAZ INTERNA DE CALLBACKS ===

    internal interface SipCallbacks {
        fun onCallTerminated() {}
        fun onCallStateChanged(state: CallState) {}
        fun onRegistrationStateChanged(state: RegistrationState) {}
        fun onIncomingCall(callerNumber: String, callerName: String?) {}
        fun onCallConnected() {}
        fun onCallFailed(error: String) {}
        fun onAccountRegistrationStateChanged(username: String, domain: String, state: RegistrationState) {}
    }


    class SipLibraryException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
//class EddysSipLibrary private constructor() {
//
//    private var sipCoreManager: SipCoreManager? = null
//    private var isInitialized = false
//    private lateinit var config: SipConfig
//
//    companion object {
//        @Volatile
//        private var INSTANCE: EddysSipLibrary? = null
//        private const val TAG = "EddysSipLibrary"
//
//        /**
//         * Obtiene la instancia singleton de la biblioteca
//         */
//        fun getInstance(): EddysSipLibrary {
//            return INSTANCE ?: synchronized(this) {
//                INSTANCE ?: EddysSipLibrary().also { INSTANCE = it }
//            }
//        }
//    }
//
//    /**
//     * Configuración de la biblioteca
//     */
//    data class SipConfig(
//        val defaultDomain: String = "",
//        val webSocketUrl: String = "",
//        val userAgent: String = "",
//        val enableLogs: Boolean = true,
//        val enableAutoReconnect: Boolean = true,
//        val pingIntervalMs: Long = 30000L
//    )
//
//    /**
//     * Inicializa la biblioteca SIP
//     *
//     * @param application Instancia de la aplicación Android
//     * @param config Configuración opcional de la biblioteca
//     */
////    fun initialize(
////        application: Application,
////        config: SipConfig = SipConfig()
////    ) {
////        if (isInitialized) {
////            log.w(tag = TAG) { "Library already initialized" }
////            return
////        }
////
////        try {
////            log.d(tag = TAG) { "Initializing EddysSipLibrary v1.0.0 by Eddys Larez" }
////
////            // Inicializar el core manager
////            sipCoreManager = SipCoreManager.createInstance(application, config)
////            sipCoreManager?.initialize()
////
////            isInitialized = true
////            log.d(tag = TAG) { "EddysSipLibrary initialized successfully" }
////
////        } catch (e: Exception) {
////            log.e(tag = TAG) { "Error initializing library: ${e.message}" }
////            throw SipLibraryException("Failed to initialize library", e)
////        }
////    }
//
//    fun initialize(
//        application: Application,
//        config: SipConfig = SipConfig()
//    ) {
//        if (isInitialized) {
//            log.w(tag = TAG) { "Library already initialized" }
//            return
//        }
//
//        try {
//            log.d(tag = TAG) { "Initializing EddysSipLibrary v1.0.0 by Eddys Larez" }
//
//            this.config = config // <--- GUARDAMOS CONFIGURACIÓN
//            sipCoreManager = SipCoreManager.createInstance(application, config)
//            sipCoreManager?.initialize()
//
//            isInitialized = true
//            log.d(tag = TAG) { "EddysSipLibrary initialized successfully" }
//
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error initializing library: ${e.message}" }
//            throw SipLibraryException("Failed to initialize library", e)
//        }
//    }
//
//
//    /**
//     * Registra una cuenta SIP
//     *
//     * @param username Nombre de usuario SIP
//     * @param password Contraseña SIP
//     * @param domain Dominio SIP (opcional, usa el configurado por defecto)
//     * @param pushToken Token para notificaciones push (opcional)
//     * @param pushProvider Proveedor de push (fcm/apns)
//     */
//    fun registerAccount(
//        username: String,
//        password: String,
//        domain: String? = null,
//        pushToken: String? = null,
//        pushProvider: String = "fcm"
//    ) {
//        checkInitialized()
//
//        val finalDomain = domain ?: sipCoreManager?.getDefaultDomain() ?: "mcn.ru"
//        val finalToken = pushToken ?: ""
//
//        sipCoreManager?.register(
//            username = username,
//            password = password,
//            domain = finalDomain,
//            provider = pushProvider,
//            token = finalToken
//        )
//    }
//
//    /**
//     * Desregistra una cuenta SIP
//     */
//    fun unregisterAccount(username: String, domain: String? = null) {
//        checkInitialized()
//        val finalDomain = domain ?: sipCoreManager?.getDefaultDomain() ?: ""
//        sipCoreManager?.unregister(username, finalDomain)
//    }
//
//    /**
//     * Realiza una llamada
//     *
//     * @param phoneNumber Número de teléfono a llamar
//     * @param username Cuenta SIP a usar (opcional)
//     * @param domain Dominio SIP (opcional)
//     */
//    fun makeCall(
//        phoneNumber: String,
//        username: String? = null,
//        domain: String? = null
//    ) {
//        checkInitialized()
//
//        val finalUsername = username ?: sipCoreManager?.getCurrentUsername()
//        val finalDomain = domain ?: sipCoreManager?.getDefaultDomain() ?: ""
//
//        if (finalUsername == null) {
//            throw SipLibraryException("No registered account available for calling")
//        }
//
//        sipCoreManager?.makeCall(phoneNumber, finalUsername, finalDomain)
//    }
//
//    /**
//     * Acepta una llamada entrante
//     */
//    fun acceptCall() {
//        checkInitialized()
//        sipCoreManager?.acceptCall()
//    }
//
//    /**
//     * Rechaza una llamada entrante
//     */
//    fun declineCall() {
//        checkInitialized()
//        sipCoreManager?.declineCall()
//    }
//
//    /**
//     * Termina la llamada actual
//     */
//    fun endCall() {
//        checkInitialized()
//        sipCoreManager?.endCall()
//    }
//
//    /**
//     * Pone la llamada en espera
//     */
//    fun holdCall() {
//        checkInitialized()
//        sipCoreManager?.holdCall()
//    }
//
//    /**
//     * Reanuda una llamada en espera
//     */
//    fun resumeCall() {
//        checkInitialized()
//        sipCoreManager?.resumeCall()
//    }
//
//    /**
//     * Envía tonos DTMF
//     *
//     * @param digit Dígito DTMF (0-9, *, #, A-D)
//     * @param duration Duración en milisegundos
//     */
//    fun sendDtmf(digit: Char, duration: Int = 160): Boolean {
//        checkInitialized()
//        return sipCoreManager?.sendDtmf(digit, duration) ?: false
//    }
//
//    /**
//     * Envía una secuencia de tonos DTMF
//     */
//    fun sendDtmfSequence(digits: String, duration: Int = 160): Boolean {
//        checkInitialized()
//        return sipCoreManager?.sendDtmfSequence(digits, duration) ?: false
//    }
//
//    /**
//     * Silencia/desmute el micrófono
//     */
//    fun toggleMute() {
//        checkInitialized()
//        sipCoreManager?.mute()
//    }
//
//    /**
//     * Verifica si está silenciado
//     */
//    fun isMuted(): Boolean {
//        checkInitialized()
//        return sipCoreManager?.webRtcManager?.isMuted() ?: false
//    }
//
//    /**
//     * Obtiene dispositivos de audio disponibles
//     */
//    fun getAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
//        checkInitialized()
//        return sipCoreManager?.webRtcManager?.getAllAudioDevices() ?: Pair(emptyList(), emptyList())
//    }
//
//    /**
//     * Cambia el dispositivo de audio de salida
//     */
//    fun changeAudioOutput(device: AudioDevice): Boolean {
//        checkInitialized()
//        return sipCoreManager?.webRtcManager?.changeAudioOutputDeviceDuringCall(device) ?: false
//    }
//
//    /**
//     * Obtiene el historial de llamadas
//     */
//    fun getCallLogs(): List<CallLog> {
//        checkInitialized()
//        return sipCoreManager?.callLogs() ?: emptyList()
//    }
//
//    /**
//     * Limpia el historial de llamadas
//     */
//    fun clearCallLogs() {
//        checkInitialized()
//        sipCoreManager?.clearCallLogs()
//    }
//
//    /**
//     * Obtiene el estado actual de la llamada
//     */
//    fun getCurrentCallState(): CallState {
//        checkInitialized()
//        return sipCoreManager?.callState ?: CallState.NONE
//    }
//
//    /**
//     * Obtiene el estado de registro
//     */
//    fun getRegistrationState(): RegistrationState {
//        checkInitialized()
//        return sipCoreManager?.getRegistrationState() ?: RegistrationState.NONE
//    }
//
//    /**
//     * Verifica si hay una llamada activa
//     */
//    fun hasActiveCall(): Boolean {
//        checkInitialized()
//        return sipCoreManager?.currentCall() ?: false
//    }
//
//    /**
//     * Verifica si la llamada está conectada
//     */
//    fun isCallConnected(): Boolean {
//        checkInitialized()
//        return sipCoreManager?.currentCallConnected() ?: false
//    }
//
//    /**
//     * Obtiene el Flow del estado de llamada para observar cambios
//     */
//    fun getCallStateFlow(): Flow<CallState> {
//        checkInitialized()
//        return CallStateManager.callStateFlow
//    }
//
//    /**
//     * Obtiene el Flow del estado de registro para observar cambios
//     */
//    fun getRegistrationStateFlow(): Flow<RegistrationState> {
//        checkInitialized()
//        return RegistrationStateManager.registrationStateFlow
//    }
//
//    /**
//     * Actualiza el token de push notifications
//     */
//    fun updatePushToken(token: String, provider: String = "fcm") {
//        checkInitialized()
//        sipCoreManager?.enterPushMode(token)
//    }
//
//    /**
//     * Obtiene información de salud del sistema
//     */
//    fun getSystemHealthReport(): String {
//        checkInitialized()
//        return sipCoreManager?.getSystemHealthReport() ?: "Library not initialized"
//    }
//
//    /**
//     * Verifica si el sistema está saludable
//     */
//    fun isSystemHealthy(): Boolean {
//        checkInitialized()
//        return sipCoreManager?.isSipCoreManagerHealthy() ?: false
//    }
//
//    /**
//     * Configura callbacks para eventos de la biblioteca
//     */
//    fun setCallbacks(callbacks: SipCallbacks) {
//        checkInitialized()
//        sipCoreManager?.onCallTerminated = { callbacks.onCallTerminated() }
//    }
//
//    /**
//     * Libera recursos de la biblioteca
//     */
//    fun dispose() {
//        if (isInitialized) {
//            sipCoreManager?.dispose()
//            sipCoreManager = null
//            isInitialized = false
//            log.d(tag = TAG) { "EddysSipLibrary disposed" }
//        }
//    }
//
//    private fun checkInitialized() {
//        if (!isInitialized || sipCoreManager == null) {
//            throw SipLibraryException("Library not initialized. Call initialize() first.")
//        }
//    }
//
//    /**
//     * Interface para callbacks de eventos
//     */
//    interface SipCallbacks {
//        fun onCallTerminated() {}
//        fun onCallStateChanged(state: CallState) {}
//        fun onRegistrationStateChanged(state: RegistrationState) {}
//        fun onIncomingCall(callerNumber: String, callerName: String?) {}
//        fun onCallConnected() {}
//        fun onCallFailed(error: String) {}
//    }
//
//    /**
//     * Excepción personalizada para la biblioteca
//     */
//    class SipLibraryException(message: String, cause: Throwable? = null) : Exception(message, cause)
//}