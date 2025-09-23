package com.eddyslarez.siplibrary.core

import android.app.Application
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
import com.eddyslarez.siplibrary.data.services.audio.AudioUnit
import com.eddyslarez.siplibrary.data.services.audio.AudioUnitTypes
import com.eddyslarez.siplibrary.data.services.audio.WebRtcConnectionState
import com.eddyslarez.siplibrary.data.services.audio.WebRtcEventListener
import com.eddyslarez.siplibrary.data.services.audio.WebRtcManagerFactory
import com.eddyslarez.siplibrary.data.services.sip.SipMessageHandler
import com.eddyslarez.siplibrary.data.services.websocket.MultiplatformWebSocket
import com.eddyslarez.siplibrary.data.services.websocket.WebSocket
import com.eddyslarez.siplibrary.data.store.SettingsDataStore
import com.eddyslarez.siplibrary.platform.PlatformInfo
import com.eddyslarez.siplibrary.platform.PlatformRegistration
import com.eddyslarez.siplibrary.platform.WindowManager
import com.eddyslarez.siplibrary.utils.*
import com.eddyslarez.siplibrary.utils.MultiCallManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/**
 * Gestor principal del core SIP - Optimizado sin estados legacy
 * Versión simplificada usando únicamente los nuevos estados
 *
 * @author Eddys Larez
 */
class SipCoreManager private constructor(
    internal val application: Application,
    private val config: EddysSipLibrary.SipConfig,
    private val windowManager: WindowManager,
    private val platformInfo: PlatformInfo,
    private val settingsDataStore: SettingsDataStore,
) {
    private var databaseManager: DatabaseManager? = null
    private var loadedConfig: AppConfigEntity? = null
    private var isRegistrationInProgress = false
    private var healthCheckJob: Job? = null
    private var lastRegistrationAttempt = 0L
    internal var sipCallbacks: EddysSipLibrary.SipCallbacks? = null
    private var isShuttingDown = false
    val callHistoryManager = CallHistoryManager()
    private var lifecycleCallback: ((String) -> Unit)? = null

    // Managers
    internal lateinit var audioManager: SipAudioManager
    private lateinit var reconnectionManager: SipReconnectionManager
    internal lateinit var callManager: CallManager
    internal lateinit var networkManager: NetworkManager
    val webRtcManager = WebRtcManagerFactory.createWebRtcManager(application)
    private val platformRegistration = PlatformRegistration()
    private val messageHandler = SipMessageHandler(this)

    // Estados de registro por cuenta
    private val _registrationStates = MutableStateFlow<Map<String, RegistrationState>>(emptyMap())
    val registrationStatesFlow: StateFlow<Map<String, RegistrationState>> =
        _registrationStates.asStateFlow()

    // Mapa thread-safe de cuentas activas
    val activeAccounts = ConcurrentHashMap<String, AccountInfo>()
    var callStartTimeMillis: Long = 0
    var currentAccountInfo: AccountInfo? = null
    var isAppInBackground = false
    private var lastConnectionCheck = 0L
    var onCallTerminated: (() -> Unit)? = null
    private var registrationCallbackForCall: ((AccountInfo, Boolean) -> Unit)? = null

    // Sincronización de cuentas con BD
    private var accountSyncJob: Job? = null
    private val accountSyncMutex = Mutex()

    companion object {
        private const val TAG = "SipCoreManager"
        private const val WEBSOCKET_PROTOCOL = "sip"
        private const val REGISTRATION_CHECK_INTERVAL_MS = 30 * 1000L
        private const val ACCOUNT_SYNC_INTERVAL_MS = 60 * 1000L // 1 minuto

        fun createInstance(
            application: Application,
            config: EddysSipLibrary.SipConfig
        ): SipCoreManager {
            return SipCoreManager(
                application = application,
                config = config,
                windowManager = WindowManager(),
                platformInfo = PlatformInfo(),
                settingsDataStore = SettingsDataStore(application)
            )
        }
    }

    fun observeLifecycleChanges(callback: (String) -> Unit) {
        this.lifecycleCallback = callback
    }

    fun userAgent(): String = config.userAgent

    fun getDefaultDomain(): String? = currentAccountInfo?.domain

    private fun getFirstRegisteredAccount(): AccountInfo? {
        return activeAccounts.values.firstOrNull { it.isRegistered }
    }

    private fun ensureCurrentAccount(): AccountInfo? {
        if (currentAccountInfo == null || !currentAccountInfo!!.isRegistered) {
            currentAccountInfo = getFirstRegisteredAccount()
        }
        return currentAccountInfo
    }

    fun getCurrentUsername(): String? = currentAccountInfo?.username

    fun initialize() {
        log.d(tag = TAG) { "Initializing SIP Core with integrated managers" }

        // Inicializar managers en orden
        initializeNetworkManager()
        initializeAudioManager()
        initializeReconnectionManager()
        initializeCallManager()

        loadConfigurationFromDatabase()
        setupWebRtcEventListener()
        setupPlatformLifecycleObservers()
        startAccountSyncTask()

        CallStateManager.initialize()

        log.d(tag = TAG) { "SIP Core initialization completed" }
    }



    fun prepareAudioForCall(){
        webRtcManager.prepareAudioForCall()
    }
    fun onBluetoothConnectionChanged(isConnected: Boolean){
        webRtcManager.onBluetoothConnectionChanged(isConnected)
    }
    fun refreshAudioDevicesWithBluetoothPriority(){
        webRtcManager.refreshAudioDevicesWithBluetoothPriority()
    }
    fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean{
        return webRtcManager.applyAudioRouteChange(audioUnitType)
    }
    fun getAvailableAudioUnits(): Set<AudioUnit>{
       return webRtcManager.getAvailableAudioUnits()
    }
    fun getCurrentActiveAudioUnit(): AudioUnit?{
       return webRtcManager.getCurrentActiveAudioUnit()
    }

    private fun initializeNetworkManager() {
        try {
            networkManager = NetworkManager.getInstance(application)
            networkManager.initialize()
            log.d(tag = TAG) { "NetworkManager initialized successfully" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing NetworkManager: ${e.message}" }
        }
    }

    private fun initializeAudioManager() {
        try {
            val systemAudioManager = AudioManager(application)
            audioManager = SipAudioManager(
                application = application,
                audioManager = systemAudioManager,
                webRtcManager = webRtcManager
            )
            audioManager.initialize()
            log.d(tag = TAG) { "SipAudioManager initialized successfully" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing SipAudioManager: ${e.message}" }
        }
    }

    private fun initializeReconnectionManager() {
        try {
            // IMPORTANTE: Pasar referencia de SipCoreManager al constructor
            reconnectionManager = SipReconnectionManager(
                networkManager = networkManager,
                messageHandler = messageHandler,
                sipCoreManager = this // Esto permite acceso a activeAccounts
            )

            // Configurar listener de reconexión mejorado
            reconnectionManager.setReconnectionListener(object : ReconnectionListener {
                override fun onNetworkLost() {
                    log.w(tag = TAG) { "🌐 Network lost detected by ReconnectionManager" }
                    lifecycleCallback?.invoke("NETWORK_LOST")

                    // Marcar todas las cuentas como no registradas
                    activeAccounts.values.forEach { account ->
                        account.isRegistered = false
                        val accountKey = "${account.username}@${account.domain}"
                        updateRegistrationState(accountKey, RegistrationState.NONE)
                    }
                }

                override fun onNetworkRestored() {
                    log.d(tag = TAG) { "🌐 Network restored detected by ReconnectionManager" }
                    lifecycleCallback?.invoke("NETWORK_RESTORED")
                }

                override fun onReconnectionStarted() {
                    log.d(tag = TAG) { "🔄 Reconnection process started" }
                    lifecycleCallback?.invoke("RECONNECTION_STARTED")
                }

                override fun onReconnectionCompleted(successful: Boolean) {
                    log.d(tag = TAG) { "🔄 Reconnection process completed: $successful" }
                    lifecycleCallback?.invoke("RECONNECTION_COMPLETED:$successful")
                }

                override fun onReconnectionAttempt(accountKey: String, attempt: Int) {
                    log.d(tag = TAG) { "🔄 Reconnection attempt $attempt for $accountKey" }
                    updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)
                }

                override fun onReconnectAccount(accountInfo: AccountInfo): Boolean {
                    return try {
                        log.d(tag = TAG) { "🔌 Attempting to reconnect WebSocket for ${accountInfo.username}@${accountInfo.domain}" }
                        connectWebSocketAndRegister(accountInfo)
                        true
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "❌ Error reconnecting account: ${e.message}" }
                        false
                    }
                }

                override fun onAccountReconnected(accountKey: String, successful: Boolean) {
                    if (successful) {
                        log.d(tag = TAG) { "✅ Account successfully reconnected: $accountKey" }
                        updateRegistrationState(accountKey, RegistrationState.OK)
                    } else {
                        log.w(tag = TAG) { "❌ Account reconnection failed: $accountKey" }
                        updateRegistrationState(accountKey, RegistrationState.FAILED)
                    }
                }

                override fun onReconnectionFailed(accountKey: String) {
                    log.e(tag = TAG) { "💥 Reconnection completely failed for: $accountKey" }
                    updateRegistrationState(accountKey, RegistrationState.FAILED)
                }
            })

            reconnectionManager.initialize()
            log.d(tag = TAG) { "SipReconnectionManager initialized successfully" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing SipReconnectionManager: ${e.message}" }
        }
    }

    private fun initializeCallManager() {
        try {
            callManager = CallManager(
                sipCoreManager = this,
                audioManager = audioManager,
                webRtcManager = webRtcManager,
                messageHandler = messageHandler
            )
            log.d(tag = TAG) { "CallManager initialized successfully" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing CallManager: ${e.message}" }
        }
    }

    /**
     * NUEVO: Iniciar tarea de sincronización de cuentas con BD
     */
    private fun startAccountSyncTask() {
        accountSyncJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && !isShuttingDown) {
                try {
                    delay(ACCOUNT_SYNC_INTERVAL_MS)
                    syncAccountsWithDatabase()
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in account sync task: ${e.message}" }
                }
            }
        }
        log.d(tag = TAG) { "Account sync task started" }
    }

    /**
     * NUEVO: Sincronizar cuentas entre memoria y BD
     */
    private suspend fun syncAccountsWithDatabase() {
        accountSyncMutex.withLock {
            try {
                val dbManager = getDatabaseManager() ?: return

                // Obtener cuentas registradas de BD
                val dbAccounts = dbManager.getRegisteredSipAccounts().first()

                log.d(tag = TAG) { "Syncing accounts - Memory: ${activeAccounts.size}, DB: ${dbAccounts.size}" }

                // Verificar si alguna cuenta en BD no está en memoria
                dbAccounts.forEach { dbAccount ->
                    val accountKey = "${dbAccount.username}@${dbAccount.domain}"

                    if (!activeAccounts.containsKey(accountKey)) {
                        log.d(tag = TAG) { "Found account in DB not in memory, adding: $accountKey" }

                        val accountInfo = AccountInfo(
                            username = dbAccount.username,
                            password = dbAccount.password,
                            domain = dbAccount.domain
                        ).apply {
                            token = dbAccount.pushToken ?: ""
                            provider = dbAccount.pushProvider ?: "fcm"
                            userAgent = userAgent()
                            isRegistered = false
                        }

                        activeAccounts[accountKey] = accountInfo
                        updateRegistrationState(accountKey, RegistrationState.NONE)
                    }
                }

                // Actualizar estados de registro en BD
                activeAccounts.forEach { (accountKey, accountInfo) ->
                    if (accountInfo.isRegistered) {
                        val dbAccount = dbAccounts.find {
                            "${it.username}@${it.domain}" == accountKey
                        }

                        dbAccount?.let {
                            dbManager.updateSipAccountRegistrationState(
                                it.id,
                                RegistrationState.OK,
                                System.currentTimeMillis() + 3600000L // 1 hora
                            )
                        }
                    }
                }

                log.d(tag = TAG) { "Account sync completed successfully" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error syncing accounts with database: ${e.message}" }
            }
        }
    }

    /**
     * NUEVO: Recuperar cuentas desde BD cuando se pierden en memoria
     */
    suspend fun recoverAccountsFromDatabase(): List<AccountInfo> {
        return try {
            log.d(tag = TAG) { "🔍 Recovering accounts from database..." }

            val dbManager = getDatabaseManager() ?: return emptyList()
            val dbAccounts = dbManager.getRegisteredSipAccounts().first()

            val recoveredAccounts = dbAccounts.mapNotNull { dbAccount ->
                try {
                    val accountKey = "${dbAccount.username}@${dbAccount.domain}"

                    val accountInfo = AccountInfo(
                        username = dbAccount.username,
                        password = dbAccount.password,
                        domain = dbAccount.domain
                    ).apply {
                        token = dbAccount.pushToken ?: ""
                        provider = dbAccount.pushProvider ?: "fcm"
                        userAgent = userAgent()
                        isRegistered = false
                    }

                    // Agregar a cuentas activas
                    activeAccounts[accountKey] = accountInfo
                    updateRegistrationState(accountKey, RegistrationState.NONE)

                    log.d(tag = TAG) { "✅ Recovered account: $accountKey" }
                    accountInfo

                } catch (e: Exception) {
                    log.e(tag = TAG) { "❌ Error recovering account: ${e.message}" }
                    null
                }
            }

            log.d(tag = TAG) { "🔍 Account recovery completed: ${recoveredAccounts.size} accounts" }
            recoveredAccounts

        } catch (e: Exception) {
            log.e(tag = TAG) { "💥 Database account recovery failed: ${e.message}" }
            emptyList()
        }
    }

    private fun getDatabaseManager(): DatabaseManager? {
        if (databaseManager == null) {
            databaseManager = DatabaseManager.getInstance(application)
        }
        return databaseManager
    }


    private fun loadConfigurationFromDatabase() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dbManager = getDatabaseManager()
                loadedConfig = dbManager?.loadOrCreateDefaultConfig()
                loadedConfig?.let { config ->
                    audioManager.loadAudioConfigFromDatabase(config)
                    log.d(tag = TAG) { "Configuration loaded from database" }
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error loading configuration: ${e.message}" }
            }
        }
    }

    internal fun setCallbacks(callbacks: EddysSipLibrary.SipCallbacks) {
        this.sipCallbacks = callbacks
        log.d(tag = TAG) { "SipCallbacks configured" }
    }


    fun updateRegistrationState(accountKey: String, newState: RegistrationState) {
        log.d(tag = TAG) { "Updating registration state for $accountKey: $newState" }

        val currentStates = _registrationStates.value.toMutableMap()
        val previousState = currentStates[accountKey]

        if (previousState == newState) {
            log.d(tag = TAG) { "Registration state unchanged for $accountKey: $newState" }
            return
        }

        currentStates[accountKey] = newState
        _registrationStates.value = currentStates

        val account = activeAccounts[accountKey]
        if (account != null) {
            when (newState) {
                RegistrationState.OK -> {
                    account.isRegistered = true
                    log.d(tag = TAG) { "Account marked as registered: $accountKey" }
                }

                RegistrationState.FAILED, RegistrationState.NONE, RegistrationState.CLEARED -> {
                    account.isRegistered = false
                    log.d(tag = TAG) { "Account marked as not registered: $accountKey" }
                }

                else -> {
                    // Estados intermedios, no cambiar flag interno
                }
            }

            // Actualizar BD en background
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dbManager = getDatabaseManager()
                    val dbAccount =
                        dbManager?.getSipAccountByCredentials(account.username, account.domain)

                    dbAccount?.let {
                        val expiry = if (newState == RegistrationState.OK) {
                            System.currentTimeMillis() + 3600000L // 1 hora
                        } else null

                        dbManager.updateSipAccountRegistrationState(it.id, newState, expiry)
                    }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error updating registration state in database: ${e.message}" }
                }
            }

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    sipCallbacks?.onAccountRegistrationStateChanged(
                        account.username,
                        account.domain,
                        newState
                    )
                    sipCallbacks?.onRegistrationStateChanged(newState)
                    log.d(tag = TAG) { "Registration callbacks executed for $accountKey" }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in registration callbacks: ${e.message}" }
                }
            }

            notifyRegistrationStateChanged(newState, account.username, account.domain)
        }
    }

    fun updateRegistrationState(newState: RegistrationState) {
        currentAccountInfo?.let { account ->
            val accountKey = "${account.username}@${account.domain}"
            updateRegistrationState(accountKey, newState)
        }
    }

    fun getRegistrationState(accountKey: String): RegistrationState {
        return _registrationStates.value[accountKey] ?: RegistrationState.NONE
    }

    fun getAllRegistrationStates(): Map<String, RegistrationState> {
        return _registrationStates.value
    }

    private fun notifyRegistrationStateChanged(
        state: RegistrationState,
        username: String,
        domain: String
    ) {
        try {
            sipCallbacks?.onRegistrationStateChanged(state)
            sipCallbacks?.onAccountRegistrationStateChanged(username, domain, state)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error notifying registration state change: ${e.message}" }
        }
    }

    fun notifyCallStateChanged(state: CallState) {
        try {
            log.d(tag = TAG) { "Notifying call state change: $state" }

            when (state) {
                CallState.INCOMING_RECEIVED -> {
                    currentAccountInfo?.currentCallData?.let { callData ->
                        sipCallbacks?.onIncomingCall(callData.from, callData.remoteDisplayName)
                    }
                }

                CallState.CONNECTED, CallState.STREAMS_RUNNING -> {
                    sipCallbacks?.onCallConnected()
                }

                CallState.ENDED -> {
                    sipCallbacks?.onCallTerminated()
                }

                else -> {
                    log.d(tag = TAG) { "Other call state: $state" }
                }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error notifying call state change: ${e.message}" }
        }
    }


    private fun setupWebRtcEventListener() {
        webRtcManager.setListener(object : WebRtcEventListener {
            override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                // Implementar envío de ICE candidate si es necesario
            }

            override fun onConnectionStateChange(state: WebRtcConnectionState) {
                when (state) {
                    WebRtcConnectionState.CONNECTED -> callManager.handleWebRtcConnected()
                    WebRtcConnectionState.CLOSED -> callManager.handleWebRtcClosed()
                    else -> {}
                }
            }

            override fun onRemoteAudioTrack() {
                log.d(tag = TAG) { "Remote audio track received" }
            }

            override fun onAudioDeviceChanged(device: AudioDevice?) {
                log.d(tag = TAG) { "Audio device changed: ${device?.name}" }
                audioManager.refreshAudioDevices()
            }
        })
    }

    private fun setupPlatformLifecycleObservers() {
        platformRegistration.setupNotificationObservers(object : AppLifecycleListener {
            override fun onEvent(event: AppLifecycleEvent) {
                when (event) {
                    AppLifecycleEvent.EnterBackground -> {
                        CoroutineScope(Dispatchers.IO).launch {
                            log.d(tag = TAG) { "App entering background" }
                            isAppInBackground = true
                            lifecycleCallback?.invoke("APP_BACKGROUNDED")
                            onAppBackgrounded()
                        }
                    }

                    AppLifecycleEvent.EnterForeground -> {
                        CoroutineScope(Dispatchers.IO).launch {
                            log.d(tag = TAG) { "App entering foreground" }
                            isAppInBackground = false
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

    internal fun handleCallTermination() {
        onCallTerminated?.invoke()
        sipCallbacks?.onCallTerminated()
    }


    private suspend fun refreshAllRegistrationsWithNewUserAgent() {
        if (CallStateManager.getCurrentState().isActive()) {
            log.d(tag = TAG) { "Skipping registration refresh - call is active" }
            return
        }

        log.d(tag = TAG) { "🔄 Starting registration refresh for all accounts" }

        val registeredAccounts = activeAccounts.values.filter { it.isRegistered }

        if (registeredAccounts.isEmpty()) {
            log.w(tag = TAG) { "⚠️ No registered accounts to refresh" }
            return
        }

        var successfulRefreshes = 0
        var failedRefreshes = 0

        registeredAccounts.forEach { accountInfo ->
            val accountKey = "${accountInfo.username}@${accountInfo.domain}"

            try {
                log.d(tag = TAG) { "🔄 Refreshing registration for: $accountKey" }

                // CRÍTICO: Verificar conectividad WebSocket antes de refrescar
                if (!ensureWebSocketConnectivity(accountInfo)) {
                    log.e(tag = TAG) { "❌ Cannot ensure WebSocket connectivity for refresh: $accountKey" }
                    updateRegistrationState(accountKey, RegistrationState.FAILED)
                    failedRefreshes++
                    return@forEach
                }

                // Verificar que el WebSocket está realmente conectado y saludable
                if (!accountInfo.isWebSocketHealthy()) {
                    log.e(tag = TAG) { "❌ WebSocket not healthy after connectivity check for refresh: $accountKey" }
                    updateRegistrationState(accountKey, RegistrationState.FAILED)
                    failedRefreshes++
                    return@forEach
                }

                // Actualizar user agent
                accountInfo.userAgent = userAgent()

                // Marcar como en progreso
                updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)

                // Enviar registro actualizado
                messageHandler.sendRegister(accountInfo, isAppInBackground)

                log.d(tag = TAG) { "✅ Registration refreshed successfully for: $accountKey" }
                successfulRefreshes++

            } catch (e: Exception) {
                log.e(tag = TAG) { "💥 Error refreshing registration for $accountKey: ${e.message}" }
                updateRegistrationState(accountKey, RegistrationState.FAILED)
                failedRefreshes++
            }
        }

        log.d(tag = TAG) { "🔄 Registration refresh completed - Success: $successfulRefreshes, Failed: $failedRefreshes" }
    }

    // === MÉTODOS PÚBLICOS DELEGADOS A MANAGERS ===

    // Métodos de registro
    fun register(
        username: String,
        password: String,
        domain: String,
        provider: String,
        token: String
    ) {
        val accountKey = "$username@$domain"

        try {
            // Actualizar o crear en la base de datos en un Coroutine separado
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dbManager = getDatabaseManager() ?: return@launch

                    // Obtener la cuenta existente de la BD si existe
                    val dbAccounts = dbManager.getRegisteredSipAccounts().first()
                    val existingAccount = dbAccounts.firstOrNull { it.username == username && it.domain == domain }


                    // Determinar qué valores usar: los nuevos si no son nulos/vacíos, si no usar los de BD
                    val finalPassword = if (!password.isNullOrEmpty()) password else existingAccount?.password ?: ""
                    val finalToken = if (!token.isNullOrEmpty()) token else existingAccount?.pushToken ?: ""
                    val finalProvider = if (!provider.isNullOrEmpty()) provider else existingAccount?.pushProvider ?: "fcm"

                    // Crear o actualizar en BD
                    dbManager.createOrUpdateSipAccount(
                        username = username,
                        password = finalPassword,
                        domain = domain,
                        pushToken = finalToken,
                        pushProvider = finalProvider
                    )

                    log.d(tag = TAG) { "Account saved to database: $accountKey" }

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error saving account to database: ${e.message}" }
                }
            }

            // Crear AccountInfo y registrar en memoria
            val accountInfo = AccountInfo(
                username = username,
                password = if (!password.isNullOrEmpty()) password else "",
                domain = domain
            )

            // Asignar valores de token y proveedor (usar los valores recibidos si existen)
            accountInfo.token = if (!token.isNullOrEmpty()) token else ""
            accountInfo.provider = if (!provider.isNullOrEmpty()) provider else "fcm"
            accountInfo.userAgent = userAgent()

            // Guardar en memoria
            activeAccounts[accountKey] = accountInfo

            // Actualizar estado y registrar en WebSocket
            updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)
            connectWebSocketAndRegister(accountInfo)

        } catch (e: Exception) {
            updateRegistrationState(accountKey, RegistrationState.FAILED)
            throw Exception("Registration error: ${e.message}")
        }
    }
    // Métodos de registro
    fun register2(
        username: String,
        password: String,
        domain: String,
        provider: String,
        token: String
    ) {
        val accountKey = "$username@$domain"

        try {
            // Actualizar o crear en la base de datos en un Coroutine separado
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dbManager = getDatabaseManager() ?: return@launch

                    // Obtener la cuenta existente de la BD si existe
                    val dbAccounts = dbManager.getRegisteredSipAccounts().first()
                    val existingAccount = dbAccounts.firstOrNull { it.username == username && it.domain == domain }

                    // Determinar qué valores usar: los nuevos si no son nulos/vacíos, si no usar los de BD
                    val finalPassword = if (!password.isNullOrEmpty()) password else existingAccount?.password ?: ""
                    val finalToken = if (!token.isNullOrEmpty()) token else existingAccount?.pushToken ?: ""
                    val finalProvider = if (!provider.isNullOrEmpty()) provider else existingAccount?.pushProvider ?: "fcm"

                    // Crear o actualizar en BD
                    dbManager.createOrUpdateSipAccount(
                        username = username,
                        password = finalPassword,
                        domain = domain,
                        pushToken = finalToken,
                        pushProvider = finalProvider
                    )

                    log.d(tag = TAG) { "Account saved to database: $accountKey" }

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error saving account to database: ${e.message}" }
                }
            }

            // Crear AccountInfo y registrar en memoria
            val accountInfo = AccountInfo(
                username = username,
                password = if (!password.isNullOrEmpty()) password else "",
                domain = domain
            )

            // **FORZAR MODO PUSH** - Cambios específicos para register2
            accountInfo.token = if (!token.isNullOrEmpty()) token else ""
            accountInfo.provider = if (!provider.isNullOrEmpty()) provider else "fcm"

            // **CRÍTICO: Forzar User-Agent específico para push**
            accountInfo.userAgent = "${userAgent()} Push"

            isAppInBackground = true

            // Guardar en memoria
            activeAccounts[accountKey] = accountInfo

            // Actualizar estado y registrar en WebSocket
            updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)

            // **CONECTAR EN MODO PUSH** - Usar safeRegister con push forzado
            CoroutineScope(Dispatchers.IO).launch {
                safeRegister(accountInfo, isBackground = true) // Siempre en background/push
            }

        } catch (e: Exception) {
            updateRegistrationState(accountKey, RegistrationState.FAILED)
            throw Exception("Registration error: ${e.message}")
        }
    }

    /**
     * NUEVO: Registro seguro de cuenta
     */
    private suspend fun register3(
        username: String,
        password: String,
        domain: String,
        provider: String,
        token: String
    ) {
        val accountKey = "$username@$domain"

        try {
            // Crear AccountInfo
            val accountInfo = AccountInfo(username, password, domain)
            activeAccounts[accountKey] = accountInfo

            accountInfo.token = token
            accountInfo.provider = provider
            accountInfo.userAgent = userAgent()

            updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)

            // Usar registro seguro
            val success = safeRegister(accountInfo, isAppInBackground)

            if (!success) {
                log.e(tag = TAG) { "Safe register failed during account creation for: $accountKey" }
                updateRegistrationState(accountKey, RegistrationState.FAILED)
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in safe account registration for $accountKey: ${e.message}" }
            updateRegistrationState(accountKey, RegistrationState.FAILED)
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

            // Eliminar de BD
            val dbManager = getDatabaseManager()
            val dbAccount = dbManager?.getSipAccountByCredentials(username, domain)
            dbAccount?.let {
                dbManager.deleteSipAccount(it.id)
                log.d(tag = TAG) { "Account removed from database: $accountKey" }
            }

            val currentStates = _registrationStates.value.toMutableMap()
            currentStates.remove(accountKey)
            _registrationStates.value = currentStates

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error unregistering account: ${e.message}" }
        }
    }

    // Métodos de llamadas (delegados a CallManager)
    fun makeCall(phoneNumber: String, sipName: String, domain: String) {
        val accountKey = "$sipName@$domain"
        val accountInfo = activeAccounts[accountKey] ?: run {
            log.e(tag = TAG) { "Account not found: $accountKey" }
            sipCallbacks?.onCallFailed("Account not found: $accountKey")
            return
        }

        currentAccountInfo = accountInfo
        callManager.makeCall(phoneNumber, accountInfo)
    }

    fun endCall(callId: String? = null) = callManager.endCall(callId)
    fun acceptCall(callId: String? = null) = callManager.acceptCall(callId)
    fun declineCall(callId: String? = null) = callManager.declineCall(callId)
    fun rejectCall(callId: String? = null) = callManager.declineCall(callId)
    fun holdCall(callId: String? = null) = callManager.holdCall(callId)
    fun resumeCall(callId: String? = null) = callManager.resumeCall(callId)

    // Métodos de audio (delegados a SipAudioManager)
    fun mute() = audioManager.toggleMute()
    fun isMuted() = audioManager.isMuted()
    fun getAudioDevices() = audioManager.getAudioDevices()
    fun getCurrentDevices() = audioManager.getCurrentDevices()
    fun refreshAudioDevices() = audioManager.refreshAudioDevices()
    fun changeAudioDevice(device: AudioDevice) = audioManager.changeAudioDevice(device)
    fun saveIncomingRingtoneUri(uri: Uri) =
        audioManager.saveIncomingRingtoneUri(uri, databaseManager)

    fun saveOutgoingRingtoneUri(uri: Uri) =
        audioManager.saveOutgoingRingtoneUri(uri, databaseManager)

    suspend fun saveRingtoneUris(incomingUri: Uri?, outgoingUri: Uri?) =
        audioManager.saveRingtoneUris(incomingUri, outgoingUri, databaseManager)

    // Métodos DTMF (delegados a CallManager)
    fun sendDtmf(digit: Char, duration: Int = 160) = callManager.sendDtmf(digit, duration)
    fun sendDtmfSequence(digits: String, duration: Int = 160) =
        callManager.sendDtmfSequence(digits, duration)

    // Métodos de conectividad (delegados a SipReconnectionManager)
    fun forceReconnection() {
        val accountsToReconnect = if (activeAccounts.isEmpty()) {
            // Si no hay cuentas en memoria, intentar recuperar desde BD
            CoroutineScope(Dispatchers.IO).launch {
                val recoveredAccounts = recoverAccountsFromDatabase()
                reconnectionManager.forceReconnection(recoveredAccounts)
            }
            return
        } else {
            activeAccounts.values.toList()
        }

        reconnectionManager.forceReconnection(accountsToReconnect)
    }

    fun verifyAndFixConnectivity() {
        val accountsToCheck = if (activeAccounts.isEmpty()) {
            // Si no hay cuentas en memoria, intentar recuperar desde BD
            CoroutineScope(Dispatchers.IO).launch {
                val recoveredAccounts = recoverAccountsFromDatabase()
                reconnectionManager.verifyAndFixConnectivity(recoveredAccounts)
            }
            return
        } else {
            activeAccounts.values.toList()
        }

        reconnectionManager.verifyAndFixConnectivity(accountsToCheck)
    }

    fun getConnectivityStatus(): Map<String, Any> {
        val reconnectionStatus = reconnectionManager.getConnectivityStatus()
        val additionalStatus = mapOf(
            "activeAccountsCount" to activeAccounts.size,
            "activeAccountKeys" to activeAccounts.keys,
            "registrationStates" to _registrationStates.value,
            "currentAccount" to currentAccountInfo?.let { "${it.username}@${it.domain}" },
            "isShuttingDown" to isShuttingDown
        )

        return (reconnectionStatus + additionalStatus) as Map<String, Any>
    }

    fun isNetworkAvailable() = reconnectionManager.isNetworkAvailable()

    // === MÉTODOS DE WEBSOCKET ===

    private fun connectWebSocketAndRegister(accountInfo: AccountInfo) {
        try {
            accountInfo.webSocketClient?.close()
            val headers = createHeaders()
            val webSocketClient = createWebSocketClient(accountInfo, headers)
            accountInfo.webSocketClient = webSocketClient
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error connecting WebSocket: ${e.stackTraceToString()}" }
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
                    messageHandler.sendRegister(accountInfo, isAppInBackground)
                }
            }

            override fun onMessage(message: String) {
                CoroutineScope(Dispatchers.IO).launch {
                    messageHandler.handleSipMessage(message, accountInfo)
                }
            }

            override fun onClose(code: Int, reason: String) {
                log.d(tag = TAG) { "WebSocket closed for ${accountInfo.username}@${accountInfo.domain}" }

                val account = databaseManager?.getActiveSipAccounts()
                if (code != 1000 && !isShuttingDown) {
                    // Delegar reconexión al SipReconnectionManager

                    reconnectionManager.startReconnectionProcess(listOf(accountInfo))
                }
            }

            override fun onError(error: Exception) {
                log.e(tag = TAG) { "WebSocket error: ${error.message}" }
                accountInfo.isRegistered = false
                val accountKey = "${accountInfo.username}@${accountInfo.domain}"
                updateRegistrationState(accountKey, RegistrationState.FAILED)
            }

            override fun onPong(timeMs: Long) {
                lastConnectionCheck = Clock.System.now().toEpochMilliseconds()
            }

            override fun onRegistrationRenewalRequired(accountKey: String) {
                CoroutineScope(Dispatchers.IO).launch {
                    val account = activeAccounts[accountKey]
                    if (account != null && account.webSocketClient?.isConnected() == true) {
                        messageHandler.sendRegister(account, isAppInBackground)
                    } else {
                        // Delegar reconexión al manager
                        account?.let {
                            reconnectionManager.reconnectAccountWithRetry(it)
                        }
                    }
                }
            }
        })
    }

    // === MANEJO DE EVENTOS DE REGISTRO ===

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

        accountInfo.isRegistered = true
        updateRegistrationState(accountKey, RegistrationState.OK)

        if (currentAccountInfo == null) {
            currentAccountInfo = accountInfo
            log.d(tag = TAG) { "Set current account to: $accountKey" }
        }

        CoroutineScope(Dispatchers.Main).launch {
            delay(100)
            sipCallbacks?.onAccountRegistrationStateChanged(
                accountInfo.username,
                accountInfo.domain,
                RegistrationState.OK
            )
        }
    }

    // === MÉTODOS DE ESTADO Y INFORMACIÓN ===

    fun getCallStatistics() = callHistoryManager.getCallStatistics()
    fun getRegistrationState(): RegistrationState {
        val registeredAccounts =
            _registrationStates.value.values.filter { it == RegistrationState.OK }
        return if (registeredAccounts.isNotEmpty()) RegistrationState.OK else RegistrationState.NONE
    }

    fun currentCall(): Boolean = callManager.hasActiveCall()
    fun currentCallConnected(): Boolean = callManager.hasConnectedCall()
    fun getCurrentCallState(): CallStateInfo = CallStateManager.getCurrentState()

    fun getAllActiveCalls(): List<CallData> = MultiCallManager.getAllCalls()
    fun getActiveCalls(): List<CallData> = MultiCallManager.getActiveCalls()
    fun cleanupTerminatedCalls() = MultiCallManager.cleanupTerminatedCalls()
    fun getCallsInfo(): String = MultiCallManager.getDiagnosticInfo()

    fun isSipCoreManagerHealthy(): Boolean {
        return try {
            audioManager.isWebRtcInitialized() && activeAccounts.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    // === MÉTODOS DE LIFECYCLE ===

    suspend fun onAppBackgrounded() {
        log.d(tag = TAG) { "App backgrounded - updating registrations" }
        isAppInBackground = true
        refreshAllRegistrationsWithNewUserAgent()
    }

    suspend fun onAppForegrounded() {
        log.d(tag = TAG) { "App foregrounded - updating registrations and checking connectivity" }
        isAppInBackground = false

        // Verificar conectividad al regresar del background
        verifyAndFixConnectivity()
        refreshAllRegistrationsWithNewUserAgent()
    }

    fun enterPushMode(token: String? = null) {
        token?.let { newToken ->
            activeAccounts.values.forEach { accountInfo ->
                accountInfo.token = newToken
            }
        }
    }
    /**
     * Cambia una cuenta específica a modo push con verificación previa de conectividad
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
            // CRÍTICO: Asegurar conectividad WebSocket antes del cambio
            if (!ensureWebSocketConnectivity(accountInfo)) {
                log.e(tag = TAG) { "❌ Cannot ensure WebSocket connectivity for push mode switch: $accountKey" }
                updateRegistrationState(accountKey, RegistrationState.FAILED)
                return
            }

            // Verificar una vez más que el WebSocket está saludable
            if (!accountInfo.isWebSocketHealthy()) {
                log.e(tag = TAG) { "❌ WebSocket not healthy after connectivity check for push mode: $accountKey" }
                updateRegistrationState(accountKey, RegistrationState.FAILED)
                return
            }

            // Cambiar a modo push
            val pushUserAgent = "${userAgent()} Push"
            accountInfo.userAgent = pushUserAgent
            isAppInBackground = true

            // Enviar registro en modo push
            messageHandler.sendRegister(accountInfo, true)

            log.d(tag = TAG) { "✅ Account switched to push mode successfully: $accountKey" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "💥 Error switching to push mode for $accountKey: ${e.message}" }
            updateRegistrationState(accountKey, RegistrationState.FAILED)
        }
    }

    /**
     * Cambia una cuenta específica a modo foreground con verificación previa de conectividad
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
            // CRÍTICO: Asegurar conectividad WebSocket antes del cambio
            if (!ensureWebSocketConnectivity(accountInfo)) {
                log.e(tag = TAG) { "❌ Cannot ensure WebSocket connectivity for foreground mode switch: $accountKey" }
                updateRegistrationState(accountKey, RegistrationState.FAILED)
                return
            }

            // Verificar una vez más que el WebSocket está saludable
            if (!accountInfo.isWebSocketHealthy()) {
                log.e(tag = TAG) { "❌ WebSocket not healthy after connectivity check for foreground mode: $accountKey" }
                updateRegistrationState(accountKey, RegistrationState.FAILED)
                return
            }

            // Cambiar a modo foreground
            accountInfo.userAgent = userAgent()
            isAppInBackground = false

            // Enviar registro en modo foreground
            messageHandler.sendRegister(accountInfo, false)

            log.d(tag = TAG) { "✅ Account switched to foreground mode successfully: $accountKey" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "💥 Error switching to foreground mode for $accountKey: ${e.message}" }
            updateRegistrationState(accountKey, RegistrationState.FAILED)
        }
    }

    /**
     * Cambiar TODAS las cuentas activas a modo push con verificación de conectividad
     */
    suspend fun switchAllAccountsToPushMode() {
        log.d(tag = TAG) { "🔄 Switching all accounts to push mode" }

        val registeredAccounts = activeAccounts.filter { it.value.isRegistered }

        if (registeredAccounts.isEmpty()) {
            log.w(tag = TAG) { "⚠️ No registered accounts to switch to push mode" }
            return
        }

        registeredAccounts.forEach { (accountKey, accountInfo) ->
            try {
                switchToPushMode(accountInfo.username, accountInfo.domain)
            } catch (e: Exception) {
                log.e(tag = TAG) { "❌ Error switching $accountKey to push mode: ${e.message}" }
            }
        }

        log.d(tag = TAG) { "✅ Completed switching ${registeredAccounts.size} accounts to push mode" }
    }

    /**
     * Cambiar TODAS las cuentas activas a modo foreground con verificación de conectividad
     */
    suspend fun switchAllAccountsToForegroundMode() {
        log.d(tag = TAG) { "🔄 Switching all accounts to foreground mode" }

        val registeredAccounts = activeAccounts.filter { it.value.isRegistered }

        if (registeredAccounts.isEmpty()) {
            log.w(tag = TAG) { "⚠️ No registered accounts to switch to foreground mode" }
            return
        }

        registeredAccounts.forEach { (accountKey, accountInfo) ->
            try {
                switchToForegroundMode(accountInfo.username, accountInfo.domain)
            } catch (e: Exception) {
                log.e(tag = TAG) { "❌ Error switching $accountKey to foreground mode: ${e.message}" }
            }
        }

        log.d(tag = TAG) { "✅ Completed switching ${registeredAccounts.size} accounts to foreground mode" }
    }

    /**
     * Método de conveniencia para cambio masivo de modo basado en estado de app
     */
    suspend fun updateAllAccountsForAppState(isBackground: Boolean) {
        if (isBackground) {
            switchAllAccountsToPushMode()
        } else {
            switchAllAccountsToForegroundMode()
        }
    }

    // === MÉTODOS DE LIMPIEZA ===

    suspend fun unregisterAllAccounts() {
        log.d(tag = TAG) { "Starting complete unregister and shutdown" }
        isShuttingDown = true

        try {
            // Cancelar tareas de sincronización
            accountSyncJob?.cancel()

            healthCheckJob?.cancel()
            audioManager.stopAllRingtones()

            if (CallStateManager.getCurrentState().isActive()) {
                callManager.endCall()
            }

            if (activeAccounts.isNotEmpty()) {
                val accountsToUnregister = activeAccounts.toMap()

                accountsToUnregister.values.forEach { accountInfo ->
                    try {
                        val accountKey = "${accountInfo.username}@${accountInfo.domain}"

                        accountInfo.webSocketClient?.let { webSocket ->
                            webSocket.stopPingTimer()
                            webSocket.stopRegistrationRenewalTimer()
                        }

                        if (accountInfo.isRegistered && accountInfo.webSocketClient?.isConnected() == true) {
                            messageHandler.sendUnregister(accountInfo)
                        }

                        accountInfo.webSocketClient?.close(1000, "User logout")
                        accountInfo.webSocketClient = null
                        accountInfo.isRegistered = false
                        accountInfo.resetCallState()

                        updateRegistrationState(accountKey, RegistrationState.CLEARED)
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error unregistering account: ${e.message}" }
                    }
                }
            }

            activeAccounts.clear()
            currentAccountInfo = null
            _registrationStates.value = emptyMap()

            audioManager.dispose()
            callStartTimeMillis = 0
            isAppInBackground = false
            isRegistrationInProgress = false
            lastConnectionCheck = 0L
            lastRegistrationAttempt = 0L

            CallStateManager.forceResetToIdle()
            CallStateManager.clearHistory()

            log.d(tag = TAG) { "Complete unregister and shutdown successful" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error during complete unregister: ${e.message}" }
        }
    }

    fun dispose() {
        isShuttingDown = true

        // Cancelar tareas
        accountSyncJob?.cancel()

        audioManager.dispose()
        reconnectionManager.dispose()
        networkManager.dispose()

        MultiCallManager.clearAllCalls()
        activeAccounts.clear()
        _registrationStates.value = emptyMap()

        CallStateManager.resetToIdle()
        CallStateManager.clearHistory()

        // Cerrar base de datos si es necesario
        databaseManager?.closeDatabase()
    }

    // === MÉTODOS AUXILIARES ===

    fun getMessageHandler(): SipMessageHandler = messageHandler
    fun getLoadedConfig(): AppConfigEntity? = loadedConfig
    fun notifyCallEndedForSpecificAccount(accountKey: String) {
        sipCallbacks?.onCallEndedForAccount(accountKey)
        lifecycleCallback?.invoke("CALL_ENDED:$accountKey")
    }

    // === MÉTODOS DE INTEGRACIÓN CON BD ===
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

    suspend fun getCallLogsFromDatabase(limit: Int = 50): List<CallLog> {
        return try {
            val dbIntegration = DatabaseAutoIntegration.getInstance(application, this)
            val dbManager = DatabaseManager.getInstance(application)
            val callLogsWithContact = dbManager.getRecentCallLogs(limit).first()
            callLogsWithContact.toCallLogs()
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting call logs from database: ${e.message}" }
            callHistoryManager.getAllCallLogs()
        }
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

    /**
     * Verificar y garantizar conectividad antes de enviar mensajes SIP
     */
    suspend fun ensureWebSocketConnectivity(accountInfo: AccountInfo): Boolean {
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"

        try {
            // 1. Verificar si el WebSocket ya está conectado y funcional
            if (accountInfo.isWebSocketHealthy()) {
                log.d(tag = TAG) { "✅ WebSocket already healthy for: $accountKey" }
                return true
            }

            // 2. Verificar conectividad de red primero
            if (!networkManager.isNetworkAvailable()) {
                log.w(tag = TAG) { "🌐 No network connectivity available for: $accountKey" }
                return false
            }

            // 3. Si el WebSocket existe pero no está conectado, cerrarlo primero
            if (accountInfo.webSocketClient?.isConnected() == false) {
                log.d(tag = TAG) { "🧹 Cleaning up disconnected WebSocket for: $accountKey" }
                try {
                    accountInfo.webSocketClient?.close()
                    delay(1000) // Esperar cierre completo
                } catch (e: Exception) {
                    log.w(tag = TAG) { "⚠️ Error closing existing WebSocket: ${e.message}" }
                }
                accountInfo.webSocketClient = null
            }

            // 4. Crear nueva conexión WebSocket si es necesario
            if (accountInfo.webSocketClient == null) {
                log.d(tag = TAG) { "🔌 Creating new WebSocket connection for: $accountKey" }
                updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)

                // Crear nueva conexión
                val headers = createHeaders()
                val newWebSocket = createWebSocketClient(accountInfo, headers)
                accountInfo.webSocketClient = newWebSocket

                // Esperar a que la conexión se establezca
                var waitTime = 0L
                val maxWaitTime = 10000L // 10 segundos máximo
                val checkInterval = 250L

                while (waitTime < maxWaitTime) {
                    if (accountInfo.webSocketClient?.isConnected() == true) {
                        log.d(tag = TAG) { "✅ WebSocket connection established for: $accountKey" }
                        return true
                    }

                    delay(checkInterval)
                    waitTime += checkInterval
                }

                log.e(tag = TAG) { "⏰ WebSocket connection timeout for: $accountKey" }
                updateRegistrationState(accountKey, RegistrationState.FAILED)
                return false
            }

            return accountInfo.isWebSocketHealthy()

        } catch (e: Exception) {
            log.e(tag = TAG) { "💥 Error ensuring WebSocket connectivity for $accountKey: ${e.message}" }
            updateRegistrationState(accountKey, RegistrationState.FAILED)
            return false
        }
    }

    /**
     * Enviar registro con verificación previa de conectividad
     */
    suspend fun safeRegister(accountInfo: AccountInfo, isBackground: Boolean = false): Boolean {
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"

        try {
            log.d(tag = TAG) { "🔒 Safe register initiated for: $accountKey, background: $isBackground" }

            // 1. Asegurar conectividad WebSocket
            if (!ensureWebSocketConnectivity(accountInfo)) {
                log.e(tag = TAG) { "❌ Cannot ensure WebSocket connectivity for: $accountKey" }
                return false
            }

            // 2. Verificar una vez más antes de enviar
            if (!accountInfo.isWebSocketHealthy()) {
                log.e(tag = TAG) { "❌ WebSocket not healthy after connectivity check for: $accountKey" }
                updateRegistrationState(accountKey, RegistrationState.FAILED)
                return false
            }

            // 3. Actualizar user agent basado en modo
            accountInfo.userAgent = if (isBackground) {
                "${userAgent()} Push"
            } else {
                userAgent()
            }

            // 4. Enviar registro
            updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)
            messageHandler.sendRegister(accountInfo, isBackground)

            log.d(tag = TAG) { "✅ Safe register message sent for: $accountKey" }
            return true

        } catch (e: Exception) {
            log.e(tag = TAG) { "💥 Error in safe register for $accountKey: ${e.message}" }
            updateRegistrationState(accountKey, RegistrationState.FAILED)
            return false
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

    fun callLogs(): List<CallLog> {
        return try {
            runBlocking { getCallLogsFromDatabase() }
        } catch (e: Exception) {
            callHistoryManager.getAllCallLogs()
        }
    }

    // === NUEVOS MÉTODOS DE GESTIÓN DE CUENTAS ===

    fun getAllRegisteredAccountKeys(): Set<String> {
        return activeAccounts.filter { it.value.isRegistered }.keys
    }

    fun getAllAccountKeys(): Set<String> = activeAccounts.keys.toSet()

    fun isAccountRegistered(username: String, domain: String): Boolean {
        val accountKey = "$username@$domain"
        return activeAccounts[accountKey]?.isRegistered ?: false
    }

    fun getAccountInfo(username: String, domain: String): AccountInfo? {
        val accountKey = "$username@$domain"
        return activeAccounts[accountKey]
    }

    suspend fun updatePushTokenForAllAccounts(newToken: String, provider: String = "fcm") {
        activeAccounts.values.forEach { accountInfo ->
            if (accountInfo.isRegistered) {
                accountInfo.token = newToken
                accountInfo.provider = provider
                try {
                    messageHandler.sendRegister(accountInfo, isAppInBackground)

                    // Actualizar en BD también
                    val dbManager = getDatabaseManager()
                    val dbAccount = dbManager?.getSipAccountByCredentials(
                        accountInfo.username,
                        accountInfo.domain
                    )
                    dbAccount?.let {
                        dbManager.createOrUpdateSipAccount(
                            username = it.username,
                            password = it.password,
                            domain = it.domain,
                            displayName = it.displayName,
                            pushToken = newToken,
                            pushProvider = provider
                        )
                    }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error updating push token: ${e.message}" }
                }
            }
        }
    }
}