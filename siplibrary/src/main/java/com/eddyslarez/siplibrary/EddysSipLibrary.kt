package com.eddyslarez.siplibrary

import android.app.Application
import android.content.Context
import android.media.RingtoneManager
import com.eddyslarez.siplibrary.core.SipCoreManager
import com.eddyslarez.siplibrary.data.models.*
import com.eddyslarez.siplibrary.data.services.audio.AudioDevice
import com.eddyslarez.siplibrary.utils.CallStateManager
import com.eddyslarez.siplibrary.utils.RegistrationStateManager
import com.eddyslarez.siplibrary.utils.PushModeManager
import com.eddyslarez.siplibrary.utils.log
import com.eddyslarez.siplibrary.utils.MultiCallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import android.net.Uri
import com.eddyslarez.siplibrary.core.NetworkConnectivityListener
import com.eddyslarez.siplibrary.data.services.network.NetworkMonitor
import com.eddyslarez.siplibrary.data.database.DatabaseAutoIntegration
import com.eddyslarez.siplibrary.data.database.DatabaseManager
import com.eddyslarez.siplibrary.data.database.SipDatabase
import com.eddyslarez.siplibrary.data.database.converters.toCallLogs
import com.eddyslarez.siplibrary.data.database.entities.AppConfigEntity
import com.eddyslarez.siplibrary.data.database.entities.ContactEntity
import com.eddyslarez.siplibrary.data.database.entities.SipAccountEntity
import com.eddyslarez.siplibrary.data.database.repository.CallLogWithContact
import com.eddyslarez.siplibrary.data.database.repository.GeneralStatistics
import com.eddyslarez.siplibrary.data.database.setupDatabaseIntegration
import com.eddyslarez.siplibrary.data.services.audio.AudioUnit
import com.eddyslarez.siplibrary.data.services.audio.AudioUnitTypes
import com.eddyslarez.siplibrary.utils.PushNotificationSimulator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

/**
 * EddysSipLibrary - Biblioteca SIP/VoIP para Android (Versión Optimizada)
 * Versión simplificada usando únicamente los nuevos estados
 *
 * @author Eddys Larez
 * @version 1.5.0
 */
class EddysSipLibrary private constructor() {

    private var sipCoreManager: SipCoreManager? = null
    private var isInitialized = false
    private lateinit var config: SipConfig
    private val listeners = mutableSetOf<SipEventListener>()
    private var registrationListener: RegistrationListener? = null
    private var callListener: CallListener? = null
    private var incomingCallListener: IncomingCallListener? = null
    private val pushSimulator = PushNotificationSimulator()
    private var databaseIntegration: DatabaseAutoIntegration? = null
    private var databaseManager: DatabaseManager? = null

    // Push Mode Manager
    private var pushModeManager: PushModeManager? = null
    private var networkStatusListener: NetworkStatusListener? = null
    private var autoReconnectionListener: AutoReconnectionListener? = null
    private val lastNotifiedRegistrationStates = mutableMapOf<String, RegistrationState>()
    private val lastNotifiedCallState = AtomicReference<CallStateInfo?>(null)

    companion object {
        @Volatile
        private var INSTANCE: EddysSipLibrary? = null
        private const val TAG = "EddysSipLibrary"
        private const val TAG1 = "ProcesoPush"

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
        val pingIntervalMs: Long = 30000L,
        val pushModeConfig: PushModeConfig = PushModeConfig(),
        val incomingRingtoneUri: Uri? = null,
        val outgoingRingtoneUri: Uri? = null
    )

    /**
     * Listener principal para todos los eventos SIP
     */
    interface SipEventListener {
        fun onRegistrationStateChanged(
            state: RegistrationState,
            username: String,
            domain: String
        ) {
        }

        fun onCallStateChanged(stateInfo: CallStateInfo) {}
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

    // NUEVO: Listener para estado de red
    interface NetworkStatusListener {
        fun onNetworkConnected(networkType: String, hasInternet: Boolean)
        fun onNetworkDisconnected()
        fun onNetworkChanged(oldNetworkType: String, newNetworkType: String)
        fun onInternetConnectivityChanged(hasInternet: Boolean)
    }

    // NUEVO: Listener para reconexión automática
    interface AutoReconnectionListener {
        fun onReconnectionStarted(accountKey: String, reason: String)
        fun onReconnectionSuccess(accountKey: String, attempts: Int)
        fun onReconnectionFailed(accountKey: String, attempts: Int, error: String)
        fun onReconnectionProgress(accountKey: String, attempt: Int, maxAttempts: Int)
    }

    interface NetworkStateCallbacks {
        fun onNetworkLost()
        fun onNetworkRestored()
        fun onReconnectionStarted()
        fun onReconnectionCompleted(successful: Boolean)
        fun onConnectionHealthCheckFailed()
    }
    private var networkStateCallbacks: NetworkStateCallbacks? = null
    fun setNetworkStateCallbacks(callbacks: NetworkStateCallbacks?) {
        this.networkStateCallbacks = callbacks

        // Configurar listener en SipCoreManager
        sipCoreManager?.networkManager?.setConnectivityListener(object : NetworkConnectivityListener {
            override fun onNetworkLost() {
                callbacks?.onNetworkLost()
            }

            override fun onNetworkRestored() {
                callbacks?.onNetworkRestored()
            }

            override fun onReconnectionStarted() {
                callbacks?.onReconnectionStarted()
            }

            override fun onReconnectionCompleted(successful: Boolean) {
                callbacks?.onReconnectionCompleted(successful)
            }
        })
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
        fun onCallStateChanged(stateInfo: CallStateInfo)
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
        val state: CallState? = null,
        val isCurrentCall: Boolean = false
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
        INCOMING,
        OUTGOING
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
        config: SipConfig = SipConfig(),
        enableDatabase: Boolean = true
    ) {
        if (isInitialized) {
            log.w(tag = TAG) { "Library already initialized" }
            return
        }

        try {
            log.d(tag = TAG) { "Initializing EddysSipLibrary v1.5.0 Optimized by Eddys Larez" }

            this.config = config
            sipCoreManager = SipCoreManager.createInstance(application, config)
            sipCoreManager?.initialize() // ESTO ya carga la configuración de DB

            clearInitialStates()

            if (enableDatabase) {
                setupDatabaseIntegration(application)
            }

            pushModeManager = PushModeManager(config.pushModeConfig)
            setupPushModeManager()
            setupInternalListeners()

            // MODIFICADO: Solo aplicar ringtones del config si no se cargaron desde BD
            val loadedConfig = sipCoreManager?.getLoadedConfig()

            if (loadedConfig?.incomingRingtoneUri == null && config.incomingRingtoneUri != null) {
                sipCoreManager?.saveIncomingRingtoneUri(config.incomingRingtoneUri!!)
            }

            if (loadedConfig?.outgoingRingtoneUri == null && config.outgoingRingtoneUri != null) {
                sipCoreManager?.saveOutgoingRingtoneUri(config.outgoingRingtoneUri!!)
            }

            isInitialized = true
            log.d(tag = TAG) { "EddysSipLibrary initialized successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing library: ${e.message}" }
            throw SipLibraryException("Failed to initialize library", e)
        }
    }
//    fun initialize(
//        application: Application,
//        config: SipConfig = SipConfig(),
//        enableDatabase: Boolean = true
//    ) {
//        if (isInitialized) {
//            log.w(tag = TAG) { "Library already initialized" }
//            return
//        }
//
//        try {
//            log.d(tag = TAG) { "Initializing EddysSipLibrary v1.5.0 Optimized by Eddys Larez" }
//
//            this.config = config
//            sipCoreManager = SipCoreManager.createInstance(application, config)
//            sipCoreManager?.initialize()
//
//            // NUEVO: Limpiar states iniciales antes de configurar listeners
//            clearInitialStates()
//
//            if (enableDatabase) {
//                setupDatabaseIntegration(application)
//            }
//
//            // Inicializar Push Mode Manager
//            pushModeManager = PushModeManager(config.pushModeConfig)
//            setupPushModeManager()
//
//            // Configurar listeners internos DESPUÉS de limpiar estados iniciales
//            setupInternalListeners()
//
//            // Configurar ringtones personalizados si se proporcionan
//            config.incomingRingtoneUri?.let { uri ->
//                sipCoreManager?.audioManager?.setIncomingRingtone(uri)
//            }
//            config.outgoingRingtoneUri?.let { uri ->
//                sipCoreManager?.audioManager?.setOutgoingRingtone(uri)
//            }
//
//            isInitialized = true
//            log.d(tag = TAG) { "EddysSipLibrary initialized successfully" }
//
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error initializing library: ${e.message}" }
//            throw SipLibraryException("Failed to initialize library", e)
//        }
//    }

    /**
     * NUEVO: Limpia estados iniciales erróneos
     */
    private fun clearInitialStates() {
        try {
            log.d(tag = TAG) { "Clearing initial states..." }

            // Limpiar cache de estados
            lastNotifiedRegistrationStates.clear()
            lastNotifiedCallState.set(null)

            // Limpiar historial de estados de llamada si existe
            CallStateManager.clearHistory()

            // Verificar si hay un estado inicial erróneo
            val currentState = CallStateManager.getCurrentState()
            if (currentState.timestamp > System.currentTimeMillis() + 1000) {
                log.w(tag = TAG) { "Detected future timestamp in initial call state: ${currentState.timestamp}, clearing..." }

                // Forzar un estado inicial limpio con timestamp actual
                val cleanState = CallStateInfo(
                    state = CallState.IDLE,
                    previousState = null,
                    errorReason = CallErrorReason.NONE,
                    timestamp = System.currentTimeMillis(),
                    sipCode = null,
                    sipReason = null,
                    callId = "",
                    direction = CallDirections.OUTGOING
                )

                // Si CallStateManager tiene un método para resetear, usarlo
                // CallStateManager.resetToState(cleanState)
            }

            log.d(tag = TAG) { "Initial states cleared" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error clearing initial states: ${e.message}" }
        }
    }


    private fun setupPushModeManager() {
        pushModeManager?.setCallbacks(
            onModeChange = { pushModeState ->
                log.d(tag = TAG1) { "Push mode changed: ${pushModeState.currentMode} (${pushModeState.reason})" }

                // Notificar a listeners si es necesario
                listeners.forEach { listener ->
                    try {
                        // Podrías añadir un método onPushModeChanged al SipEventListener si lo necesitas
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error in push mode change notification: ${e.message}" }
                    }
                }
            },
            onRegistrationRequired = { accounts, mode ->
                log.d(tag = TAG1) { "Registration required for ${accounts.size} accounts in $mode mode" }

                // Reregistrar cuentas según el modo
                accounts.forEach { accountKey ->
                    val parts = accountKey.split("@")
                    if (parts.size == 2) {
                        val username = parts[0]
                        val domain = parts[1]
                        log.d(tag = TAG1) { "username $username domain: $domain" }

                        when (mode) {
                            PushMode.PUSH -> {
                                CoroutineScope(Dispatchers.IO).launch {

                                    log.d(tag = TAG1) { "Switching $accountKey to push mode" }
                                    sipCoreManager?.switchToPushMode(username, domain)
                                }
                            }

                            PushMode.FOREGROUND -> {
                                CoroutineScope(Dispatchers.IO).launch {

                                    log.d(tag = TAG1) { "Switching $accountKey to foreground mode" }
                                    sipCoreManager?.switchToForegroundMode(username, domain)
                                }
                            }

                            else -> {}
                        }
                    }
                }
            }
        )

        // Observar cambios de lifecycle de la aplicación
        setupAppLifecycleObserver()
    }

    private fun setupAppLifecycleObserver() {
        // Conectar con el PlatformRegistration para observar cambios de lifecycle
        // y notificar al PushModeManager
        CoroutineScope(Dispatchers.Main).launch {
            // Observar estados de lifecycle desde SipCoreManager
            sipCoreManager?.let { manager ->
                // Configurar observer para cambios de lifecycle
                manager.observeLifecycleChanges { event ->
                    val registeredAccounts = manager.getAllRegisteredAccountKeys()

                    when (event) {
                        "APP_BACKGROUNDED" -> {
                            log.d(tag = TAG1) { "App backgrounded - notifying PushModeManager" }
                            pushModeManager?.onAppBackgrounded(registeredAccounts)
                        }

                        "APP_FOREGROUNDED" -> {
                            log.d(tag = TAG1) { "App foregrounded - notifying PushModeManager" }
                            pushModeManager?.onAppForegrounded(registeredAccounts)
                        }
                    }
                }
            }
        }
    }


    /**
     * Método setupInternalListeners() COMPLETO con reconexión automática
     */
//    private fun setupInternalListeners() {
//        sipCoreManager?.let { manager ->
//
//            // Configurar callback para eventos principales
//            manager.setCallbacks(object : SipCallbacks {
//                override fun onCallTerminated() {
//                    log.d(tag = TAG) { "Internal callback: onCallTerminated" }
//                    val callInfo = getCurrentCallInfo()
//                    notifyCallEnded(callInfo, CallEndReason.NORMAL_HANGUP)
//                }
//
//                override fun onRegistrationStateChanged(state: RegistrationState) {
//                    log.d(tag = TAG) { "Internal callback: onRegistrationStateChanged - $state" }
//                }
//
//                override fun onAccountRegistrationStateChanged(
//                    username: String,
//                    domain: String,
//                    state: RegistrationState
//                ) {
//                    log.d(tag = TAG) { "Internal callback: onAccountRegistrationStateChanged - $username@$domain -> $state" }
//                    notifyRegistrationStateChanged(state, username, domain)
//                }
//
//                override fun onIncomingCall(callerNumber: String, callerName: String?) {
//                    log.d(tag = TAG) { "Internal callback: onIncomingCall from $callerNumber" }
//
//                    // Notificar al Push Mode Manager
////                    val registeredAccounts = sipCoreManager?.getAllRegisteredAccountKeys() ?: emptySet()
////                    pushModeManager?.onIncomingCallReceived(registeredAccounts)
//
//                    val callInfo = createIncomingCallInfoFromCurrentCall(callerNumber, callerName)
//                    notifyIncomingCall(callInfo)
//                }
//
//                override fun onCallConnected() {
//                    log.d(tag = TAG) { "Internal callback: onCallConnected" }
//                    getCurrentCallInfo()?.let { notifyCallConnected(it) }
//                }
//
//                override fun onCallFailed(error: String) {
//                    log.d(tag = TAG) { "Internal callback: onCallFailed - $error" }
//                    val callInfo = getCurrentCallInfo()
//                    notifyCallFailed(error, callInfo)
//                }
//
//                // NUEVO: Manejo mejorado del callback de llamada terminada por cuenta específica
//                override fun onCallEndedForAccount(accountKey: String) {
//                    log.d(tag = TAG) { "Internal callback: onCallEndedForAccount - $accountKey" }
//
//                    // Notificar al PushModeManager que la llamada terminó para esta cuenta específica
//                    pushModeManager?.onCallEndedForAccount(accountKey, setOf(accountKey))
//                }
//            })
//
//            // *** CONFIGURAR LISTENERS DE RED Y RECONEXIÓN ***
//            setupNetworkStatusListener()
//            setupAutoReconnectionListener()
//
//            // Observar estados usando el nuevo CallStateManager
//            CoroutineScope(Dispatchers.Main).launch {
//                CallStateManager.callStateFlow.collect { stateInfo ->
//                    // Obtener información completa de la llamada
//                    val callInfo = getCallInfoForState(stateInfo)
//                    val enhancedStateInfo = stateInfo.copy(
//                        // Agregar información adicional si es necesario
//                    )
//
//                    notifyCallStateChanged(enhancedStateInfo)
//
//                    // Mapear a eventos específicos para compatibilidad
//                    callInfo?.let { info ->
//                        when (stateInfo.state) {
//                            CallState.CONNECTED -> notifyCallConnected(info)
//                            CallState.OUTGOING_RINGING -> notifyCallRinging(info)
//                            CallState.OUTGOING_INIT -> notifyCallInitiated(info)
//                            CallState.INCOMING_RECEIVED -> handleIncomingCall()
//                            CallState.ENDED -> {
//                                val reason = mapErrorReasonToCallEndReason(stateInfo.errorReason)
//                                notifyCallEnded(info, reason)
//
//                                // CORREGIDO: Notificar al Push Mode Manager usando el accountKey específico
//                                val accountKey = determineAccountKeyFromCallInfo(info)
//                                if (accountKey != null) {
//                                    log.d(tag = TAG1) { "Notifying PushModeManager: call ended for $accountKey" }
//                                    pushModeManager?.onCallEndedForAccount(accountKey, setOf(accountKey))
//                                } else {
//                                    // Fallback: usar todas las cuentas registradas
//                                    val registeredAccounts = sipCoreManager?.getAllRegisteredAccountKeys() ?: emptySet()
//                                    pushModeManager?.onCallEnded(registeredAccounts)
//                                }
//                            }
//
//                            CallState.PAUSED -> notifyCallHeld(info)
//                            CallState.STREAMS_RUNNING -> notifyCallResumed(info)
//                            CallState.ERROR -> {
//                                val reason = mapErrorReasonToCallEndReason(stateInfo.errorReason)
//                                notifyCallEnded(info, reason)
//                            }
//
//                            else -> {}
//                        }
//                    }
//                }
//            }
//        }
//    }

    fun setBluetoothAutoPriority(enabled: Boolean) {
        checkInitialized()
        try {
            sipCoreManager?.webRtcManager?.setBluetoothAutoPriority(enabled)
            log.d(tag = TAG) { "Bluetooth auto priority set to: $enabled" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting Bluetooth auto priority: ${e.message}" }
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
                    // REMOVIDO: Este callback genérico ya no se usa para evitar duplicados
                    log.d(tag = TAG) { "Internal callback: onRegistrationStateChanged - $state (ignored to avoid duplicates)" }
                }

                override fun onAccountRegistrationStateChanged(
                    username: String,
                    domain: String,
                    state: RegistrationState
                ) {
                    log.d(tag = TAG) { "Internal callback: onAccountRegistrationStateChanged - $username@$domain -> $state" }
                    // ESTE es el único lugar donde se debe notificar cambios de registro
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

                override fun onCallEndedForAccount(accountKey: String) {
                    log.d(tag = TAG) { "Internal callback: onCallEndedForAccount - $accountKey" }
                    pushModeManager?.onCallEndedForAccount(accountKey, setOf(accountKey))
                }
            })

            // MODIFICADO: Observar estados de llamada con filtrado
            CoroutineScope(Dispatchers.Main).launch {
                var isFirstState = true

                CallStateManager.callStateFlow.collect { stateInfo ->
                    // NUEVO: Filtrar el primer estado si es inválido
                    if (isFirstState) {
                        isFirstState = false

                        // Verificar si el primer estado es válido
                        if (stateInfo.timestamp > System.currentTimeMillis() + 1000) {
                            log.w(tag = TAG) { "Skipping invalid initial state with future timestamp: $stateInfo" }
                            return@collect
                        }

                        // Si no hay llamadas activas y el estado no es IDLE, ignorar
                        if (MultiCallManager.getAllCalls()
                                .isEmpty() && stateInfo.state != CallState.IDLE
                        ) {
                            log.w(tag = TAG) { "Skipping invalid initial state with no active calls: $stateInfo" }
                            return@collect
                        }
                    }

                    // Obtener información completa de la llamada
                    val callInfo = getCallInfoForState(stateInfo)

                    // Notificar cambio de estado (con deduplicación)
                    notifyCallStateChanged(stateInfo)

                    // Mapear a eventos específicos para compatibilidad
                    callInfo?.let { info ->
                        when (stateInfo.state) {
                            CallState.CONNECTED -> notifyCallConnected(info)
                            CallState.OUTGOING_RINGING -> notifyCallRinging(info)
                            CallState.OUTGOING_INIT -> notifyCallInitiated(info)
                            CallState.INCOMING_RECEIVED -> handleIncomingCall()
                            CallState.ENDED -> {
                                val reason = mapErrorReasonToCallEndReason(stateInfo.errorReason)
                                notifyCallEnded(info, reason)

                                val accountKey = determineAccountKeyFromCallInfo(info)
                                if (accountKey != null) {
                                    log.d(tag = TAG1) { "Notifying PushModeManager: call ended for $accountKey" }
                                    pushModeManager?.onCallEndedForAccount(
                                        accountKey,
                                        setOf(accountKey)
                                    )
                                } else {
                                    val registeredAccounts =
                                        sipCoreManager?.getAllRegisteredAccountKeys() ?: emptySet()
                                    pushModeManager?.onCallEnded(registeredAccounts)
                                }
                            }

                            CallState.PAUSED -> notifyCallHeld(info)
                            CallState.STREAMS_RUNNING -> notifyCallResumed(info)
                            CallState.ERROR -> {
                                val reason = mapErrorReasonToCallEndReason(stateInfo.errorReason)
                                notifyCallEnded(info, reason)
                            }

                            else -> {
                                // No hacer nada para estados que no requieren notificación específica
                            }
                        }
                    }
                }
            }
        }
    }


    /**
     * NUEVO: Determina el accountKey desde CallInfo
     */
    private fun determineAccountKeyFromCallInfo(callInfo: CallInfo): String? {
        val manager = sipCoreManager ?: return null

        // Primero intentar obtener desde la cuenta actual
        val currentAccount = manager.currentAccountInfo
        if (currentAccount != null) {
            val accountKey = "${currentAccount.username}@${currentAccount.domain}"
            log.d(tag = TAG) { "Determined account key from current account: $accountKey" }
            return accountKey
        }

        // Si no hay cuenta actual, intentar determinar desde localAccount en CallInfo
        if (callInfo.localAccount.isNotEmpty()) {
            val registeredAccounts = manager.getAllRegisteredAccountKeys()
            val matchingAccount =
                registeredAccounts.find { it.startsWith("${callInfo.localAccount}@") }
            if (matchingAccount != null) {
                log.d(tag = TAG) { "Determined account key from CallInfo localAccount: $matchingAccount" }
                return matchingAccount
            }
        }

        log.w(tag = TAG) { "Could not determine specific account key for call ${callInfo.callId}" }
        return null
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

    //    private fun notifyRegistrationStateChanged(
//        state: RegistrationState,
//        username: String,
//        domain: String
//    ) {
//        val accountKey = "$username@$domain"
//
//        log.d(tag = TAG) { "Notifying registration state change: $state for $accountKey to ${listeners.size} listeners" }
//
//        // NUEVO: Verificar que el estado realmente cambió en el SipCoreManager
//        val actualState = sipCoreManager?.getRegistrationState(accountKey) ?: RegistrationState.NONE
//        if (actualState != state) {
//            log.w(tag = TAG) { "State mismatch for $accountKey: notified=$state, actual=$actualState. Using actual state." }
//            // Usar el estado actual del core manager
//            notifyWithActualState(actualState, username, domain, accountKey)
//            return
//        }
//
//        // Ejecutar notificaciones en el hilo principal
//        CoroutineScope(Dispatchers.Main).launch {
//            try {
//                // Notificar listeners generales
//                listeners.forEach { listener ->
//                    try {
//                        listener.onRegistrationStateChanged(state, username, domain)
//                    } catch (e: Exception) {
//                        log.e(tag = TAG) { "Error in listener onRegistrationStateChanged: ${e.message}" }
//                    }
//                }
//
//                // Notificar listener específico
//                registrationListener?.let { listener ->
//                    try {
//                        when (state) {
//                            RegistrationState.OK -> {
//                                listener.onRegistrationSuccessful(username, domain)
//                                log.d(tag = TAG) { "Notified registration successful for $accountKey" }
//                            }
//
//                            RegistrationState.FAILED -> {
//                                listener.onRegistrationFailed(
//                                    username,
//                                    domain,
//                                    "Registration failed"
//                                )
//                                log.d(tag = TAG) { "Notified registration failed for $accountKey" }
//                            }
//
//                            RegistrationState.NONE, RegistrationState.CLEARED -> {
//                                listener.onUnregistered(username, domain)
//                                log.d(tag = TAG) { "Notified unregistered for $accountKey" }
//                            }
//
//                            else -> {
//                                log.d(tag = TAG) { "No specific notification for state $state for $accountKey" }
//                            }
//                        }
//                    } catch (e: Exception) {
//                        log.e(tag = TAG) { "Error in RegistrationListener: ${e.message}" }
//                    }
//                }
//
//                log.d(tag = TAG) { "Successfully notified all listeners for $accountKey state change to $state" }
//
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Critical error in registration state notification: ${e.message}" }
//            }
//        }
//    }
    private fun notifyRegistrationStateChanged(
        state: RegistrationState,
        username: String,
        domain: String
    ) {
        val accountKey = "$username@$domain"

        // NUEVO: Verificar si el estado realmente cambió
        val lastState = lastNotifiedRegistrationStates[accountKey]
        if (lastState == state) {
            log.d(tag = TAG) { "Skipping duplicate registration state notification for $accountKey: $state" }
            return
        }

        // Actualizar cache antes de notificar
        lastNotifiedRegistrationStates[accountKey] = state

        log.d(tag = TAG) { "Notifying registration state change: $lastState -> $state for $accountKey to ${listeners.size} listeners" }

        // NUEVO: Verificar que el estado realmente cambió en el SipCoreManager
        val actualState = sipCoreManager?.getRegistrationState(accountKey) ?: RegistrationState.NONE
        if (actualState != state) {
            log.w(tag = TAG) { "State mismatch for $accountKey: notified=$state, actual=$actualState. Using actual state." }
            // Usar el estado actual del core manager y actualizar cache
            lastNotifiedRegistrationStates[accountKey] = actualState
            notifyWithActualState(actualState, username, domain, accountKey)
            return
        }

        // Ejecutar notificaciones en el hilo principal
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Notificar listeners generales
                listeners.forEach { listener ->
                    try {
                        listener.onRegistrationStateChanged(state, username, domain)
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error in listener onRegistrationStateChanged: ${e.message}" }
                    }
                }

                // Notificar listener específico SOLO UNA VEZ
                registrationListener?.let { listener ->
                    try {
                        when (state) {
                            RegistrationState.OK -> {
                                listener.onRegistrationSuccessful(username, domain)
                                log.d(tag = TAG) { "Notified registration successful for $accountKey" }
                            }

                            RegistrationState.FAILED -> {
                                listener.onRegistrationFailed(
                                    username,
                                    domain,
                                    "Registration failed"
                                )
                                log.d(tag = TAG) { "Notified registration failed for $accountKey" }
                            }

                            RegistrationState.NONE, RegistrationState.CLEARED -> {
                                listener.onUnregistered(username, domain)
                                log.d(tag = TAG) { "Notified unregistered for $accountKey" }
                            }

                            RegistrationState.IN_PROGRESS -> {
                                // No notificar IN_PROGRESS a listeners específicos para evitar spam
                                log.d(tag = TAG) { "Registration in progress for $accountKey - not notifying specific listener" }
                            }

                            else -> {
                                log.d(tag = TAG) { "No specific notification for state $state for $accountKey" }
                            }
                        }
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error in RegistrationListener: ${e.message}" }
                    }
                }

                log.d(tag = TAG) { "Successfully notified all listeners for $accountKey state change: $lastState -> $state" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Critical error in registration state notification: ${e.message}" }
            }
        }
    }

    private fun notifyWithActualState(
        actualState: RegistrationState,
        username: String,
        domain: String,
        accountKey: String
    ) {
        log.d(tag = TAG) { "Using actual state $actualState for notifications for $accountKey" }

        CoroutineScope(Dispatchers.Main).launch {
            listeners.forEach { listener ->
                try {
                    listener.onRegistrationStateChanged(actualState, username, domain)
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in listener with actual state: ${e.message}" }
                }
            }

            registrationListener?.let { listener ->
                when (actualState) {
                    RegistrationState.OK -> listener.onRegistrationSuccessful(username, domain)
                    RegistrationState.FAILED -> listener.onRegistrationFailed(
                        username,
                        domain,
                        "Registration failed"
                    )

                    RegistrationState.NONE, RegistrationState.CLEARED -> listener.onUnregistered(
                        username,
                        domain
                    )

                    else -> {}
                }
            }
        }
    }


    /**
     * NUEVO: Verifica y corrige inconsistencias de estado de registro
     */
    fun verifyAndCorrectRegistrationStates(): String {
        checkInitialized()

        val diagnosticInfo = mutableListOf<String>()
        diagnosticInfo.add("=== REGISTRATION STATE VERIFICATION ===")

        val coreStates = sipCoreManager?.getAllRegistrationStates() ?: emptyMap()
        val coreAccounts = sipCoreManager?.activeAccounts ?: emptyMap()

        diagnosticInfo.add("Active accounts: ${coreAccounts.size}")
        diagnosticInfo.add("Tracked states: ${coreStates.size}")

        coreAccounts.forEach { (accountKey, accountInfo) ->
            val coreState = coreStates[accountKey] ?: RegistrationState.NONE
            val internalFlag = accountInfo.isRegistered
            val webSocketConnected = accountInfo.webSocketClient?.isConnected() == true

            diagnosticInfo.add("\nAccount: $accountKey")
            diagnosticInfo.add("  Core State: $coreState")
            diagnosticInfo.add("  Internal Flag: $internalFlag")
            diagnosticInfo.add("  WebSocket Connected: $webSocketConnected")

            // Detectar inconsistencias
            var needsCorrection = false
            var correctedState = coreState

            when {
                coreState == RegistrationState.OK && !internalFlag -> {
                    diagnosticInfo.add("  ⚠️ INCONSISTENCY: Core says OK but internal flag is false")
                    accountInfo.isRegistered = true
                    needsCorrection = true
                }

                coreState != RegistrationState.OK && internalFlag -> {
                    diagnosticInfo.add("  ⚠️ INCONSISTENCY: Internal flag true but core state is $coreState")
                    accountInfo.isRegistered = false
                    needsCorrection = true
                }

                !webSocketConnected && (coreState == RegistrationState.OK || internalFlag) -> {
                    diagnosticInfo.add("  ⚠️ INCONSISTENCY: Not connected but marked as registered")
                    accountInfo.isRegistered = false
                    correctedState = RegistrationState.NONE
                    sipCoreManager?.updateRegistrationState(accountKey, RegistrationState.NONE)
                    needsCorrection = true
                }

                webSocketConnected && coreState == RegistrationState.NONE && !internalFlag -> {
                    diagnosticInfo.add("  ℹ️ Connected but not registered - this is normal during connection")
                }

                else -> {
                    diagnosticInfo.add("  ✅ States are consistent")
                }
            }

            if (needsCorrection) {
                diagnosticInfo.add("  🔧 CORRECTED inconsistencies")

                // Forzar notificación del estado correcto
                try {
                    sipCoreManager?.sipCallbacks?.onAccountRegistrationStateChanged(
                        accountInfo.username,
                        accountInfo.domain,
                        correctedState
                    )
                    diagnosticInfo.add("  ✅ Forced state notification sent")
                } catch (e: Exception) {
                    diagnosticInfo.add("  ❌ Error sending forced notification: ${e.message}")
                }
            }
        }

        return diagnosticInfo.joinToString("\n")
    }

    /**
     * Notificar cambios de estado
     */
//    private fun notifyCallStateChanged(stateInfo: CallStateInfo) {
//        log.d(tag = TAG) { "Notifying call state change: ${stateInfo.state} to ${listeners.size} listeners" }
//
//        listeners.forEach { listener ->
//            try {
//                listener.onCallStateChanged(stateInfo)
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error in listener onCallStateChanged: ${e.message}" }
//            }
//        }
//
//        callListener?.let { listener ->
//            try {
//                listener.onCallStateChanged(stateInfo)
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error in CallListener onCallStateChanged: ${e.message}" }
//            }
//        }
//    }
    fun prepareAudioForCall(){
        sipCoreManager?.prepareAudioForCall()
    }
    fun onBluetoothConnectionChanged(isConnected: Boolean){
        sipCoreManager?.onBluetoothConnectionChanged(isConnected)
    }
    fun refreshAudioDevicesWithBluetoothPriority(){
        sipCoreManager?.refreshAudioDevicesWithBluetoothPriority()
    }
    fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean{
        return sipCoreManager?.applyAudioRouteChange(audioUnitType) == true
    }
    fun getAvailableAudioUnits(): Set<AudioUnit>? {
        return sipCoreManager?.getAvailableAudioUnits()
    }
    fun getCurrentActiveAudioUnit(): AudioUnit?{
        return sipCoreManager?.getCurrentActiveAudioUnit()
    }


    private fun notifyCallStateChanged(stateInfo: CallStateInfo) {
        // Verificar si el estado realmente cambió
        val lastState = lastNotifiedCallState.get()

        // Comparar estados ignorando timestamp para evitar duplicados
        val stateChanged = lastState == null ||
                lastState.state != stateInfo.state ||
                lastState.callId != stateInfo.callId ||
                lastState.errorReason != stateInfo.errorReason

        if (!stateChanged) {
            log.d(tag = TAG) { "Skipping duplicate call state notification: ${stateInfo.state} for call ${stateInfo.callId}" }
            return
        }

        // Actualizar cache
        lastNotifiedCallState.set(stateInfo)

        log.d(tag = TAG) { "Notifying call state change: ${lastState?.state} -> ${stateInfo.state} for call ${stateInfo.callId} to ${listeners.size} listeners" }

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

    fun diagnoseDuplicateStates(): String {
        return buildString {
            appendLine("=== DUPLICATE STATES DIAGNOSTIC ===")
            appendLine("Last notified registration states:")
            lastNotifiedRegistrationStates.forEach { (account, state) ->
                appendLine("  $account: $state")
            }

            val lastCallState = lastNotifiedCallState.get()
            appendLine("\nLast notified call state:")
            appendLine("  State: ${lastCallState?.state ?: "None"}")
            appendLine("  Call ID: ${lastCallState?.callId ?: "None"}")
            appendLine("  Timestamp: ${lastCallState?.timestamp ?: "None"}")

            appendLine("\nCurrent actual states:")
            getAllRegistrationStates().forEach { (account, state) ->
                appendLine("  $account: $state")
            }

            appendLine("\nCurrent call state:")
            val currentCallState = getCurrentCallState()
            appendLine("  State: ${currentCallState.state}")
            appendLine("  Call ID: ${currentCallState.callId}")
        }
    }

    /**
     * NUEVO: Método para diagnosticar estados iniciales
     */
    fun diagnoseInitialStates(): String {
        return buildString {
            appendLine("=== INITIAL STATES DIAGNOSTIC ===")
            appendLine("Library initialized: $isInitialized")
            appendLine("Current timestamp: ${System.currentTimeMillis()}")

            val currentCallState = getCurrentCallState()
            appendLine("\nCurrent call state:")
            appendLine("  State: ${currentCallState.state}")
            appendLine("  Timestamp: ${currentCallState.timestamp}")
            appendLine("  Is future timestamp: ${currentCallState.timestamp > System.currentTimeMillis() + 1000}")
            appendLine("  Call ID: '${currentCallState.callId}'")
            appendLine("  Direction: ${currentCallState.direction}")

            appendLine("\nActive calls: ${MultiCallManager.getAllCalls().size}")
            MultiCallManager.getAllCalls().forEach { call ->
                appendLine("  Call ID: ${call.callId}, From: ${call.from}, To: ${call.to}")
            }

            appendLine("\nCall state history size: ${getCallStateHistory().size}")

            appendLine("\nRegistration states cache:")
            lastNotifiedRegistrationStates.forEach { (account, state) ->
                appendLine("  $account: $state")
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
        private fun createIncomingCallInfoFromCurrentCall(
            callerNumber: String,
            callerName: String?
        ): IncomingCallInfo {
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
         * Obtiene información de llamada para un estado específico
         */
        private fun getCallInfoForState(stateInfo: CallStateInfo): CallInfo? {
            val manager = sipCoreManager ?: return null
            val calls = MultiCallManager.getAllCalls()

            // Buscar la llamada específica por callId
            val callData = calls.find { it.callId == stateInfo.callId }
                ?: manager.currentAccountInfo?.currentCallData
                ?: return null

            val account = manager.currentAccountInfo ?: return null
            val currentCall1 = calls.size == 1 &&
                    stateInfo.state != CallState.ENDED &&
                    stateInfo.state != CallState.ERROR &&
                    stateInfo.state != CallState.ENDING &&
                    stateInfo.state != CallState.IDLE

            return try {
                CallInfo(
                    callId = callData.callId,
                    phoneNumber = if (callData.direction == CallDirections.INCOMING) callData.from else callData.to,
                    displayName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
                    direction = if (callData.direction == CallDirections.INCOMING) CallDirection.INCOMING else CallDirection.OUTGOING,
                    startTime = callData.startTime,
                    duration = if (callData.startTime > 0) System.currentTimeMillis() - callData.startTime else 0,
                    isOnHold = callData.isOnHold ?: false,
                    isMuted = manager.webRtcManager.isMuted(),
                    localAccount = account.username,
                    codec = null,
                    state = stateInfo.state,
                    isCurrentCall = currentCall1
                )
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error creating CallInfo: ${e.message}" }
                null
            }
        }

        /**
         * Método getCurrentCallInfo() actualizado
         */
        private fun getCurrentCallInfo(): CallInfo? {
            val manager = sipCoreManager ?: return null
            val account = manager.currentAccountInfo ?: return null
            val calls = MultiCallManager.getAllCalls()
            val callData = calls.firstOrNull() ?: account.currentCallData ?: return null

            val currentCall1 = calls.size == 1 &&
                    CallStateManager.getCurrentState().let { state ->
                        state.state != CallState.ENDED &&
                                state.state != CallState.ERROR &&
                                state.state != CallState.ENDING &&
                                state.state != CallState.IDLE
                    }

            return try {
                // Obtener estado actual
                val currentState = CallStateManager.getCurrentState()

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
                    state = currentState.state,
                    isCurrentCall = currentCall1
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

        private fun generateCallId(): String {
            return "call_${System.currentTimeMillis()}_${(1000..9999).random()}"
        }

        // === MÉTODOS PÚBLICOS DE LA API ===

        /**
         * Registra una cuenta SIP
         */
        suspend fun registerAccount(
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
//  suspend  fun unregisterAccount(username: String, domain: String) {
//        checkInitialized()
//        log.d(tag = TAG) { "Unregistering account: $username@$domain" }
//        sipCoreManager?.unregister(username, domain)
//    }
        suspend fun unregisterAccount(username: String, domain: String) {
            checkInitialized()
            val accountKey = "$username@$domain"
            log.d(tag = TAG) { "Unregistering account: $accountKey" }

            // Limpiar del cache
            lastNotifiedRegistrationStates.remove(accountKey)

            sipCoreManager?.unregister(username, domain)
        }


        /**
         * NUEVO: Método para limpiar todos los caches
         */
        suspend fun unregisterAllAccounts() {
            checkInitialized()
            log.d(tag = TAG) { "Unregistering all accounts" }

            // Limpiar todos los caches
            lastNotifiedRegistrationStates.clear()
            lastNotifiedCallState.set(null)

            sipCoreManager?.unregisterAllAccounts()
        }

        /**
         * Desregistra todas las cuentas
         */
// suspend   fun unregisterAllAccounts() {
//        checkInitialized()
//        log.d(tag = TAG) { "Unregistering all accounts" }
//        sipCoreManager?.unregisterAllAccounts()
//    }

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
         * Flow para observar estados de llamada
         */
        fun getCallStateFlow(): Flow<CallStateInfo> {
            checkInitialized()
            return CallStateManager.callStateFlow
        }

        /**
         * Obtener estado actual
         */
        fun getCurrentCallState(): CallStateInfo {
            checkInitialized()
            return CallStateManager.getCurrentState()
        }

        /**
         * Obtener historial de estados de llamada
         */
        fun getCallStateHistory(): List<CallStateInfo> {
            checkInitialized()
            return CallStateManager.getStateHistory()
        }

        /**
         * Limpiar historial de estados
         */
        fun clearCallStateHistory() {
            checkInitialized()
            CallStateManager.clearHistory()
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

        /**
         * Realiza una llamada
         */
        fun makeCall(
            phoneNumber: String,
            username: String? = null,
            domain: String? = null
        ) {
            checkInitialized()

            val finalUsername = username ?: sipCoreManager?.getCurrentUsername() ?: run {
                log.e(tag = TAG) { "No username provided and no current account available" }
                throw SipLibraryException("No username available for making call")
            }

            val finalDomain = domain ?: sipCoreManager?.getDefaultDomain() ?: run {
                log.e(tag = TAG) { "No domain provided and no current account available" }
                throw SipLibraryException("No domain available for making call")
            }

            log.d(tag = TAG) { "Making call to $phoneNumber from $finalUsername@$finalDomain" }

            sipCoreManager?.makeCall(phoneNumber, finalUsername, finalDomain)
        }

        /**
         * Acepta una llamada (con soporte para múltiples llamadas)
         */
        fun acceptCall(callId: String? = null) {
            checkInitialized()

            val calls = MultiCallManager.getAllCalls()
            val targetCallId = if (callId == null && calls.size == 1) {
                // Llamada única, usar sin callId
                log.d(tag = TAG) { "Accepting single call" }
                sipCoreManager?.acceptCall()
                return
            } else {
                callId ?: calls.firstOrNull()?.callId
            }

            if (targetCallId != null) {
                log.d(tag = TAG) { "Accepting call: $targetCallId" }
                sipCoreManager?.acceptCall(targetCallId)
            } else {
                log.w(tag = TAG) { "No call to accept" }
            }
        }

        /**
         * Rechaza una llamada (con soporte para múltiples llamadas)
         */
        fun declineCall(callId: String? = null) {
            checkInitialized()

            val calls = MultiCallManager.getAllCalls()
            val targetCallId = if (callId == null && calls.size == 1) {
                // Llamada única, usar sin callId
                log.d(tag = TAG) { "Declining single call" }
                sipCoreManager?.declineCall()
                return
            } else {
                callId ?: calls.firstOrNull()?.callId
            }

            if (targetCallId != null) {
                log.d(tag = TAG) { "Declining call: $targetCallId" }
                sipCoreManager?.declineCall(targetCallId)
            } else {
                log.w(tag = TAG) { "No call to decline" }
            }
        }

        /**
         * Termina una llamada (con soporte para múltiples llamadas)
         */
        fun endCall(callId: String? = null) {
            checkInitialized()

            val calls = MultiCallManager.getAllCalls()
            val targetCallId = if (callId == null && calls.size == 1) {
                // Llamada única, usar sin callId
                log.d(tag = TAG) { "Ending single call" }
                sipCoreManager?.endCall()
                return
            } else {
                callId ?: calls.firstOrNull()?.callId
            }

            if (targetCallId != null) {
                log.d(tag = TAG) { "Ending call: $targetCallId" }
                sipCoreManager?.endCall(targetCallId)
            } else {
                log.w(tag = TAG) { "No call to end" }
            }
        }

        /**
         * Pone una llamada en espera (con soporte para múltiples llamadas)
         */
        fun holdCall(callId: String? = null) {
            checkInitialized()

            val calls = MultiCallManager.getAllCalls()
            val targetCallId = if (callId == null && calls.size == 1) {
                // Llamada única, usar sin callId
                log.d(tag = TAG) { "Holding single call" }
                sipCoreManager?.holdCall()
                return
            } else {
                callId ?: calls.firstOrNull()?.callId
            }

            if (targetCallId != null) {
                log.d(tag = TAG) { "Holding call: $targetCallId" }
                sipCoreManager?.holdCall(targetCallId)
            } else {
                log.w(tag = TAG) { "No call to hold" }
            }
        }

        /**
         * Reanuda una llamada (con soporte para múltiples llamadas)
         */
        fun resumeCall(callId: String? = null) {
            checkInitialized()

            val calls = MultiCallManager.getAllCalls()
            val targetCallId = if (callId == null && calls.size == 1) {
                // Llamada única, usar sin callId
                log.d(tag = TAG) { "Resuming single call" }
                sipCoreManager?.resumeCall()
                return
            } else {
                callId ?: calls.firstOrNull()?.callId
            }

            if (targetCallId != null) {
                log.d(tag = TAG) { "Resuming call: $targetCallId" }
                sipCoreManager?.resumeCall(targetCallId)
            } else {
                log.w(tag = TAG) { "No call to resume" }
            }
        }

        /**
         * Alias para resumeCall para compatibilidad
         */
        fun unholdCall(callId: String? = null) = resumeCall(callId)

        /**
         * Obtiene todas las llamadas activas
         */
        fun getAllCalls(): List<CallInfo> {
            checkInitialized()
            return MultiCallManager.getAllCalls().mapNotNull { callData ->
                try {
                    val account = sipCoreManager?.currentAccountInfo ?: return@mapNotNull null
                    val allActiveCalls = MultiCallManager.getActiveCalls()

                    // Determinar si es la llamada actual
                    val isCurrentCall = allActiveCalls.size == 1 &&
                            allActiveCalls.first().callId == callData.callId

                    CallInfo(
                        callId = callData.callId,
                        phoneNumber = if (callData.direction == CallDirections.INCOMING) callData.from else callData.to,
                        displayName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
                        direction = if (callData.direction == CallDirections.INCOMING) CallDirection.INCOMING else CallDirection.OUTGOING,
                        startTime = callData.startTime,
                        duration = if (callData.startTime > 0) System.currentTimeMillis() - callData.startTime else 0,
                        isOnHold = callData.isOnHold ?: false,
                        isMuted = sipCoreManager?.webRtcManager?.isMuted() ?: false,
                        localAccount = account.username,
                        codec = null,
                        state = CallStateManager.getStateForCall(callData.callId)?.state,
                        isCurrentCall = isCurrentCall
                    )
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error creating CallInfo for ${callData.callId}: ${e.message}" }
                    null
                }
            }
        }

        /**
         * Fuerza la limpieza de llamadas terminadas
         */
        fun cleanupTerminatedCalls() {
            checkInitialized()
            sipCoreManager?.cleanupTerminatedCalls()
        }

        /**
         * Obtiene información detallada sobre las llamadas
         */
        fun getCallsDiagnostic(): String {
            checkInitialized()
            return sipCoreManager?.getCallsInfo() ?: "No call information available"
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
    // === MÉTODOS DE CAMBIO RINGTONE ACTUALIZADOS ===

     fun setIncomingRingtone(uri: Uri) {
        checkInitialized()
        try {
            // Guardar en base de datos Y configurar en AudioManager
            sipCoreManager?.saveIncomingRingtoneUri(uri)
            log.d(tag = TAG) { "Incoming ringtone URI updated and saved: $uri" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting incoming ringtone: ${e.message}" }
        }
    }

     fun setOutgoingRingtone(uri: Uri) {
        checkInitialized()
        try {
            // Guardar en base de datos Y configurar en AudioManager
            sipCoreManager?.saveOutgoingRingtoneUri(uri)
            log.d(tag = TAG) { "Outgoing ringtone URI updated and saved: $uri" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting outgoing ringtone: ${e.message}" }
        }
    }

    /**
     * NUEVO: Actualiza ambos ringtones de una vez
     */
    suspend fun setRingtoneUris(incomingUri: Uri?, outgoingUri: Uri?) {
        checkInitialized()
        try {
            sipCoreManager?.saveRingtoneUris(incomingUri, outgoingUri)
            log.d(tag = TAG) { "Both ringtone URIs updated and saved" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting ringtone URIs: ${e.message}" }
        }
    }

    /**
     * NUEVO: Obtiene las URIs de ringtones actuales desde la base de datos
     */
    suspend fun getCurrentRingtoneUris(): Pair<Uri?, Uri?> {
        checkInitialized()
        return try {
            val config = databaseManager?.getAppConfig()
            val incomingUri = config?.incomingRingtoneUri?.let { Uri.parse(it) }
            val outgoingUri = config?.outgoingRingtoneUri?.let { Uri.parse(it) }
            Pair(incomingUri, outgoingUri)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting ringtone URIs: ${e.message}" }
            Pair(null, null)
        }
    }

    /**
     * NUEVO: Flow para observar cambios en la configuración
     */
    fun getConfigurationFlow(): Flow<AppConfigEntity?> {
        checkInitialized()
        return databaseManager?.getAppConfigFlow() ?: flowOf(null)
    }

    /**
     * NUEVO: Resetea los ringtones a los valores por defecto
     */
    suspend fun resetRingtonesToDefault() {
        checkInitialized()
        try {
            // Usar ringtones por defecto del sistema
            val defaultIncoming = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val defaultOutgoing = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            setRingtoneUris(defaultIncoming, defaultOutgoing)
            log.d(tag = TAG) { "Ringtones reset to system defaults" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error resetting ringtones: ${e.message}" }
        }
    }

//        // === MÉTODOS DE CAMBIO RINGTONE ===
//        fun setIncomingRingtone(uri: Uri) {
//            checkInitialized()
//            try {
//                sipCoreManager?.audioManager?.setIncomingRingtone(uri)
//                log.d(tag = TAG) { "Incoming ringtone URI set: $uri" }
//
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error in CallListener onMuteStateChanged: ${e.message}" }
//            }
//
//        }
//
//        fun setOutgoingRingtone(uri: Uri) {
//            checkInitialized()
//            try {
//                sipCoreManager?.audioManager?.setOutgoingRingtone(uri)
//                log.d(tag = TAG) { "Outgoing ringtone URI set: $uri" }
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error in CallListener onMuteStateChanged: ${e.message}" }
//            }
//
//        }
//        // === MÉTODOS DE CAMBIO RINGTONE ===


        // === MÉTODOS DE INFORMACIÓN ===

        fun hasActiveCall(): Boolean {
            checkInitialized()
            return MultiCallManager.hasActiveCalls()
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
            return sipCoreManager?.webRtcManager?.getAllAudioDevices() ?: Pair(
                emptyList(),
                emptyList()
            )
        }

        fun changeAudioOutput(device: AudioDevice): Boolean {
            checkInitialized()
            return sipCoreManager?.webRtcManager?.changeAudioOutputDeviceDuringCall(device) ?: false
        }

        fun getCallLogs(limit: Int = 50): List<CallLog> {
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

        // === MÉTODOS DE GESTIÓN DE MODO PUSH ===

        /**
         * Cambia manualmente a modo push para cuentas específicas
         */
        fun switchToPushMode(accounts: Set<String>? = null) {
            checkInitialized()

            val accountsToSwitch =
                accounts ?: sipCoreManager?.getAllRegisteredAccountKeys() ?: emptySet()
            pushModeManager?.switchToPushMode(accountsToSwitch)

            log.d(tag = TAG) { "Manual switch to push mode for ${accountsToSwitch.size} accounts" }
        }

        /**
         * NUEVO: Sincroniza datos de memoria a BD
         */
        fun syncCallLogsToDB() {
            checkInitialized()
            sipCoreManager?.syncMemoryCallLogsToDB()
        }

        /**
         * NUEVO: Flow para observar cambios en call logs desde BD
         */
        fun getCallLogsFlow(limit: Int = 50): Flow<List<CallLog>>? {
            checkInitialized()
            return try {
                databaseManager?.getRecentCallLogs(limit)?.map { callLogsWithContact ->
                    callLogsWithContact.toCallLogs()
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error getting call logs flow: ${e.message}" }
                null
            }
        }

        /**
         * NUEVO: Flow para observar missed calls desde BD
         */
        fun getMissedCallsFlow(): Flow<List<CallLog>>? {
            checkInitialized()
            return try {
                databaseManager?.getMissedCallLogs()?.map { callLogsWithContact ->
                    callLogsWithContact.toCallLogs()
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error getting missed calls flow: ${e.message}" }
                null
            }
        }

        /**
         * NUEVO: Método para obtener estadísticas de llamadas desde BD
         */
        suspend fun getCallStatisticsFromDB(): CallHistoryManager.CallStatistics? {
            checkInitialized()
            return try {
                val stats = databaseManager?.getGeneralStatistics()
                stats?.let {
                    CallHistoryManager.CallStatistics(
                        totalCalls = it.totalCalls,
                        missedCalls = it.missedCalls,
                        successfulCalls = it.totalCalls - it.missedCalls, // aproximación
                        declinedCalls = 0, // se puede calcular si se agrega a GeneralStatistics
                        abortedCalls = 0,  // se puede calcular si se agrega a GeneralStatistics
                        incomingCalls = 0, // se puede calcular si se agrega a GeneralStatistics
                        outgoingCalls = 0, // se puede calcular si se agrega a GeneralStatistics
                        totalDuration = 0  // se puede calcular si se agrega a GeneralStatistics
                    )
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error getting call statistics from DB: ${e.message}" }
                null
            }
        }

        /**
         * Cambia manualmente a modo foreground para cuentas específicas
         */
        fun switchToForegroundMode(accounts: Set<String>? = null) {
            checkInitialized()

            val accountsToSwitch =
                accounts ?: sipCoreManager?.getAllRegisteredAccountKeys() ?: emptySet()
            pushModeManager?.switchToForegroundMode(accountsToSwitch)

            log.d(tag = TAG) { "Manual switch to foreground mode for ${accountsToSwitch.size} accounts" }
        }

        /**
         * Obtiene el modo push actual
         */
        fun getCurrentPushMode(): PushMode {
            checkInitialized()
            return pushModeManager?.getCurrentMode() ?: PushMode.FOREGROUND
        }

        /**
         * Verifica si está en modo push
         */
        fun isInPushMode(): Boolean {
            checkInitialized()
            return pushModeManager?.isInPushMode() ?: false
        }

        /**
         * Obtiene el estado completo del modo push
         */
        fun getPushModeState(): PushModeState {
            checkInitialized()
            return pushModeManager?.getCurrentState() ?: PushModeState(
                currentMode = PushMode.FOREGROUND,
                previousMode = null,
                timestamp = System.currentTimeMillis(),
                reason = "Not initialized"
            )
        }

        /**
         * Flow para observar cambios de modo push
         */
        fun getPushModeStateFlow(): Flow<PushModeState> {
            checkInitialized()
            return pushModeManager?.pushModeStateFlow ?: flowOf(getPushModeState())
        }

        /**
         * Notifica que se recibió una notificación push (para uso interno o externo)
         */
        fun onPushNotificationReceived(data: Map<String, Any>? = null) {
            log.d(tag = TAG1) { "onPushNotificationReceived: inicio data : $data" }

            checkInitialized()

            if (data != null && data.containsKey("sipName")) {
                // Notificación push específica para una cuenta
                val sipName = data["sipName"] as? String
                val phoneNumber = data["phoneNumber"] as? String
                val callId = data["callId"] as? String

                log.d(tag = TAG1) {
                    "Push notification for specific account: sipName=$sipName, phoneNumber=$phoneNumber, callId=$callId"
                }

                if (sipName != null) {
                    // Buscar la cuenta completa (sipName@domain)
                    val registeredAccounts =
                        sipCoreManager?.getAllRegisteredAccountKeys() ?: emptySet()
                    val specificAccount = registeredAccounts.find { it.startsWith("$sipName@") }

                    if (specificAccount != null) {
                        log.d(tag = TAG1) { "Found specific account for push: $specificAccount" }
                        pushModeManager?.onPushNotificationReceived(
                            specificAccount = specificAccount,
                            allRegisteredAccounts = registeredAccounts
                        )

//                    // Preparar para la llamada entrante específica
//                    prepareForIncomingCall(specificAccount, phoneNumber, callId)
                    } else {
                        log.w(tag = TAG1) { "Account not found for sipName: $sipName" }
                        // Fallback: cambiar todas las cuentas
                        val allAccounts =
                            sipCoreManager?.getAllRegisteredAccountKeys() ?: emptySet()
                        pushModeManager?.onPushNotificationReceived(allRegisteredAccounts = allAccounts)
                    }
                }
            } else {
                // Notificación push genérica (comportamiento anterior)
                val registeredAccounts = sipCoreManager?.getAllRegisteredAccountKeys() ?: emptySet()
                pushModeManager?.onPushNotificationReceived(allRegisteredAccounts = registeredAccounts)
            }

            log.d(tag = TAG1) { "Push notification processed, managing mode transition" }
        }

        fun diagnosePushMode(): String {
            return buildString {
                appendLine("=== PUSH MODE DIAGNOSTIC ===")
                appendLine("Push Mode Manager: ${pushModeManager != null}")
                appendLine("SipCore Manager: ${sipCoreManager != null}")

                pushModeManager?.let { pm ->
                    appendLine("\n--- Push Mode State ---")
                    appendLine(pm.getDiagnosticInfo())
                }

                sipCoreManager?.let { sm ->
                    appendLine("\n--- Registered Accounts ---")
                    val accounts = sm.getAllRegisteredAccountKeys()
                    appendLine("Count: ${accounts.size}")
                    accounts.forEach { account ->
                        appendLine("- $account")
                    }

                    appendLine("\n--- App State ---")
                    appendLine("Is in background: ${sm.isAppInBackground}")
                }
            }
        }

//    /**
//     * Prepara el sistema para una llamada entrante específica
//     */
//    private fun prepareForIncomingCall(accountKey: String, phoneNumber: String?, callId: String?) {
//        log.d(tag = TAG) { "Preparing for incoming call on account: $accountKey" }
//
//        sipCoreManager?.prepareForIncomingCall(accountKey, phoneNumber, callId)
//    }

        fun updatePushToken(token: String, provider: String = "fcm") {
            checkInitialized()
            sipCoreManager?.enterPushMode(token)
        }
//
//        fun getSystemHealthReport(): String {
//            checkInitialized()
//            return sipCoreManager?.getSystemHealthReport() ?: "Library not initialized"
//        }

        fun isSystemHealthy(): Boolean {
            checkInitialized()
            return sipCoreManager?.isSipCoreManagerHealthy() ?: false
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
                appendLine("Push Mode: ${getCurrentPushMode()}")
                appendLine("Push Mode State: ${getPushModeState()}")
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
            }
        }

        fun dispose() {
            if (isInitialized) {
                log.d(tag = TAG) { "Disposing EddysSipLibrary" }

                // Limpiar integración de base de datos primero
                databaseIntegration?.dispose()
                databaseIntegration = null

                // Cerrar base de datos de forma segura
                databaseManager?.closeDatabase()
                databaseManager = null

                // Resto del cleanup
                sipCoreManager?.dispose()
                sipCoreManager = null
                pushModeManager?.dispose()
                pushModeManager = null
                listeners.clear()

                registrationListener = null
                callListener = null
                incomingCallListener = null
                networkStatusListener = null
                autoReconnectionListener = null

                isInitialized = false
                log.d(tag = TAG) { "EddysSipLibrary disposed completely" }
            }
        }

        // === INTERFAZ INTERNA DE CALLBACKS ===

        internal interface SipCallbacks {
            fun onCallTerminated() {}
            fun onRegistrationStateChanged(state: RegistrationState) {}
            fun onAccountRegistrationStateChanged(
                username: String,
                domain: String,
                state: RegistrationState
            ) {
            }

            fun onIncomingCall(callerNumber: String, callerName: String?) {}
            fun onCallConnected() {}
            fun onCallFailed(error: String) {}
            fun onCallEndedForAccount(accountKey: String) {}
        }

        class SipLibraryException(message: String, cause: Throwable? = null) :
            Exception(message, cause)


        ////// métodos de debugging ///////

        /**
         * SOLO PARA DEBUGGING: Simula recibir una notificación push de llamada entrante
         */
        fun simulateIncomingCallPush(
            sipName: String,
            phoneNumber: String,
            callId: String? = null,
        ): String {
            checkInitialized()

            log.d(tag = TAG) {
                "=== DEBUGGING: SIMULATING INCOMING CALL PUSH ===" +
                        "\nSIP Name: $sipName" +
                        "\nPhone Number: $phoneNumber" +
                        "\nCall ID: $callId"
            }

            // Generar datos de push simulados
            val pushData = pushSimulator.simulateIncomingCallPush(
                sipName = sipName,
                phoneNumber = phoneNumber,
                callId = callId

            )

            // Procesar la notificación push simulada
            onPushNotificationReceived(pushData)

            val result = buildString {
                appendLine("=== PUSH SIMULATION COMPLETED ===")
                appendLine("Generated Push Data:")
                pushData.forEach { (key, value) ->
                    appendLine("  $key: $value")
                }
                appendLine("\nPush Mode State After Processing:")
                appendLine(getPushModeState().toString())
                appendLine("\nRegistered Accounts:")
                getAllRegistrationStates().forEach { (account, state) ->
                    appendLine("  $account: $state")
                }
            }

            log.d(tag = TAG) { result }
            return result
        }

        /**
         * SOLO PARA DEBUGGING: Simula recibir una notificación push genérica
         */
        fun simulateGenericPush(
            type: String = "generic",
            additionalData: Map<String, Any> = emptyMap()
        ): String {
            checkInitialized()

            log.d(tag = TAG) {
                "=== DEBUGGING: SIMULATING GENERIC PUSH ===" +
                        "\nType: $type" +
                        "\nAdditional Data: $additionalData"
            }

            val pushData = pushSimulator.simulateGenericPush(type, additionalData)

            // Procesar la notificación push simulada
            onPushNotificationReceived(pushData)

            val result = buildString {
                appendLine("=== GENERIC PUSH SIMULATION COMPLETED ===")
                appendLine("Generated Push Data:")
                pushData.forEach { (key, value) ->
                    appendLine("  $key: $value")
                }
                appendLine("\nPush Mode State After Processing:")
                appendLine(getPushModeState().toString())
            }

            log.d(tag = TAG) { result }
            return result
        }

        /**
         * SOLO PARA DEBUGGING: Simula el flujo completo de una llamada entrante via push
         */
        fun simulateCompleteIncomingCallFlow(
            sipName: String,
            phoneNumber: String,
            domain: String = "mcn.ru"
        ): String {
            checkInitialized()

            val accountKey = "$sipName@$domain"
            val callId = generateCallId()

            log.d(tag = TAG) {
                "=== DEBUGGING: SIMULATING COMPLETE INCOMING CALL FLOW ===" +
                        "\nAccount: $accountKey" +
                        "\nPhone Number: $phoneNumber" +
                        "\nCall ID: $callId"
            }

            val steps = mutableListOf<String>()

            try {
                // Paso 1: Verificar que la cuenta esté registrada
                val registrationState = getRegistrationState(sipName, domain)
                steps.add("Step 1: Account registration check - $registrationState")

                if (registrationState != RegistrationState.OK) {
                    steps.add("ERROR: Account not registered, cannot simulate call")
                    return steps.joinToString("\n")
                }

                // Paso 2: Verificar modo push actual
                val currentMode = getCurrentPushMode()
                steps.add("Step 2: Current push mode - $currentMode")

                // Paso 3: Simular notificación push
                steps.add("Step 3: Simulating push notification...")
                val pushData = pushSimulator.simulateIncomingCallPush(
                    sipName = sipName,
                    phoneNumber = phoneNumber,
                    callId = callId,
                )

                // Paso 4: Procesar push notification
                steps.add("Step 4: Processing push notification...")
                onPushNotificationReceived(pushData)

                // Paso 5: Verificar cambio de modo
                val newMode = getCurrentPushMode()
                steps.add("Step 5: Push mode after notification - $newMode")

                // Paso 6: Simular llamada entrante real (opcional)
                steps.add("Step 6: Ready to receive actual incoming call")
                steps.add("  - Account switched to foreground mode: ${newMode == PushMode.FOREGROUND}")
                steps.add("  - SIP registration refreshed for account: $accountKey")

                // Información final
                steps.add("\n=== SIMULATION SUMMARY ===")
                steps.add("Account: $accountKey")
                steps.add("Phone Number: $phoneNumber")
                steps.add("Call ID: $callId")
                steps.add("Mode Transition: $currentMode -> $newMode")
                steps.add("Ready for incoming call: ${newMode == PushMode.FOREGROUND}")

            } catch (e: Exception) {
                steps.add("ERROR in simulation: ${e.message}")
                log.e(tag = TAG) { "Error in complete call flow simulation: ${e.message}" }
            }

            val result = steps.joinToString("\n")
            log.d(tag = TAG) { result }
            return result
        }

        /**
         * SOLO PARA DEBUGGING: Obtiene información detallada del estado de push
         */
        fun getDetailedPushState(): String {
            checkInitialized()

            return buildString {
                appendLine("=== DETAILED PUSH STATE ===")
                appendLine("Current Push Mode: ${getCurrentPushMode()}")
                appendLine("Is In Push Mode: ${isInPushMode()}")

                val pushState = getPushModeState()
                appendLine("\n--- Push Mode State Details ---")
                appendLine("Current Mode: ${pushState.currentMode}")
                appendLine("Previous Mode: ${pushState.previousMode}")
                appendLine("Reason: ${pushState.reason}")
                appendLine("Timestamp: ${pushState.timestamp}")
                appendLine("Accounts In Push: ${pushState.accountsInPushMode}")
                appendLine("Was In Push Before Call: ${pushState.wasInPushBeforeCall}")
                appendLine("Specific Account In Foreground: ${pushState.specificAccountInForeground}")

                appendLine("\n--- Registered Accounts ---")
                getAllRegistrationStates().forEach { (account, state) ->
                    appendLine("$account: $state")
                }

                appendLine("\n--- Push Mode Manager Diagnostic ---")
                pushModeManager?.let { pm ->
                    appendLine(pm.getDiagnosticInfo())
                } ?: appendLine("Push Mode Manager not initialized")
            }
        }




        /**
         * NUEVO: Data class para estadísticas de conectividad
         */
        data class ConnectivityStatistics(
            val totalAccounts: Int,
            val registeredAccounts: Int,
            val reconnectingAccounts: Int,
            val totalReconnectionAttempts: Int,
            val networkConnected: Boolean,
            val hasInternet: Boolean,
            val networkType: String,
            val autoReconnectEnabled: Boolean
        )

        ///////////base de datos //////

        private fun setupDatabaseIntegration(application: Application) {
            try {
                log.d(tag = TAG) { "Setting up database integration" }

                // Verificar si la base de datos está disponible
                if (!SipDatabase.isDatabaseAvailable(application)) {
                    log.d(tag = TAG) { "Database not found, will be created" }
                }

                databaseManager = DatabaseManager.getInstance(application)

                sipCoreManager?.let { coreManager ->
                    databaseIntegration =
                        DatabaseAutoIntegration.getInstance(application, coreManager).apply {
                            initialize()
                        }
                    log.d(tag = TAG) { "Database integration initialized successfully" }
                }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error setting up database integration: ${e.message}" }
                // Continuar sin base de datos si falla
                databaseManager = null
                databaseIntegration = null
            }
        }

        fun verifyAndRestoreDatabase(application: Application): String {
            return try {
                checkInitialized()
                val dbAvailable = SipDatabase.isDatabaseAvailable(application)
                val managerExists = DatabaseManager.hasInstance()

                buildString {
                    appendLine("=== DATABASE VERIFICATION ===")
                    appendLine("Database file exists: $dbAvailable")
                    appendLine("Manager instance exists: $managerExists")

                    if (!managerExists && dbAvailable) {
                        appendLine("Attempting to restore database manager...")
                        try {
                            setupDatabaseIntegration(application)
                            appendLine("✅ Database manager restored successfully")
                        } catch (e: Exception) {
                            appendLine("❌ Failed to restore database manager: ${e.message}")
                        }
                    }

                    databaseManager?.let { manager ->
                        appendLine("\n--- Database Statistics ---")
                        runBlocking {
                            try {
                                val stats = manager.getGeneralStatistics()
                                appendLine("Total Accounts: ${stats.totalAccounts}")
                                appendLine("Total Calls: ${stats.totalCalls}")
                                appendLine("Total Contacts: ${stats.totalContacts}")
                                appendLine("Active Calls: ${stats.activeCalls}")
                                appendLine("✅ Database is functional")
                            } catch (e: Exception) {
                                appendLine("❌ Database error: ${e.message}")
                            }
                        }
                    } ?: appendLine("Database manager not available")
                }

            } catch (e: Exception) {
                "Error verifying database: ${e.message}"
            }
        }
        // === NUEVOS MÉTODOS PÚBLICOS PARA BD ===

        /**
         * Obtiene el manager de base de datos (si está habilitado)
         */
        fun getDatabaseManager(): DatabaseManager? {
            checkInitialized()
            return databaseManager
        }

        /**
         * Obtiene historial de llamadas desde la base de datos
         */
        fun getCallHistoryFromDB(limit: Int = 50): Flow<List<CallLogWithContact>>? {
            checkInitialized()
            return databaseManager?.getRecentCallLogs(limit)
        }

        /**
         * Busca en historial de llamadas
         */
        fun searchCallHistoryFromDB(query: String): Flow<List<CallLogWithContact>>? {
            checkInitialized()
            return databaseManager?.searchCallLogs(query)
        }

        /**
         * Busca en el historial de llamadas
         */
        suspend fun searchCallLogs(query: String): List<CallLog> {
            checkInitialized()
            return sipCoreManager?.searchCallLogsInDatabase(query) ?: emptyList()
        }

        /**
         * Obtiene contactos desde la base de datos
         */
        fun getContactsFromDB(): Flow<List<ContactEntity>>? {
            checkInitialized()
            return databaseManager?.getAllContacts()
        }

        /**
         * Busca contactos en la base de datos
         */
        fun searchContactsFromDB(query: String): Flow<List<ContactEntity>>? {
            checkInitialized()
            return databaseManager?.searchContacts(query)
        }

        /**
         * Obtiene cuentas SIP desde la base de datos
         */
        fun getSipAccountsFromDB(): Flow<List<SipAccountEntity>>? {
            checkInitialized()
            return databaseManager?.getActiveSipAccounts()
        }

        /**
         * Obtiene estadísticas generales desde la base de datos
         */
        suspend fun getStatisticsFromDB(): GeneralStatistics? {
            checkInitialized()
            return try {
                databaseManager?.getGeneralStatistics()
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error getting statistics: ${e.message}" }
                null
            }
        }

        /**
         * Crea o actualiza un contacto en la base de datos
         */
        suspend fun createOrUpdateContactInDB(
            phoneNumber: String,
            displayName: String,
            firstName: String? = null,
            lastName: String? = null,
            email: String? = null,
            company: String? = null
        ): ContactEntity? {
            checkInitialized()
            return try {
                databaseManager?.createOrUpdateContact(
                    phoneNumber = phoneNumber,
                    displayName = displayName,
                    firstName = firstName,
                    lastName = lastName,
                    email = email,
                    company = company
                )
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error creating/updating contact: ${e.message}" }
                null
            }
        }

        /**
         * Verifica si un número está bloqueado
         */
        suspend fun isPhoneNumberBlocked(phoneNumber: String): Boolean {
            checkInitialized()
            return try {
                databaseManager?.isPhoneNumberBlocked(phoneNumber) ?: false
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error checking if number is blocked: ${e.message}" }
                false
            }
        }

        /**
         * Limpia historial de llamadas
         */
        suspend fun clearCallHistoryFromDB(): Boolean {
            checkInitialized()
            return try {
                databaseManager?.clearAllCallLogs()
                true
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error clearing call history: ${e.message}" }
                false
            }
        }

        /**
         * Fuerza la sincronización de todas las cuentas con la BD
         */
        fun forceDatabaseSync() {
            checkInitialized()
            databaseIntegration?.forceSyncAllAccounts()
        }

        /**
         * Obtiene información de diagnóstico de la base de datos
         */
        suspend fun getDatabaseDiagnostic(): String {
            checkInitialized()
            return try {
                databaseIntegration?.getDiagnosticInfo() ?: "Database integration not available"
            } catch (e: Exception) {
                "Error getting database diagnostic: ${e.message}"
            }
        }

        /**
         * Configura limpieza automática de la base de datos
         */
        fun configureDatabaseMaintenance(
            daysToKeepLogs: Int = 30,
            maxCallLogs: Int = 1000,
            maxStateHistory: Int = 5000
        ) {
            checkInitialized()
            databaseManager?.let { dbManager ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        dbManager.cleanupOldData(daysToKeepLogs)
                        dbManager.keepOnlyRecentData(maxCallLogs, maxStateHistory)
                        log.d(tag = TAG) { "Database maintenance configured and executed" }
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error in database maintenance: ${e.message}" }
                    }
                }
            }
        }
    }