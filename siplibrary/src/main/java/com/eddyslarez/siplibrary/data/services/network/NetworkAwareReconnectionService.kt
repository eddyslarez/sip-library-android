package com.eddyslarez.siplibrary.data.services.network

import android.app.Application
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Servicio que combina monitoreo de red con reconexión automática
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
    private val registeredAccounts = mutableMapOf<String, AccountInfo>()
    
    // Callbacks
    private var onReconnectionRequiredCallback: ((AccountInfo, ReconnectionManager.ReconnectionReason) -> Unit)? = null
    private var onNetworkStatusChangedCallback: ((NetworkMonitor.NetworkInfo) -> Unit)? = null
    
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
        
        try {
            // Detener reconexiones
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
     * Configura los callbacks
     */
    private fun setupCallbacks() {
        // Callback del monitor de red
        networkMonitor.addNetworkChangeListener(object : NetworkMonitor.NetworkChangeListener {
            
            override fun onNetworkConnected(networkInfo: NetworkMonitor.NetworkInfo) {
                log.d(tag = TAG) { "Network connected: ${networkInfo.networkType}" }
                
                scope.launch {
                    handleNetworkConnected(networkInfo)
                }
                
                onNetworkStatusChangedCallback?.invoke(networkInfo)
            }
            
            override fun onNetworkDisconnected(previousNetworkInfo: NetworkMonitor.NetworkInfo) {
                log.d(tag = TAG) { "Network disconnected: ${previousNetworkInfo.networkType}" }
                
                scope.launch {
                    handleNetworkDisconnected(previousNetworkInfo)
                }
                
                val disconnectedInfo = previousNetworkInfo.copy(
                    isConnected = false,
                    hasInternet = false,
                    timestamp = Clock.System.now().toEpochMilliseconds()
                )
                onNetworkStatusChangedCallback?.invoke(disconnectedInfo)
            }
            
            override fun onNetworkChanged(oldNetworkInfo: NetworkMonitor.NetworkInfo, newNetworkInfo: NetworkMonitor.NetworkInfo) {
                log.d(tag = TAG) { 
                    "Network changed: ${oldNetworkInfo.networkType} -> ${newNetworkInfo.networkType}" 
                }
                
                scope.launch {
                    handleNetworkChanged(oldNetworkInfo, newNetworkInfo)
                }
                
                onNetworkStatusChangedCallback?.invoke(newNetworkInfo)
            }
            
            override fun onNetworkLost(networkInfo: NetworkMonitor.NetworkInfo) {
                log.d(tag = TAG) { "Network lost: ${networkInfo.networkType}" }
                
                scope.launch {
                    handleNetworkLost(networkInfo)
                }
                
                val lostInfo = networkInfo.copy(
                    isConnected = false,
                    hasInternet = false,
                    timestamp = Clock.System.now().toEpochMilliseconds()
                )
                onNetworkStatusChangedCallback?.invoke(lostInfo)
            }
            
            override fun onInternetConnectivityChanged(hasInternet: Boolean) {
                log.d(tag = TAG) { "Internet connectivity changed: $hasInternet" }
                
                scope.launch {
                    handleInternetConnectivityChanged(hasInternet)
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
                log.d(tag = TAG) { 
                    "Reconnection required for ${accountInfo.getAccountIdentity()}: $reason" 
                }
                onReconnectionRequiredCallback?.invoke(accountInfo, reason)
            },
            onReconnectionStatus = { accountKey, state ->
                log.d(tag = TAG) { 
                    "Reconnection status for $accountKey: attempts=${state.attempts}, reconnecting=${state.isReconnecting}" 
                }
            }
        )
    }
    
    /**
     * Maneja conexión de red
     */
    private suspend fun handleNetworkConnected(networkInfo: NetworkMonitor.NetworkInfo) {
        log.d(tag = TAG) { "Handling network connected event" }
        
        if (registeredAccounts.isNotEmpty()) {
            val accounts = registeredAccounts.values.toList()
            reconnectionManager.onNetworkRecovered(accounts)
        }
    }
    
    /**
     * Maneja desconexión de red
     */
    private suspend fun handleNetworkDisconnected(previousNetworkInfo: NetworkMonitor.NetworkInfo) {
        log.d(tag = TAG) { "Handling network disconnected event" }
        
        if (registeredAccounts.isNotEmpty()) {
            val accounts = registeredAccounts.values.toList()
            reconnectionManager.onNetworkLost(accounts)
        }
    }
    
    /**
     * Maneja cambio de red
     */
    private suspend fun handleNetworkChanged(
        oldNetworkInfo: NetworkMonitor.NetworkInfo, 
        newNetworkInfo: NetworkMonitor.NetworkInfo
    ) {
        log.d(tag = TAG) { "Handling network changed event" }
        
        // Verificar si es un cambio significativo
        val isSignificantChange = isSignificantNetworkChange(oldNetworkInfo, newNetworkInfo)
        
        if (isSignificantChange && registeredAccounts.isNotEmpty()) {
            log.d(tag = TAG) { "Significant network change detected, triggering reconnection" }
            
            val accounts = registeredAccounts.values.toList()
            reconnectionManager.onNetworkChanged(accounts)
        }
    }
    
    /**
     * Maneja pérdida de red
     */
    private suspend fun handleNetworkLost(networkInfo: NetworkMonitor.NetworkInfo) {
        log.d(tag = TAG) { "Handling network lost event" }
        
        if (registeredAccounts.isNotEmpty()) {
            val accounts = registeredAccounts.values.toList()
            reconnectionManager.onNetworkLost(accounts)
        }
    }
    
    /**
     * Maneja cambio de conectividad a internet
     */
    private suspend fun handleInternetConnectivityChanged(hasInternet: Boolean) {
        log.d(tag = TAG) { "Handling internet connectivity changed: $hasInternet" }
        
        if (hasInternet && registeredAccounts.isNotEmpty()) {
            // Internet recuperado, verificar reconexiones
            val accounts = registeredAccounts.values.toList()
            reconnectionManager.onNetworkRecovered(accounts)
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
     * Registra una cuenta para monitoreo
     */
    fun registerAccount(accountInfo: AccountInfo) {
        val accountKey = accountInfo.getAccountIdentity()
        registeredAccounts[accountKey] = accountInfo
        
        log.d(tag = TAG) { "Account registered for monitoring: $accountKey" }
    }
    
    /**
     * Desregistra una cuenta del monitoreo
     */
    fun unregisterAccount(accountKey: String) {
        registeredAccounts.remove(accountKey)
        reconnectionManager.stopReconnection(accountKey)
        
        log.d(tag = TAG) { "Account unregistered from monitoring: $accountKey" }
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
     * Notifica fallo de registro
     */
    fun notifyRegistrationFailed(accountKey: String, error: String?) {
        val accountInfo = registeredAccounts[accountKey]
        if (accountInfo != null) {
            reconnectionManager.onRegistrationFailed(accountInfo, error)
        }
    }
    
    /**
     * Notifica desconexión de WebSocket
     */
    fun notifyWebSocketDisconnected(accountKey: String) {
        val accountInfo = registeredAccounts[accountKey]
        if (accountInfo != null) {
            reconnectionManager.onWebSocketDisconnected(accountInfo)
        }
    }
    
    /**
     * Fuerza reconexión manual
     */
    fun forceReconnection(accountKey: String) {
        val accountInfo = registeredAccounts[accountKey]
        if (accountInfo != null) {
            reconnectionManager.manualReconnection(accountInfo)
        }
    }
    
    /**
     * Fuerza verificación de red
     */
    fun forceNetworkCheck() {
        networkMonitor.forceNetworkCheck()
    }
    
    /**
     * Resetea intentos de reconexión
     */
    fun resetReconnectionAttempts(accountKey: String) {
        reconnectionManager.resetReconnectionAttempts(accountKey)
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
            appendLine("Registered Accounts: ${registeredAccounts.size}")
            appendLine("Network Connected: ${isNetworkConnected()}")
            appendLine("Has Internet: ${hasInternet()}")
            appendLine("Network Type: ${getNetworkInfo().networkType}")
            
            if (registeredAccounts.isNotEmpty()) {
                appendLine("\n--- Registered Accounts ---")
                registeredAccounts.forEach { (accountKey, accountInfo) ->
                    val isReconnecting = isAccountReconnecting(accountKey)
                    val attempts = getReconnectionAttempts(accountKey)
                    appendLine("$accountKey: reconnecting=$isReconnecting, attempts=$attempts")
                }
            }
            
            appendLine("\n${networkMonitor.getDiagnosticInfo()}")
            appendLine("\n${reconnectionManager.getDiagnosticInfo()}")
        }
    }
    
    /**
     * Limpieza de recursos
     */
    fun dispose() {
        stop()
        networkMonitor.dispose()
        reconnectionManager.dispose()
        log.d(tag = TAG) { "NetworkAwareReconnectionService disposed" }
    }
}