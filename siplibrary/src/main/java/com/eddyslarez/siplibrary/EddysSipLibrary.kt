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
import kotlinx.coroutines.flow.Flow

/**
 * EddysSipLibrary - Biblioteca SIP/VoIP para Android
 * 
 * Biblioteca desarrollada por Eddys Larez para manejo de llamadas SIP/VoIP
 * con soporte para WebRTC y WebSocket.
 * 
 * @author Eddys Larez
 * @version 1.0.0
 */

/**
 * EddysSipLibrary - Biblioteca SIP/VoIP para Android (Versión Mejorada con Listeners)
 *
 * @author Eddys Larez
 * @version 1.1.0
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
            log.d(tag = TAG) { "Initializing EddysSipLibrary v1.1.0 by Eddys Larez" }

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

            // Configurar callback para eventos principales
            manager.setCallbacks(object : SipCallbacks {
                override fun onCallTerminated() {
                    val callInfo = getCurrentCallInfo()
                    notifyCallEnded(callInfo, CallEndReason.NORMAL_HANGUP)
                }

                override fun onCallStateChanged(state: CallState) {
                    val callInfo = getCurrentCallInfo()
                    notifyCallStateChanged(state, callInfo)

                    when (state) {
                        CallState.CONNECTED -> callInfo?.let { notifyCallConnected(it) }
                        CallState.RINGING -> callInfo?.let { notifyCallRinging(it) }
                        CallState.INCOMING -> handleIncomingCall()
                        else -> {}
                    }
                }

                override fun onRegistrationStateChanged(state: RegistrationState) {
                    val username = manager.getCurrentUsername() ?: ""
                    val domain = manager.getDefaultDomain() ?: ""
                    notifyRegistrationStateChanged(state, username, domain)
                }

                override fun onIncomingCall(callerNumber: String, callerName: String?) {
                    val callInfo = createIncomingCallInfo(callerNumber, callerName)
                    notifyIncomingCall(callInfo)
                }

                override fun onCallConnected() {
                    getCurrentCallInfo()?.let { notifyCallConnected(it) }
                }

                override fun onCallFailed(error: String) {
                    val callInfo = getCurrentCallInfo()
                    notifyCallFailed(error, callInfo)
                }
            })
        }
    }

    // === MÉTODOS PARA CONFIGURAR LISTENERS ===

    /**
     * Añade un listener general para eventos SIP
     */
    fun addSipEventListener(listener: SipEventListener) {
        listeners.add(listener)
    }

    /**
     * Remueve un listener general
     */
    fun removeSipEventListener(listener: SipEventListener) {
        listeners.remove(listener)
    }

    /**
     * Configura un listener específico para registro
     */
    fun setRegistrationListener(listener: RegistrationListener?) {
        this.registrationListener = listener
    }

    /**
     * Configura un listener específico para llamadas
     */
    fun setCallListener(listener: CallListener?) {
        this.callListener = listener
    }

    /**
     * Configura un listener específico para llamadas entrantes
     */
    fun setIncomingCallListener(listener: IncomingCallListener?) {
        this.incomingCallListener = listener
    }

    // === MÉTODOS DE NOTIFICACIÓN INTERNA ===

    private fun notifyRegistrationStateChanged(state: RegistrationState, username: String, domain: String) {
        listeners.forEach { it.onRegistrationStateChanged(state, username, domain) }

        registrationListener?.let { listener ->
            when (state) {
                RegistrationState.OK -> listener.onRegistrationSuccessful(username, domain)
                RegistrationState.FAILED -> listener.onRegistrationFailed(username, domain, "Registration failed")
                RegistrationState.NONE -> listener.onUnregistered(username, domain)
                else -> {}
            }
        }
    }

    private fun notifyCallStateChanged(state: CallState, callInfo: CallInfo?) {
        listeners.forEach { it.onCallStateChanged(state, callInfo) }
    }

    private fun notifyCallConnected(callInfo: CallInfo) {
        listeners.forEach { it.onCallConnected(callInfo) }
        callListener?.onCallConnected(callInfo)
    }

    private fun notifyCallRinging(callInfo: CallInfo) {
        callListener?.onCallRinging(callInfo)
    }

    private fun notifyCallEnded(callInfo: CallInfo?, reason: CallEndReason) {
        callInfo?.let { info ->
            listeners.forEach { it.onCallEnded(info, reason) }
            callListener?.onCallEnded(info, reason)
        }
    }

    private fun notifyIncomingCall(callInfo: IncomingCallInfo) {
        listeners.forEach { it.onIncomingCall(callInfo) }
        incomingCallListener?.onIncomingCall(callInfo)
    }

    private fun notifyCallFailed(error: String, callInfo: CallInfo?) {
        listeners.forEach { it.onCallFailed(error, callInfo) }
    }

    private fun handleIncomingCall() {
        // Crear información de llamada entrante desde el core manager
        val manager = sipCoreManager ?: return
        val account = manager.currentAccountInfo ?: return
        val callData = account.currentCallData ?: return

        val callInfo = IncomingCallInfo(
            callId = callData.callId,
            callerNumber = callData.from,
            callerName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
            targetAccount = account.username,
            timestamp = callData.startTime
        )

        notifyIncomingCall(callInfo)
    }

    private fun createIncomingCallInfo(callerNumber: String, callerName: String?): IncomingCallInfo {
        val manager = sipCoreManager ?: return IncomingCallInfo(
            callId = generateCallId(),
            callerNumber = callerNumber,
            callerName = callerName,
            targetAccount = "",
            timestamp = System.currentTimeMillis()
        )

        val account = manager.currentAccountInfo
        val callData = account?.currentCallData

        return IncomingCallInfo(
            callId = callData?.callId ?: generateCallId(),
            callerNumber = callerNumber,
            callerName = callerName,
            targetAccount = account?.username ?: "",
            timestamp = callData?.startTime ?: System.currentTimeMillis()
        )
    }

    // === MÉTODOS AUXILIARES ===

    private fun getCurrentCallInfo(): CallInfo? {
        val manager = sipCoreManager ?: return null
        val account = manager.currentAccountInfo ?: return null
        val callData = account.currentCallData ?: return null

        return CallInfo(
            callId = callData.callId,
            phoneNumber = if (callData.direction == CallDirections.INCOMING) callData.from else callData.to,
            displayName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
            direction = if (callData.direction == CallDirections.INCOMING) CallDirection.INCOMING else CallDirection.OUTGOING,
            startTime = manager.callStartTimeMillis,
            duration = if (manager.callStartTimeMillis > 0) System.currentTimeMillis() - manager.callStartTimeMillis else 0,
            isOnHold = callData.isOnHold ?: false,
            isMuted = manager.webRtcManager.isMuted(),
            localAccount = account.username,
            codec = null // Extraer del SDP si está disponible
        )
    }

    private fun extractCallerNumber(): String {
        // Implementar extracción del número del caller desde el mensaje SIP
        return sipCoreManager?.currentAccountInfo?.currentCallData?.from ?: ""
    }

    private fun extractCallerName(): String? {
        // Implementar extracción del nombre del caller desde el mensaje SIP
        return sipCoreManager?.currentAccountInfo?.currentCallData?.remoteDisplayName?.takeIf { it.isNotEmpty() }
    }

    private fun generateCallId(): String {
        return "call_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }

    // === MÉTODOS EXISTENTES (mantener compatibilidad) ===

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

        sipCoreManager?.register(
            username = username,
            password = password,
            domain = finalDomain,
            provider = pushProvider,
            token = finalToken
        )
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

        callListener?.onCallInitiated(callInfo)
    }

    fun acceptCall() {
        checkInitialized()
        sipCoreManager?.acceptCall()
    }

    fun declineCall() {
        checkInitialized()
        sipCoreManager?.declineCall()

        // Notificar llamada rechazada
        getCurrentCallInfo()?.let { callInfo ->
            val reason = CallEndReason.REJECTED
            notifyCallEnded(callInfo, reason)
        }
    }

    fun endCall() {
        checkInitialized()

        val callInfo = getCurrentCallInfo()
        sipCoreManager?.endCall()

        callInfo?.let { info ->
            notifyCallEnded(info, CallEndReason.NORMAL_HANGUP)
        }
    }

    fun holdCall() {
        checkInitialized()
        sipCoreManager?.holdCall()

        getCurrentCallInfo()?.let { callInfo ->
            val heldCallInfo = callInfo.copy(isOnHold = true)
            callListener?.onCallHeld(heldCallInfo)
        }
    }

    fun resumeCall() {
        checkInitialized()
        sipCoreManager?.resumeCall()

        getCurrentCallInfo()?.let { callInfo ->
            val resumedCallInfo = callInfo.copy(isOnHold = false)
            callListener?.onCallResumed(resumedCallInfo)
        }
    }

    fun toggleMute() {
        checkInitialized()
        sipCoreManager?.mute()

        getCurrentCallInfo()?.let { callInfo ->
            val isMuted = sipCoreManager?.webRtcManager?.isMuted() ?: false
            val mutedCallInfo = callInfo.copy(isMuted = isMuted)
            callListener?.onMuteStateChanged(isMuted, mutedCallInfo)
        }
    }

    // === MÉTODOS DE INFORMACIÓN ===

    fun getCurrentCallState(): CallState {
        checkInitialized()
        return sipCoreManager?.callState ?: CallState.NONE
    }

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

    fun getRegistrationStateFlow(): Flow<RegistrationState> {
        checkInitialized()
        return RegistrationStateManager.registrationStateFlow
    }

    // === MÉTODOS ADICIONALES ===

    fun sendDtmf(digit: Char, duration: Int = 160): Boolean {
        checkInitialized()
        return sipCoreManager?.sendDtmf(digit, duration) ?: false
    }

    fun sendDtmfSequence(digits: String, duration: Int = 160): Boolean {
        checkInitialized()
        return sipCoreManager?.sendDtmfSequence(digits, duration) ?: false
    }

    fun isMuted(): Boolean {
        checkInitialized()
        return sipCoreManager?.webRtcManager?.isMuted() ?: false
    }

    fun getAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        checkInitialized()
        return sipCoreManager?.webRtcManager?.getAllAudioDevices() ?: Pair(emptyList(), emptyList())
    }

    fun changeAudioOutput(device: AudioDevice): Boolean {
        checkInitialized()
        return sipCoreManager?.webRtcManager?.changeAudioOutputDeviceDuringCall(device) ?: false
    }

    fun getCallLogs(): List<CallLog> {
        checkInitialized()
        return sipCoreManager?.callLogs() ?: emptyList()
    }

    fun getMissedCalls(): List<CallLog> {
        checkInitialized()
        return sipCoreManager?.getMissedCalls() ?: emptyList()
    }

    fun getCallLogsForNumber(phoneNumber: String): List<CallLog> {
        checkInitialized()
        return sipCoreManager?.getCallLogsForNumber(phoneNumber) ?: emptyList()
    }

    fun clearCallLogs() {
        checkInitialized()
        sipCoreManager?.clearCallLogs()
    }

    fun updatePushToken(token: String, provider: String = "fcm") {
        checkInitialized()
        sipCoreManager?.enterPushMode(token)
    }

    fun getSystemHealthReport(): String {
        checkInitialized()
        return sipCoreManager?.getSystemHealthReport() ?: "Library not initialized"
    }

    fun isSystemHealthy(): Boolean {
        checkInitialized()
        return sipCoreManager?.isSipCoreManagerHealthy() ?: false
    }

    private fun checkInitialized() {
        if (!isInitialized || sipCoreManager == null) {
            throw SipLibraryException("Library not initialized. Call initialize() first.")
        }
    }

    fun dispose() {
        if (isInitialized) {
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

    // === INTERFACES DE COMPATIBILIDAD ===

    interface SipCallbacks {
        fun onCallTerminated() {}
        fun onCallStateChanged(state: CallState) {}
        fun onRegistrationStateChanged(state: RegistrationState) {}
        fun onIncomingCall(callerNumber: String, callerName: String?) {}
        fun onCallConnected() {}
        fun onCallFailed(error: String) {}
    }

    class SipLibraryException(message: String, cause: Throwable? = null) : Exception(message, cause)
}