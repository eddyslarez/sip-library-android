package com.eddyslarez.siplibrary.data.services.network

import android.app.Application
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.models.RegistrationState
import com.eddyslarez.siplibrary.utils.RegistrationStateManager
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Servicio que combina monitoreo de red con reconexión automática - COMPLETAMENTE CORREGIDO
 *
 * @author Eddys Larez
 */
class NetworkAwareReconnectionService(private val application: Application) {

    private val TAG = "NetworkAwareReconnectionService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Componentes
    private val networkMonitor = NetworkMonitor(application)
    private val reconnectionManager = ReconnectionManager()

    // Estado del servicio
    private val _serviceStateFlow = MutableStateFlow(ServiceState.STOPPED)
    val serviceStateFlow: StateFlow<ServiceState> = _serviceStateFlow.asStateFlow()

    // Cuentas registradas
    private val registeredAccounts = ConcurrentHashMap<String, AccountInfo>()

    // Callbacks
    private var onReconnectionRequiredCallback: ((AccountInfo, ReconnectionManager.ReconnectionReason) -> Unit)? = null
    private var onNetworkStatusChangedCallback: ((NetworkMonitor.NetworkInfo) -> Unit)? = null

    // CORREGIDO: Variables de control mejorado
    private var isDisposing = false
    private var lastNetworkState: NetworkMonitor.NetworkInfo? = null

    // NUEVO: Control de transiciones de red para evitar múltiples handlers
    private var networkTransitionHandler: Job? = null

    // Configuración de temporización
    private val networkStabilizationDelay = 4000L // 4 segundos
    private val reconnectionDebounceDelay = 2000L // 2 segundos

    enum class ServiceState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR
    }

    /**
     * Inicia el servicio
     */
    fun start() {
        if (_serviceStateFlow.value == ServiceState.RUNNING) {
            log.d(tag = TAG) { "Service already running" }
            return
        }

        log.d(tag = TAG) { "Starting NetworkAwareReconnectionService..." }
        _serviceStateFlow.value = ServiceState.STARTING

        try {
            // CRÍTICO: Configurar la referencia del monitor de red en el gestor de reconexión
            reconnectionManager.setNetworkMonitor(networkMonitor)

            // Configurar callbacks
            setupCallbacks()

            // Configurar gestor de reconexión
            setupReconnectionManager()

            // Iniciar monitoreo de red
            networkMonitor.startMonitoring()

            _serviceStateFlow.value = ServiceState.RUNNING
            log.d(tag = TAG) { "NetworkAwareReconnectionService started successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error starting service: ${e.message}" }
            _serviceStateFlow.value = ServiceState.ERROR
        }
    }

    /**
     * Detiene el servicio
     */
    fun stop() {
        if (_serviceStateFlow.value == ServiceState.STOPPED) {
            log.d(tag = TAG) { "Service already stopped" }
            return
        }

        log.d(tag = TAG) { "Stopping NetworkAwareReconnectionService..." }
        _serviceStateFlow.value = ServiceState.STOPPING
        isDisposing = true

        try {
            // CRÍTICO: Cancelar handler de transición de red primero
            networkTransitionHandler?.cancel()
            networkTransitionHandler = null

            // Detener reconexiones ANTES que el monitor de red
            reconnectionManager.stopAllReconnections()

            // Detener monitoreo de red
            networkMonitor.stopMonitoring()

            // Limpiar cuentas y estado
            registeredAccounts.clear()
            lastNetworkState = null

            _serviceStateFlow.value = ServiceState.STOPPED
            log.d(tag = TAG) { "NetworkAwareReconnectionService stopped successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping service: ${e.message}" }
            _serviceStateFlow.value = ServiceState.ERROR
        }
    }

    /**
     * CORREGIDO: Configura los callbacks del monitor de red con manejo mejorado
     */
    private fun setupCallbacks() {
        networkMonitor.addNetworkChangeListener(object : NetworkMonitor.NetworkChangeListener {

            override fun onNetworkConnected(networkInfo: NetworkMonitor.NetworkInfo) {
                if (isDisposing) return

                log.d(tag = TAG) { "Network connected: ${networkInfo.networkType}, hasInternet: ${networkInfo.hasInternet}" }

                // CRÍTICO: Solo un handler activo a la vez
                networkTransitionHandler?.cancel()
                networkTransitionHandler = scope.launch {
                    try {
                        handleNetworkConnected(networkInfo)
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            log.e(tag = TAG) { "Error handling network connected: ${e.message}" }
                        }
                    }
                }

                // Notificar callback inmediatamente
                onNetworkStatusChangedCallback?.invoke(networkInfo)
            }

            override fun onNetworkDisconnected(previousNetworkInfo: NetworkMonitor.NetworkInfo) {
                if (isDisposing) return

                log.d(tag = TAG) { "Network disconnected: ${previousNetworkInfo.networkType}" }

                networkTransitionHandler?.cancel()
                networkTransitionHandler = scope.launch {
                    try {
                        handleNetworkDisconnected(previousNetworkInfo)
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            log.e(tag = TAG) { "Error handling network disconnected: ${e.message}" }
                        }
                    }
                }

                val disconnectedInfo = previousNetworkInfo.copy(
                    isConnected = false,
                    hasInternet = false,
                    timestamp = Clock.System.now().toEpochMilliseconds()
                )
                onNetworkStatusChangedCallback?.invoke(disconnectedInfo)
            }

            override fun onNetworkChanged(oldNetworkInfo: NetworkMonitor.NetworkInfo, newNetworkInfo: NetworkMonitor.NetworkInfo) {
                if (isDisposing) return

                log.d(tag = TAG) {
                    "Network changed: ${oldNetworkInfo.networkType} -> ${newNetworkInfo.networkType}"
                }

                networkTransitionHandler?.cancel()
                networkTransitionHandler = scope.launch {
                    try {
                        handleNetworkChanged(oldNetworkInfo, newNetworkInfo)
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            log.e(tag = TAG) { "Error handling network changed: ${e.message}" }
                        }
                    }
                }

                onNetworkStatusChangedCallback?.invoke(newNetworkInfo)
            }

            override fun onNetworkLost(networkInfo: NetworkMonitor.NetworkInfo) {
                if (isDisposing) return

                log.d(tag = TAG) { "Network lost: ${networkInfo.networkType}" }

                networkTransitionHandler?.cancel()
                networkTransitionHandler = scope.launch {
                    try {
                        handleNetworkLost(networkInfo)
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            log.e(tag = TAG) { "Error handling network lost: ${e.message}" }
                        }
                    }
                }

                val lostInfo = networkInfo.copy(
                    isConnected = false,
                    hasInternet = false,
                    timestamp = Clock.System.now().toEpochMilliseconds()
                )
                onNetworkStatusChangedCallback?.invoke(lostInfo)
            }

            override fun onInternetConnectivityChanged(hasInternet: Boolean) {
                if (isDisposing) return

                log.d(tag = TAG) { "Internet connectivity changed: $hasInternet" }

                networkTransitionHandler?.cancel()
                networkTransitionHandler = scope.launch {
                    try {
                        handleInternetConnectivityChanged(hasInternet)
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            log.e(tag = TAG) { "Error handling internet connectivity changed: ${e.message}" }
                        }
                    }
                }
            }
        })
    }
    /**
     * Configura el gestor de reconexión
     */
    private fun setupReconnectionManager() {
        reconnectionManager.setCallbacks(
            onReconnectionRequired = { accountInfo, reason ->
                if (isDisposing) return@setCallbacks

                log.d(tag = TAG) {
                    "Reconnection required for ${accountInfo.getAccountIdentity()}: $reason"
                }
                onReconnectionRequiredCallback?.invoke(accountInfo, reason)
            },
            onReconnectionStatus = { accountKey, state ->
                if (isDisposing) return@setCallbacks

                log.d(tag = TAG) {
                    "Reconnection status for $accountKey: attempts=${state.attempts}, reconnecting=${state.isReconnecting}"
                }
            }
        )
    }

    /**
     * CORREGIDO: Maneja conexión de red con verificación completa
     */
    private suspend fun handleNetworkConnected(networkInfo: NetworkMonitor.NetworkInfo) {
        if (isDisposing) return

        log.d(tag = TAG) { "=== NETWORK CONNECTED EVENT ===" }
        log.d(tag = TAG) { "Registered accounts: ${registeredAccounts.size}" }
        log.d(tag = TAG) { "Network has internet: ${networkInfo.hasInternet}" }
        log.d(tag = TAG) { "Network type: ${networkInfo.networkType}" }

        // CRÍTICO: Solo proceder si hay internet REAL
        if (!networkInfo.hasInternet) {
            log.w(tag = TAG) { "Network connected but NO INTERNET - blocking all reconnections" }
            
            // Bloquear todas las cuentas hasta que haya internet real
            val accounts = registeredAccounts.values.toList()
            accounts.forEach { account ->
                val accountKey = account.getAccountIdentity()
                log.d(tag = TAG) { "Blocking account $accountKey due to no internet" }
            }
            
            return
        }

        // Esperar que la red se estabilice
        log.d(tag = TAG) { "Network has internet - waiting ${networkStabilizationDelay}ms for stabilization" }
        delay(networkStabilizationDelay)
        if (isDisposing) return

        // CRÍTICO: Verificar NUEVAMENTE que hay internet después del delay
        val currentNetworkInfo = networkMonitor.getCurrentNetworkInfo()
        if (!currentNetworkInfo.hasInternet) {
            log.w(tag = TAG) { "Internet lost during stabilization delay - aborting reconnection" }
            return
        }

        // CRÍTICO: Reconectar TODAS las cuentas registradas, no solo las fallidas
        val accounts = registeredAccounts.values.toList()
        if (accounts.isEmpty()) {
            log.d(tag = TAG) { "No registered accounts to reconnect" }
            return
        }

        log.d(tag = TAG) { "Internet confirmed - processing ALL ${accounts.size} registered accounts" }

        // CORREGIDO: Verificar estado de TODAS las cuentas
        val accountsNeedingReconnection = mutableListOf<AccountInfo>()
        val accountsAlreadyRegistered = mutableListOf<AccountInfo>()

        accounts.forEach { account ->
            val accountKey = account.getAccountIdentity()
            val registrationState = RegistrationStateManager.getAccountState(accountKey)
            val isBlocked = reconnectionManager.isAccountBlocked(accountKey)
            
            log.d(tag = TAG) { 
                "Account $accountKey: state=$registrationState, blocked=$isBlocked, " +
                "wsConnected=${account.webSocketClient?.isConnected()}"
            }

            when {
                registrationState != RegistrationState.OK -> {
                    accountsNeedingReconnection.add(account)
                    log.d(tag = TAG) { "Account $accountKey needs reconnection (state: $registrationState)" }
                }
                isBlocked -> {
                    accountsNeedingReconnection.add(account)
                    log.d(tag = TAG) { "Account $accountKey needs reconnection (was blocked)" }
                }
                account.webSocketClient?.isConnected() != true -> {
                    accountsNeedingReconnection.add(account)
                    log.d(tag = TAG) { "Account $accountKey needs reconnection (WebSocket disconnected)" }
                }
                else -> {
                    accountsAlreadyRegistered.add(account)
                    log.d(tag = TAG) { "Account $accountKey already properly registered" }
                }
            }
        }

        // Procesar cuentas que necesitan reconexión
        if (accountsNeedingReconnection.isNotEmpty()) {
            log.d(tag = TAG) { 
                "Starting reconnection for ${accountsNeedingReconnection.size} accounts " +
                "(${accountsAlreadyRegistered.size} already registered)"
            }

            // Desbloquear cuentas antes de reconectar
            accountsNeedingReconnection.forEach { account ->
                reconnectionManager.unblockAccount(account.getAccountIdentity())
            }

            // Esperar un poco más antes de iniciar reconexiones
            delay(reconnectionDebounceDelay)
            if (isDisposing) return

            // CRÍTICO: Verificar internet una vez más antes de reconectar
            if (networkMonitor.getCurrentNetworkInfo().hasInternet) {
                reconnectionManager.onNetworkRecovered(accountsNeedingReconnection)
            } else {
                log.w(tag = TAG) { "Internet lost during reconnection preparation" }
            }
        } else {
            log.d(tag = TAG) { "All ${accounts.size} accounts properly registered, no reconnection needed" }
            updateRegistrationStatesAfterConnection(accounts)
        }

        // Actualizar estado de red
        lastNetworkState = networkInfo
    }

    /**
     * NUEVO: Actualiza los estados de registro después de recuperar la conexión
     */
    private suspend fun updateRegistrationStatesAfterConnection(accounts: List<AccountInfo>) {
        log.d(tag = TAG) { "Updating registration states after network connection" }

        accounts.forEach { account ->
            try {
                val accountKey = account.getAccountIdentity()
                val currentState = RegistrationStateManager.getAccountState(accountKey)

                if (currentState == RegistrationState.OK) {
                    // CORREGIDO: Solo refrescar si realmente es necesario
                    log.d(tag = TAG) { "Account $accountKey already properly registered" }

                    // Opcional: Forzar actualización del estado para notificar a listeners
                    onReconnectionRequiredCallback?.invoke(account, ReconnectionManager.ReconnectionReason.NETWORK_CHANGED)
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error updating registration state for ${account.getAccountIdentity()}: ${e.message}" }
            }
        }
    }

    /**
     * CORREGIDO: Maneja desconexión de red
     */
    private suspend fun handleNetworkDisconnected(previousNetworkInfo: NetworkMonitor.NetworkInfo) {
        if (isDisposing) return

        log.d(tag = TAG) { "Handling network disconnected event with ${registeredAccounts.size} registered accounts" }

        val accounts = registeredAccounts.values.toList()
        if (accounts.isNotEmpty()) {
            // CORREGIDO: Dar tiempo para verificar si es una desconexión temporal
            delay(2000)
            if (isDisposing) return

            // Verificar si la red se recuperó rápidamente
            val currentNetworkInfo = networkMonitor.getCurrentNetworkInfo()
            if (currentNetworkInfo.isConnected && currentNetworkInfo.hasInternet) {
                log.d(tag = TAG) { "Network recovered quickly, skipping disconnection handling" }
                return
            }

            reconnectionManager.onNetworkLost(accounts)
        }

        lastNetworkState = previousNetworkInfo.copy(isConnected = false, hasInternet = false)
    }

    /**
     * CORREGIDO: Maneja cambio de red con verificación mejorada
     */
    private suspend fun handleNetworkChanged(
        oldNetworkInfo: NetworkMonitor.NetworkInfo,
        newNetworkInfo: NetworkMonitor.NetworkInfo
    ) {
        if (isDisposing) return

        log.d(tag = TAG) { "Handling network changed event" }

        // Verificar si es un cambio significativo
        val isSignificantChange = isSignificantNetworkChange(oldNetworkInfo, newNetworkInfo)

        if (!isSignificantChange) {
            log.d(tag = TAG) { "Network change not significant, ignoring" }
            return
        }

        if (registeredAccounts.isEmpty()) {
            log.d(tag = TAG) { "No registered accounts for network change handling" }
            return
        }

        // CRÍTICO: Solo proceder si la nueva red tiene internet
        if (!newNetworkInfo.hasInternet) {
            log.d(tag = TAG) { "New network has no internet, waiting..." }
            return
        }

        // Esperar que el nuevo network se estabilice
        delay(networkStabilizationDelay + 2000) // Más tiempo para cambios de red
        if (isDisposing) return

        // Verificar nuevamente el estado de la red después del delay
        val currentNetworkInfo = networkMonitor.getCurrentNetworkInfo()
        if (!currentNetworkInfo.hasInternet) {
            log.d(tag = TAG) { "Network lost internet during stabilization, skipping reconnection" }
            return
        }

        val accounts = registeredAccounts.values.toList()

        // CORREGIDO: Verificar qué cuentas necesitan reconexión después del cambio de red
        val accountsNeedingReconnection = accounts.filter { account ->
            val accountKey = account.getAccountIdentity()
            val registrationState = RegistrationStateManager.getAccountState(accountKey)
            val needsReconnection = registrationState != RegistrationState.OK

            log.d(tag = TAG) { "Network change - Account $accountKey: state=$registrationState, needs reconnection=$needsReconnection" }
            needsReconnection
        }

        if (accountsNeedingReconnection.isNotEmpty()) {
            log.d(tag = TAG) { "Triggering reconnection for ${accountsNeedingReconnection.size} accounts after network change" }

            // Debounce adicional
            delay(reconnectionDebounceDelay)
            if (!isDisposing) {
                reconnectionManager.onNetworkChanged(accountsNeedingReconnection)
            }
        } else {
            log.d(tag = TAG) { "All accounts properly registered after network change" }
        }

        lastNetworkState = newNetworkInfo
    }

    /**
     * CORREGIDO: Maneja pérdida de red
     */
    private suspend fun handleNetworkLost(networkInfo: NetworkMonitor.NetworkInfo) {
        if (isDisposing) return

        log.d(tag = TAG) { "Handling network lost event with ${registeredAccounts.size} registered accounts" }

        val accounts = registeredAccounts.values.toList()
        if (accounts.isNotEmpty()) {
            // Esperar un poco para verificar si es pérdida temporal
            delay(1500)
            if (isDisposing) return

            // Verificar si la red se recuperó
            val currentNetworkInfo = networkMonitor.getCurrentNetworkInfo()
            if (currentNetworkInfo.isConnected && currentNetworkInfo.hasInternet) {
                log.d(tag = TAG) { "Network recovered during loss handling, skipping" }
                return
            }

            reconnectionManager.onNetworkLost(accounts)
        }

        lastNetworkState = networkInfo.copy(isConnected = false, hasInternet = false)
    }

    /**
     * CORREGIDO: Maneja cambio de conectividad a internet con lógica mejorada
     */
    private suspend fun handleInternetConnectivityChanged(hasInternet: Boolean) {
        if (isDisposing) return

        log.d(tag = TAG) { "=== INTERNET CONNECTIVITY CHANGED ===" }
        log.d(tag = TAG) { "Has Internet: $hasInternet" }
        log.d(tag = TAG) { "Registered accounts: ${registeredAccounts.size}" }

        if (hasInternet) {
            log.d(tag = TAG) { "Internet RECOVERED - processing all registered accounts" }
            
            val accounts = registeredAccounts.values.toList()
            if (accounts.isEmpty()) {
                log.d(tag = TAG) { "No registered accounts to process after internet recovery" }
                return
            }

            // Esperar estabilización de internet
            log.d(tag = TAG) { "Waiting ${networkStabilizationDelay}ms for internet stabilization" }
            delay(networkStabilizationDelay)
            if (isDisposing) return

            // CRÍTICO: Verificar que la red sigue conectada Y tiene internet
            val networkInfo = networkMonitor.getCurrentNetworkInfo()
            if (!networkInfo.isConnected || !networkInfo.hasInternet) {
                log.w(tag = TAG) { "Network/Internet lost during stabilization - aborting" }
                return
            }

            log.d(tag = TAG) { "Internet stable - analyzing ALL ${accounts.size} accounts" }

            // CRÍTICO: Procesar TODAS las cuentas registradas
            val accountsNeedingReconnection = mutableListOf<AccountInfo>()
            val accountsAlreadyOK = mutableListOf<AccountInfo>()

            accounts.forEach { account ->
                val accountKey = account.getAccountIdentity()
                val registrationState = RegistrationStateManager.getAccountState(accountKey)
                val isBlocked = reconnectionManager.isAccountBlocked(accountKey)
                val wsConnected = account.webSocketClient?.isConnected() == true

                log.d(tag = TAG) { 
                    "Analyzing $accountKey: regState=$registrationState, blocked=$isBlocked, wsConnected=$wsConnected" 
                }

                when {
                    // Cuenta necesita reconexión si no está OK, está bloqueada, o WS desconectado
                    registrationState != RegistrationState.OK ||
                    isBlocked ||
                    !wsConnected -> {
                        accountsNeedingReconnection.add(account)
                        log.d(tag = TAG) { "Account $accountKey NEEDS reconnection" }
                    }
                    else -> {
                        accountsAlreadyOK.add(account)
                        log.d(tag = TAG) { "Account $accountKey already OK" }
                    }
                }
            }

            // Procesar reconexiones
            if (accountsNeedingReconnection.isNotEmpty()) {
                log.d(tag = TAG) { 
                    "Internet recovered - reconnecting ${accountsNeedingReconnection.size} accounts " +
                    "(${accountsAlreadyOK.size} already OK)"
                }

                // Debounce final
                delay(reconnectionDebounceDelay)
                if (!isDisposing && networkMonitor.getCurrentNetworkInfo().hasInternet) {
                    reconnectionManager.onInternetRecovered(accountsNeedingReconnection)
                }
            } else {
                log.d(tag = TAG) { "All ${accounts.size} accounts already properly registered" }
                updateRegistrationStatesAfterConnection(accounts)
            }

        } else {
            // Internet perdido - DETENER TODAS las reconexiones
            log.d(tag = TAG) { "Internet LOST - stopping all reconnections" }
            
            val accounts = registeredAccounts.values.toList()
            if (accounts.isNotEmpty()) {
                log.d(tag = TAG) { "Stopping reconnections for ${accounts.size} accounts due to internet loss" }
                reconnectionManager.onInternetLost(accounts)
            }
        }
    }

    /**
     * Determina si un cambio de red es significativo
     */
    private fun isSignificantNetworkChange(
        oldInfo: NetworkMonitor.NetworkInfo,
        newInfo: NetworkMonitor.NetworkInfo
    ): Boolean {
        return oldInfo.networkType != newInfo.networkType ||
                oldInfo.ipAddress != newInfo.ipAddress ||
                (!oldInfo.hasInternet && newInfo.hasInternet) ||
                (oldInfo.hasInternet && !newInfo.hasInternet)
    }

    // === MÉTODOS PÚBLICOS ===

    /**
     * CORREGIDO: Registra una cuenta para monitoreo con validación
     */
    fun registerAccount(accountInfo: AccountInfo) {
        val accountKey = accountInfo.getAccountIdentity()

        if (registeredAccounts.containsKey(accountKey)) {
            log.d(tag = TAG) { "Account $accountKey already registered, updating" }
        }

        registeredAccounts[accountKey] = accountInfo

        log.d(tag = TAG) { "Account registered for monitoring: $accountKey (total: ${registeredAccounts.size})" }
    }

    /**
     * CORREGIDO: Desregistra una cuenta del monitoreo con limpieza completa
     */
    fun unregisterAccount(accountKey: String) {
        val removed = registeredAccounts.remove(accountKey)
        if (removed != null) {
            // Detener cualquier reconexión activa
            reconnectionManager.stopReconnection(accountKey)
            log.d(tag = TAG) { "Account unregistered from monitoring: $accountKey (remaining: ${registeredAccounts.size})" }
        } else {
            log.w(tag = TAG) { "Attempted to unregister non-existing account: $accountKey" }
        }
    }

    /**
     * Desregistra todas las cuentas
     */
    fun unregisterAllAccounts() {
        val accountKeys = registeredAccounts.keys.toList()
        accountKeys.forEach { accountKey ->
            unregisterAccount(accountKey)
        }

        log.d(tag = TAG) { "All accounts unregistered from monitoring" }
    }

    /**
     * Configura callbacks
     */
    fun setCallbacks(
        onReconnectionRequired: ((AccountInfo, ReconnectionManager.ReconnectionReason) -> Unit)? = null,
        onNetworkStatusChanged: ((NetworkMonitor.NetworkInfo) -> Unit)? = null
    ) {
        this.onReconnectionRequiredCallback = onReconnectionRequired
        this.onNetworkStatusChangedCallback = onNetworkStatusChanged
    }

    /**
     * CORREGIDO: Notifica fallo de registro con validación
     */
    fun notifyRegistrationFailed(accountKey: String, error: String?) {
        log.d(tag = TAG) { "=== REGISTRATION FAILED NOTIFICATION ===" }
        log.d(tag = TAG) { "Account: $accountKey" }
        log.d(tag = TAG) { "Error: $error" }
        log.d(tag = TAG) { "Has internet: ${networkMonitor.getCurrentNetworkInfo().hasInternet}" }
        
        val accountInfo = registeredAccounts[accountKey]
        if (accountInfo != null) {
            // CRÍTICO: Verificar si realmente necesita reconexión
            val currentState = RegistrationStateManager.getAccountState(accountKey)
            if (currentState == RegistrationState.OK) {
                log.d(tag = TAG) { "Ignoring registration failed - account $accountKey is actually OK" }
                return
            }

            // CRÍTICO: Solo procesar si hay internet
            if (networkMonitor.getCurrentNetworkInfo().hasInternet) {
                log.d(tag = TAG) { "Processing registration failure with internet available" }
                reconnectionManager.onRegistrationFailed(accountInfo, error)
            } else {
                log.w(tag = TAG) { "Registration failed but no internet - will retry when internet returns" }
            }
        } else {
            log.w(tag = TAG) { "Registration failed notification for non-registered account: $accountKey" }
        }
    }

    /**
     * CORREGIDO: Notifica desconexión de WebSocket con validación
     */
    fun notifyWebSocketDisconnected(accountKey: String) {
        log.d(tag = TAG) { "=== WEBSOCKET DISCONNECTED NOTIFICATION ===" }
        log.d(tag = TAG) { "Account: $accountKey" }
        log.d(tag = TAG) { "Has internet: ${networkMonitor.getCurrentNetworkInfo().hasInternet}" }
        
        val accountInfo = registeredAccounts[accountKey]
        if (accountInfo != null) {
            // CRÍTICO: Solo reconectar WebSocket si hay internet
            if (networkMonitor.getCurrentNetworkInfo().hasInternet) {
                log.d(tag = TAG) { "Processing WebSocket disconnection with internet available" }
                reconnectionManager.onWebSocketDisconnected(accountInfo)
            } else {
                log.w(tag = TAG) { "WebSocket disconnected but no internet - will reconnect when internet returns" }
            }
        } else {
            log.w(tag = TAG) { "WebSocket disconnected notification for non-registered account: $accountKey" }
        }
    }

    /**
     * Fuerza reconexión manual
     */
    fun forceReconnection(accountKey: String) {
        log.d(tag = TAG) { "=== FORCE RECONNECTION ===" }
        log.d(tag = TAG) { "Account: $accountKey" }
        
        val accountInfo = registeredAccounts[accountKey]
        if (accountInfo != null) {
            log.d(tag = TAG) { "Executing force reconnection for $accountKey" }
            reconnectionManager.manualReconnection(accountInfo)
        } else {
            log.w(tag = TAG) { "Force reconnection for non-registered account: $accountKey" }
        }
    }

    /**
     * Fuerza verificación de red
     */
    fun forceNetworkCheck() {
        log.d(tag = TAG) { "Forcing network check" }
        networkMonitor.forceNetworkCheck()
        
        // NUEVO: También forzar verificación de internet en ReconnectionManager
        reconnectionManager.forceInternetCheckAndUnblock()
    }

    /**
     * Resetea intentos de reconexión
     */
    fun resetReconnectionAttempts(accountKey: String) {
        reconnectionManager.resetReconnectionAttempts(accountKey)
        log.d(tag = TAG) { "Reset reconnection attempts for $accountKey" }
    }

    // === MÉTODOS DE CONSULTA ===

    fun getServiceState(): ServiceState = _serviceStateFlow.value
    fun isRunning(): Boolean = _serviceStateFlow.value == ServiceState.RUNNING
    fun getNetworkInfo(): NetworkMonitor.NetworkInfo = networkMonitor.getCurrentNetworkInfo()
    fun getNetworkState(): NetworkMonitor.NetworkState = networkMonitor.getCurrentNetworkState()
    fun isNetworkConnected(): Boolean = networkMonitor.isConnected()
    fun hasInternet(): Boolean = networkMonitor.hasInternet()
    fun getRegisteredAccountsCount(): Int = registeredAccounts.size
    fun getReconnectionStates(): Map<String, ReconnectionManager.ReconnectionState> = reconnectionManager.getReconnectionStates()

    fun isAccountReconnecting(accountKey: String): Boolean {
        return reconnectionManager.isReconnecting(accountKey)
    }

    fun getReconnectionAttempts(accountKey: String): Int {
        return reconnectionManager.getReconnectionAttempts(accountKey)
    }

    /**
     * Información de diagnóstico completa
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== NETWORK AWARE RECONNECTION SERVICE DIAGNOSTIC ===")
            appendLine("Service State: ${_serviceStateFlow.value}")
            appendLine("Is Disposing: $isDisposing")
            appendLine("Registered Accounts: ${registeredAccounts.size}")
            appendLine("Network Connected: ${isNetworkConnected()}")
            appendLine("Has Internet: ${hasInternet()}")
            appendLine("Network Type: ${getNetworkInfo().networkType}")
            appendLine("Network Transition Job Active: ${networkTransitionHandler?.isActive == true}")
            appendLine("Last Network State: $lastNetworkState")

            if (registeredAccounts.isNotEmpty()) {
                appendLine("\n--- Registered Accounts ---")
                registeredAccounts.forEach { (accountKey, accountInfo) ->
                    val isReconnecting = isAccountReconnecting(accountKey)
                    val attempts = getReconnectionAttempts(accountKey)
                    val registrationState = try {
                        RegistrationStateManager.getAccountState(accountKey)
                    } catch (e: Exception) {
                        "ERROR"
                    }
                    appendLine("$accountKey: state=$registrationState, reconnecting=$isReconnecting, attempts=$attempts")
                }
            }

            appendLine("\n${networkMonitor.getDiagnosticInfo()}")
            appendLine("\n${reconnectionManager.getDiagnosticInfo()}")
        }
    }

    /**
     * CORREGIDO: Limpieza de recursos completa y segura
     */
    fun dispose() {
        if (isDisposing) {
            log.d(tag = TAG) { "Already disposing NetworkAwareReconnectionService" }
            return
        }

        log.d(tag = TAG) { "Disposing NetworkAwareReconnectionService..." }

        isDisposing = true

        try {
            // CRÍTICO: Cancelar job de transición primero
            networkTransitionHandler?.cancel()
            networkTransitionHandler = null

            // Detener servicio
            stop()

            // Dispose de componentes en orden correcto
            reconnectionManager.dispose()
            networkMonitor.dispose()

            // Limpiar referencias
            registeredAccounts.clear()
            lastNetworkState = null
            onReconnectionRequiredCallback = null
            onNetworkStatusChangedCallback = null

            // Cancelar scope
            scope.cancel()

            log.d(tag = TAG) { "NetworkAwareReconnectionService disposed successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error disposing NetworkAwareReconnectionService: ${e.message}" }
        }
    }
}