package com.eddyslarez.siplibrary.data.services.network

import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.models.RegistrationState
import com.eddyslarez.siplibrary.utils.RegistrationStateManager
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Gestor de reconexión automática para cuentas SIP
 * 
 * @author Eddys Larez
 */
class ReconnectionManager {
    
    private val TAG = "ReconnectionManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Estados de reconexión
    private val _reconnectionStateFlow = MutableStateFlow<Map<String, ReconnectionState>>(emptyMap())
    val reconnectionStateFlow: StateFlow<Map<String, ReconnectionState>> = _reconnectionStateFlow.asStateFlow()
    
    // Jobs de reconexión por cuenta
    private val reconnectionJobs = ConcurrentHashMap<String, Job>()
    
    // Configuración de reconexión
    private val maxReconnectionAttempts = 15
    private val baseReconnectionDelay = 2000L // 2 segundos
    private val maxReconnectionDelay = 60000L // 1 minuto
    private val networkChangeDelay = 3000L // 3 segundos después de cambio de red
    
    // Callbacks
    private var onReconnectionRequiredCallback: ((AccountInfo, ReconnectionReason) -> Unit)? = null
    private var onReconnectionStatusCallback: ((String, ReconnectionState) -> Unit)? = null
    
    data class ReconnectionState(
        val accountKey: String,
        val isReconnecting: Boolean = false,
        val attempts: Int = 0,
        val maxAttempts: Int = 15,
        val lastAttemptTime: Long = 0L,
        val nextAttemptTime: Long = 0L,
        val reason: ReconnectionReason = ReconnectionReason.UNKNOWN,
        val lastError: String? = null,
        val backoffDelay: Long = 2000L,
        val isNetworkAvailable: Boolean = true,
        val timestamp: Long = Clock.System.now().toEpochMilliseconds()
    )
    
    enum class ReconnectionReason {
        NETWORK_LOST,
        NETWORK_CHANGED,
        WEBSOCKET_DISCONNECTED,
        REGISTRATION_FAILED,
        REGISTRATION_EXPIRED,
        AUTHENTICATION_FAILED,
        SERVER_ERROR,
        TIMEOUT,
        MANUAL_TRIGGER,
        UNKNOWN
    }
    
    /**
     * Configura callbacks
     */
    fun setCallbacks(
        onReconnectionRequired: ((AccountInfo, ReconnectionReason) -> Unit)? = null,
        onReconnectionStatus: ((String, ReconnectionState) -> Unit)? = null
    ) {
        this.onReconnectionRequiredCallback = onReconnectionRequired
        this.onReconnectionStatusCallback = onReconnectionStatus
    }
    
    /**
     * Inicia reconexión para una cuenta específica
     */
    fun startReconnection(
        accountInfo: AccountInfo,
        reason: ReconnectionReason,
        isNetworkAvailable: Boolean = true
    ) {
        val accountKey = accountInfo.getAccountIdentity()
        
        log.d(tag = TAG) { 
            "Starting reconnection for $accountKey, reason: $reason, network available: $isNetworkAvailable" 
        }
        
        // Cancelar reconexión existente si hay una
        stopReconnection(accountKey)
        
        // Crear estado inicial de reconexión
        val initialState = ReconnectionState(
            accountKey = accountKey,
            isReconnecting = true,
            attempts = 0,
            reason = reason,
            isNetworkAvailable = isNetworkAvailable,
            backoffDelay = baseReconnectionDelay
        )
        
        updateReconnectionState(accountKey, initialState)
        
        // Iniciar job de reconexión
        val reconnectionJob = scope.launch {
            executeReconnectionLoop(accountInfo, reason, isNetworkAvailable)
        }
        
        reconnectionJobs[accountKey] = reconnectionJob
    }
    
    /**
     * Ejecuta el bucle de reconexión
     */
    private suspend fun executeReconnectionLoop(
        accountInfo: AccountInfo,
        reason: ReconnectionReason,
        isNetworkAvailable: Boolean
    ) {
        val accountKey = accountInfo.getAccountIdentity()
        var currentState = getReconnectionState(accountKey) ?: return
        
        try {
            while (currentState.attempts < maxReconnectionAttempts && currentState.isReconnecting) {
                
                // Esperar si la red no está disponible
                if (!isNetworkAvailable) {
                    log.d(tag = TAG) { "Network not available for $accountKey, waiting..." }
                    delay(5000) // Esperar 5 segundos
                    currentState = getReconnectionState(accountKey) ?: break
                    continue
                }
                
                // Calcular delay con backoff exponencial
                val delay = calculateBackoffDelay(currentState.attempts)
                val nextAttemptTime = Clock.System.now().toEpochMilliseconds() + delay
                
                // Actualizar estado antes del intento
                currentState = currentState.copy(
                    attempts = currentState.attempts + 1,
                    nextAttemptTime = nextAttemptTime,
                    backoffDelay = delay,
                    timestamp = Clock.System.now().toEpochMilliseconds()
                )
                updateReconnectionState(accountKey, currentState)
                
                log.d(tag = TAG) { 
                    "Reconnection attempt ${currentState.attempts}/$maxReconnectionAttempts for $accountKey in ${delay}ms" 
                }
                
                // Esperar antes del intento
                delay(delay)
                
                // Verificar si la reconexión fue cancelada
                val latestState = getReconnectionState(accountKey)
                if (latestState == null || !latestState.isReconnecting) {
                    log.d(tag = TAG) { "Reconnection cancelled for $accountKey" }
                    break
                }
                
                // Actualizar tiempo del último intento
                currentState = currentState.copy(
                    lastAttemptTime = Clock.System.now().toEpochMilliseconds(),
                    timestamp = Clock.System.now().toEpochMilliseconds()
                )
                updateReconnectionState(accountKey, currentState)
                
                // Ejecutar intento de reconexión
                val success = executeReconnectionAttempt(accountInfo, reason)
                
                if (success) {
                    log.d(tag = TAG) { "Reconnection successful for $accountKey on attempt ${currentState.attempts}" }
                    
                    // Marcar como exitosa
                    val successState = currentState.copy(
                        isReconnecting = false,
                        lastError = null,
                        timestamp = Clock.System.now().toEpochMilliseconds()
                    )
                    updateReconnectionState(accountKey, successState)
                    
                    // Limpiar estado después de un tiempo
                    delay(10000) // 10 segundos
                    clearReconnectionState(accountKey)
                    break
                    
                } else {
                    log.d(tag = TAG) { "Reconnection attempt ${currentState.attempts} failed for $accountKey" }
                    
                    // Actualizar estado con error
                    currentState = currentState.copy(
                        lastError = "Reconnection attempt failed",
                        timestamp = Clock.System.now().toEpochMilliseconds()
                    )
                    updateReconnectionState(accountKey, currentState)
                }
                
                // Actualizar estado actual para la siguiente iteración
                currentState = getReconnectionState(accountKey) ?: break
            }
            
            // Si llegamos aquí, se agotaron los intentos
            if (currentState.attempts >= maxReconnectionAttempts) {
                log.w(tag = TAG) { "Max reconnection attempts reached for $accountKey" }
                
                val failedState = currentState.copy(
                    isReconnecting = false,
                    lastError = "Max reconnection attempts reached",
                    timestamp = Clock.System.now().toEpochMilliseconds()
                )
                updateReconnectionState(accountKey, failedState)
            }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in reconnection loop for $accountKey: ${e.message}" }
            
            val errorState = currentState.copy(
                isReconnecting = false,
                lastError = e.message,
                timestamp = Clock.System.now().toEpochMilliseconds()
            )
            updateReconnectionState(accountKey, errorState)
            
        } finally {
            // Limpiar job
            reconnectionJobs.remove(accountKey)
        }
    }
    
    /**
     * Ejecuta un intento de reconexión
     */
    private suspend fun executeReconnectionAttempt(
        accountInfo: AccountInfo,
        reason: ReconnectionReason
    ): Boolean {
        return try {
            log.d(tag = TAG) { "Executing reconnection attempt for ${accountInfo.getAccountIdentity()}" }
            
            // Llamar al callback de reconexión
            onReconnectionRequiredCallback?.invoke(accountInfo, reason)
            
            // Esperar un poco para que se procese la reconexión
            delay(2000)
            
            // Verificar si la reconexión fue exitosa
            val accountKey = accountInfo.getAccountIdentity()
            val registrationState = RegistrationStateManager.getAccountState(accountKey)
            
            val success = registrationState == RegistrationState.OK
            
            log.d(tag = TAG) { 
                "Reconnection attempt result for $accountKey: $success (state: $registrationState)" 
            }
            
            success
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in reconnection attempt: ${e.message}" }
            false
        }
    }
    
    /**
     * Detiene la reconexión para una cuenta
     */
    fun stopReconnection(accountKey: String) {
        log.d(tag = TAG) { "Stopping reconnection for $accountKey" }
        
        // Cancelar job
        reconnectionJobs[accountKey]?.cancel()
        reconnectionJobs.remove(accountKey)
        
        // Actualizar estado
        val currentState = getReconnectionState(accountKey)
        if (currentState != null && currentState.isReconnecting) {
            val stoppedState = currentState.copy(
                isReconnecting = false,
                timestamp = Clock.System.now().toEpochMilliseconds()
            )
            updateReconnectionState(accountKey, stoppedState)
        }
    }
    
    /**
     * Detiene todas las reconexiones
     */
    fun stopAllReconnections() {
        log.d(tag = TAG) { "Stopping all reconnections" }
        
        val accountKeys = reconnectionJobs.keys.toList()
        accountKeys.forEach { accountKey ->
            stopReconnection(accountKey)
        }
    }
    
    /**
     * Maneja cambio de red
     */
    fun onNetworkChanged(accounts: List<AccountInfo>) {
        log.d(tag = TAG) { "Network changed, scheduling reconnection for ${accounts.size} accounts" }
        
        scope.launch {
            // Esperar un poco para que la red se estabilice
            delay(networkChangeDelay)
            
            accounts.forEach { accountInfo ->
                val accountKey = accountInfo.getAccountIdentity()
                val registrationState = RegistrationStateManager.getAccountState(accountKey)
                
                // Solo reconectar si no está registrada correctamente
                if (registrationState != RegistrationState.OK) {
                    startReconnection(
                        accountInfo = accountInfo,
                        reason = ReconnectionReason.NETWORK_CHANGED,
                        isNetworkAvailable = true
                    )
                }
            }
        }
    }
    
    /**
     * Maneja pérdida de red
     */
    fun onNetworkLost(accounts: List<AccountInfo>) {
        log.d(tag = TAG) { "Network lost, preparing reconnection for ${accounts.size} accounts" }
        
        accounts.forEach { accountInfo ->
            val accountKey = accountInfo.getAccountIdentity()
            
            // Crear estado de espera por red
            val waitingState = ReconnectionState(
                accountKey = accountKey,
                isReconnecting = true,
                attempts = 0,
                reason = ReconnectionReason.NETWORK_LOST,
                isNetworkAvailable = false,
                backoffDelay = baseReconnectionDelay
            )
            
            updateReconnectionState(accountKey, waitingState)
        }
    }
    
    /**
     * Maneja recuperación de red
     */
    fun onNetworkRecovered(accounts: List<AccountInfo>) {
        log.d(tag = TAG) { "Network recovered, starting reconnection for ${accounts.size} accounts" }
        
        accounts.forEach { accountInfo ->
            val accountKey = accountInfo.getAccountIdentity()
            val currentState = getReconnectionState(accountKey)
            
            if (currentState != null && !currentState.isNetworkAvailable) {
                // Actualizar disponibilidad de red y reiniciar reconexión
                val recoveredState = currentState.copy(
                    isNetworkAvailable = true,
                    attempts = 0, // Resetear intentos
                    timestamp = Clock.System.now().toEpochMilliseconds()
                )
                updateReconnectionState(accountKey, recoveredState)
                
                // Iniciar reconexión
                startReconnection(
                    accountInfo = accountInfo,
                    reason = ReconnectionReason.NETWORK_CHANGED,
                    isNetworkAvailable = true
                )
            }
        }
    }
    
    /**
     * Maneja fallo de registro
     */
    fun onRegistrationFailed(accountInfo: AccountInfo, error: String?) {
        val accountKey = accountInfo.getAccountIdentity()
        
        log.d(tag = TAG) { "Registration failed for $accountKey: $error" }
        
        startReconnection(
            accountInfo = accountInfo,
            reason = ReconnectionReason.REGISTRATION_FAILED,
            isNetworkAvailable = true
        )
    }
    
    /**
     * Maneja desconexión de WebSocket
     */
    fun onWebSocketDisconnected(accountInfo: AccountInfo) {
        val accountKey = accountInfo.getAccountIdentity()
        
        log.d(tag = TAG) { "WebSocket disconnected for $accountKey" }
        
        startReconnection(
            accountInfo = accountInfo,
            reason = ReconnectionReason.WEBSOCKET_DISCONNECTED,
            isNetworkAvailable = true
        )
    }
    
    /**
     * Reconexión manual
     */
    fun manualReconnection(accountInfo: AccountInfo) {
        val accountKey = accountInfo.getAccountIdentity()
        
        log.d(tag = TAG) { "Manual reconnection triggered for $accountKey" }
        
        startReconnection(
            accountInfo = accountInfo,
            reason = ReconnectionReason.MANUAL_TRIGGER,
            isNetworkAvailable = true
        )
    }
    
    /**
     * Calcula el delay de backoff exponencial
     */
    private fun calculateBackoffDelay(attempt: Int): Long {
        val delay = baseReconnectionDelay * (1 shl attempt) // 2^attempt
        return delay.coerceAtMost(maxReconnectionDelay)
    }
    
    /**
     * Actualiza el estado de reconexión
     */
    private fun updateReconnectionState(accountKey: String, state: ReconnectionState) {
        val currentStates = _reconnectionStateFlow.value.toMutableMap()
        currentStates[accountKey] = state
        _reconnectionStateFlow.value = currentStates
        
        // Notificar callback
        onReconnectionStatusCallback?.invoke(accountKey, state)
    }
    
    /**
     * Obtiene el estado de reconexión
     */
    private fun getReconnectionState(accountKey: String): ReconnectionState? {
        return _reconnectionStateFlow.value[accountKey]
    }
    
    /**
     * Limpia el estado de reconexión
     */
    private fun clearReconnectionState(accountKey: String) {
        val currentStates = _reconnectionStateFlow.value.toMutableMap()
        currentStates.remove(accountKey)
        _reconnectionStateFlow.value = currentStates
    }
    
    // === MÉTODOS PÚBLICOS ===
    
    fun getReconnectionStates(): Map<String, ReconnectionState> = _reconnectionStateFlow.value
    
    fun isReconnecting(accountKey: String): Boolean {
        return getReconnectionState(accountKey)?.isReconnecting ?: false
    }
    
    fun getReconnectionAttempts(accountKey: String): Int {
        return getReconnectionState(accountKey)?.attempts ?: 0
    }
    
    fun resetReconnectionAttempts(accountKey: String) {
        val currentState = getReconnectionState(accountKey)
        if (currentState != null) {
            val resetState = currentState.copy(
                attempts = 0,
                lastError = null,
                backoffDelay = baseReconnectionDelay,
                timestamp = Clock.System.now().toEpochMilliseconds()
            )
            updateReconnectionState(accountKey, resetState)
        }
    }
    
    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        val states = _reconnectionStateFlow.value
        val activeJobs = reconnectionJobs.size
        
        return buildString {
            appendLine("=== RECONNECTION MANAGER DIAGNOSTIC ===")
            appendLine("Active Reconnection Jobs: $activeJobs")
            appendLine("Tracked Accounts: ${states.size}")
            appendLine("Max Attempts: $maxReconnectionAttempts")
            appendLine("Base Delay: ${baseReconnectionDelay}ms")
            appendLine("Max Delay: ${maxReconnectionDelay}ms")
            appendLine("Network Change Delay: ${networkChangeDelay}ms")
            
            if (states.isNotEmpty()) {
                appendLine("\n--- Reconnection States ---")
                states.forEach { (accountKey, state) ->
                    appendLine("$accountKey:")
                    appendLine("  Reconnecting: ${state.isReconnecting}")
                    appendLine("  Attempts: ${state.attempts}/${state.maxAttempts}")
                    appendLine("  Reason: ${state.reason}")
                    appendLine("  Network Available: ${state.isNetworkAvailable}")
                    appendLine("  Last Error: ${state.lastError ?: "None"}")
                    appendLine("  Next Attempt: ${state.nextAttemptTime}")
                    appendLine("  Backoff Delay: ${state.backoffDelay}ms")
                }
            }
            
            if (activeJobs > 0) {
                appendLine("\n--- Active Jobs ---")
                reconnectionJobs.forEach { (accountKey, job) ->
                    appendLine("$accountKey: Active=${job.isActive}, Completed=${job.isCompleted}")
                }
            }
        }
    }
    
    /**
     * Limpieza de recursos
     */
    fun dispose() {
        stopAllReconnections()
        _reconnectionStateFlow.value = emptyMap()
        log.d(tag = TAG) { "ReconnectionManager disposed" }
    }
}