package com.eddyslarez.siplibrary.data.services.network

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
 * Gestor de reconexión COMPLETAMENTE CORREGIDO
 * 
 * CAMBIOS PRINCIPALES:
 * 1. NO reconectar sin internet real
 * 2. Detener TODOS los intentos cuando no hay internet
 * 3. Reconectar TODAS las cuentas cuando regresa internet
 * 4. Evitar bucles infinitos de registro/fallo
 * 
 * @author Eddys Larez
 */
class ReconnectionManager {

    private val TAG = "ReconnectionManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // CRÍTICO: Referencia al monitor de red para verificar internet real
    private var networkMonitor: NetworkMonitor? = null

    // Estados de reconexión por cuenta
    private val _reconnectionStates = MutableStateFlow<Map<String, ReconnectionState>>(emptyMap())
    val reconnectionStatesFlow: StateFlow<Map<String, ReconnectionState>> = _reconnectionStates.asStateFlow()

    // Jobs de reconexión activos por cuenta
    private val reconnectionJobs = ConcurrentHashMap<String, Job>()

    // Control global de reconexión
    @Volatile
    private var isReconnectionEnabled = true
    @Volatile
    private var isDisposing = false

    // NUEVO: Control de bucles infinitos
    @Volatile
    private var lastInternetCheckTime = 0L
    @Volatile
    private var consecutiveNoInternetAttempts = 0
    private val maxNoInternetAttempts = 3

    // Callbacks
    private var onReconnectionRequiredCallback: ((AccountInfo, ReconnectionReason) -> Unit)? = null
    private var onReconnectionStatusCallback: ((String, ReconnectionState) -> Unit)? = null

    // Configuración de reconexión
    private val baseDelayMs = 2000L
    private val maxDelayMs = 60000L
    private val maxReconnectionAttempts = 10

    enum class ReconnectionReason {
        NETWORK_LOST,
        NETWORK_RECOVERED,
        NETWORK_CHANGED,
        INTERNET_RECOVERED,
        INTERNET_LOST,
        WEBSOCKET_DISCONNECTED,
        REGISTRATION_FAILED,
        MANUAL_RECONNECTION,
        AUTHENTICATION_FAILED,
        SERVER_ERROR
    }

    data class ReconnectionState(
        val accountKey: String,
        val isReconnecting: Boolean,
        val attempts: Int,
        val lastAttemptTime: Long,
        val nextAttemptTime: Long,
        val reason: ReconnectionReason,
        val lastError: String? = null,
        val isBlocked: Boolean = false // NUEVO: Para bloquear reconexiones sin internet
    )

    /**
     * CRÍTICO: Establece referencia al monitor de red
     */
    fun setNetworkMonitor(networkMonitor: NetworkMonitor) {
        this.networkMonitor = networkMonitor
        log.d(tag = TAG) { "Network monitor reference set" }
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

    // === EVENTOS DE RED ===

    /**
     * CORREGIDO: Maneja pérdida de red - DETIENE TODAS las reconexiones
     */
    fun onNetworkLost(accounts: List<AccountInfo>) {
        log.d(tag = TAG) { "=== NETWORK LOST - STOPPING ALL RECONNECTIONS ===" }
        log.d(tag = TAG) { "Affected accounts: ${accounts.size}" }

        // CRÍTICO: Detener INMEDIATAMENTE todas las reconexiones
        stopAllReconnections()

        // Marcar todas las cuentas como bloqueadas para reconexión
        accounts.forEach { account ->
            val accountKey = account.getAccountIdentity()
            updateReconnectionState(
                accountKey = accountKey,
                isReconnecting = false,
                reason = ReconnectionReason.NETWORK_LOST,
                isBlocked = true // BLOQUEAR reconexiones
            )

            log.d(tag = TAG) { "Account $accountKey marked as blocked due to network loss" }
        }

        // Reset contadores
        consecutiveNoInternetAttempts = 0

        log.d(tag = TAG) { "All reconnections stopped due to network loss" }
    }

    /**
     * CORREGIDO: Maneja recuperación de red - VERIFICA INTERNET REAL
     */
    fun onNetworkRecovered(accounts: List<AccountInfo>) {
        log.d(tag = TAG) { "=== NETWORK RECOVERED EVENT ===" }
        log.d(tag = TAG) { "Accounts to check: ${accounts.size}" }

        // CRÍTICO: Verificar que realmente hay internet antes de proceder
        if (!hasRealInternet()) {
            log.w(tag = TAG) { "Network recovered but NO REAL INTERNET - blocking reconnections" }
            
            // Incrementar contador de intentos sin internet
            consecutiveNoInternetAttempts++
            
            if (consecutiveNoInternetAttempts >= maxNoInternetAttempts) {
                log.w(tag = TAG) { "Too many attempts without internet ($consecutiveNoInternetAttempts), stopping" }
                return
            }

            // Marcar cuentas como bloqueadas
            accounts.forEach { account ->
                updateReconnectionState(
                    accountKey = account.getAccountIdentity(),
                    isReconnecting = false,
                    reason = ReconnectionReason.NETWORK_RECOVERED,
                    isBlocked = true,
                    lastError = "No real internet connection"
                )
            }
            return
        }

        // Reset contador si hay internet real
        consecutiveNoInternetAttempts = 0
        lastInternetCheckTime = System.currentTimeMillis()

        log.d(tag = TAG) { "Real internet confirmed - starting reconnection for ALL ${accounts.size} accounts" }

        // CRÍTICO: Reconectar TODAS las cuentas, no solo las que estaban fallando
        accounts.forEach { account ->
            val accountKey = account.getAccountIdentity()
            
            // Desbloquear y iniciar reconexión
            updateReconnectionState(
                accountKey = accountKey,
                isReconnecting = false,
                reason = ReconnectionReason.NETWORK_RECOVERED,
                isBlocked = false // DESBLOQUEAR
            )

            // Iniciar reconexión inmediata
            startReconnection(account, ReconnectionReason.NETWORK_RECOVERED, immediateAttempt = true)
        }
    }

    /**
     * NUEVO: Maneja recuperación específica de internet
     */
    fun onInternetRecovered(accounts: List<AccountInfo>) {
        log.d(tag = TAG) { "=== INTERNET RECOVERED EVENT ===" }
        log.d(tag = TAG) { "Accounts to reconnect: ${accounts.size}" }

        // Verificación doble de internet
        if (!hasRealInternet()) {
            log.w(tag = TAG) { "Internet recovery event but no real internet detected" }
            return
        }

        consecutiveNoInternetAttempts = 0
        lastInternetCheckTime = System.currentTimeMillis()

        // Reconectar TODAS las cuentas proporcionadas
        accounts.forEach { account ->
            val accountKey = account.getAccountIdentity()
            
            log.d(tag = TAG) { "Starting internet recovery for account: $accountKey" }
            
            updateReconnectionState(
                accountKey = accountKey,
                isReconnecting = false,
                reason = ReconnectionReason.INTERNET_RECOVERED,
                isBlocked = false
            )

            startReconnection(account, ReconnectionReason.INTERNET_RECOVERED, immediateAttempt = true)
        }
    }

    /**
     * NUEVO: Maneja pérdida específica de internet
     */
    fun onInternetLost(accounts: List<AccountInfo>) {
        log.d(tag = TAG) { "=== INTERNET LOST EVENT ===" }
        log.d(tag = TAG) { "Affected accounts: ${accounts.size}" }

        // Detener todas las reconexiones
        stopAllReconnections()

        // Marcar todas las cuentas como bloqueadas
        accounts.forEach { account ->
            val accountKey = account.getAccountIdentity()
            updateReconnectionState(
                accountKey = accountKey,
                isReconnecting = false,
                reason = ReconnectionReason.INTERNET_LOST,
                isBlocked = true,
                lastError = "Internet connection lost"
            )
        }

        consecutiveNoInternetAttempts = 0
    }

    /**
     * Maneja cambio de red
     */
    fun onNetworkChanged(accounts: List<AccountInfo>) {
        log.d(tag = TAG) { "=== NETWORK CHANGED EVENT ===" }
        log.d(tag = TAG) { "Accounts to reconnect: ${accounts.size}" }

        if (!hasRealInternet()) {
            log.w(tag = TAG) { "Network changed but no real internet - blocking reconnections" }
            return
        }

        // Reconectar cuentas que necesitan reconexión
        accounts.forEach { account ->
            val accountKey = account.getAccountIdentity()
            startReconnection(account, ReconnectionReason.NETWORK_CHANGED, immediateAttempt = false)
        }
    }

    // === EVENTOS DE WEBSOCKET Y REGISTRO ===

    /**
     * CORREGIDO: Maneja desconexión de WebSocket - VERIFICA INTERNET
     */
    fun onWebSocketDisconnected(account: AccountInfo) {
        val accountKey = account.getAccountIdentity()
        log.d(tag = TAG) { "WebSocket disconnected for: $accountKey" }

        // CRÍTICO: Solo reconectar si hay internet real
        if (!hasRealInternet()) {
            log.w(tag = TAG) { "WebSocket disconnected but no internet - blocking reconnection for $accountKey" }
            
            updateReconnectionState(
                accountKey = accountKey,
                isReconnecting = false,
                reason = ReconnectionReason.WEBSOCKET_DISCONNECTED,
                isBlocked = true,
                lastError = "No internet connection"
            )
            return
        }

        // Iniciar reconexión solo si hay internet
        startReconnection(account, ReconnectionReason.WEBSOCKET_DISCONNECTED)
    }

    /**
     * CORREGIDO: Maneja fallo de registro - VERIFICA INTERNET
     */
    fun onRegistrationFailed(account: AccountInfo, error: String?) {
        val accountKey = account.getAccountIdentity()
        log.d(tag = TAG) { "Registration failed for: $accountKey, error: $error" }

        // CRÍTICO: No reconectar si no hay internet
        if (!hasRealInternet()) {
            log.w(tag = TAG) { "Registration failed but no internet - blocking reconnection for $accountKey" }
            
            updateReconnectionState(
                accountKey = accountKey,
                isReconnecting = false,
                reason = ReconnectionReason.REGISTRATION_FAILED,
                isBlocked = true,
                lastError = "No internet: $error"
            )
            return
        }

        // Determinar razón específica
        val reason = when {
            error?.contains("auth", ignoreCase = true) == true -> ReconnectionReason.AUTHENTICATION_FAILED
            error?.contains("server", ignoreCase = true) == true -> ReconnectionReason.SERVER_ERROR
            else -> ReconnectionReason.REGISTRATION_FAILED
        }

        startReconnection(account, reason)
    }

    /**
     * Reconexión manual (siempre permitida)
     */
    fun manualReconnection(account: AccountInfo) {
        val accountKey = account.getAccountIdentity()
        log.d(tag = TAG) { "Manual reconnection requested for: $accountKey" }

        // Desbloquear la cuenta para reconexión manual
        updateReconnectionState(
            accountKey = accountKey,
            isReconnecting = false,
            reason = ReconnectionReason.MANUAL_RECONNECTION,
            isBlocked = false
        )

        startReconnection(account, ReconnectionReason.MANUAL_RECONNECTION, immediateAttempt = true)
    }

    // === LÓGICA DE RECONEXIÓN ===

    /**
     * CORREGIDO: Inicia reconexión con verificación de internet obligatoria
     */
    private fun startReconnection(
        account: AccountInfo,
        reason: ReconnectionReason,
        immediateAttempt: Boolean = false
    ) {
        val accountKey = account.getAccountIdentity()

        if (isDisposing || !isReconnectionEnabled) {
            log.d(tag = TAG) { "Reconnection disabled or disposing, skipping $accountKey" }
            return
        }

        // CRÍTICO: Verificar internet ANTES de cualquier intento (excepto manual)
        if (reason != ReconnectionReason.MANUAL_RECONNECTION && !hasRealInternet()) {
            log.w(tag = TAG) { "No real internet - blocking reconnection for $accountKey (reason: $reason)" }
            
            updateReconnectionState(
                accountKey = accountKey,
                isReconnecting = false,
                reason = reason,
                isBlocked = true,
                lastError = "No internet connection available"
            )
            return
        }

        // Verificar si ya está en reconexión
        val currentState = _reconnectionStates.value[accountKey]
        if (currentState?.isReconnecting == true) {
            log.d(tag = TAG) { "Account $accountKey already reconnecting, skipping" }
            return
        }

        // Verificar límite de intentos
        val attempts = currentState?.attempts ?: 0
        if (attempts >= maxReconnectionAttempts && reason != ReconnectionReason.MANUAL_RECONNECTION) {
            log.w(tag = TAG) { "Max reconnection attempts reached for $accountKey ($attempts)" }
            
            updateReconnectionState(
                accountKey = accountKey,
                isReconnecting = false,
                reason = reason,
                isBlocked = true,
                lastError = "Max attempts reached"
            )
            return
        }

        // Cancelar job anterior si existe
        reconnectionJobs[accountKey]?.cancel()

        // Calcular delay
        val delay = if (immediateAttempt) {
            500L // Delay mínimo para immediate attempts
        } else {
            calculateBackoffDelay(attempts)
        }

        log.d(tag = TAG) { "Starting reconnection for $accountKey (attempt ${attempts + 1}, delay: ${delay}ms, reason: $reason)" }

        // Actualizar estado a "reconectando"
        updateReconnectionState(
            accountKey = accountKey,
            isReconnecting = true,
            reason = reason,
            attempts = attempts + 1,
            nextAttemptTime = System.currentTimeMillis() + delay,
            isBlocked = false
        )

        // Crear job de reconexión
        val reconnectionJob = scope.launch {
            try {
                delay(delay)

                // CRÍTICO: Verificar internet NUEVAMENTE antes del intento
                if (!hasRealInternet()) {
                    log.w(tag = TAG) { "Internet lost during reconnection delay for $accountKey" }
                    
                    updateReconnectionState(
                        accountKey = accountKey,
                        isReconnecting = false,
                        reason = reason,
                        isBlocked = true,
                        lastError = "Internet lost during reconnection"
                    )
                    return@launch
                }

                // Verificar si aún necesitamos reconectar
                val currentRegistrationState = RegistrationStateManager.getAccountState(accountKey)
                if (currentRegistrationState == RegistrationState.OK) {
                    log.d(tag = TAG) { "Account $accountKey already registered, cancelling reconnection" }
                    
                    updateReconnectionState(
                        accountKey = accountKey,
                        isReconnecting = false,
                        reason = reason,
                        isBlocked = false
                    )
                    return@launch
                }

                log.d(tag = TAG) { "Executing reconnection attempt for $accountKey" }

                // Ejecutar reconexión
                onReconnectionRequiredCallback?.invoke(account, reason)

                // Esperar resultado de la reconexión
                delay(5000) // 5 segundos para que complete

                // Verificar resultado
                val newRegistrationState = RegistrationStateManager.getAccountState(accountKey)
                val success = newRegistrationState == RegistrationState.OK

                if (success) {
                    log.d(tag = TAG) { "Reconnection successful for $accountKey" }
                    
                    updateReconnectionState(
                        accountKey = accountKey,
                        isReconnecting = false,
                        reason = reason,
                        attempts = 0, // Reset attempts on success
                        isBlocked = false
                    )
                } else {
                    log.w(tag = TAG) { "Reconnection failed for $accountKey (state: $newRegistrationState)" }
                    
                    val newAttempts = (currentState?.attempts ?: 0) + 1
                    
                    if (newAttempts >= maxReconnectionAttempts) {
                        log.e(tag = TAG) { "Max attempts reached for $accountKey, blocking further attempts" }
                        
                        updateReconnectionState(
                            accountKey = accountKey,
                            isReconnecting = false,
                            reason = reason,
                            attempts = newAttempts,
                            isBlocked = true,
                            lastError = "Max reconnection attempts reached"
                        )
                    } else {
                        // Programar siguiente intento
                        updateReconnectionState(
                            accountKey = accountKey,
                            isReconnecting = false,
                            reason = reason,
                            attempts = newAttempts,
                            isBlocked = false,
                            lastError = "Reconnection failed, will retry"
                        )

                        // CRÍTICO: Solo programar siguiente intento si hay internet
                        if (hasRealInternet()) {
                            delay(2000) // Pequeño delay antes del siguiente intento
                            startReconnection(account, reason)
                        } else {
                            log.w(tag = TAG) { "Internet lost during reconnection, blocking $accountKey" }
                            updateReconnectionState(
                                accountKey = accountKey,
                                isReconnecting = false,
                                reason = reason,
                                isBlocked = true,
                                lastError = "Internet lost during reconnection"
                            )
                        }
                    }
                }

            } catch (e: CancellationException) {
                log.d(tag = TAG) { "Reconnection cancelled for $accountKey" }
                
                updateReconnectionState(
                    accountKey = accountKey,
                    isReconnecting = false,
                    reason = reason,
                    lastError = "Reconnection cancelled"
                )
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in reconnection for $accountKey: ${e.message}" }
                
                updateReconnectionState(
                    accountKey = accountKey,
                    isReconnecting = false,
                    reason = reason,
                    lastError = "Reconnection error: ${e.message}"
                )
            } finally {
                reconnectionJobs.remove(accountKey)
            }
        }

        reconnectionJobs[accountKey] = reconnectionJob
    }

    /**
     * CRÍTICO: Verifica si hay internet real usando el NetworkMonitor
     */
    private fun hasRealInternet(): Boolean {
        val monitor = networkMonitor
        if (monitor == null) {
            log.w(tag = TAG) { "NetworkMonitor not set, assuming no internet" }
            return false
        }

        val networkInfo = monitor.getCurrentNetworkInfo()
        val hasInternet = networkInfo.isConnected && networkInfo.hasInternet

        log.d(tag = TAG) { 
            "Internet check: connected=${networkInfo.isConnected}, " +
            "hasInternet=${networkInfo.hasInternet}, " +
            "networkType=${networkInfo.networkType}" 
        }

        return hasInternet
    }

    /**
     * CORREGIDO: Detiene TODAS las reconexiones inmediatamente
     */
    fun stopAllReconnections() {
        log.d(tag = TAG) { "=== STOPPING ALL RECONNECTIONS ===" }
        log.d(tag = TAG) { "Active jobs: ${reconnectionJobs.size}" }

        // Cancelar todos los jobs
        reconnectionJobs.values.forEach { job ->
            try {
                job.cancel()
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error cancelling reconnection job: ${e.message}" }
            }
        }
        reconnectionJobs.clear()

        // Actualizar todos los estados a "no reconectando"
        val currentStates = _reconnectionStates.value.toMutableMap()
        currentStates.replaceAll { _, state ->
            state.copy(
                isReconnecting = false,
                lastError = "Reconnection stopped"
            )
        }
        _reconnectionStates.value = currentStates

        log.d(tag = TAG) { "All reconnections stopped successfully" }
    }

    /**
     * Detiene reconexión para una cuenta específica
     */
    fun stopReconnection(accountKey: String) {
        log.d(tag = TAG) { "Stopping reconnection for: $accountKey" }

        reconnectionJobs[accountKey]?.cancel()
        reconnectionJobs.remove(accountKey)

        updateReconnectionState(
            accountKey = accountKey,
            isReconnecting = false,
            lastError = "Reconnection stopped manually"
        )
    }

    /**
     * Reset intentos de reconexión para una cuenta
     */
    fun resetReconnectionAttempts(accountKey: String) {
        log.d(tag = TAG) { "Resetting reconnection attempts for: $accountKey" }

        updateReconnectionState(
            accountKey = accountKey,
            attempts = 0,
            isBlocked = false,
            lastError = null
        )
    }

    /**
     * NUEVO: Desbloquea una cuenta para permitir reconexiones
     */
    fun unblockAccount(accountKey: String) {
        log.d(tag = TAG) { "Unblocking account for reconnection: $accountKey" }

        updateReconnectionState(
            accountKey = accountKey,
            isBlocked = false,
            attempts = 0,
            lastError = null
        )
    }

    /**
     * NUEVO: Desbloquea todas las cuentas
     */
    fun unblockAllAccounts() {
        log.d(tag = TAG) { "Unblocking all accounts for reconnection" }

        val currentStates = _reconnectionStates.value.toMutableMap()
        currentStates.replaceAll { _, state ->
            state.copy(
                isBlocked = false,
                attempts = 0,
                lastError = null
            )
        }
        _reconnectionStates.value = currentStates
    }

    // === MÉTODOS DE UTILIDAD ===

    /**
     * Actualiza estado de reconexión
     */
    private fun updateReconnectionState(
        accountKey: String,
        isReconnecting: Boolean? = null,
        attempts: Int? = null,
        reason: ReconnectionReason? = null,
        nextAttemptTime: Long? = null,
        lastError: String? = null,
        isBlocked: Boolean? = null
    ) {
        val currentStates = _reconnectionStates.value.toMutableMap()
        val currentState = currentStates[accountKey]

        val newState = ReconnectionState(
            accountKey = accountKey,
            isReconnecting = isReconnecting ?: currentState?.isReconnecting ?: false,
            attempts = attempts ?: currentState?.attempts ?: 0,
            lastAttemptTime = System.currentTimeMillis(),
            nextAttemptTime = nextAttemptTime ?: currentState?.nextAttemptTime ?: 0L,
            reason = reason ?: currentState?.reason ?: ReconnectionReason.MANUAL_RECONNECTION,
            lastError = lastError ?: currentState?.lastError,
            isBlocked = isBlocked ?: currentState?.isBlocked ?: false
        )

        currentStates[accountKey] = newState
        _reconnectionStates.value = currentStates

        // Notificar callback
        onReconnectionStatusCallback?.invoke(accountKey, newState)
    }

    /**
     * Calcula delay con backoff exponencial
     */
    private fun calculateBackoffDelay(attempts: Int): Long {
        val delay = baseDelayMs * (1 shl attempts.coerceAtMost(6)) // Max 2^6 = 64x
        return delay.coerceAtMost(maxDelayMs)
    }

    // === MÉTODOS DE CONSULTA ===

    fun isReconnecting(accountKey: String): Boolean {
        return _reconnectionStates.value[accountKey]?.isReconnecting ?: false
    }

    fun getReconnectionAttempts(accountKey: String): Int {
        return _reconnectionStates.value[accountKey]?.attempts ?: 0
    }

    fun getReconnectionStates(): Map<String, ReconnectionState> {
        return _reconnectionStates.value
    }

    fun isAccountBlocked(accountKey: String): Boolean {
        return _reconnectionStates.value[accountKey]?.isBlocked ?: false
    }

    fun getBlockedAccounts(): List<String> {
        return _reconnectionStates.value.filter { it.value.isBlocked }.keys.toList()
    }

    fun getActiveReconnections(): List<String> {
        return _reconnectionStates.value.filter { it.value.isReconnecting }.keys.toList()
    }

    /**
     * NUEVO: Verifica si el sistema está en estado saludable
     */
    fun isSystemHealthy(): Boolean {
        val states = _reconnectionStates.value
        val hasInternet = hasRealInternet()
        
        return hasInternet && 
               states.values.none { it.isBlocked } &&
               states.values.count { it.isReconnecting } <= 2 && // Max 2 reconexiones simultáneas
               consecutiveNoInternetAttempts < maxNoInternetAttempts
    }

    /**
     * Información de diagnóstico completa
     */
    fun getDiagnosticInfo(): String {
        val states = _reconnectionStates.value
        val hasInternet = hasRealInternet()
        val activeJobs = reconnectionJobs.size

        return buildString {
            appendLine("=== RECONNECTION MANAGER DIAGNOSTIC ===")
            appendLine("Reconnection Enabled: $isReconnectionEnabled")
            appendLine("Is Disposing: $isDisposing")
            appendLine("Has Real Internet: $hasInternet")
            appendLine("Network Monitor Set: ${networkMonitor != null}")
            appendLine("Active Reconnection Jobs: $activeJobs")
            appendLine("Total Managed Accounts: ${states.size}")
            appendLine("Consecutive No Internet Attempts: $consecutiveNoInternetAttempts")
            appendLine("Last Internet Check: ${if (lastInternetCheckTime > 0) lastInternetCheckTime else "Never"}")
            appendLine("System Healthy: ${isSystemHealthy()}")

            appendLine("\n--- Account States ---")
            states.forEach { (accountKey, state) ->
                appendLine("$accountKey:")
                appendLine("  Reconnecting: ${state.isReconnecting}")
                appendLine("  Blocked: ${state.isBlocked}")
                appendLine("  Attempts: ${state.attempts}")
                appendLine("  Reason: ${state.reason}")
                appendLine("  Last Error: ${state.lastError ?: "None"}")
                appendLine("  Next Attempt: ${if (state.nextAttemptTime > 0) state.nextAttemptTime else "Not scheduled"}")
                appendLine()
            }

            appendLine("--- Active Jobs ---")
            reconnectionJobs.forEach { (accountKey, job) ->
                appendLine("$accountKey: active=${job.isActive}, completed=${job.isCompleted}, cancelled=${job.isCancelled}")
            }

            if (networkMonitor != null) {
                appendLine("\n--- Network Info ---")
                val networkInfo = networkMonitor!!.getCurrentNetworkInfo()
                appendLine("Connected: ${networkInfo.isConnected}")
                appendLine("Has Internet: ${networkInfo.hasInternet}")
                appendLine("Network Type: ${networkInfo.networkType}")
                appendLine("IP Address: ${networkInfo.ipAddress}")
            }
        }
    }

    /**
     * Habilita/deshabilita reconexión automática
     */
    fun setReconnectionEnabled(enabled: Boolean) {
        val wasEnabled = isReconnectionEnabled
        isReconnectionEnabled = enabled

        log.d(tag = TAG) { "Reconnection enabled changed: $wasEnabled -> $enabled" }

        if (!enabled) {
            stopAllReconnections()
        }
    }

    /**
     * NUEVO: Fuerza verificación de internet y desbloqueo si es necesario
     */
    fun forceInternetCheckAndUnblock() {
        log.d(tag = TAG) { "Forcing internet check and unblock if internet available" }

        if (hasRealInternet()) {
            log.d(tag = TAG) { "Internet confirmed - unblocking all accounts" }
            
            consecutiveNoInternetAttempts = 0
            lastInternetCheckTime = System.currentTimeMillis()
            
            // Desbloquear todas las cuentas bloqueadas
            val currentStates = _reconnectionStates.value.toMutableMap()
            var unblocked = 0
            
            currentStates.replaceAll { accountKey, state ->
                if (state.isBlocked) {
                    unblocked++
                    state.copy(
                        isBlocked = false,
                        attempts = 0,
                        lastError = null
                    )
                } else {
                    state
                }
            }
            
            _reconnectionStates.value = currentStates
            
            log.d(tag = TAG) { "Unblocked $unblocked accounts due to internet recovery" }
        } else {
            log.w(tag = TAG) { "Force internet check failed - no real internet available" }
        }
    }

    /**
     * Limpieza completa de recursos
     */
    fun dispose() {
        log.d(tag = TAG) { "Disposing ReconnectionManager..." }

        isDisposing = true
        isReconnectionEnabled = false

        // Detener todas las reconexiones
        stopAllReconnections()

        // Limpiar estados
        _reconnectionStates.value = emptyMap()

        // Limpiar callbacks
        onReconnectionRequiredCallback = null
        onReconnectionStatusCallback = null

        // Cancelar scope
        scope.cancel()

        log.d(tag = TAG) { "ReconnectionManager disposed completely" }
    }
}