package com.eddyslarez.siplibrary.data.services.network

import android.app.Application
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.models.RegistrationState
import com.eddyslarez.siplibrary.utils.RegistrationStateManager
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Servicio que combina monitoreo de red con reconexión automática - CORREGIDO
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

    // Variables de control
    private var isDisposing = false

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
            // Configurar callbacks
            setupCallbacks()

            // Iniciar monitoreo de red
            networkMonitor.startMonitoring()

            // Configurar gestor de reconexión
            setupReconnectionManager()

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
            // Detener reconexiones PRIMERO
            reconnectionManager.stopAllReconnections()

            // Detener monitoreo de red
            networkMonitor.stopMonitoring()

            // Limpiar cuentas
            registeredAccounts.clear()

            _serviceStateFlow.value = ServiceState.STOPPED
            log.d(tag = TAG) { "NetworkAwareReconnectionService stopped successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping service: ${e.message}" }
            _serviceStateFlow.value = ServiceState.ERROR
        }
    }

    /**
     * Configura los callbacks del monitor de red
     */
    private fun setupCallbacks() {
        networkMonitor.addNetworkChangeListener(object : NetworkMonitor.NetworkChangeListener {

            override fun onNetworkConnected(networkInfo: NetworkMonitor.NetworkInfo) {
                if (isDisposing) return

                log.d(tag = TAG) { "Network connected: ${networkInfo.networkType}" }

                scope.launch {
                    try {
                        handleNetworkConnected(networkInfo)
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error handling network connected: ${e.message}" }
                    }
                }

                onNetworkStatusChangedCallback?.invoke(networkInfo)
            }

            override fun onNetworkDisconnected(previousNetworkInfo: NetworkMonitor.NetworkInfo) {
                if (isDisposing) return

                log.d(tag = TAG) { "Network disconnected: ${previousNetworkInfo.networkType}" }

                scope.launch {
                    try {
                        handleNetworkDisconnected(previousNetworkInfo)
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error handling network disconnected: ${e.message}" }
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

                scope.launch {
                    try {
                        handleNetworkChanged(oldNetworkInfo, newNetworkInfo)
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error handling network changed: ${e.message}" }
                    }
                }

                onNetworkStatusChangedCallback?.invoke(newNetworkInfo)
            }

            override fun onNetworkLost(networkInfo: NetworkMonitor.NetworkInfo) {
                if (isDisposing) return

                log.d(tag = TAG) { "Network lost: ${networkInfo.networkType}" }

                scope.launch {
                    try {
                        handleNetworkLost(networkInfo)
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error handling network lost: ${e.message}" }
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

                scope.launch {
                    try {
                        handleInternetConnectivityChanged(hasInternet)
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error handling internet connectivity changed: ${e.message}" }
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
     * CORREGIDO: Maneja conexión de red
     */
    private suspend fun handleNetworkConnected(networkInfo: NetworkMonitor.NetworkInfo) {
        if (isDisposing) return

        log.d(tag = TAG) { "Handling network connected event with ${registeredAccounts.size} registered accounts" }

        if (registeredAccounts.isNotEmpty()) {
            // CRÍTICO: Esperar que la red se estabilice antes de iniciar reconexiones
            delay(2000)

            if (!networkInfo.hasInternet) {
                log.d(tag = TAG) { "Network connected but no internet, waiting..." }
                return
            }

            val accounts = registeredAccounts.values.toList()

            // Verificar qué cuentas necesitan reconexión
            val accountsNeedingReconnection = accounts.filter { account ->
                val accountKey = account.getAccountIdentity()
                val registrationState = RegistrationStateManager.getAccountState(accountKey)

                val needsReconnection = registrationState != RegistrationState.OK
                log.d(tag = TAG) { "Account $accountKey: state=$registrationState, needsReconnection=$needsReconnection" }

                needsReconnection
            }

            if (accountsNeedingReconnection.isNotEmpty()) {
                log.d(tag = TAG) { "Network recovered - reconnecting ${accountsNeedingReconnection.size} accounts" }
                reconnectionManager.onNetworkRecovered(accountsNeedingReconnection)
            } else {
                log.d(tag = TAG) { "All accounts properly registered, no reconnection needed" }

                // NUEVO: Actualizar estados de registro para las cuentas ya conectadas
                updateRegistrationStatesAfterConnection(accounts)
            }
        }
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
                    // Forzar actualización del estado para notificar a listeners
                    log.d(tag = TAG) { "Refreshing registration state for $accountKey" }

                    // Esto debería disparar las notificaciones correspondientes
                    onReconnectionRequiredCallback?.invoke(account, ReconnectionManager.ReconnectionReason.NETWORK_CHANGED)
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error updating registration state for ${account.getAccountIdentity()}: ${e.message}" }
            }
        }
    }

    /**
     * Maneja desconexión de red
     */
    private suspend fun handleNetworkDisconnected(previousNetworkInfo: NetworkMonitor.NetworkInfo) {
        if (isDisposing) return

        log.d(tag = TAG) { "Handling network disconnected event with ${registeredAccounts.size} registered accounts" }

        if (registeredAccounts.isNotEmpty()) {
            val accounts = registeredAccounts.values.toList()
            reconnectionManager.onNetworkLost(accounts)
        }
    }

    /**
     * CORREGIDO: Maneja cambio de red
     */
    private suspend fun handleNetworkChanged(
        oldNetworkInfo: NetworkMonitor.NetworkInfo,
        newNetworkInfo: NetworkMonitor.NetworkInfo
    ) {
        if (isDisposing) return

        log.d(tag = TAG) { "Handling network changed event" }

        // Verificar si es un cambio significativo
        val isSignificantChange = isSignificantNetworkChange(oldNetworkInfo, newNetworkInfo)

        if (isSignificantChange && registeredAccounts.isNotEmpty()) {
            log.d(tag = TAG) { "Significant network change detected, evaluating reconnection needs" }

            // Esperar que el nuevo network se estabilice
            delay(3000)

            if (!newNetworkInfo.hasInternet) {
                log.d(tag = TAG) { "New network has no internet, skipping reconnection" }
                return
            }

            val accounts = registeredAccounts.values.toList()

            // Verificar qué cuentas necesitan reconexión después del cambio de red
            val accountsNeedingReconnection = accounts.filter { account ->
                val accountKey = account.getAccountIdentity()
                val registrationState = RegistrationStateManager.getAccountState(accountKey)
                registrationState != RegistrationState.OK
            }

            if (accountsNeedingReconnection.isNotEmpty()) {
                log.d(tag = TAG) { "Triggering reconnection for ${accountsNeedingReconnection.size} accounts after network change" }
                reconnectionManager.onNetworkChanged(accountsNeedingReconnection)
            } else {
                log.d(tag = TAG) { "All accounts properly registered after network change" }
            }
        }
    }

    /**
     * Maneja pérdida de red
     */
    private suspend fun handleNetworkLost(networkInfo: NetworkMonitor.NetworkInfo) {
        if (isDisposing) return

        log.d(tag = TAG) { "Handling network lost event with ${registeredAccounts.size} registered accounts" }

        if (registeredAccounts.isNotEmpty()) {
            val accounts = registeredAccounts.values.toList()
            reconnectionManager.onNetworkLost(accounts)
        }
    }

    /**
     * CORREGIDO: Maneja cambio de conectividad a internet
     */
    private suspend fun handleInternetConnectivityChanged(hasInternet: Boolean) {
        if (isDisposing) return

        log.d(tag = TAG) { "Handling internet connectivity changed: $hasInternet with ${registeredAccounts.size} registered accounts" }

        if (hasInternet && registeredAccounts.isNotEmpty()) {
            // Internet recuperado, esperar estabilización
            delay(1500)

            val accounts = registeredAccounts.values.toList()

            // Verificar qué cuentas necesitan reconexión
            val accountsNeedingReconnection = accounts.filter { account ->
                val accountKey = account.getAccountIdentity()
                val registrationState = RegistrationStateManager.getAccountState(accountKey)

                val needsReconnection = registrationState != RegistrationState.OK
                log.d(tag = TAG) { "Internet recovered - Account $accountKey: state=$registrationState, needs reconnection=$needsReconnection" }

                needsReconnection
            }

            if (accountsNeedingReconnection.isNotEmpty()) {
                log.d(tag = TAG) { "Internet recovered - reconnecting ${accountsNeedingReconnection.size} accounts" }
                reconnectionManager.onNetworkRecovered(accountsNeedingReconnection)
            } else {
                log.d(tag = TAG) { "Internet recovered - all accounts properly registered" }

                // NUEVO: Actualizar estados para cuentas ya registradas
                updateRegistrationStatesAfterConnection(accounts)
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
     * CORREGIDO: Registra una cuenta para monitoreo
     */
    fun registerAccount(accountInfo: AccountInfo) {
        val accountKey = accountInfo.getAccountIdentity()
        registeredAccounts[accountKey] = accountInfo

        log.d(tag = TAG) { "Account registered for monitoring: $accountKey (total: ${registeredAccounts.size})" }
    }

    /**
     * CORREGIDO: Desregistra una cuenta del monitoreo
     */
    fun unregisterAccount(accountKey: String) {
        val removed = registeredAccounts.remove(accountKey)
        if (removed != null) {
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
     * CORREGIDO: Notifica fallo de registro
     */
    fun notifyRegistrationFailed(accountKey: String, error: String?) {
        val accountInfo = registeredAccounts[accountKey]
        if (accountInfo != null) {
            log.d(tag = TAG) { "Registration failed notification for $accountKey: $error" }
            reconnectionManager.onRegistrationFailed(accountInfo, error)
        } else {
            log.w(tag = TAG) { "Registration failed notification for non-registered account: $accountKey" }
        }
    }

    /**
     * CORREGIDO: Notifica desconexión de WebSocket
     */
    fun notifyWebSocketDisconnected(accountKey: String) {
        val accountInfo = registeredAccounts[accountKey]
        if (accountInfo != null) {
            log.d(tag = TAG) { "WebSocket disconnected notification for $accountKey" }
            reconnectionManager.onWebSocketDisconnected(accountInfo)
        } else {
            log.w(tag = TAG) { "WebSocket disconnected notification for non-registered account: $accountKey" }
        }
    }

    /**
     * Fuerza reconexión manual
     */
    fun forceReconnection(accountKey: String) {
        val accountInfo = registeredAccounts[accountKey]
        if (accountInfo != null) {
            log.d(tag = TAG) { "Force reconnection for $accountKey" }
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
     * CORREGIDO: Limpieza de recursos
     */
    fun dispose() {
        if (isDisposing) {
            log.d(tag = TAG) { "Already disposing NetworkAwareReconnectionService" }
            return
        }

        log.d(tag = TAG) { "Disposing NetworkAwareReconnectionService..." }

        isDisposing = true

        try {
            stop()

            // Dispose de componentes
            networkMonitor.dispose()
            reconnectionManager.dispose()

            // Cancelar scope
            scope.cancel()

            log.d(tag = TAG) { "NetworkAwareReconnectionService disposed successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error disposing NetworkAwareReconnectionService: ${e.message}" }
        }
    }
}