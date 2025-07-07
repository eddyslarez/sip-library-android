package com.eddyslarez.siplibrary

import android.app.Application
import android.content.Context
import android.net.Uri
import com.eddyslarez.siplibrary.core.SipCoreManager
import com.eddyslarez.siplibrary.data.models.*
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
 * EddysSipLibrary - Biblioteca SIP/VoIP para Android (Versión Optimizada)
 * Versión optimizada con estados unificados de llamada
 *
 * @author Eddys Larez
 * @version 1.4.0
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

    /**
     * Configuración completa de la biblioteca SIP
     */
    data class SipConfig(
        // === CONFIGURACIÓN BÁSICA ===
        val defaultDomain: String = "",
        val webSocketUrl: String = "",
        val userAgent: String = "EddysSipLibrary/1.4.0",
        
        // === CONFIGURACIÓN DE LOGS ===
        val enableLogs: Boolean = true,
        val logLevel: LogLevel = LogLevel.DEBUG,
        val enableFileLogging: Boolean = false,
        val maxLogFileSize: Long = 10 * 1024 * 1024, // 10MB
        
        // === CONFIGURACIÓN DE CONEXIÓN ===
        val enableAutoReconnect: Boolean = true,
        val maxReconnectAttempts: Int = 5,
        val reconnectDelayMs: Long = 2000L,
        val connectionTimeoutMs: Long = 30000L,
        val pingIntervalMs: Long = 30000L,
        val registrationExpiresSeconds: Int = 3600, // 1 hora
        val keepAliveIntervalMs: Long = 25000L,
        
        // === CONFIGURACIÓN DE PUSH NOTIFICATIONS ===
        val enablePushNotifications: Boolean = true,
        val defaultPushProvider: String = "fcm", // fcm, apns, custom
        val pushTimeoutMs: Long = 30000L,
        val enablePushWakeup: Boolean = true,
        
        // === CONFIGURACIÓN DE AUDIO ===
        val enableAudioProcessing: Boolean = true,
        val enableEchoCancellation: Boolean = true,
        val enableNoiseSuppression: Boolean = true,
        val enableAutoGainControl: Boolean = true,
        val audioSampleRate: Int = 48000,
        val audioChannels: AudioChannels = AudioChannels.MONO,
        val preferredAudioCodec: AudioCodec = AudioCodec.OPUS,
        val enableHDAudio: Boolean = true,
        
        // === CONFIGURACIÓN DE RINGTONES ===
        val enableIncomingRingtone: Boolean = true,
        val enableOutgoingRingtone: Boolean = true,
        val incomingRingtoneUri: Uri? = null, // null = usar por defecto
        val outgoingRingtoneUri: Uri? = null, // null = usar por defecto
        val ringtoneVolume: Float = 1.0f, // 0.0 - 1.0
        val enableVibration: Boolean = true,
        val vibrationPattern: LongArray = longArrayOf(0, 1000, 500, 1000), // patrón de vibración
        
        // === CONFIGURACIÓN DE LLAMADAS ===
        val enableDTMF: Boolean = true,
        val dtmfToneDuration: Int = 160, // milisegundos
        val dtmfToneGap: Int = 70, // milisegundos entre tonos
        val enableCallHold: Boolean = true,
        val enableCallTransfer: Boolean = true,
        val enableConferenceCall: Boolean = false,
        val maxCallDuration: Long = 0L, // 0 = sin límite, en milisegundos
        val enableCallRecording: Boolean = false,
        
        // === CONFIGURACIÓN DE SEGURIDAD ===
        val enableTLS: Boolean = true,
        val enableSRTP: Boolean = true,
        val tlsVersion: TLSVersion = TLSVersion.TLS_1_2,
        val certificateValidation: CertificateValidation = CertificateValidation.STRICT,
        val enableDigestAuthentication: Boolean = true,
        
        // === CONFIGURACIÓN DE INTERFAZ ===
        val enableFullScreenIncomingCall: Boolean = true,
        val enableCallNotifications: Boolean = true,
        val enableMissedCallNotifications: Boolean = true,
        val notificationChannelId: String = "sip_calls",
        val notificationChannelName: String = "SIP Calls",
        val enableCallHistory: Boolean = true,
        val maxCallHistoryEntries: Int = 1000,
        
        // === CONFIGURACIÓN DE DISPOSITIVOS DE AUDIO ===
        val enableBluetoothAudio: Boolean = true,
        val enableWiredHeadsetAudio: Boolean = true,
        val enableSpeakerAudio: Boolean = true,
        val autoSwitchToBluetoothWhenConnected: Boolean = true,
        val autoSwitchToWiredHeadsetWhenConnected: Boolean = true,
        val preferredAudioRoute: AudioRoute = AudioRoute.AUTO,
        
        // === CONFIGURACIÓN DE RENDIMIENTO ===
        val enableBatteryOptimization: Boolean = true,
        val enableNetworkOptimization: Boolean = true,
        val enableCpuOptimization: Boolean = true,
        val maxConcurrentCalls: Int = 1,
        val enableCallQualityMonitoring: Boolean = true,
        
        // === CONFIGURACIÓN DE DEBUGGING ===
        val enableDiagnosticMode: Boolean = false,
        val enableNetworkDiagnostics: Boolean = false,
        val enableAudioDiagnostics: Boolean = false,
        val enablePerformanceMetrics: Boolean = false,
        val diagnosticReportIntervalMs: Long = 60000L,
        
        // === CONFIGURACIÓN EXPERIMENTAL ===
        val enableExperimentalFeatures: Boolean = false,
        val enableVideoCall: Boolean = false,
        val enableScreenSharing: Boolean = false,
        val enableChatMessaging: Boolean = false,
        val enablePresenceStatus: Boolean = false
    )

    /**
     * Niveles de logging
     */
    enum class LogLevel {
        VERBOSE, DEBUG, INFO, WARN, ERROR, NONE
    }

    /**
     * Canales de audio
     */
    enum class AudioChannels {
        MONO, STEREO
    }

    /**
     * Códecs de audio soportados
     */
    enum class AudioCodec {
        OPUS, G722, G711_PCMU, G711_PCMA, G729, SPEEX
    }

    /**
     * Versiones de TLS
     */
    enum class TLSVersion {
        TLS_1_0, TLS_1_1, TLS_1_2, TLS_1_3
    }

    /**
     * Validación de certificados
     */
    enum class CertificateValidation {
        STRICT, PERMISSIVE, DISABLED
    }

    /**
     * Rutas de audio preferidas
     */
    enum class AudioRoute {
        AUTO, EARPIECE, SPEAKER, BLUETOOTH, WIRED_HEADSET
    }

    /**
     * Listener principal para todos los eventos SIP
     */
    interface SipEventListener {
        fun onRegistrationStateChanged(state: RegistrationState, username: String, domain: String) {}
        fun onCallStateChanged(stateInfo: CallStateInfo) {}  // OPTIMIZADO: Unificado con estados detallados
        fun onIncomingCall(callInfo: IncomingCallInfo) {}
        fun onCallConnected(callInfo: CallInfo) {}
        fun onCallEnded(callInfo: CallInfo, reason: CallEndReason) {}
        fun onCallFailed(error: String, callInfo: CallInfo?) {}
        fun onDtmfReceived(digit: Char, callInfo: CallInfo) {}
        fun onAudioDeviceChanged(device: AudioDevice) {}
        fun onNetworkStateChanged(isConnected: Boolean) {}
        fun onCallQualityChanged(quality: CallQuality) {}
        fun onBatteryOptimizationChanged(isOptimized: Boolean) {}
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
        fun onCallStateChanged(stateInfo: CallStateInfo)  // OPTIMIZADO: Renombrado de onDetailedStateChanged
        fun onCallQualityChanged(callInfo: CallInfo, quality: CallQuality)
        fun onCallDurationChanged(callInfo: CallInfo, duration: Long)
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
        val codec: String? = null,
        val state: DetailedCallState? = null,  // OPTIMIZADO: Renombrado de detailedState
        val quality: CallQuality? = null
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
        val headers: Map<String, String> = emptyMap(),
        val priority: CallPriority = CallPriority.NORMAL
    )

    /**
     * Calidad de llamada
     */
    data class CallQuality(
        val audioQuality: AudioQuality,
        val networkQuality: NetworkQuality,
        val overallScore: Int, // 0-100
        val jitter: Double,
        val packetLoss: Double,
        val roundTripTime: Double
    )

    /**
     * Dirección de la llamada
     */
    enum class CallDirection {
        INCOMING, OUTGOING
    }

    /**
     * Prioridad de llamada
     */
    enum class CallPriority {
        LOW, NORMAL, HIGH, URGENT
    }

    /**
     * Calidad de audio
     */
    enum class AudioQuality {
        POOR, FAIR, GOOD, EXCELLENT
    }

    /**
     * Calidad de red
     */
    enum class NetworkQuality {
        POOR, FAIR, GOOD, EXCELLENT
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
        ERROR,
        MAX_DURATION_REACHED,
        BATTERY_LOW,
        PERMISSION_DENIED
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
            log.d(tag = TAG) { "Initializing EddysSipLibrary v1.4.0 Multi-Account Optimized by Eddys Larez" }

            this.config = config
            sipCoreManager = SipCoreManager.createInstance(application, config)
            sipCoreManager?.initialize()

            // Configurar listeners internos
            setupInternalListeners()

            isInitialized = true
            log.d(tag = TAG) { "EddysSipLibrary initialized successfully with config: ${config.javaClass.simpleName}" }

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
                    log.d(tag = TAG) { "Internal callback: onCallTerminated" }
                    val callInfo = getCurrentCallInfo()
                    notifyCallEnded(callInfo, CallEndReason.NORMAL_HANGUP)
                }

                override fun onRegistrationStateChanged(state: RegistrationState) {
                    log.d(tag = TAG) { "Internal callback: onRegistrationStateChanged - $state" }
                }

                override fun onAccountRegistrationStateChanged(username: String, domain: String, state: RegistrationState) {
                    log.d(tag = TAG) { "Internal callback: onAccountRegistrationStateChanged - $username@$domain -> $state" }
                    notifyRegistrationStateChanged(state, username, domain)
                }

                override fun onIncomingCall(callerNumber: String, callerName: String?) {
                    log.d(tag = TAG) { "Internal callback: onIncomingCall from $callerNumber" }
                    val callInfo = createIncomingCallInfoFromCurrentCall(callerNumber, callerName)
                    notifyIncomingCall(callInfo)
                }

                override fun onCallConnected() {
                    log.d(tag = TAG) { "Internal callback: onCallConnected" }
                    getCurrentCallInfo()?.let { notifyCallConnected(it) }
                }

                override fun onCallFailed(error: String) {
                    log.d(tag = TAG) { "Internal callback: onCallFailed - $error" }
                    val callInfo = getCurrentCallInfo()
                    notifyCallFailed(error, callInfo)
                }
            })

            // OPTIMIZADO: Observar estados unificados
            CoroutineScope(Dispatchers.Main).launch {
                CallStateManager.advanced.callStateFlow.collect { stateInfo ->
                    notifyCallStateChanged(stateInfo)
                    
                    // Mapear a eventos específicos para compatibilidad
                    val callInfo = getCurrentCallInfo()
                    when (stateInfo.state) {
                        DetailedCallState.CONNECTED -> callInfo?.let { notifyCallConnected(it) }
                        DetailedCallState.OUTGOING_RINGING -> callInfo?.let { notifyCallRinging(it) }
                        DetailedCallState.OUTGOING_INIT -> callInfo?.let { notifyCallInitiated(it) }
                        DetailedCallState.INCOMING_RECEIVED -> handleIncomingCall()
                        DetailedCallState.ENDED -> callInfo?.let { notifyCallEnded(it, CallEndReason.NORMAL_HANGUP) }
                        DetailedCallState.PAUSED -> callInfo?.let { notifyCallHeld(it) }
                        DetailedCallState.STREAMS_RUNNING -> callInfo?.let { notifyCallResumed(it) }
                        DetailedCallState.ERROR -> {
                            val reason = mapErrorReasonToCallEndReason(stateInfo.errorReason)
                            callInfo?.let { notifyCallEnded(it, reason) }
                        }
                        else -> {}
                    }
                }
            }
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

    /**
     * OPTIMIZADO: Notificar cambios de estado unificado
     */
    private fun notifyCallStateChanged(stateInfo: CallStateInfo) {
        log.d(tag = TAG) { "Notifying call state change: ${stateInfo.state} to ${listeners.size} listeners" }

        listeners.forEach { listener ->
            try {
                listener.onCallStateChanged(stateInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in listener onCallStateChanged: ${e.message}" }
            }
        }

        callListener?.let { listener ->
            try {
                listener.onCallStateChanged(stateInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in CallListener onCallStateChanged: ${e.message}" }
            }
        }
    }

    private fun notifyCallInitiated(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call initiated to ${listeners.size} listeners" }

        try {
            callListener?.onCallInitiated(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallInitiated: ${e.message}" }
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

    private fun notifyCallHeld(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call held" }

        try {
            callListener?.onCallHeld(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallHeld: ${e.message}" }
        }
    }

    private fun notifyCallResumed(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call resumed" }

        try {
            callListener?.onCallResumed(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallResumed: ${e.message}" }
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

    /**
     * Crear IncomingCallInfo desde los datos actuales de la llamada
     */
    private fun createIncomingCallInfoFromCurrentCall(callerNumber: String, callerName: String?): IncomingCallInfo {
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

    /**
     * OPTIMIZADO: Método getCurrentCallInfo() con estados unificados
     */
    private fun getCurrentCallInfo(): CallInfo? {
        val manager = sipCoreManager ?: return null
        val account = manager.currentAccountInfo ?: return null
        val callData = account.currentCallData ?: return null

        return try {
            // Obtener estado actual
            val currentState = CallStateManager.advanced.getCurrentState()
            
            CallInfo(
                callId = callData.callId,
                phoneNumber = if (callData.direction == CallDirections.INCOMING) callData.from else callData.to,
                displayName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
                direction = if (callData.direction == CallDirections.INCOMING) CallDirection.INCOMING else CallDirection.OUTGOING,
                startTime = manager.callStartTimeMillis,
                duration = if (manager.callStartTimeMillis > 0) System.currentTimeMillis() - manager.callStartTimeMillis else 0,
                isOnHold = callData.isOnHold ?: false,
                isMuted = manager.webRtcManager.isMuted(),
                localAccount = account.username,
                codec = null,
                state = currentState.state  // OPTIMIZADO: Renombrado
            )
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating CallInfo: ${e.message}" }
            null
        }
    }

    private fun mapErrorReasonToCallEndReason(errorReason: CallErrorReason): CallEndReason {
        return when (errorReason) {
            CallErrorReason.BUSY -> CallEndReason.BUSY
            CallErrorReason.NO_ANSWER -> CallEndReason.NO_ANSWER
            CallErrorReason.REJECTED -> CallEndReason.REJECTED
            CallErrorReason.NETWORK_ERROR -> CallEndReason.NETWORK_ERROR
            else -> CallEndReason.ERROR
        }
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

    // === MÉTODOS PÚBLICOS DE LA API ===

    /**
     * Registra una cuenta SIP
     */
    fun registerAccount(
        username: String,
        password: String,
        domain: String? = null,
        pushToken: String? = null,
        pushProvider: String = config.defaultPushProvider
    ) {
        checkInitialized()

        val finalDomain = domain ?: sipCoreManager?.getDefaultDomain() ?: config.defaultDomain
        val finalToken = if (config.enablePushNotifications) pushToken ?: "" else ""

        log.d(tag = TAG) { "Registering account: $username@$finalDomain with push: ${config.enablePushNotifications}" }

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
     * Obtiene el estado de registro para una cuenta específica
     */
    fun getRegistrationState(username: String, domain: String): RegistrationState {
        checkInitialized()
        val accountKey = "$username@$domain"
        return sipCoreManager?.getRegistrationState(accountKey) ?: RegistrationState.NONE
    }

    /**
     * Obtiene todos los estados de registro
     */
    fun getAllRegistrationStates(): Map<String, RegistrationState> {
        checkInitialized()
        return sipCoreManager?.getAllRegistrationStates() ?: emptyMap()
    }

    /**
     * Flow para observar estados de registro de todas las cuentas
     */
    fun getRegistrationStatesFlow(): Flow<Map<String, RegistrationState>> {
        checkInitialized()
        return sipCoreManager?.registrationStatesFlow ?: flowOf(emptyMap())
    }

    /**
     * OPTIMIZADO: Flow para observar estados de llamada (renombrado)
     */
    fun getCallStateFlow(): Flow<CallStateInfo> {
        checkInitialized()
        return CallStateManager.advanced.callStateFlow
    }

    /**
     * OPTIMIZADO: Obtener estado actual (renombrado)
     */
    fun getCurrentCallState(): CallStateInfo {
        checkInitialized()
        return CallStateManager.advanced.getCurrentState()
    }

    /**
     * Obtener historial de estados de llamada
     */
    fun getCallStateHistory(): List<CallStateInfo> {
        checkInitialized()
        return CallStateManager.advanced.getStateHistory()
    }

    /**
     * Limpiar historial de estados
     */
    fun clearCallStateHistory() {
        checkInitialized()
        CallStateManager.advanced.clearHistory()
    }

    /**
     * Cambia el dispositivo de audio (entrada o salida) durante una llamada.
     */
    fun changeAudioDevice(device: AudioDevice) {
        checkInitialized()
        if (config.enableBluetoothAudio || !device.isBluetooth) {
            sipCoreManager?.changeAudioDevice(device)
        }
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
        val finalDomain = domain ?: sipCoreManager?.getDefaultDomain() ?: config.defaultDomain

        if (finalUsername == null) {
            throw SipLibraryException("No registered account available for calling")
        }

        log.d(tag = TAG) { "Making call to $phoneNumber from $finalUsername@$finalDomain" }

        sipCoreManager?.makeCall(phoneNumber, finalUsername, finalDomain)
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
    }

    fun endCall() {
        checkInitialized()
        log.d(tag = TAG) { "Ending call" }
        sipCoreManager?.endCall()
    }

    fun holdCall() {
        checkInitialized()
        if (config.enableCallHold) {
            log.d(tag = TAG) { "Holding call" }
            sipCoreManager?.holdCall()
        }
    }

    fun resumeCall() {
        checkInitialized()
        if (config.enableCallHold) {
            log.d(tag = TAG) { "Resuming call" }
            sipCoreManager?.resumeCall()
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

    /**
     * OBSOLETO: Use getCurrentCallState() que ahora devuelve CallStateInfo
     */
    @Deprecated("Use getCurrentCallState() que ahora devuelve CallStateInfo con más información")
    fun getCurrentCallStateLegacy(): CallState {
        checkInitialized()
        val stateInfo = getCurrentCallState()
        return CallStateManager.mapDetailedToLegacyState(stateInfo.state)
    }

    /**
     * OBSOLETO: Use getRegistrationStatesFlow() para obtener estados de todas las cuentas
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

    /**
     * OBSOLETO: Use getCallStateFlow() que ahora devuelve CallStateInfo
     */
    @Deprecated("Use getCallStateFlow() que ahora devuelve CallStateInfo con más información")
    fun getCallStateFlowLegacy(): Flow<CallState> {
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

    // === MÉTODOS ADICIONALES ===

    fun sendDtmf(digit: Char, duration: Int = config.dtmfToneDuration): Boolean {
        checkInitialized()
        return if (config.enableDTMF) {
            sipCoreManager?.sendDtmf(digit, duration) ?: false
        } else {
            false
        }
    }

    fun sendDtmfSequence(digits: String, duration: Int = config.dtmfToneDuration): Boolean {
        checkInitialized()
        return if (config.enableDTMF) {
            sipCoreManager?.sendDtmfSequence(digits, duration) ?: false
        } else {
            false
        }
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
        return if (config.enableCallHistory) {
            sipCoreManager?.callLogs() ?: emptyList()
        } else {
            emptyList()
        }
    }

    fun getMissedCalls(): List<CallLog> {
        checkInitialized()
        return if (config.enableCallHistory) {
            sipCoreManager?.getMissedCalls() ?: emptyList()
        } else {
            emptyList()
        }
    }

    fun getCallLogsForNumber(phoneNumber: String): List<CallLog> {
        checkInitialized()
        return if (config.enableCallHistory) {
            sipCoreManager?.getCallLogsForNumber(phoneNumber) ?: emptyList()
        } else {
            emptyList()
        }
    }

    fun clearCallLogs() {
        checkInitialized()
        if (config.enableCallHistory) {
            sipCoreManager?.clearCallLogs()
        }
    }

    fun updatePushToken(token: String, provider: String = config.defaultPushProvider) {
        checkInitialized()
        if (config.enablePushNotifications) {
            sipCoreManager?.enterPushMode(token)
        }
    }

    fun getSystemHealthReport(): String {
        checkInitialized()
        return sipCoreManager?.getSystemHealthReport() ?: "Library not initialized"
    }

    fun isSystemHealthy(): Boolean {
        checkInitialized()
        return sipCoreManager?.isSipCoreManagerHealthy() ?: false
    }

    /**
     * Obtiene la configuración actual
     */
    fun getCurrentConfig(): SipConfig {
        checkInitialized()
        return config
    }

    /**
     * Actualiza configuración en tiempo de ejecución (solo algunas opciones)
     */
    fun updateRuntimeConfig(
        enableIncomingRingtone: Boolean? = null,
        enableOutgoingRingtone: Boolean? = null,
        ringtoneVolume: Float? = null,
        enableVibration: Boolean? = null,
        enablePushNotifications: Boolean? = null
    ) {
        checkInitialized()
        
        // Crear nueva configuración con los cambios
        val newConfig = config.copy(
            enableIncomingRingtone = enableIncomingRingtone ?: config.enableIncomingRingtone,
            enableOutgoingRingtone = enableOutgoingRingtone ?: config.enableOutgoingRingtone,
            ringtoneVolume = ringtoneVolume ?: config.ringtoneVolume,
            enableVibration = enableVibration ?: config.enableVibration,
            enablePushNotifications = enablePushNotifications ?: config.enablePushNotifications
        )
        
        // Actualizar configuración interna
        this.config = newConfig
        
        // Aplicar cambios al core manager
        sipCoreManager?.updateConfig(newConfig)
        
        log.d(tag = TAG) { "Runtime configuration updated" }
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

            appendLine("\n--- Call State History ---")
            val history = getCallStateHistory()
            appendLine("History entries: ${history.size}")
            history.takeLast(5).forEach { state ->
                appendLine("${state.timestamp}: ${state.previousState} -> ${state.state}")
            }

            appendLine("\n--- Configuration ---")
            appendLine("Push notifications: ${config.enablePushNotifications}")
            appendLine("Incoming ringtone: ${config.enableIncomingRingtone}")
            appendLine("Outgoing ringtone: ${config.enableOutgoingRingtone}")
            appendLine("DTMF enabled: ${config.enableDTMF}")
            appendLine("Call hold enabled: ${config.enableCallHold}")
            appendLine("Bluetooth audio: ${config.enableBluetoothAudio}")
            appendLine("Auto reconnect: ${config.enableAutoReconnect}")
            appendLine("Max concurrent calls: ${config.maxConcurrentCalls}")
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
        fun onRegistrationStateChanged(state: RegistrationState) {}
        fun onAccountRegistrationStateChanged(username: String, domain: String, state: RegistrationState) {}
        fun onIncomingCall(callerNumber: String, callerName: String?) {}
        fun onCallConnected() {}
        fun onCallFailed(error: String) {}
    }

    class SipLibraryException(message: String, cause: Throwable? = null) : Exception(message, cause)
}