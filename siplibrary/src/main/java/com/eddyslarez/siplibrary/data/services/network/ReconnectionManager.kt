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
     * CORREGIDO: Ejecuta el bucle de reconexión con mejor control de flujo
     */
    private suspend fun executeReconnectionLoop(
        accountInfo: AccountInfo,
        reason: ReconnectionReason,
        initialNetworkAvailable: Boolean
    ) {
        val accountKey = accountInfo.getAccountIdentity()
        var currentState = getReconnectionState(accountKey) ?: return
        var currentNetworkAvailable = initialNetworkAvailable

        log.d(tag = TAG) { "Starting reconnection loop for $accountKey" }

        try {
            while (currentState.attempts < maxReconnectionAttempts &&
                currentState.isReconnecting &&
                !Thread.currentThread().isInterrupted) {

                // CRÍTICO: Verificar si el job fue cancelado
                if (!reconnectionJobs.containsKey(accountKey)) {
                    log.d(tag = TAG) { "Reconnection job was cancelled for $accountKey" }
                    break
                }

                // Verificar estado actual de reconexión (puede haber cambiado externamente)
                currentState = getReconnectionState(accountKey) ?: break
                if (!currentState.isReconnecting) {
                    log.d(tag = TAG) { "Reconnection stopped externally for $accountKey" }
                    break
                }

                // CORREGIDO: Verificar estado de red actual antes de cada intento
                currentNetworkAvailable = checkNetworkAvailability()

                // Si la red no está disponible, esperar pero no incrementar intentos
                if (!currentNetworkAvailable) {
                    log.d(tag = TAG) { "Network not available for $accountKey, waiting..." }

                    // Actualizar estado para indicar que estamos esperando por red
                    currentState = currentState.copy(
                        isNetworkAvailable = false,
                        timestamp = Clock.System.now().toEpochMilliseconds()
                    )
                    updateReconnectionState(accountKey, currentState)

                    delay(5000) // Esperar 5 segundos antes de verificar de nuevo
                    continue // No incrementar intentos, solo esperar
                }

                // Red disponible, continuar con reconexión
                if (!currentState.isNetworkAvailable) {
                    // Red acaba de recuperarse, resetear algunos valores
                    log.d(tag = TAG) { "Network recovered for $accountKey, resuming reconnection" }
                    currentState = currentState.copy(
                        isNetworkAvailable = true,
                        backoffDelay = baseReconnectionDelay, // Resetear delay
                        timestamp = Clock.System.now().toEpochMilliseconds()
                    )
                    updateReconnectionState(accountKey, currentState)
                }

                // Incrementar contador de intentos
                val attemptNumber = currentState.attempts + 1
                val delay = calculateBackoffDelay(attemptNumber)
                val nextAttemptTime = Clock.System.now().toEpochMilliseconds() + delay

                // Actualizar estado antes del intento
                currentState = currentState.copy(
                    attempts = attemptNumber,
                    nextAttemptTime = nextAttemptTime,
                    backoffDelay = delay,
                    isNetworkAvailable = currentNetworkAvailable,
                    timestamp = Clock.System.now().toEpochMilliseconds()
                )
                updateReconnectionState(accountKey, currentState)

                log.d(tag = TAG) {
                    "Reconnection attempt $attemptNumber/$maxReconnectionAttempts for $accountKey in ${delay}ms"
                }

                // Esperar antes del intento
                delay(delay)

                // CRÍTICO: Verificar nuevamente si la reconexión fue cancelada después del delay
                val latestState = getReconnectionState(accountKey)
                if (latestState == null || !latestState.isReconnecting) {
                    log.d(tag = TAG) { "Reconnection cancelled during delay for $accountKey" }
                    break
                }

                // CRÍTICO: Verificar nuevamente el estado de la red después del delay
                if (!checkNetworkAvailability()) {
                    log.d(tag = TAG) { "Network lost during reconnection delay for $accountKey" }
                    continue
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
                    log.d(tag = TAG) { "Reconnection successful for $accountKey on attempt $attemptNumber" }

                    // Marcar como exitosa y limpiar estado después de un tiempo
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
                    log.d(tag = TAG) { "Reconnection attempt $attemptNumber failed for $accountKey" }

                    // Actualizar estado con error
                    currentState = currentState.copy(
                        lastError = "Reconnection attempt $attemptNumber failed",
                        timestamp = Clock.System.now().toEpochMilliseconds()
                    )
                    updateReconnectionState(accountKey, currentState)
                }

                // Actualizar estado actual para la siguiente iteración
                currentState = getReconnectionState(accountKey) ?: break
            }

            // Verificar por qué terminó el bucle
            val finalState = getReconnectionState(accountKey)
            if (finalState?.isReconnecting == true) {
                if (finalState.attempts >= maxReconnectionAttempts) {
                    log.w(tag = TAG) { "Max reconnection attempts reached for $accountKey" }

                    val failedState = finalState.copy(
                        isReconnecting = false,
                        lastError = "Max reconnection attempts (${maxReconnectionAttempts}) reached",
                        timestamp = Clock.System.now().toEpochMilliseconds()
                    )
                    updateReconnectionState(accountKey, failedState)
                } else {
                    log.d(tag = TAG) { "Reconnection loop ended for other reason for $accountKey" }
                }
            }

        } catch (e: Exception) {
            if (e is CancellationException) {
                log.d(tag = TAG) { "Reconnection loop cancelled for $accountKey" }
            } else {
                log.e(tag = TAG) { "Error in reconnection loop for $accountKey: ${e.message}" }

                val errorState = currentState.copy(
                    isReconnecting = false,
                    lastError = "Exception: ${e.message}",
                    timestamp = Clock.System.now().toEpochMilliseconds()
                )
                updateReconnectionState(accountKey, errorState)
            }
        } finally {
            // Limpiar job
            reconnectionJobs.remove(accountKey)
            log.d(tag = TAG) { "Reconnection loop finished for $accountKey" }
        }
    }

    /**
     * NUEVO: Verifica la disponibilidad de red actual
     */
    private fun checkNetworkAvailability(): Boolean {
        return try {
            // Aquí deberías verificar el estado real de la red
            // Por ahora, asumiremos que hay un método para verificar esto
            true // Placeholder - implementar verificación real
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error checking network availability: ${e.message}" }
            false
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
            delay(3000) // Aumentado a 3 segundos para dar más tiempo

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
     * MEJORADO: Detiene la reconexión para una cuenta
     */
    fun stopReconnection(accountKey: String) {
        log.d(tag = TAG) { "Stopping reconnection for $accountKey" }

        // Cancelar job de manera segura
        reconnectionJobs[accountKey]?.let { job ->
            try {
                job.cancel()
                reconnectionJobs.remove(accountKey)
                log.d(tag = TAG) { "Reconnection job cancelled for $accountKey" }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error cancelling reconnection job for $accountKey: ${e.message}" }
            }
        }

        // Actualizar estado
        val currentState = getReconnectionState(accountKey)
        if (currentState != null && currentState.isReconnecting) {
            val stoppedState = currentState.copy(
                isReconnecting = false,
                timestamp = Clock.System.now().toEpochMilliseconds()
            )
            updateReconnectionState(accountKey, stoppedState)
            log.d(tag = TAG) { "Reconnection state updated to stopped for $accountKey" }
        }
    }

    /**
     * MEJORADO: Detiene todas las reconexiones
     */
    fun stopAllReconnections() {
        log.d(tag = TAG) { "Stopping all reconnections (${reconnectionJobs.size} active)" }

        val accountKeys = reconnectionJobs.keys.toList()
        accountKeys.forEach { accountKey ->
            stopReconnection(accountKey)
        }

        // Verificar que todas se detuvieron
        if (reconnectionJobs.isNotEmpty()) {
            log.w(tag = TAG) { "${reconnectionJobs.size} jobs still active after stop attempt" }
        }
    }

    /**
     * CORREGIDO: Maneja cambio de red con mejor lógica
     */
    fun onNetworkChanged(accounts: List<AccountInfo>) {
        log.d(tag = TAG) { "Network changed, checking reconnection for ${accounts.size} accounts" }

        scope.launch {
            // Esperar un poco para que la red se estabilice
            delay(networkChangeDelay)

            accounts.forEach { accountInfo ->
                val accountKey = accountInfo.getAccountIdentity()
                val registrationState = RegistrationStateManager.getAccountState(accountKey)

                // Solo reconectar si no está registrada correctamente
                if (registrationState != RegistrationState.OK) {
                    log.d(tag = TAG) { "Account $accountKey needs reconnection after network change (state: $registrationState)" }

                    // Detener reconexión actual si existe
                    stopReconnection(accountKey)

                    // Iniciar nueva reconexión
                    startReconnection(
                        accountInfo = accountInfo,
                        reason = ReconnectionReason.NETWORK_CHANGED,
                        isNetworkAvailable = true
                    )
                } else {
                    log.d(tag = TAG) { "Account $accountKey is properly registered, no reconnection needed" }
                }
            }
        }
    }

    /**
     * CORREGIDO: Maneja pérdida de red
     */
    fun onNetworkLost(accounts: List<AccountInfo>) {
        log.d(tag = TAG) { "Network lost, preparing reconnection for ${accounts.size} accounts" }

        accounts.forEach { accountInfo ->
            val accountKey = accountInfo.getAccountIdentity()

            // Crear estado de espera por red (sin iniciar reconexión activa todavía)
            val waitingState = ReconnectionState(
                accountKey = accountKey,
                isReconnecting = true,
                attempts = 0,
                reason = ReconnectionReason.NETWORK_LOST,
                isNetworkAvailable = false,
                backoffDelay = baseReconnectionDelay
            )

            updateReconnectionState(accountKey, waitingState)
            log.d(tag = TAG) { "Account $accountKey marked for reconnection when network recovers" }
        }
    }

    /**
     * CORREGIDO: Maneja recuperación de red
     */
    fun onNetworkRecovered(accounts: List<AccountInfo>) {
        log.d(tag = TAG) { "Network recovered, starting reconnection for ${accounts.size} accounts" }

        accounts.forEach { accountInfo ->
            val accountKey = accountInfo.getAccountIdentity()
            val currentState = getReconnectionState(accountKey)
            val registrationState = RegistrationStateManager.getAccountState(accountKey)

            // Solo reconectar si no está registrada correctamente O si estaba esperando por red
            if (registrationState != RegistrationState.OK ||
                (currentState != null && !currentState.isNetworkAvailable)) {

                log.d(tag = TAG) { "Starting reconnection for $accountKey after network recovery" }

                // Detener cualquier proceso de reconexión anterior
                stopReconnection(accountKey)

                // Iniciar nueva reconexión
                startReconnection(
                    accountInfo = accountInfo,
                    reason = ReconnectionReason.NETWORK_CHANGED,
                    isNetworkAvailable = true
                )
            } else {
                log.d(tag = TAG) { "Account $accountKey is properly registered, no reconnection needed after recovery" }

                // Limpiar estado de reconexión si existía
                clearReconnectionState(accountKey)
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
        val delay = baseReconnectionDelay * (1 shl (attempt - 1)) // 2^(attempt-1)
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
     * CORREGIDO: Limpia el estado de reconexión de manera segura
     */
    private fun clearReconnectionState(accountKey: String) {
        val currentStates = _reconnectionStateFlow.value.toMutableMap()
        if (currentStates.remove(accountKey) != null) {
            _reconnectionStateFlow.value = currentStates
            log.d(tag = TAG) { "Cleared reconnection state for $accountKey" }
        }
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
            log.d(tag = TAG) { "Reset reconnection attempts for $accountKey" }
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
                    appendLine("$accountKey: Active=${job.isActive}, Completed=${job.isCompleted}, Cancelled=${job.isCancelled}")
                }
            }
        }
    }

    /**
     * Limpieza de recursos
     */
    fun dispose() {
        log.d(tag = TAG) { "Disposing ReconnectionManager..." }
        stopAllReconnections()
        _reconnectionStateFlow.value = emptyMap()

        // Cancelar scope
        scope.cancel()

        log.d(tag = TAG) { "ReconnectionManager disposed" }
    }
}