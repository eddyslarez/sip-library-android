package com.eddyslarez.siplibrary.core

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import com.eddyslarez.siplibrary.EddysSipLibrary
import com.eddyslarez.siplibrary.data.database.DatabaseAutoIntegration
import com.eddyslarez.siplibrary.data.database.DatabaseManager
import com.eddyslarez.siplibrary.data.database.converters.toCallLogs
import com.eddyslarez.siplibrary.data.database.entities.AppConfigEntity
import com.eddyslarez.siplibrary.data.models.*
import com.eddyslarez.siplibrary.data.services.audio.AudioDevice
import com.eddyslarez.siplibrary.data.services.audio.AudioDeviceManager
import com.eddyslarez.siplibrary.data.services.audio.CallHoldManager
import com.eddyslarez.siplibrary.data.services.audio.AudioManager
import com.eddyslarez.siplibrary.data.services.audio.WebRtcConnectionState
import com.eddyslarez.siplibrary.data.services.audio.WebRtcEventListener
import com.eddyslarez.siplibrary.data.services.audio.WebRtcManagerFactory
import com.eddyslarez.siplibrary.data.services.network.NetworkAwareReconnectionService
import com.eddyslarez.siplibrary.data.services.network.NetworkMonitor
import com.eddyslarez.siplibrary.data.services.network.ReconnectionManager
import com.eddyslarez.siplibrary.data.services.sip.SipMessageBuilder
import com.eddyslarez.siplibrary.data.services.sip.SipMessageHandler
import com.eddyslarez.siplibrary.data.services.websocket.MultiplatformWebSocket
import com.eddyslarez.siplibrary.data.services.websocket.WebSocket
import com.eddyslarez.siplibrary.data.store.SettingsDataStore
import com.eddyslarez.siplibrary.platform.PlatformInfo
import com.eddyslarez.siplibrary.platform.PlatformRegistration
import com.eddyslarez.siplibrary.platform.WindowManager
import com.eddyslarez.siplibrary.utils.*
import com.eddyslarez.siplibrary.utils.MultiCallManager
import com.eddyslarez.siplibrary.utils.MultiCallManager.getCall
import com.eddyslarez.siplibrary.utils.MultiCallManager.getCallState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.math.pow

/**
 * Gestor principal del core SIP - Optimizado sin estados legacy
 * Versión simplificada usando únicamente los nuevos estados
 *
 * @author Eddys Larez
 */
class SipCoreManager private constructor(
    private val application: Application,
    private val config: EddysSipLibrary.SipConfig,
    val audioManager: AudioManager,
    val windowManager: WindowManager,
    val platformInfo: PlatformInfo,
    val settingsDataStore: SettingsDataStore,
) {
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectionJob: Job? = null
    private var isNetworkAvailable = false
    private var wasDisconnectedDueToNetwork = false
    private val reconnectionAttempts = mutableMapOf<String, Int>()
    private val maxReconnectionAttempts = 5
    private val reconnectionBaseDelay = 2000L // 2 segundos base
    private val reconnectionMaxDelay = 30000L // 30 segundos máximo
    private var databaseManager: DatabaseManager? = null
    private var loadedConfig: AppConfigEntity? = null
    private var isRegistrationInProgress = false
    private var healthCheckJob: Job? = null
    private val registrationTimeout = 30000L
    private var lastRegistrationAttempt = 0L
    internal var sipCallbacks: EddysSipLibrary.SipCallbacks? = null
    private var isShuttingDown = false
    val callHistoryManager = CallHistoryManager()
    private var networkConnectivityListener: NetworkConnectivityListener? = null
    private var transferConfig = TransferConfig()
    private var deflectionConfig = CallDeflectionConfig()

    private var lifecycleCallback: ((String) -> Unit)? = null


    // Estados de registro por cuenta
    private val _registrationStates = MutableStateFlow<Map<String, RegistrationState>>(emptyMap())
    val registrationStatesFlow: StateFlow<Map<String, RegistrationState>> =
        _registrationStates.asStateFlow()

    val activeAccounts = HashMap<String, AccountInfo>()
    var callStartTimeMillis: Long = 0
    var currentAccountInfo: AccountInfo? = null
    var isAppInBackground = false
    private var lastConnectionCheck = 0L
    private val connectionCheckInterval = 30000L
    private val dtmfQueue = mutableListOf<DtmfRequest>()
    private var isDtmfProcessing = false
    private val dtmfMutex = Mutex()
    var onCallTerminated: (() -> Unit)? = null
    var isCallFromPush = false
    private var registrationCallbackForCall: ((AccountInfo, Boolean) -> Unit)? = null


    // WebRTC manager and other managers
    val webRtcManager = WebRtcManagerFactory.createWebRtcManager(application)
    private val platformRegistration = PlatformRegistration()
    private val callHoldManager = CallHoldManager(webRtcManager)
    private val audioDeviceManager = AudioDeviceManager()

    companion object {
        private const val TAG = "SipCoreManager"
        private const val WEBSOCKET_PROTOCOL = "sip"
        private const val REGISTRATION_CHECK_INTERVAL_MS = 30 * 1000L

        fun createInstance(
            application: Application,
            config: EddysSipLibrary.SipConfig
        ): SipCoreManager {
            return SipCoreManager(
                application = application,
                config = config,
                audioManager = AudioManager(application),
                windowManager = WindowManager(),
                platformInfo = PlatformInfo(),
                settingsDataStore = SettingsDataStore(application)
            )
        }
    }

    // Método para observar cambios de lifecycle
    fun observeLifecycleChanges(callback: (String) -> Unit) {
        this.lifecycleCallback = callback
    }

    private val messageHandler = SipMessageHandler(this)

    fun userAgent(): String = config.userAgent

    fun getDefaultDomain(): String? = currentAccountInfo?.domain


    /**
     * Obtiene la primera cuenta registrada disponible
     */
    private fun getFirstRegisteredAccount(): AccountInfo? {
        return activeAccounts.values.firstOrNull { it.isRegistered }
    }

    /**
     * Establece la cuenta actual basada en la primera registrada
     */
    private fun ensureCurrentAccount(): AccountInfo? {
        if (currentAccountInfo == null || !currentAccountInfo!!.isRegistered) {
            currentAccountInfo = getFirstRegisteredAccount()
        }
        return currentAccountInfo
    }

    fun getCurrentUsername(): String? = currentAccountInfo?.username

    fun initialize() {
        log.d(tag = TAG) { "Initializing SIP Core with optimized call states" }
        loadConfigurationFromDatabase()

        webRtcManager.initialize()
        setupWebRtcEventListener()
        setupPlatformLifecycleObservers()
        setupNetworkMonitoring()

        CallStateManager.initialize()

    }


    /**
     * NUEVO: Configura el monitoreo de conectividad de red
     */
    private fun setupNetworkMonitoring() {
        try {
            connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    log.d(tag = TAG) { "Network available: ${network}" }
                    handleNetworkAvailable()
                }

                override fun onLost(network: Network) {
                    log.d(tag = TAG) { "Network lost: ${network}" }
                    handleNetworkLost()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    log.d(tag = TAG) { "Network capabilities changed. Has internet: $hasInternet" }

                    if (hasInternet && !isNetworkAvailable) {
                        handleNetworkAvailable()
                    }
                }
            }

            // Registrar callback de red
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()

            connectivityManager?.registerNetworkCallback(request, networkCallback!!)

            // Verificar estado inicial de red
            checkInitialNetworkState()

            log.d(tag = TAG) { "Network monitoring configured successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting up network monitoring: ${e.message}" }
        }
    }


    /**
     * Verifica el estado inicial de la red
     */
    private fun checkInitialNetworkState() {
        try {
            val networkInfo = connectivityManager?.activeNetworkInfo
            isNetworkAvailable = networkInfo?.isConnected == true

            log.d(tag = TAG) { "Initial network state - Available: $isNetworkAvailable" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error checking initial network state: ${e.message}" }
            isNetworkAvailable = false
        }
    }

    /**
     * Maneja cuando la red está disponible
     */
    private fun handleNetworkAvailable() {
        CoroutineScope(Dispatchers.IO).launch {
            val wasDisconnected = !isNetworkAvailable || wasDisconnectedDueToNetwork
            isNetworkAvailable = true

            log.d(tag = TAG) { "Network became available. Was disconnected: $wasDisconnected" }

            if (wasDisconnected) {
                wasDisconnectedDueToNetwork = false

                // Notificar al listener si existe
                networkConnectivityListener?.onNetworkRestored()

                // Dar un momento para que la red se estabilice
                delay(2000)

                // Iniciar proceso de reconexión
                startReconnectionProcess()
            }
        }
    }
    /**
     * Maneja cuando se pierde la red
     */
    private fun handleNetworkLost() {
        isNetworkAvailable = false
        wasDisconnectedDueToNetwork = true

        log.d(tag = TAG) { "Network lost - marking accounts for reconnection" }

        // Cancelar trabajos de reconexión en curso
        reconnectionJob?.cancel()

        // Marcar todas las cuentas como desconectadas por red
        activeAccounts.values.forEach { accountInfo ->
            val accountKey = "${accountInfo.username}@${accountInfo.domain}"
            if (accountInfo.isRegistered) {
                log.d(tag = TAG) { "Marking account as disconnected due to network: $accountKey" }
                accountInfo.isRegistered = false
                updateRegistrationState(accountKey, RegistrationState.FAILED)
            }
        }

        // Notificar al listener si existe
        networkConnectivityListener?.onNetworkLost()
    }

    /**
     * NUEVO: Inicia el proceso de reconexión automática
     */
    private suspend fun startReconnectionProcess() {
        // Cancelar reconexión anterior si está en curso
        reconnectionJob?.cancel()

        reconnectionJob = CoroutineScope(Dispatchers.IO).launch {
            log.d(tag = TAG) { "Starting automatic reconnection process for ${activeAccounts.size} accounts" }

            // Obtener cuentas que necesitan reconectarse
            val accountsToReconnect = getAccountsNeedingReconnection()

            if (accountsToReconnect.isEmpty()) {
                log.d(tag = TAG) { "No accounts need reconnection" }
                return@launch
            }

            log.d(tag = TAG) { "Reconnecting ${accountsToReconnect.size} accounts" }

            // Reconectar cada cuenta con retry logic
            accountsToReconnect.forEach { accountInfo ->
                if (isActive) { // Verificar que la corrutina sigue activa
                    reconnectAccountWithRetry(accountInfo)
                }
            }
        }
    }

    /**
     * Obtiene las cuentas que necesitan reconectarse
     */
    private fun getAccountsNeedingReconnection(): List<AccountInfo> {
        return activeAccounts.values.filter { accountInfo ->
            // Solo reconectar cuentas que estaban registradas pero ahora no están
            val wasRegistered = reconnectionAttempts.containsKey("${accountInfo.username}@${accountInfo.domain}")
            val needsReconnection = !accountInfo.isRegistered &&
                    (wasDisconnectedDueToNetwork || wasRegistered ||
                            accountInfo.webSocketClient?.isConnected() != true)

            if (needsReconnection) {
                log.d(tag = TAG) { "Account needs reconnection: ${accountInfo.username}@${accountInfo.domain}" }
            }

            needsReconnection
        }
    }

    /**
     * NUEVO: Reconecta una cuenta específica con lógica de retry
     */
    private suspend fun reconnectAccountWithRetry(accountInfo: AccountInfo) {
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"

        if (!isNetworkAvailable) {
            log.w(tag = TAG) { "Network not available, skipping reconnection for: $accountKey" }
            return
        }

        var attempts = reconnectionAttempts[accountKey] ?: 0

        while (attempts < maxReconnectionAttempts && isNetworkAvailable && !accountInfo.isRegistered) {
            attempts++
            reconnectionAttempts[accountKey] = attempts

            log.d(tag = TAG) { "Reconnection attempt $attempts/$maxReconnectionAttempts for: $accountKey" }

            try {
                updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)

                // Cerrar WebSocket existente si está abierto pero no funcional
                if (accountInfo.webSocketClient?.isConnected() == true) {
                    try {
                        accountInfo.webSocketClient?.close()
                        delay(1000) // Esperar a que se cierre completamente
                    } catch (e: Exception) {
                        log.w(tag = TAG) { "Error closing existing WebSocket: ${e.message}" }
                    }
                }

                // Reconectar WebSocket y registrar
                connectWebSocketAndRegister(accountInfo)

                // Esperar resultado de la conexión
                val success = waitForReconnectionResult(accountInfo, 10000L) // 10 segundos timeout

                if (success) {
                    log.d(tag = TAG) { "Successfully reconnected: $accountKey" }
                    reconnectionAttempts.remove(accountKey) // Limpiar contador de intentos
                    break
                } else {
                    log.w(tag = TAG) { "Reconnection failed for: $accountKey (attempt $attempts)" }

                    if (attempts < maxReconnectionAttempts) {
                        val delayMs = calculateReconnectionDelay(attempts)
                        log.d(tag = TAG) { "Waiting ${delayMs}ms before next reconnection attempt for: $accountKey" }
                        delay(delayMs)
                    }
                }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error during reconnection attempt $attempts for $accountKey: ${e.message}" }

                if (attempts < maxReconnectionAttempts) {
                    val delayMs = calculateReconnectionDelay(attempts)
                    delay(delayMs)
                } else {
                    updateRegistrationState(accountKey, RegistrationState.FAILED)
                }
            }
        }

        if (attempts >= maxReconnectionAttempts) {
            log.e(tag = TAG) { "Max reconnection attempts reached for: $accountKey" }
            updateRegistrationState(accountKey, RegistrationState.FAILED)
            reconnectionAttempts.remove(accountKey)
        }
    }


    /**
     * Espera el resultado de la reconexión con timeout
     */
    private suspend fun waitForReconnectionResult(accountInfo: AccountInfo, timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (accountInfo.isRegistered) {
                return true
            }
            delay(500) // Verificar cada 500ms
        }

        return false
    }

    /**
     * Calcula el delay para el siguiente intento de reconexión (backoff exponencial)
     */
    private fun calculateReconnectionDelay(attempt: Int): Long {
        val delay = minOf(
            reconnectionBaseDelay * (1 shl (attempt - 1)), // 2^(attempt-1) * base
            reconnectionMaxDelay
        )
        return delay
    }

    /**
     * NUEVO: Método público para forzar reconexión manual
     */
    fun forceReconnection() {
        if (!isNetworkAvailable) {
            log.w(tag = TAG) { "Cannot force reconnection - network not available" }
            return
        }

        log.d(tag = TAG) { "Forcing manual reconnection" }

        CoroutineScope(Dispatchers.IO).launch {
            // Resetear contadores de intentos
            reconnectionAttempts.clear()

            // Marcar como desconectado para forzar reconexión
            wasDisconnectedDueToNetwork = true

            startReconnectionProcess()
        }
    }

    /**
     * NUEVO: Verifica y corrige el estado de conectividad de todas las cuentas
     */
    fun verifyAndFixConnectivity() {
        if (!isNetworkAvailable) {
            log.d(tag = TAG) { "Network not available, cannot verify connectivity" }
            return
        }

        log.d(tag = TAG) { "Verifying and fixing connectivity for all accounts" }

        CoroutineScope(Dispatchers.IO).launch {
            activeAccounts.values.forEach { accountInfo ->
                val accountKey = "${accountInfo.username}@${accountInfo.domain}"

                try {
                    val webSocketConnected = accountInfo.webSocketClient?.isConnected() == true
                    val isRegistered = accountInfo.isRegistered
                    val registrationState = getRegistrationState(accountKey)

                    log.d(tag = TAG) {
                        "Account $accountKey - WebSocket: $webSocketConnected, Registered: $isRegistered, State: $registrationState"
                    }

                    // Si debería estar registrada pero no lo está, intentar reconectar
                    if (!isRegistered && !webSocketConnected) {
                        log.d(tag = TAG) { "Account $accountKey needs reconnection" }
                        reconnectAccountWithRetry(accountInfo)
                    }

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error verifying connectivity for $accountKey: ${e.message}" }
                }
            }
        }
    }

    /**
     * Configura listener para eventos de conectividad
     */
    fun setNetworkConnectivityListener(listener: NetworkConnectivityListener?) {
        this.networkConnectivityListener = listener
    }

    /**
     * NUEVO: Obtiene información de estado de conectividad
     */
    fun getConnectivityStatus(): Map<String, Any> {
        return mapOf(
            "networkAvailable" to isNetworkAvailable,
            "wasDisconnectedDueToNetwork" to wasDisconnectedDueToNetwork,
            "reconnectionInProgress" to (reconnectionJob?.isActive == true),
            "accountsNeedingReconnection" to getAccountsNeedingReconnection().size,
            "reconnectionAttempts" to reconnectionAttempts.toMap()
        )
    }

    /**
     * NUEVO: Carga configuración desde la base de datos
     */
    private fun loadConfigurationFromDatabase() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Inicializar database manager si no existe
                if (databaseManager == null) {
                    databaseManager = DatabaseManager.getInstance(application)
                }

                // Cargar configuración
                loadedConfig = databaseManager?.loadOrCreateDefaultConfig()

                // Aplicar configuración de ringtones si existen
                loadedConfig?.let { config ->
                    log.d(tag = TAG) { "Loading configuration from database" }

                    // Aplicar ringtones
                    config.incomingRingtoneUri?.let { uriString ->
                        try {
                            val uri = Uri.parse(uriString)
                            audioManager.setIncomingRingtone(uri)
                            log.d(tag = TAG) { "Loaded incoming ringtone from DB: $uriString" }
                        } catch (e: Exception) {
                            log.e(tag = TAG) { "Error loading incoming ringtone URI: ${e.message}" }
                        }
                    }

                    config.outgoingRingtoneUri?.let { uriString ->
                        try {
                            val uri = Uri.parse(uriString)
                            audioManager.setOutgoingRingtone(uri)
                            log.d(tag = TAG) { "Loaded outgoing ringtone from DB: $uriString" }
                        } catch (e: Exception) {
                            log.e(tag = TAG) { "Error loading outgoing ringtone URI: ${e.message}" }
                        }
                    }

                    log.d(tag = TAG) { "Configuration loaded successfully from database" }
                } ?: run {
                    log.d(tag = TAG) { "No configuration found in database, using defaults" }
                }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error loading configuration from database: ${e.message}" }
                // Continuar con configuración por defecto
            }
        }
    }


    internal fun setCallbacks(callbacks: EddysSipLibrary.SipCallbacks) {
        this.sipCallbacks = callbacks
        log.d(tag = TAG) { "SipCallbacks configured in SipCoreManager" }
    }

    /**
     * Actualiza el estado de registro para una cuenta específica
     */
    /**
     * Actualiza el estado de registro para una cuenta específica con verificación mejorada
     */
    fun updateRegistrationState(accountKey: String, newState: RegistrationState) {
        log.d(tag = TAG) { "Updating registration state for $accountKey: $newState" }

        val currentStates = _registrationStates.value.toMutableMap()
        val previousState = currentStates[accountKey]

        // CRÍTICO: Solo actualizar si realmente cambió
        if (previousState == newState) {
            log.d(tag = TAG) { "Registration state unchanged for $accountKey: $newState" }
            return
        }

        currentStates[accountKey] = newState
        _registrationStates.value = currentStates

        // NUEVO: Verificar y sincronizar estado interno de la cuenta
        val account = activeAccounts[accountKey]
        if (account != null) {
            when (newState) {
                RegistrationState.OK -> {
                    account.isRegistered = true
                    log.d(tag = TAG) { "Synchronized internal account state to registered for $accountKey" }
                }

                RegistrationState.FAILED, RegistrationState.NONE, RegistrationState.CLEARED -> {
                    account.isRegistered = false
                    log.d(tag = TAG) { "Synchronized internal account state to not registered for $accountKey" }
                }

                else -> {
                    // Estados intermedios, no cambiar el flag interno
                }
            }

            log.d(tag = TAG) { "Notifying registration state change from $previousState to $newState for $accountKey" }

            // CORREGIDO: Ejecutar callbacks en el hilo principal con manejo de errores
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    // Callback específico de cuenta
                    sipCallbacks?.onAccountRegistrationStateChanged(
                        account.username,
                        account.domain,
                        newState
                    )

                    // Callback general
                    sipCallbacks?.onRegistrationStateChanged(newState)

                    log.d(tag = TAG) { "Successfully notified registration state change for $accountKey" }

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in registration state callbacks for $accountKey: ${e.message}" }
                }
            }

            // Llamar al método de notificación (para EddysSipLibrary)
            notifyRegistrationStateChanged(newState, account.username, account.domain)
        } else {
            log.w(tag = TAG) { "Account not found for key: $accountKey" }
        }

        log.d(tag = TAG) { "Updated registration state for $accountKey: $previousState -> $newState" }
    }


    /**
     * Método de conveniencia para mantener compatibilidad
     */
    fun updateRegistrationState(newState: RegistrationState) {
        currentAccountInfo?.let { account ->
            val accountKey = "${account.username}@${account.domain}"
            updateRegistrationState(accountKey, newState)
        }
    }

    /**
     * Obtiene el estado de registro para una cuenta específica
     */
    fun getRegistrationState(accountKey: String): RegistrationState {
        return _registrationStates.value[accountKey] ?: RegistrationState.NONE
    }

    /**
     * Obtiene todos los estados de registro
     */
    fun getAllRegistrationStates(): Map<String, RegistrationState> {
        return _registrationStates.value
    }

    /**
     * Método para notificar cambios de estado de registro
     */
    private fun notifyRegistrationStateChanged(
        state: RegistrationState,
        username: String,
        domain: String
    ) {
        try {
            log.d(tag = TAG) { "Notifying registration state change: $state for $username@$domain" }

            // Notificar a través del callback principal
            sipCallbacks?.onRegistrationStateChanged(state)

            // Notificar con información específica de la cuenta
            sipCallbacks?.onAccountRegistrationStateChanged(username, domain, state)

            log.d(tag = TAG) { "Registration state notification sent successfully" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error notifying registration state change: ${e.message}" }
        }
    }

    /**
     * Método para notificar estados de llamada usando únicamente los nuevos estados
     */
    fun notifyCallStateChanged(state: CallState) {
        try {
            log.d(tag = TAG) { "Notifying call state change: $state" }

            // Notificar cambios específicos
            when (state) {
                CallState.INCOMING_RECEIVED -> {
                    currentAccountInfo?.currentCallData?.let { callData ->
                        log.d(tag = TAG) { "Notifying incoming call from ${callData.from}" }
                        sipCallbacks?.onIncomingCall(callData.from, callData.remoteDisplayName)
                    }
                }

                CallState.CONNECTED, CallState.STREAMS_RUNNING -> {
                    log.d(tag = TAG) { "Notifying call connected" }
                    sipCallbacks?.onCallConnected()
                }

                CallState.ENDED -> {
                    log.d(tag = TAG) { "Notifying call terminated" }
                    sipCallbacks?.onCallTerminated()
                }

                else -> {
                    log.d(tag = TAG) { "Other call state: $state" }
                }
            }

            log.d(tag = TAG) { "Call state notification sent successfully" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error notifying call state change: ${e.message}" }
        }
    }

    private fun setupWebRtcEventListener() {
        webRtcManager.setListener(object : WebRtcEventListener {
            override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                // Implementar envío de ICE candidate
            }

            override fun onConnectionStateChange(state: WebRtcConnectionState) {
                when (state) {
                    WebRtcConnectionState.CONNECTED -> handleWebRtcConnected()
                    WebRtcConnectionState.CLOSED -> handleWebRtcClosed()
                    else -> {}
                }
            }

            override fun onRemoteAudioTrack() {
                log.d(tag = TAG) { "Remote audio track received" }
            }

            override fun onAudioDeviceChanged(device: AudioDevice?) {
                log.d(tag = TAG) { "Audio device changed: ${device?.name}" }
            }
        })
    }

    /**
     * Get available audio devices
     */
    fun getAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        return webRtcManager.getAllAudioDevices()
    }

    /**
     * Get current audio devices
     */
    fun getCurrentDevices(): Pair<AudioDevice?, AudioDevice?> {
        return Pair(
            webRtcManager.getCurrentInputDevice(),
            webRtcManager.getCurrentOutputDevice()
        )
    }

    /**
     * Refresh the list of available audio devices
     */
    fun refreshAudioDevices() {
        val (inputs, outputs) = webRtcManager.getAllAudioDevices()
        audioDeviceManager.updateDevices(inputs, outputs)
    }

    /**
     * Change audio device during call
     */
    fun changeAudioDevice(device: AudioDevice) {
        CoroutineScope(Dispatchers.IO).launch {
            val isInput = audioDeviceManager.inputDevices.value.contains(device)

            val success = if (isInput) {
                webRtcManager.changeAudioInputDeviceDuringCall(device)
            } else {
                webRtcManager.changeAudioOutputDeviceDuringCall(device)
            }

            if (success) {
                if (isInput) {
                    audioDeviceManager.selectInputDevice(device)
                } else {
                    audioDeviceManager.selectOutputDevice(device)
                }
            }
        }
    }

    /**
     * Configuración mejorada de observadores de lifecycle
     */
    // Método mejorado para setup de lifecycle observers
    private fun setupPlatformLifecycleObservers() {
        platformRegistration.setupNotificationObservers(object : AppLifecycleListener {
            override fun onEvent(event: AppLifecycleEvent) {
                when (event) {
                    AppLifecycleEvent.EnterBackground -> {
                        CoroutineScope(Dispatchers.IO).launch {

                            log.d(tag = TAG) { "App entering background" }
                            isAppInBackground = true

                            // Notificar al callback para EddysSipLibrary
                            lifecycleCallback?.invoke("APP_BACKGROUNDED")


                            onAppBackgrounded()
                        }
                    }

                    AppLifecycleEvent.EnterForeground -> {
                        CoroutineScope(Dispatchers.IO).launch {

                            log.d(tag = TAG) { "App entering foreground" }
                            isAppInBackground = false

                            // Notificar al callback para EddysSipLibrary
                            lifecycleCallback?.invoke("APP_FOREGROUNDED")

                            onAppForegrounded()
                        }
                    }

                    else -> {
                        log.d(tag = TAG) { "Other lifecycle event: $event" }
                    }
                }
            }
        })
    }

    private fun handleWebRtcConnected() {
        callStartTimeMillis = Clock.System.now().toEpochMilliseconds()

        // Usar estados nuevos
        currentAccountInfo?.currentCallData?.let { callData ->
            CallStateManager.streamsRunning(callData.callId)
        }

        notifyCallStateChanged(CallState.STREAMS_RUNNING)
    }

    private fun handleWebRtcClosed() {
        // Finalizar con nuevos estados
        currentAccountInfo?.currentCallData?.let { callData ->
            CallStateManager.callEnded(callData.callId)
        }

        notifyCallStateChanged(CallState.ENDED)

        currentAccountInfo?.currentCallData?.let { callData ->
            val endTime = Clock.System.now().toEpochMilliseconds()
            val callType = determineCallType(callData, CallStateManager.getCurrentState().state)
            callHistoryManager.addCallLog(callData, callType, endTime)
        }
    }

    internal fun handleCallTermination() {
        onCallTerminated?.invoke()
        sipCallbacks?.onCallTerminated()
    }

    /**
     * Actualiza el user agent de todas las cuentas registradas
     */
    private suspend fun refreshAllRegistrationsWithNewUserAgent() {
        if (CallStateManager.getCurrentState().isActive()) {
            log.d(tag = TAG) { "Skipping registration refresh - call is active" }
            return
        }

        activeAccounts.values.forEach { accountInfo ->
            if (accountInfo.isRegistered && accountInfo.webSocketClient?.isConnected() == true) {
                try {
                    // Actualizar user agent según el estado de la app
                    accountInfo.userAgent = userAgent()

                    // Re-registrar con nuevo user agent
                    messageHandler.sendRegister(accountInfo, isAppInBackground)

                    log.d(tag = TAG) { "Refreshed registration for: ${accountInfo.username}@${accountInfo.domain}" }

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error refreshing registration for ${accountInfo.username}: ${e.message}" }
                }
            }
        }
    }


    fun register(
        username: String,
        password: String,
        domain: String,
        provider: String,
        token: String
    ) {
        try {
            val accountKey = "$username@$domain"
            val accountInfo = AccountInfo(username, password, domain)
            activeAccounts[accountKey] = accountInfo

            accountInfo.token = token
            accountInfo.provider = provider
            accountInfo.userAgent = userAgent()

            // Inicializar estado de registro para esta cuenta
            updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)

            connectWebSocketAndRegister(accountInfo)
        } catch (e: Exception) {
            val accountKey = "$username@$domain"
            updateRegistrationState(accountKey, RegistrationState.FAILED)
            throw Exception("Registration error: ${e.message}")
        }
    }

    suspend fun unregister(username: String, domain: String) {
        val accountKey = "$username@$domain"
        val accountInfo = activeAccounts[accountKey] ?: return

        try {
            messageHandler.sendUnregister(accountInfo)
            accountInfo.webSocketClient?.close()

            activeAccounts.remove(accountKey)

            updateRegistrationState(accountKey, RegistrationState.NONE)

            val currentStates = _registrationStates.value.toMutableMap()
            currentStates.remove(accountKey)
            _registrationStates.value = currentStates

        } catch (e: Exception) {
            log.d(tag = TAG) { "Error unregistering account: ${e.message}" }
        }
    }

    private fun connectWebSocketAndRegister(accountInfo: AccountInfo) {
        try {
            accountInfo.webSocketClient?.close()
            val headers = createHeaders()
            val webSocketClient = createWebSocketClient(accountInfo, headers)
            accountInfo.webSocketClient = webSocketClient
        } catch (e: Exception) {
            log.d(tag = TAG) { "Error connecting WebSocket: ${e.stackTraceToString()}" }
        }
    }

    private fun createHeaders(): HashMap<String, String> {
        return hashMapOf(
            "User-Agent" to userAgent(),
            "Origin" to "https://telephony.${config.defaultDomain}",
            "Sec-WebSocket-Protocol" to WEBSOCKET_PROTOCOL
        )
    }

    private fun createWebSocketClient(
        accountInfo: AccountInfo,
        headers: Map<String, String>
    ): MultiplatformWebSocket {
        val websocket = WebSocket(config.webSocketUrl, headers)
        setupWebSocketListeners(websocket, accountInfo)
        websocket.connect()
        websocket.startPingTimer(config.pingIntervalMs)
        websocket.startRegistrationRenewalTimer(REGISTRATION_CHECK_INTERVAL_MS, 60000L)
        return websocket
    }



    private fun setupWebSocketListeners(websocket: WebSocket, accountInfo: AccountInfo) {
        websocket.setListener(object : MultiplatformWebSocket.Listener {
            override fun onOpen() {
                CoroutineScope(Dispatchers.IO).launch {
                    lastConnectionCheck = Clock.System.now().toEpochMilliseconds()

                    // Limpiar contador de intentos de reconexión al conectarse exitosamente
                    val accountKey = "${accountInfo.username}@${accountInfo.domain}"
                    reconnectionAttempts.remove(accountKey)

                    messageHandler.sendRegister(accountInfo, isAppInBackground)
                }
            }

            override fun onMessage(message: String) {
                CoroutineScope(Dispatchers.IO).launch {
                    messageHandler.handleSipMessage(message, accountInfo)
                }
            }

            override fun onClose(code: Int, reason: String) {
                log.d(tag = TAG) { "WebSocket closed for ${accountInfo.username}@${accountInfo.domain} - Code: $code, Reason: $reason" }

                // Solo marcar para reconexión si no es un cierre normal (código 1000)
                // y si la red está disponible
                if (code != 1000 && isNetworkAvailable && !isShuttingDown) {
                    wasDisconnectedDueToNetwork = true

                    CoroutineScope(Dispatchers.IO).launch {
                        delay(2000) // Esperar antes de intentar reconectar
                        if (isNetworkAvailable) {
                            startReconnectionProcess()
                        }
                    }
                }
            }

            override fun onError(error: Exception) {
                log.e(tag = TAG) { "WebSocket error for ${accountInfo.username}@${accountInfo.domain}: ${error.message}" }
                accountInfo.isRegistered = false

                val accountKey = "${accountInfo.username}@${accountInfo.domain}"
                updateRegistrationState(accountKey, RegistrationState.FAILED)

                // Si hay conectividad, marcar para reconexión
                if (isNetworkAvailable && !isShuttingDown) {
                    wasDisconnectedDueToNetwork = true
                }
            }

            override fun onPong(timeMs: Long) {
                lastConnectionCheck = Clock.System.now().toEpochMilliseconds()
            }

            override fun onRegistrationRenewalRequired(accountKey: String) {
                CoroutineScope(Dispatchers.IO).launch {
                    val account = activeAccounts[accountKey]
                    if (account != null && account.webSocketClient?.isConnected() == true && isNetworkAvailable) {
                        messageHandler.sendRegister(account, isAppInBackground)
                    } else if (isNetworkAvailable) {
                        // Si la red está disponible pero WebSocket no, reconectar
                        account?.let { reconnectAccountWithRetry(it) }
                    }
                }
            }
        })
    }

    fun handleRegistrationError(accountInfo: AccountInfo, reason: String) {
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
        log.e(tag = TAG) { "Registration failed for $accountKey: $reason" }

        accountInfo.isRegistered = false
        updateRegistrationState(accountKey, RegistrationState.FAILED)

        registrationCallbackForCall?.invoke(accountInfo, false)

    }

    fun handleRegistrationSuccess(accountInfo: AccountInfo) {
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
        log.d(tag = TAG) { "Registration successful for $accountKey" }

        // CRÍTICO: Actualizar AMBOS estados de forma sincronizada
        accountInfo.isRegistered = true

        // CORREGIDO: Usar el método correcto para actualizar estado
        updateRegistrationState(accountKey, RegistrationState.OK)

        // Establecer como cuenta actual si no hay una
        if (currentAccountInfo == null) {
            currentAccountInfo = accountInfo
            log.d(tag = TAG) { "Set current account to: $accountKey" }
        }


        // NUEVO: Forzar notificación inmediata
        CoroutineScope(Dispatchers.Main).launch {
            delay(100) // Pequeño delay para asegurar que todo se actualice
            sipCallbacks?.onAccountRegistrationStateChanged(
                accountInfo.username,
                accountInfo.domain,
                RegistrationState.OK
            )
            log.d(tag = TAG) { "Force notified registration success for $accountKey" }
        }
    }


    /**
     * NUEVO: Método para verificar y corregir el estado de todas las cuentas
     */
    fun verifyAndCorrectAllAccountStates() {
        log.d(tag = TAG) { "Verifying and correcting states for ${activeAccounts.size} accounts" }

        activeAccounts.values.forEach { accountInfo ->
            val accountKey = "${accountInfo.username}@${accountInfo.domain}"

            try {
                val registrationState = getRegistrationState(accountKey)
                val isWebSocketConnected = accountInfo.webSocketClient?.isConnected() == true
                val internalRegistrationFlag = accountInfo.isRegistered

                log.d(tag = TAG) {
                    "Account $accountKey: regState=$registrationState, webSocket=$isWebSocketConnected, internal=$internalRegistrationFlag"
                }

                // Corregir inconsistencias
                if (registrationState != RegistrationState.OK && internalRegistrationFlag) {
                    log.w(tag = TAG) { "Correcting internal flag for $accountKey - marking as not registered" }
                    accountInfo.isRegistered = false
                }

                if (registrationState == RegistrationState.OK && !internalRegistrationFlag) {
                    log.w(tag = TAG) { "Correcting internal flag for $accountKey - marking as registered" }
                    accountInfo.isRegistered = true
                }

                if (!isWebSocketConnected && (registrationState == RegistrationState.OK || internalRegistrationFlag)) {
                    log.w(tag = TAG) { "WebSocket disconnected for $accountKey but marked as registered - correcting" }
                    accountInfo.isRegistered = false
                    updateRegistrationState(accountKey, RegistrationState.NONE)
                }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error verifying account $accountKey: ${e.message}" }
            }
        }
    }


    private fun calculateBackoffDelay(attempt: Int): Long {
        val baseDelay = 2000L // 2 segundos base
        val maxDelay = 30000L // máximo 30 segundos
        val delay = (2.0.pow((attempt - 1).toDouble()) * baseDelay).toLong()
        return minOf(delay, maxDelay)
    }


    suspend fun unregisterAllAccounts() {
        log.d(tag = TAG) { "Starting complete unregister and shutdown of all accounts" }

        // CRÍTICO: Marcar como shutting down PRIMERO
        isShuttingDown = true

        try {
            // 1. Detener health check inmediatamente
            healthCheckJob?.cancel()
            healthCheckJob = null

            // 2. DETENER TODOS LOS RINGTONES INMEDIATAMENTE
            audioManager.stopAllRingtones()

            // 3. Terminar llamada activa si existe
            if (CallStateManager.getCurrentState().isActive()) {
                log.d(tag = TAG) { "Terminating active call during unregister" }
                try {
                    currentAccountInfo?.currentCallData?.let { callData ->
                        CallStateManager.callEnded(callData.callId)
                    }
                    webRtcManager.dispose()
                    notifyCallStateChanged(CallState.ENDED)
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error terminating call: ${e.message}" }
                }
            }

            // 4. Unregister todas las cuentas
            if (activeAccounts.isNotEmpty()) {
                log.d(tag = TAG) { "Unregistering ${activeAccounts.size} accounts" }

                val accountsToUnregister = activeAccounts.toMap()

                accountsToUnregister.values.forEach { accountInfo ->
                    try {
                        val accountKey = "${accountInfo.username}@${accountInfo.domain}"

                        // Detener timers del WebSocket
                        accountInfo.webSocketClient?.let { webSocket ->
                            webSocket.stopPingTimer()
                            webSocket.stopRegistrationRenewalTimer()
                        }

                        // Enviar unregister si está registrada
                        if (accountInfo.isRegistered && accountInfo.webSocketClient?.isConnected() == true) {
                            messageHandler.sendUnregister(accountInfo)
                        }

                        // Cerrar WebSocket
                        accountInfo.webSocketClient?.close(1000, "User logout")
                        accountInfo.webSocketClient = null

                        // Marcar como no registrada
                        accountInfo.isRegistered = false
                        accountInfo.resetCallState()

                        // Actualizar estado
                        updateRegistrationState(accountKey, RegistrationState.CLEARED)

                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error unregistering account ${accountInfo.username}@${accountInfo.domain}: ${e.message}" }
                    }
                }
            }

            // 5. Limpiar todas las estructuras de datos
            activeAccounts.clear()
            currentAccountInfo = null

            // Limpiar estados de registro
            _registrationStates.value = emptyMap()

            // 6. Limpiar WebRTC completamente
            try {
                webRtcManager.setListener(null)
                webRtcManager.dispose()
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error disposing WebRTC: ${e.message}" }
            }

            // 7. Resetear todos los estados
            callStartTimeMillis = 0
            isAppInBackground = false
            isRegistrationInProgress = false
            lastConnectionCheck = 0L
            lastRegistrationAttempt = 0L

            // 8. Limpiar colas
            clearDtmfQueue()

            // 9. RESETEAR ESTADOS DE LLAMADA CORRECTAMENTE
            CallStateManager.forceResetToIdle()
            CallStateManager.clearHistory()

            log.d(tag = TAG) { "Complete unregister and shutdown successful" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error during complete unregister: ${e.message}" }
        }
    }

    fun makeCall(phoneNumber: String, sipName: String, domain: String) {

        val accountKey = "$sipName@$domain"
        val accountInfo = activeAccounts[accountKey] ?: run {
            log.e(tag = TAG) { "Account not found: $accountKey" }
            sipCallbacks?.onCallFailed("Account not found: $accountKey")
            return
        }

        // Establecer como cuenta actual
        currentAccountInfo = accountInfo

        if (!accountInfo.isRegistered) {
            log.d(tag = TAG) { "Error: Not registered with SIP server" }
            sipCallbacks?.onCallFailed("Not registered with SIP server")
            return
        }
        log.d(tag = TAG) { "Making call from $accountKey to $phoneNumber" }
        audioManager.stopAllRingtones()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                webRtcManager.setAudioEnabled(true)
                val sdp = webRtcManager.createOffer()

                val callId = accountInfo.generateCallId()
                val md5Hash = calculateMD5(callId)
                val callData = CallData(
                    callId = callId,
                    to = phoneNumber,
                    from = accountInfo.username,
                    direction = CallDirections.OUTGOING,
                    inviteFromTag = generateSipTag(),
                    localSdp = sdp,
                    md5Hash = md5Hash
                )

                accountInfo.currentCallData = callData

                // CORREGIDO: Solo un lugar para actualizar estados
                CallStateManager.startOutgoingCall(callId, phoneNumber)
                notifyCallStateChanged(CallState.OUTGOING_INIT)

                // Iniciar outgoing ringtone
//                audioManager.playOutgoingRingtone()

                messageHandler.sendInvite(accountInfo, callData)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error creating call: ${e.stackTraceToString()}" }
                sipCallbacks?.onCallFailed("Error creating call: ${e.message}")

                // Error al crear llamada
                accountInfo.currentCallData?.let { callData ->
                    CallStateManager.callError(
                        callData.callId,
                        errorReason = CallErrorReason.NETWORK_ERROR
                    )
                }

                // Detener outgoing ringtone en error
                audioManager.stopOutgoingRingtone()
            }
        }
    }

//      fun endCall(callId: String? = null) {
//        val accountInfo = ensureCurrentAccount() ?: run {
//            log.e(tag = TAG) { "No current account available for end call" }
//            return
//        }
//
//        val targetCallData = if (callId != null) {
//            MultiCallManager.getCall(callId)
//        } else {
//            accountInfo.currentCallData
//        } ?: run {
//            log.e(tag = TAG) { "No call data available for end" }
//            return
//        }
//
//        val callState = if (callId != null) {
//            MultiCallManager.getCallState(callId)
//        } else {
//            CallStateManager.getCurrentState()
//        }
//
//        if (callState?.isActive() != true) {
//            log.w(tag = TAG) { "No active call to end" }
//            return
//        }
//
//        val endTime = Clock.System.now().toEpochMilliseconds()
//        val currentState = callState.state
//
//        log.d(tag = TAG) { "Ending single call" }
//
//        // CRÍTICO: Detener ringtones INMEDIATAMENTE y con force stop
//        audioManager.stopAllRingtones()
//        log.d(tag = TAG) { "Stopping ALL ringtones - FORCE STOP" }
//
//        // CRÍTICO: Iniciar proceso de finalización
//        CallStateManager.startEnding(targetCallData.callId)
//
//        // CRÍTICO: Enviar mensaje apropiado según estado y dirección
//        when (currentState) {
//            CallState.CONNECTED, CallState.STREAMS_RUNNING, CallState.PAUSED -> {
//                log.d(tag = TAG) { "Sending BYE for established call (${targetCallData.direction})" }
//                messageHandler.sendBye(accountInfo, targetCallData)
//                callHistoryManager.addCallLog(targetCallData, CallTypes.SUCCESS, endTime)
//            }
//
//            CallState.OUTGOING_INIT, CallState.OUTGOING_PROGRESS, CallState.OUTGOING_RINGING -> {
//                log.d(tag = TAG) { "Sending CANCEL for outgoing call" }
//                messageHandler.sendCancel(accountInfo, targetCallData)
//                callHistoryManager.addCallLog(targetCallData, CallTypes.ABORTED, endTime)
//            }
//
//            CallState.INCOMING_RECEIVED -> {
//                log.d(tag = TAG) { "Sending DECLINE for incoming call" }
//                messageHandler.sendDeclineResponse(accountInfo, targetCallData)
//                callHistoryManager.addCallLog(targetCallData, CallTypes.DECLINED, endTime)
//            }
//
//            else -> {
//                log.w(tag = TAG) { "Ending call in unexpected state: $currentState" }
//                messageHandler.sendBye(accountInfo, targetCallData)
//            }
//        }
//
//        clearDtmfQueue()
//
//        // NUEVO: Notificar que la llamada terminó para esta cuenta específica
//        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
//        notifyCallEndedForSpecificAccount(accountKey)
//
//        // MEJORADO: Una sola corrutina para manejar la limpieza
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//                // Esperar un poco para que se envíe el mensaje SIP
//                delay(500)
//
//                // Limpiar WebRTC solo si no hay más llamadas
//                if (MultiCallManager.getAllCalls().size <= 1) {
//                    webRtcManager.dispose()
//                    log.d(tag = TAG) { "WebRTC disposed - no more active calls" }
//                }
//
//                // Finalizar llamada
//                delay(500) // Total 1 segundo como antes
//                CallStateManager.callEnded(targetCallData.callId)
//                notifyCallStateChanged(CallState.ENDED)
//
//                // Limpiar datos de cuenta
//                if (accountInfo.currentCallData?.callId == targetCallData.callId) {
//                    accountInfo.resetCallState()
//                }
//
//                handleCallTermination()
//
//                log.d(tag = TAG) { "Call cleanup completed successfully" }
//
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error during call cleanup: ${e.message}" }
//                // Forzar limpieza en caso de error
//                audioManager.stopAllRingtones()
//                if (accountInfo.currentCallData?.callId == targetCallData.callId) {
//                    accountInfo.resetCallState()
//                }
//            }
//        }
//    }

    fun endCall(callId: String? = null) {
        val accountInfo = ensureCurrentAccount() ?: run {
            log.e(tag = TAG) { "No current account available for end call" }
            return
        }

        val targetCallData = if (callId != null) {
            MultiCallManager.getCall(callId)
        } else {
            accountInfo.currentCallData
        } ?: run {
            log.e(tag = TAG) { "No call data available for end" }
            return
        }

        val callState = if (callId != null) {
            MultiCallManager.getCallState(callId)
        } else {
            CallStateManager.getCurrentState()
        }

        if (callState?.isActive() != true) {
            log.w(tag = TAG) { "No active call to end" }
            return
        }

        val endTime = Clock.System.now().toEpochMilliseconds()
        val currentState = callState.state

        log.d(tag = TAG) { "Ending single call" }

        // CRÍTICO: Detener ringtones INMEDIATAMENTE y con force stop
        audioManager.stopAllRingtones()
        log.d(tag = TAG) { "Stopping ALL ringtones - FORCE STOP" }

        // CRÍTICO: Iniciar proceso de finalización
        CallStateManager.startEnding(targetCallData.callId)

        // NUEVO: Notificar que la llamada terminó para esta cuenta específica
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
        notifyCallEndedForSpecificAccount(accountKey)

        clearDtmfQueue()

        // Lanzar operaciones suspend en paralelo para máximo rendimiento
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // CRÍTICO: Enviar mensaje apropiado según estado y dirección
                val messageJob = launch {
                    when (currentState) {
                        CallState.CONNECTED, CallState.STREAMS_RUNNING, CallState.PAUSED -> {
                            log.d(tag = TAG) { "Sending BYE for established call (${targetCallData.direction})" }
                            messageHandler.sendBye(accountInfo, targetCallData)
                            callHistoryManager.addCallLog(
                                targetCallData,
                                CallTypes.SUCCESS,
                                endTime
                            )
                        }

                        CallState.OUTGOING_INIT, CallState.OUTGOING_PROGRESS, CallState.OUTGOING_RINGING -> {
                            log.d(tag = TAG) { "Sending CANCEL for outgoing call" }
                            messageHandler.sendCancel(accountInfo, targetCallData)
                            callHistoryManager.addCallLog(
                                targetCallData,
                                CallTypes.ABORTED,
                                endTime
                            )
                        }

                        CallState.INCOMING_RECEIVED -> {
                            log.d(tag = TAG) { "Sending DECLINE for incoming call" }
                            messageHandler.sendDeclineResponse(accountInfo, targetCallData)
                            callHistoryManager.addCallLog(
                                targetCallData,
                                CallTypes.DECLINED,
                                endTime
                            )
                        }

                        else -> {
                            log.w(tag = TAG) { "Ending call in unexpected state: $currentState" }
                            messageHandler.sendBye(accountInfo, targetCallData)
                        }
                    }
                }

                // Cleanup job que se ejecuta en paralelo
                val cleanupJob = launch {
                    // Esperar un poco para que se envíe el mensaje SIP
                    delay(500)

                    // Limpiar WebRTC solo si no hay más llamadas
                    if (MultiCallManager.getAllCalls().size <= 1) {
                        webRtcManager.dispose()
                        log.d(tag = TAG) { "WebRTC disposed - no more active calls" }
                    }

                    // Finalizar llamada
                    delay(500) // Total 1 segundo como antes
                    CallStateManager.callEnded(targetCallData.callId)
                    notifyCallStateChanged(CallState.ENDED)

                    // Limpiar datos de cuenta
                    if (accountInfo.currentCallData?.callId == targetCallData.callId) {
                        accountInfo.resetCallState()
                    }

                    handleCallTermination()
                }

                // Esperar que ambos jobs terminen
                messageJob.join()
                cleanupJob.join()

                log.d(tag = TAG) { "Call cleanup completed successfully" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error during call cleanup: ${e.message}" }
                // Forzar limpieza en caso de error
                audioManager.stopAllRingtones()
                if (accountInfo.currentCallData?.callId == targetCallData.callId) {
                    accountInfo.resetCallState()
                }
            }
        }
    }

    fun acceptCall(callId: String? = null) {
        val accountInfo = ensureCurrentAccount() ?: run {
            log.e(tag = TAG) { "No current account available for accepting call" }
            return
        }

        val targetCallData = if (callId != null) {
            MultiCallManager.getCall(callId)
        } else {
            accountInfo.currentCallData
        } ?: run {
            log.e(tag = TAG) { "No call data available for accepting" }
            return
        }

        val callState = if (callId != null) {
            MultiCallManager.getCallState(callId)
        } else {
            CallStateManager.getCurrentState()
        }

        if (targetCallData.direction != CallDirections.INCOMING ||
            callState?.state != CallState.INCOMING_RECEIVED
        ) {
            log.w(tag = TAG) { "Cannot accept call - invalid state or direction" }
            return
        }

        log.d(tag = TAG) { "Accepting call: ${targetCallData.callId}" }

        // CRÍTICO: Detener ringtone INMEDIATAMENTE al aceptar
        audioManager.stopAllRingtones()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!webRtcManager.isInitialized()) {
                    webRtcManager.initialize()
                    delay(1000)
                }

                webRtcManager.prepareAudioForIncomingCall()
                delay(500) // Dar más tiempo para preparación

                val sdp = webRtcManager.createAnswer(accountInfo, targetCallData.remoteSdp ?: "")
                targetCallData.localSdp = sdp

                // ENVIAR 200 OK INMEDIATAMENTE
                messageHandler.sendInviteOkResponse(accountInfo, targetCallData)

                // Transición a CONNECTED inmediatamente después de enviar 200 OK
                CallStateManager.callConnected(targetCallData.callId, 200)
                notifyCallStateChanged(CallState.CONNECTED)

                delay(500)

                // Preparar audio
                webRtcManager.setAudioEnabled(true)
                webRtcManager.setMuted(false)


            } catch (e: Exception) {
                log.e(tag = TAG) { "Error accepting call: ${e.message}" }
                CallStateManager.callError(
                    targetCallData.callId,
                    errorReason = CallErrorReason.NETWORK_ERROR
                )
                rejectCall(callId)
            }
        }
    }

    fun declineCall(callId: String? = null) {
        val accountInfo = ensureCurrentAccount() ?: run {
            log.e(tag = TAG) { "No current account available for declining call" }
            return
        }

        val targetCallData = if (callId != null) {
            MultiCallManager.getCall(callId)
        } else {
            accountInfo.currentCallData
        } ?: run {
            log.e(tag = TAG) { "No call data available for declining" }
            return
        }

        val callState = if (callId != null) {
            MultiCallManager.getCallState(callId)
        } else {
            CallStateManager.getCurrentState()
        }

        if (targetCallData.direction != CallDirections.INCOMING ||
            callState?.state != CallState.INCOMING_RECEIVED
        ) {
            log.w(tag = TAG) { "Cannot decline call - invalid state or direction" }
            return
        }

        log.d(tag = TAG) { "Declining call: ${targetCallData.callId}" }

        if (targetCallData.toTag?.isEmpty() == true) {
            targetCallData.toTag = generateId()
        }

        // CORREGIDO: Detener ringtone antes de rechazar
        audioManager.stopRingtone()

        messageHandler.sendDeclineResponse(accountInfo, targetCallData)

        val endTime = Clock.System.now().toEpochMilliseconds()
        callHistoryManager.addCallLog(targetCallData, CallTypes.DECLINED, endTime)

        // NUEVO: Notificar que la llamada terminó para esta cuenta específica
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
        notifyCallEndedForSpecificAccount(accountKey)

        // Estado de rechazo y limpieza
        CallStateManager.callEnded(targetCallData.callId, sipReason = "Declined")
        // notifyCallStateChanged(CallState.ENDED) // Comentado para evitar doble notificación
    }


    fun rejectCall(callId: String? = null) = declineCall(callId)

    fun mute() {
        webRtcManager.setMuted(!webRtcManager.isMuted())
    }

    fun sendDtmf(digit: Char, duration: Int = 160): Boolean {
        val validDigits = setOf(
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '*', '#', 'A', 'B', 'C', 'D', 'a', 'b', 'c', 'd'
        )

        if (!validDigits.contains(digit)) {
            return false
        }

        val request = DtmfRequest(digit, duration)
        CoroutineScope(Dispatchers.IO).launch {
            dtmfMutex.withLock {
                dtmfQueue.add(request)
            }
            processDtmfQueue()
        }

        return true
    }

    fun sendDtmfSequence(digits: String, duration: Int = 160): Boolean {
        if (digits.isEmpty()) return false

        digits.forEach { digit ->
            sendDtmf(digit, duration)
        }

        return true
    }

    private suspend fun processDtmfQueue() = withContext(Dispatchers.IO) {
        dtmfMutex.withLock {
            if (isDtmfProcessing || dtmfQueue.isEmpty()) {
                return@withLock
            }
            isDtmfProcessing = true
        }

        try {
            while (true) {
                val request: DtmfRequest? = dtmfMutex.withLock {
                    if (dtmfQueue.isNotEmpty()) {
                        dtmfQueue.removeAt(0)
                    } else {
                        null
                    }
                }

                if (request == null) break

                val success = sendSingleDtmf(request.digit, request.duration)
                if (success) {
                    delay(150) // Gap between digits
                }
            }
        } finally {
            dtmfMutex.withLock {
                isDtmfProcessing = false
            }
        }
    }

    /**
     * NUEVO: Guarda la URI del ringtone de entrada en la base de datos
     */
    fun saveIncomingRingtoneUri(uri: Uri) {
        try {
            CoroutineScope(Dispatchers.IO).launch {
                databaseManager?.updateIncomingRingtoneUri(uri)
                audioManager.setIncomingRingtone(uri)
                log.d(tag = TAG) { "Incoming ringtone URI saved to database: $uri" }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error saving incoming ringtone URI: ${e.message}" }
        }
    }

    /**
     * NUEVO: Guarda la URI del ringtone de salida en la base de datos
     */
    fun saveOutgoingRingtoneUri(uri: Uri) {
        try {
            CoroutineScope(Dispatchers.IO).launch {
                databaseManager?.updateOutgoingRingtoneUri(uri)
                audioManager.setOutgoingRingtone(uri)
                log.d(tag = TAG) { "Outgoing ringtone URI saved to database: $uri" }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error saving outgoing ringtone URI: ${e.message}" }
        }
    }

    /**
     * NUEVO: Guarda ambas URIs de ringtones en la base de datos
     */
    suspend fun saveRingtoneUris(incomingUri: Uri?, outgoingUri: Uri?) {
        try {
            databaseManager?.updateRingtoneUris(incomingUri, outgoingUri)

            incomingUri?.let { audioManager.setIncomingRingtone(it) }
            outgoingUri?.let { audioManager.setOutgoingRingtone(it) }

            log.d(tag = TAG) { "Both ringtone URIs saved to database - Incoming: $incomingUri, Outgoing: $outgoingUri" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error saving ringtone URIs: ${e.message}" }
        }
    }

    /**
     * NUEVO: Obtiene la configuración actual cargada
     */
    fun getLoadedConfig(): AppConfigEntity? {
        return loadedConfig
    }

    private suspend fun sendSingleDtmf(digit: Char, duration: Int): Boolean {
        val currentAccount = currentAccountInfo
        val callData = currentAccount?.currentCallData

        if (currentAccount == null || callData == null || !CallStateManager.getCurrentState()
                .isConnected()
        ) {
            return false
        }

        return try {
            // Usar WebRTC para DTMF en Android
            webRtcManager.sendDtmfTones(
                tones = digit.toString().uppercase(),
                duration = duration,
                gap = 100
            )
        } catch (e: Exception) {
            false
        }
    }

    fun clearDtmfQueue() {
        CoroutineScope(Dispatchers.IO).launch {
            dtmfMutex.withLock {
                dtmfQueue.clear()
                isDtmfProcessing = false
            }
        }
    }

    fun holdCall(callId: String? = null) {
        val accountInfo = currentAccountInfo ?: return
        val targetCallData = if (callId != null) {
            MultiCallManager.getCall(callId)
        } else {
            accountInfo.currentCallData
        } ?: return

        val currentState = CallStateManager.getCurrentState()
        if (currentState.state != CallState.STREAMS_RUNNING && currentState.state != CallState.CONNECTED) {
            log.w(tag = TAG) { "Cannot hold call in current state: ${currentState.state}" }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Iniciar proceso de hold
                CallStateManager.startHold(targetCallData.callId)
                notifyCallStateChanged(CallState.PAUSING)

                callHoldManager.holdCall()?.let { holdSdp ->
                    targetCallData.localSdp = holdSdp
                    targetCallData.isOnHold = true
                    messageHandler.sendReInvite(accountInfo, targetCallData, holdSdp)

                    // Esperar respuesta y luego transicionar a PAUSED
                    delay(1000)
                    CallStateManager.callOnHold(targetCallData.callId)
                    notifyCallStateChanged(CallState.PAUSED)
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error holding call: ${e.message}" }
            }
        }
    }

    fun resumeCall(callId: String? = null) {
        val accountInfo = currentAccountInfo ?: return
        val targetCallData = if (callId != null) {
            MultiCallManager.getCall(callId)
        } else {
            accountInfo.currentCallData
        } ?: return

        val currentState = CallStateManager.getCurrentState()
        if (currentState.state != CallState.PAUSED) {
            log.w(tag = TAG) { "Cannot resume call in current state: ${currentState.state}" }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Iniciar proceso de resume
                CallStateManager.startResume(targetCallData.callId)
                notifyCallStateChanged(CallState.RESUMING)

                callHoldManager.resumeCall()?.let { resumeSdp ->
                    targetCallData.localSdp = resumeSdp
                    targetCallData.isOnHold = false
                    messageHandler.sendReInvite(accountInfo, targetCallData, resumeSdp)

                    // Esperar respuesta y luego transicionar a STREAMS_RUNNING
                    delay(1000)
                    CallStateManager.callResumed(targetCallData.callId)
                    notifyCallStateChanged(CallState.STREAMS_RUNNING)
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error resuming call: ${e.message}" }
            }
        }
    }

    fun getCallStatistics() = callHistoryManager.getCallStatistics()

    fun getRegistrationState(): RegistrationState {
        val registeredAccounts =
            _registrationStates.value.values.filter { it == RegistrationState.OK }
        return if (registeredAccounts.isNotEmpty()) RegistrationState.OK else RegistrationState.NONE
    }

    fun currentCall(): Boolean = CallStateManager.getCurrentState().isActive()
    fun currentCallConnected(): Boolean = CallStateManager.getCurrentState().isConnected()
    fun getCurrentCallState(): CallStateInfo = CallStateManager.getCurrentState()

    /**
     * Obtiene todas las llamadas activas (filtradas automáticamente)
     */
    fun getAllActiveCalls(): List<CallData> = MultiCallManager.getAllCalls()

    /**
     * Obtiene solo las llamadas realmente activas (sin estados terminales)
     */
    fun getActiveCalls(): List<CallData> = MultiCallManager.getActiveCalls()

    /**
     * Limpia manualmente las llamadas terminadas
     */
    fun cleanupTerminatedCalls() {
        MultiCallManager.cleanupTerminatedCalls()
    }

    /**
     * Obtiene información detallada sobre el estado de las llamadas
     */
    fun getCallsInfo(): String = MultiCallManager.getDiagnosticInfo()
    fun isSipCoreManagerHealthy(): Boolean {
        return try {
            webRtcManager.isInitialized() &&
                    activeAccounts.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun getSystemHealthReport(): String {
        return buildString {
            appendLine("=== SIP Core Manager Health Report ===")
            appendLine("Overall Health: ${if (isSipCoreManagerHealthy()) "✅ HEALTHY" else "❌ UNHEALTHY"}")
            appendLine("WebRTC Initialized: ${webRtcManager.isInitialized()}")
            appendLine("Active Accounts: ${activeAccounts.size}")
            appendLine("Current Call State: ${getCurrentCallState().state}")
            appendLine("Registration States per Account:")
            _registrationStates.value.forEach { (account, state) ->
                appendLine("  - $account: $state")
            }

            // Información de estados
            appendLine("\n--- Call State Info ---")
            appendLine(CallStateManager.getDiagnosticInfo())

            // Información de múltiples llamadas
            appendLine("\n--- Multi Call Info ---")
            appendLine(MultiCallManager.getDiagnosticInfo())
        }
    }

    fun enterPushMode(token: String? = null) {
        token?.let { newToken ->
            activeAccounts.values.forEach { accountInfo ->
                accountInfo.token = newToken
            }
        }
    }

    private fun determineCallType(callData: CallData, finalState: CallState): CallTypes {
        return when (finalState) {
            CallState.CONNECTED, CallState.STREAMS_RUNNING, CallState.ENDED -> CallTypes.SUCCESS
            CallState.ERROR -> if (callData.direction == CallDirections.INCOMING) {
                CallTypes.MISSED
            } else {
                CallTypes.ABORTED
            }

            else -> if (callData.direction == CallDirections.INCOMING) {
                CallTypes.MISSED
            } else {
                CallTypes.ABORTED
            }
        }
    }

    fun dispose() {
        // Detener todos los ringtones
        audioManager.stopAllRingtones()

        // Cancelar trabajos de reconexión
        reconnectionJob?.cancel()

        // Desregistrar callback de red
        try {
            networkCallback?.let { callback ->
                connectivityManager?.unregisterNetworkCallback(callback)
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error unregistering network callback: ${e.message}" }
        }

        // Limpiar contadores de reconexión
        reconnectionAttempts.clear()

        MultiCallManager.clearAllCalls()

        webRtcManager.dispose()
        activeAccounts.clear()
        _registrationStates.value = emptyMap()

        // Resetear estados
        CallStateManager.resetToIdle()
        CallStateManager.clearHistory()

        isNetworkAvailable = false
        wasDisconnectedDueToNetwork = false
        networkConnectivityListener = null
    }


    fun getMessageHandler(): SipMessageHandler = messageHandler

//nuevas funciones de prueba


    /**
     * NUEVO: Notifica que una llamada terminó para una cuenta específica
     */
    fun notifyCallEndedForSpecificAccount(accountKey: String) {
        log.d(tag = TAG) { "Notifying call ended for specific account: $accountKey" }

        // Notificar a callbacks internos
        sipCallbacks?.onCallEndedForAccount(accountKey)

        // Si el lifecycle callback está configurado, notificar también
        lifecycleCallback?.invoke("CALL_ENDED:$accountKey")
    }

    /**
     * Cambia una cuenta específica a modo push
     */
    suspend fun switchToPushMode(username: String, domain: String) {
        val accountKey = "$username@$domain"
        val accountInfo = activeAccounts[accountKey] ?: run {
            log.w(tag = TAG) { "Account not found for push mode switch: $accountKey" }
            return
        }

        if (!accountInfo.isRegistered) {
            log.w(tag = TAG) { "Account not registered, cannot switch to push mode: $accountKey" }
            return
        }

        log.d(tag = TAG) { "Switching account to push mode: $accountKey" }
        updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)

        try {
            // Actualizar user agent para modo push
            val pushUserAgent = "${userAgent()} Push"
            accountInfo.userAgent = pushUserAgent
            isAppInBackground = true

            // Re-registrar con nuevo user agent para push
            messageHandler.sendRegister(accountInfo, true) // true = push mode

            log.d(tag = TAG) { "Account switched to push mode successfully: $accountKey" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error switching to push mode for $accountKey: ${e.message}" }
        }
    }

    /**
     * Cambia una cuenta específica a modo foreground
     */
    suspend fun switchToForegroundMode(username: String, domain: String) {
        val accountKey = "$username@$domain"
        val accountInfo = activeAccounts[accountKey] ?: run {
            log.w(tag = TAG) { "Account not found for foreground mode switch: $accountKey" }
            return
        }

        if (!accountInfo.isRegistered) {
            log.w(tag = TAG) { "Account not registered, cannot switch to foreground mode: $accountKey" }
            return
        }

        log.d(tag = TAG) { "Switching account to foreground mode: $accountKey" }
        updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)

        try {
            // Actualizar user agent para modo foreground (normal)
            accountInfo.userAgent = userAgent()
            isAppInBackground = false

            // Re-registrar con user agent normal
            messageHandler.sendRegister(accountInfo, false) // false = foreground mode

            log.d(tag = TAG) { "Account switched to foreground mode successfully: $accountKey" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error switching to foreground mode for $accountKey: ${e.message}" }
        }
    }

    /**
     * Obtiene todas las cuentas registradas en formato "username@domain"
     */
    fun getAllRegisteredAccountKeys(): Set<String> {
        val registeredKeys = mutableSetOf<String>()

        activeAccounts.forEach { (accountKey, accountInfo) ->
            if (accountInfo.isRegistered) {
                registeredKeys.add(accountKey)
            }
        }

        log.d(tag = TAG) { "Registered accounts: ${registeredKeys.size} - $registeredKeys" }
        return registeredKeys
    }

    /**
     * Obtiene todas las cuentas (registradas y no registradas)
     */
    fun getAllAccountKeys(): Set<String> {
        return activeAccounts.keys.toSet()
    }

    /**
     * Verifica si una cuenta específica está registrada
     */
    fun isAccountRegistered(username: String, domain: String): Boolean {
        val accountKey = "$username@$domain"
        return activeAccounts[accountKey]?.isRegistered ?: false
    }

    /**
     * Obtiene información de una cuenta específica
     */
    fun getAccountInfo(username: String, domain: String): AccountInfo? {
        val accountKey = "$username@$domain"
        return activeAccounts[accountKey]
    }


    /**
     * Funciones adicionales para integración completa con PushModeManager
     */

    /**
     * Notifica que la aplicación pasó a segundo plano
     */
    suspend fun onAppBackgrounded() {
        log.d(tag = TAG) { "App backgrounded - updating all registrations" }
        isAppInBackground = true

        // Actualizar user agent y re-registrar todas las cuentas activas
        refreshAllRegistrationsWithNewUserAgent()
    }

    /**
     * Notifica que la aplicación pasó a primer plano
     */
    suspend fun onAppForegrounded() {
        log.d(tag = TAG) { "App foregrounded - updating all registrations" }
        isAppInBackground = false

        // Actualizar user agent y re-registrar todas las cuentas activas
        refreshAllRegistrationsWithNewUserAgent()
    }


    /**
     * Fuerza el re-registro de todas las cuentas (útil para cambios de push token)
     */
    suspend fun forceReregisterAllAccounts() {
        log.d(tag = TAG) { "Force re-registering all accounts" }

        getAllRegisteredAccountKeys().forEach { accountKey ->
            val parts = accountKey.split("@")
            if (parts.size == 2) {
                val username = parts[0]
                val domain = parts[1]
                val accountInfo = activeAccounts[accountKey]

                if (accountInfo != null && accountInfo.isRegistered) {
                    try {
                        // Actualizar user agent
                        accountInfo.userAgent = userAgent()

                        // Re-registrar
                        messageHandler.sendRegister(accountInfo, isAppInBackground)

                        log.d(tag = TAG) { "Force re-registered: $accountKey" }

                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error force re-registering $accountKey: ${e.message}" }
                    }
                }
            }
        }
    }

    /**
     * Actualiza el push token para todas las cuentas registradas
     */
    suspend fun updatePushTokenForAllAccounts(newToken: String, provider: String = "fcm") {
        log.d(tag = TAG) { "Updating push token for all accounts" }

        activeAccounts.values.forEach { accountInfo ->
            if (accountInfo.isRegistered) {
                accountInfo.token = newToken
                accountInfo.provider = provider

                try {
                    // Re-registrar con nuevo token
                    messageHandler.sendRegister(accountInfo, isAppInBackground)
                    log.d(tag = TAG) { "Updated push token for: ${accountInfo.username}@${accountInfo.domain}" }

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error updating push token for ${accountInfo.username}: ${e.message}" }
                }
            }
        }
    }


    // === MÉTODOS DE HISTORIAL CON BASE DE DATOS ===

    /**
     * Obtiene call logs desde la base de datos
     */
    suspend fun getCallLogsFromDatabase(limit: Int = 50): List<CallLog> {
        return try {
            this.let { manager ->
                val dbIntegration = DatabaseAutoIntegration.getInstance(application, manager)
                val dbManager = DatabaseManager.getInstance(application)
                // Obtener desde BD y convertir al formato esperado
                val callLogsWithContact = dbManager.getRecentCallLogs(limit).first()
                log.d(tag = "CallHistoryManager") { " getting call logs from database: ${callLogsWithContact}" }


                callLogsWithContact.toCallLogs()


            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting call logs from database: ${e.message}" }
            // Fallback a memoria si falla la BD
            callHistoryManager.getAllCallLogs()
        }
    }

    /**
     * Obtiene call logs híbrido (BD + memoria)
     */
    fun getCallLogsHybrid(limit: Int = 50): List<CallLog> {
        return try {
            val databaseLogs = runBlocking {
                getCallLogsFromDatabase(limit)
            }

            if (databaseLogs.isNotEmpty()) {
                // Si hay datos en BD, usar esos
                databaseLogs
            } else {
                // Si no hay datos en BD, usar memoria como fallback
                callHistoryManager.getAllCallLogs()
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in hybrid call logs: ${e.message}" }
            // Fallback final a memoria
            callHistoryManager.getAllCallLogs()
        }
    }

    /**
     * Obtiene missed calls desde la base de datos
     */
    suspend fun getMissedCallsFromDatabase(): List<CallLog> {
        return try {
            val dbManager = DatabaseManager.getInstance(application)
            val missedCallsWithContact = dbManager.getMissedCallLogs().first()
            missedCallsWithContact.toCallLogs()
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting missed calls from database: ${e.message}" }
            callHistoryManager.getMissedCalls()
        }
    }

    /**
     * Busca call logs en la base de datos
     */
    suspend fun searchCallLogsInDatabase(query: String): List<CallLog> {
        return try {
            val dbManager = DatabaseManager.getInstance(application)
            val searchResults = dbManager.searchCallLogs(query).first()
            searchResults.toCallLogs()
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error searching call logs in database: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Obtiene call logs para un número específico desde la BD
     */
    suspend fun getCallLogsForNumberFromDatabase(phoneNumber: String): List<CallLog> {
        return try {
            val dbManager = DatabaseManager.getInstance(application)
            val callLogsFlow = dbManager.getRecentCallLogs(1000) // Obtener más para filtrar
            val allLogs = callLogsFlow.first()

            // Filtrar por número de teléfono
            val filteredLogs = allLogs.filter {
                it.callLog.phoneNumber == phoneNumber
            }

            filteredLogs.toCallLogs()
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting call logs for number from database: ${e.message}" }
            callHistoryManager.getCallLogsForNumber(phoneNumber)
        }
    }

// === MÉTODOS ACTUALIZADOS PARA USAR BD ===

    /**
     * Actualiza el método existente para usar BD como principal
     */
    fun callLogs(): List<CallLog> {
        return try {
            // Intentar obtener desde BD primero
            runBlocking { getCallLogsFromDatabase() }
        } catch (e: Exception) {
            log.w(tag = TAG) { "Database unavailable, using memory fallback: ${e.message}" }
            callHistoryManager.getAllCallLogs()
        }
    }

    /**
     * Método sobrecargado con límite
     */
    fun callLogs(limit: Int): List<CallLog> {
        return try {
            runBlocking { getCallLogsFromDatabase(limit) }
        } catch (e: Exception) {
            log.w(tag = TAG) { "Database unavailable, using memory fallback: ${e.message}" }
            callHistoryManager.getAllCallLogs().take(limit)
        }
    }

    /**
     * Actualiza el método de missed calls para usar BD
     */
    fun getMissedCalls(): List<CallLog> {
        return try {
            runBlocking { getMissedCallsFromDatabase() }
        } catch (e: Exception) {
            log.w(tag = TAG) { "Database unavailable, using memory fallback: ${e.message}" }
            callHistoryManager.getMissedCalls()
        }
    }

    /**
     * Actualiza el método de call logs para número específico
     */
    fun getCallLogsForNumber(phoneNumber: String): List<CallLog> {
        return try {
            runBlocking { getCallLogsForNumberFromDatabase(phoneNumber) }
        } catch (e: Exception) {
            log.w(tag = TAG) { "Database unavailable, using memory fallback: ${e.message}" }
            callHistoryManager.getCallLogsForNumber(phoneNumber)
        }
    }

    /**
     * Limpia call logs tanto en BD como en memoria
     */
    fun clearCallLogs() {
        try {
            // Limpiar en BD
            runBlocking {
                val dbManager = DatabaseManager.getInstance(application)
                dbManager.clearAllCallLogs()
            }
            log.d(tag = TAG) { "Call logs cleared from database" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error clearing call logs from database: ${e.message}" }
        }

        // Limpiar en memoria también
        callHistoryManager.clearCallLogs()
        log.d(tag = TAG) { "Call logs cleared from memory" }
    }

    /**
     * Sincroniza call logs de memoria a BD (útil para migrar datos existentes)
     */
    fun syncMemoryCallLogsToDB() {
        val memoryLogs = callHistoryManager.getAllCallLogs()

        if (memoryLogs.isEmpty()) {
            log.d(tag = TAG) { "No call logs in memory to sync" }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dbManager = DatabaseManager.getInstance(application)
                val currentAccount = currentAccountInfo

                if (currentAccount == null) {
                    log.w(tag = TAG) { "No current account for syncing call logs" }
                    return@launch
                }

                // Buscar o crear cuenta en BD
                var account = dbManager.getSipAccountByCredentials(
                    currentAccount.username,
                    currentAccount.domain
                )

                if (account == null) {
                    account = dbManager.createOrUpdateSipAccount(
                        username = currentAccount.username,
                        password = currentAccount.password,
                        domain = currentAccount.domain
                    )
                }

                // Convertir y guardar cada call log
                memoryLogs.forEach { callLog ->
                    try {
                        // Crear CallData desde CallLog para usar el método existente
                        val callData = CallData(
                            callId = callLog.id,
                            from = if (callLog.direction == CallDirections.INCOMING)
                                callLog.from else currentAccount.username,
                            to = if (callLog.direction == CallDirections.OUTGOING)
                                callLog.to else currentAccount.username,
                            direction = callLog.direction,
                            startTime = parseFormattedDate(callLog.formattedStartDate),
                            remoteDisplayName = callLog.contact ?: ""
                        )

                        // Calcular endTime basado en duración
                        val endTime = callData.startTime + (callLog.duration * 1000)

                        dbManager.createCallLog(
                            accountId = account.id,
                            callData = callData,
                            callType = callLog.callType,
                            endTime = endTime
                        )

                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error syncing individual call log ${callLog.id}: ${e.message}" }
                    }
                }

                log.d(tag = TAG) { "Synced ${memoryLogs.size} call logs from memory to database" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error syncing call logs to database: ${e.message}" }
            }
        }
    }

    private fun parseFormattedDate(formattedDate: String): Long {
        // Implementación simple para parsear la fecha formateada
        // Formato esperado: "DD.MM.YYYY HH:MM"
        return try {
            // Para este ejemplo, usar timestamp actual si no se puede parsear
            // En producción, implementar parser completo
            System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    /**
     * Configura los ajustes de transferencia
     */
    fun configureTransfer(config: TransferConfig) {
        this.transferConfig = config
        log.d(tag = TAG) { "Transfer configuration updated: ${config.defaultTransferNumber}" }
    }

    /**
     * Obtiene la configuración actual de transferencia
     */
    fun getTransferConfig(): TransferConfig = transferConfig

    /**
     * Transfiere la llamada actual a un número específico
     */
    fun transferCall(transferTo: String, callId: String? = null): Boolean {
        val accountInfo = ensureCurrentAccount() ?: run {
            log.e(tag = TAG) { "No current account available for transfer" }
            return false
        }

        val targetCallData = if (callId != null) {
            MultiCallManager.getCall(callId)
        } else {
            accountInfo.currentCallData
        } ?: run {
            log.e(tag = TAG) { "No call data available for transfer" }
            return false
        }

        val callState = CallStateManager.getCurrentState()
        if (!callState.isConnected() && !callState.isActive()) {
            log.w(tag = TAG) { "Cannot transfer call - no active call" }
            return false
        }

        if (transferTo.isEmpty()) {
            log.w(tag = TAG) { "Cannot transfer - empty transfer number" }
            return false
        }

        log.d(tag = TAG) { "Initiating call transfer to: $transferTo" }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Actualizar estado de llamada
                CallStateManager.callTransferring(targetCallData.callId)

                // Enviar REFER
                messageHandler.sendRefer(accountInfo, targetCallData, transferTo)

                // Notificar callback
                sipCallbacks?.onCallTransferInitiated(transferTo)

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error initiating call transfer: ${e.message}" }
                sipCallbacks?.onCallTransferCompleted(false)
            }
        }

        return true
    }

    /**
     * Transfiere la llamada actual al número por defecto configurado
     */
    fun transferCallToDefault(callId: String? = null): Boolean {
        if (transferConfig.defaultTransferNumber.isEmpty()) {
            log.w(tag = TAG) { "No default transfer number configured" }
            return false
        }

        return transferCall(transferConfig.defaultTransferNumber, callId)
    }

    /**
     * Acepta una transferencia entrante
     */
    fun acceptTransfer(callId: String? = null): Boolean {
        val accountInfo = ensureCurrentAccount() ?: return false
        val targetCallData = if (callId != null) {
            MultiCallManager.getCall(callId)
        } else {
            accountInfo.currentCallData
        } ?: return false

        log.d(tag = TAG) { "Accepting call transfer" }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Enviar NOTIFY con estado "200 OK"
                val notifySuccess = SipMessageBuilder.buildNotifyMessage(
                    accountInfo, targetCallData, "terminated", "200 OK"
                )
                accountInfo.webSocketClient?.send(notifySuccess)

                sipCallbacks?.onCallTransferCompleted(true)

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error accepting transfer: ${e.message}" }
            }
        }

        return true
    }

    /**
     * Rechaza una transferencia entrante
     */
    fun rejectTransfer(callId: String? = null): Boolean {
        val accountInfo = ensureCurrentAccount() ?: return false
        val targetCallData = if (callId != null) {
            MultiCallManager.getCall(callId)
        } else {
            accountInfo.currentCallData
        } ?: return false

        log.d(tag = TAG) { "Rejecting call transfer" }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Enviar NOTIFY con estado "403 Forbidden"
                val notifyReject = SipMessageBuilder.buildNotifyMessage(
                    accountInfo, targetCallData, "terminated", "403 Forbidden"
                )
                accountInfo.webSocketClient?.send(notifyReject)

                sipCallbacks?.onCallTransferCompleted(false)

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error rejecting transfer: ${e.message}" }
            }
        }

        return true
    }
    fun transferIncomingCallImmediately(transferTo: String) {
        val accountInfo = currentAccountInfo ?: return
        val callData = accountInfo.currentCallData ?: return

        // Asegurarse de que es una llamada entrante y está en estado de ringing
        if (callData.direction != CallDirections.INCOMING ||
            CallStateManager.getCurrentState().state != CallState.INCOMING_RECEIVED) {
            return
        }

        // 1. Contestar la llamada (200 OK) pero sin activar audio
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Crear una respuesta SDP sin audio (o con audio inactivo) si es necesario
//                val sdp = createInactiveSdp() // Debes implementar este método

//                callData.localSdp = sdp
                messageHandler.sendInviteOkResponse(accountInfo, callData)

                // Actualizar el estado a conectado (aunque no activamos audio)
                CallStateManager.callConnected(callData.callId, 200)
                notifyCallStateChanged(CallState.CONNECTED)

                // 2. Inmediatamente enviar REFER
                messageHandler.sendRefer(accountInfo, callData, transferTo)

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in immediate transfer: ${e.message}" }
            }
        }
    }

    /**
     * Configura los ajustes de deflection/redirección
     */
    fun configureCallDeflection(config: CallDeflectionConfig) {
        this.deflectionConfig = config
        log.d(tag = TAG) { "Call deflection configuration updated: ${config.defaultDeflectionNumber}" }
    }

    /**
     * Obtiene la configuración actual de deflection
     */
    fun getCallDeflectionConfig(): CallDeflectionConfig = deflectionConfig

    /**
     * Redirige una llamada entrante sin contestarla (Call Deflection)
     */
    fun deflectIncomingCall(redirectToNumber: String, callId: String? = null): Boolean {
        val accountInfo = ensureCurrentAccount() ?: run {
            log.e(tag = TAG) { "No current account available for call deflection" }
            return false
        }

        val targetCallData = if (callId != null) {
            MultiCallManager.getCall(callId)
        } else {
            accountInfo.currentCallData
        } ?: run {
            log.e(tag = TAG) { "No call data available for deflection" }
            return false
        }

        // Verificar que sea una llamada entrante y no contestada
        val callState = CallStateManager.getCurrentState()
        if (targetCallData.direction != CallDirections.INCOMING ||
            callState.state != CallState.INCOMING_RECEIVED) {
            log.w(tag = TAG) { "Cannot deflect call - invalid state or direction" }
            log.w(tag = TAG) { "Current state: ${callState.state}, Direction: ${targetCallData.direction}" }
            return false
        }

        if (redirectToNumber.isEmpty()) {
            log.w(tag = TAG) { "Cannot deflect - empty redirect number" }
            return false
        }

        log.d(tag = TAG) { "Deflecting incoming call to: $redirectToNumber" }

        // Realizar la deflection
        messageHandler.deflectCall(accountInfo, targetCallData, redirectToNumber)

        // Notificar callback
        sipCallbacks?.onCallDeflected(targetCallData.from, redirectToNumber)

        return true
    }

    /**
     * Redirige la llamada entrante al número por defecto configurado
     */
    fun deflectIncomingCallToDefault(callId: String? = null): Boolean {
        if (deflectionConfig.defaultDeflectionNumber.isEmpty()) {
            log.w(tag = TAG) { "No default deflection number configured" }
            return false
        }

        return deflectIncomingCall(deflectionConfig.defaultDeflectionNumber, callId)
    }

    /**
     * Auto-deflection basada en reglas configuradas
     */
    private fun checkAutoDeflection(callData: CallData): Boolean {
        if (!deflectionConfig.autoDeflectEnabled) {
            return false
        }

        // Verificar reglas específicas por caller
        val redirectNumber = deflectionConfig.deflectionRules[callData.from]
        if (redirectNumber != null) {
            log.d(tag = TAG) { "Auto-deflecting call from ${callData.from} to $redirectNumber" }
            deflectIncomingCall(redirectNumber, callData.callId)
            return true
        }

        // Auto-deflection al número por defecto si está configurado
        if (deflectionConfig.defaultDeflectionNumber.isNotEmpty()) {
            log.d(tag = TAG) { "Auto-deflecting call from ${callData.from} to default number" }
            deflectIncomingCallToDefault(callData.callId)
            return true
        }

        return false
    }

    fun getCallData(callId: String): CallData? {
        return getCall(callId)
    }
}
interface NetworkConnectivityListener {
    fun onNetworkLost()
    fun onNetworkRestored()
    fun onReconnectionStarted()
    fun onReconnectionCompleted(successful: Boolean)
}