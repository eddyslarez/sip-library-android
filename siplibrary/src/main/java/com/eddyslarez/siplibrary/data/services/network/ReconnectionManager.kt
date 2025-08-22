package com.eddyslarez.siplibrary.data.services.network

import android.net.NetworkCapabilities
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

class ReconnectionManager {

    private val TAG = "ReconnectionManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Estados de reconexión
    private val _reconnectionStateFlow = MutableStateFlow<Map<String, ReconnectionState>>(emptyMap())
    val reconnectionStateFlow: StateFlow<Map<String, ReconnectionState>> = _reconnectionStateFlow.asStateFlow()

    // Jobs de reconexión por cuenta
    private val reconnectionJobs = ConcurrentHashMap<String, Job>()

    // Configuración de reconexión
    private val maxReconnectionAttempts = 8
    private val baseReconnectionDelay = 3000L // 3 segundos
    private val maxReconnectionDelay = 45000L // 45 segundos
    private val networkChangeDelay = 3000L // 3 segundos después de cambio de red

    // Callbacks
    private var onReconnectionRequiredCallback: ((AccountInfo, ReconnectionReason) -> Unit)? = null
    private var onReconnectionStatusCallback: ((String, ReconnectionState) -> Unit)? = null

    // CORREGIDO: Control de estado global mejorado
    private var isDisposing = false
    private var networkMonitor: NetworkMonitor? = null

    // NUEVO: Cache de estados de conexión para evitar reconexiones innecesarias
    private val connectionSuccessCache = ConcurrentHashMap<String, Long>()

    data class ReconnectionState(
        val accountKey: String,
        val isReconnecting: Boolean = false,
        val attempts: Int = 0,
        val maxAttempts: Int = 8,
        val lastAttemptTime: Long = 0L,
        val nextAttemptTime: Long = 0L,
        val reason: ReconnectionReason = ReconnectionReason.UNKNOWN,
        val lastError: String? = null,
        val backoffDelay: Long = 3000L,
        val isNetworkAvailable: Boolean = true,
        val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        val shouldStop: Boolean = false
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
     * NUEVO: Configura el monitor de red
     */
    fun setNetworkMonitor(monitor: NetworkMonitor) {
        this.networkMonitor = monitor
    }
    private suspend fun checkIfActive() {
        if (!coroutineContext.isActive) {
            throw CancellationException("Coroutine was cancelled")
        }
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
     * CORREGIDO: Inicia reconexión con verificaciones estrictas
     */
    fun startReconnection(
    accountInfo: AccountInfo,
    reason: ReconnectionReason,
    isNetworkAvailable: Boolean = true
    ) {
        if (isDisposing) {
            log.d(tag = TAG) { "Ignoring reconnection start - manager is disposing" }
            return
        }

        val accountKey = accountInfo.getAccountIdentity()

        // CRÍTICO: Verificar cache de éxito reciente primero
        val lastSuccessTime = connectionSuccessCache[accountKey]
        if (lastSuccessTime != null) {
            val timeSinceSuccess = Clock.System.now().toEpochMilliseconds() - lastSuccessTime
            if (timeSinceSuccess < 30000) { // 30 segundos
                log.d(tag = TAG) { "Account $accountKey was recently connected (${timeSinceSuccess}ms ago) - SKIPPING reconnection" }
                return
            }
        }

        // CRÍTICO: Verificar múltiples veces el estado antes de iniciar reconexión
        repeat(2) { attempt ->
            val registrationState = RegistrationStateManager.getAccountState(accountKey)
            if (registrationState == RegistrationState.OK) {
                log.d(tag = TAG) { "Account $accountKey already properly registered (check ${attempt + 1}) - SKIPPING reconnection" }

                // Actualizar cache de éxito y limpiar estado
                connectionSuccessCache[accountKey] = Clock.System.now().toEpochMilliseconds()
                clearReconnectionState(accountKey)
                return
            }

            if (attempt == 0) {
                // Esperar un poco antes de la segunda verificación
                Thread.sleep(1000)
            }
        }

        log.d(tag = TAG) {
            "Starting reconnection for $accountKey, reason: $reason, network available: $isNetworkAvailable"
        }

        // CRÍTICO: Detener reconexión existente completamente
        stopReconnection(accountKey)

        // CRÍTICO: Verificar disponibilidad de red con NetworkMonitor
        val actualNetworkAvailable = networkMonitor?.isNetworkAvailable() ?: isNetworkAvailable

        if (!actualNetworkAvailable) {
            log.d(tag = TAG) { "Network not available for $accountKey - creating pending state" }
            val pendingState = ReconnectionState(
                accountKey = accountKey,
                isReconnecting = false, // NO reconectar sin red
                attempts = 0,
                reason = reason,
                isNetworkAvailable = false,
                backoffDelay = baseReconnectionDelay,
                shouldStop = true // CRÍTICO: Detener hasta que haya red
            )
            updateReconnectionState(accountKey, pendingState)
            return
        }

        // Crear estado inicial de reconexión
        val initialState = ReconnectionState(
            accountKey = accountKey,
            isReconnecting = true,
            attempts = 0,
            reason = reason,
            isNetworkAvailable = actualNetworkAvailable,
            backoffDelay = baseReconnectionDelay
        )

        updateReconnectionState(accountKey, initialState)

        // Iniciar job de reconexión
        val reconnectionJob = scope.launch {
            executeReconnectionLoop(accountInfo, reason, actualNetworkAvailable)
        }

        reconnectionJobs[accountKey] = reconnectionJob
    }


    private suspend fun executeReconnectionLoop(
        accountInfo: AccountInfo,
        reason: ReconnectionReason,
        initialNetworkAvailable: Boolean
    ) {
        val accountKey = accountInfo.getAccountIdentity()
        var currentState = getReconnectionState(accountKey) ?: return

        log.d(tag = TAG) { "Starting reconnection loop for $accountKey" }

        try {
            while (currentState.attempts < maxReconnectionAttempts &&
                currentState.isReconnecting &&
                !currentState.shouldStop &&
                !isDisposing &&
                reconnectionJobs.containsKey(accountKey)) {

                // CRÍTICO: Verificar si la corrutina fue cancelada
                if (!coroutineContext.isActive) return

                // CRÍTICO: Verificar estado de registro ANTES de cada intento
                val registrationState = RegistrationStateManager.getAccountState(accountKey)
                if (registrationState == RegistrationState.OK) {
                    log.d(tag = TAG) { "Account $accountKey is now registered - STOPPING reconnection immediately" }
                    markReconnectionSuccessful(accountKey, currentState.attempts)
                    return
                }

                // CRÍTICO: Verificar que hay internet real ANTES de intentar
                val hasRealInternet = networkMonitor?.hasInternet() ?: false

                if (!hasRealInternet) {
                    log.d(tag = TAG) { "No internet available for $accountKey - STOPPING reconnection attempts" }

                    // Actualizar estado para indicar espera por internet
                    currentState = currentState.copy(
                        isReconnecting = false, // DETENER reconexión sin internet
                        isNetworkAvailable = false,
                        shouldStop = true, // CRÍTICO: No continuar sin internet
                        lastError = "Internet connection lost - reconnection stopped",
                        timestamp = Clock.System.now().toEpochMilliseconds()
                    )
                    updateReconnectionState(accountKey, currentState)

                    log.d(tag = TAG) { "Reconnection stopped for $accountKey due to no internet" }
                    return // SALIR del bucle completamente
                }

                // CRÍTICO: También verificar conectividad de red
                val networkAvailable = networkMonitor?.isNetworkAvailable() ?: false

                if (!networkAvailable) {
                    log.d(tag = TAG) { "Network not available for $accountKey - STOPPING reconnection" }

                    currentState = currentState.copy(
                        isReconnecting = false,
                        isNetworkAvailable = false,
                        shouldStop = true,
                        lastError = "Network connection lost - reconnection stopped",
                        timestamp = Clock.System.now().toEpochMilliseconds()
                    )
                    updateReconnectionState(accountKey, currentState)
                    return
                }

                // Verificar estado actual de reconexión
                currentState = getReconnectionState(accountKey) ?: break
                if (!currentState.isReconnecting || currentState.shouldStop) {
                    log.d(tag = TAG) { "Reconnection stopped externally for $accountKey" }
                    break
                }

                // Proceder con el intento de reconexión
                val attemptNumber = currentState.attempts + 1
                val delay = calculateBackoffDelay(attemptNumber)

                log.d(tag = TAG) {
                    "Reconnection attempt $attemptNumber/$maxReconnectionAttempts for $accountKey in ${delay}ms"
                }

                // CRÍTICO: Esperar con verificaciones frecuentes de internet
                val delaySeconds = (delay / 1000).toInt()
                var delayCount = 0
                while (delayCount < delaySeconds && coroutineContext.isActive) {
                    delay(1000)
                    delayCount++

                    // CRÍTICO: Verificar internet cada segundo durante la espera
                    if (!networkMonitor?.hasInternet()!! == true) {
                        log.d(tag = TAG) { "Internet lost during reconnection delay for $accountKey - STOPPING" }

                        val stoppedState = currentState.copy(
                            isReconnecting = false,
                            shouldStop = true,
                            lastError = "Internet lost during reconnection delay",
                            timestamp = Clock.System.now().toEpochMilliseconds()
                        )
                        updateReconnectionState(accountKey, stoppedState)
                        return
                    }

                    // Verificar si fue cancelado durante la espera
                    if (isDisposing || !reconnectionJobs.containsKey(accountKey)) {
                        log.d(tag = TAG) { "Reconnection cancelled during delay for $accountKey" }
                        return
                    }

                    // Verificar si la conexión se recuperó durante la espera
                    val regState = RegistrationStateManager.getAccountState(accountKey)
                    if (regState == RegistrationState.OK) {
                        log.d(tag = TAG) { "Connection recovered during delay for $accountKey - SUCCESS" }
                        markReconnectionSuccessful(accountKey, attemptNumber)
                        return
                    }
                }

                // Verificar una vez más antes del intento
                if (!networkMonitor?.hasInternet()!! == true) {
                    log.d(tag = TAG) { "No internet after delay for $accountKey - STOPPING" }

                    val noInternetState = currentState.copy(
                        isReconnecting = false,
                        shouldStop = true,
                        lastError = "No internet connection available",
                        timestamp = Clock.System.now().toEpochMilliseconds()
                    )
                    updateReconnectionState(accountKey, noInternetState)
                    return
                }

                // Actualizar estado antes del intento
                currentState = currentState.copy(
                    attempts = attemptNumber,
                    lastAttemptTime = Clock.System.now().toEpochMilliseconds(),
                    backoffDelay = delay,
                    isNetworkAvailable = true,
                    timestamp = Clock.System.now().toEpochMilliseconds()
                )
                updateReconnectionState(accountKey, currentState)

                // Ejecutar intento de reconexión
                val success = executeReconnectionAttempt(accountInfo, reason)

                if (success) {
                    log.d(tag = TAG) { "Reconnection successful for $accountKey on attempt $attemptNumber" }
                    markReconnectionSuccessful(accountKey, attemptNumber)
                    return
                } else {
                    log.d(tag = TAG) { "Reconnection attempt $attemptNumber failed for $accountKey" }
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
            if (finalState?.isReconnecting == true && !finalState.shouldStop) {
                if (finalState.attempts >= maxReconnectionAttempts) {
                    log.w(tag = TAG) { "Max reconnection attempts reached for $accountKey" }
                    markReconnectionFailed(accountKey, "Max reconnection attempts (${maxReconnectionAttempts}) reached")
                } else {
                    log.d(tag = TAG) { "Reconnection loop ended for other reason for $accountKey" }
                    markReconnectionFailed(accountKey, "Reconnection loop ended unexpectedly")
                }
            }

        } catch (e: Exception) {
            if (e is CancellationException) {
                log.d(tag = TAG) { "Reconnection loop cancelled for $accountKey" }
            } else {
                log.e(tag = TAG) { "Error in reconnection loop for $accountKey: ${e.message}" }
                markReconnectionFailed(accountKey, "Exception: ${e.message}")
            }
        } finally {
            // Limpiar job
            reconnectionJobs.remove(accountKey)
            log.d(tag = TAG) { "Reconnection loop finished for $accountKey" }
        }
    }
//    private suspend fun executeReconnectionLoop(
//        accountInfo: AccountInfo,
//        reason: ReconnectionReason,
//        initialNetworkAvailable: Boolean
//    ) {
//        val accountKey = accountInfo.getAccountIdentity()
//        var currentState = getReconnectionState(accountKey) ?: return
//
//        log.d(tag = TAG) { "Starting reconnection loop for $accountKey" }
//
//        try {
//            while (currentState.attempts < maxReconnectionAttempts &&
//                currentState.isReconnecting &&
//                !currentState.shouldStop &&
//                !isDisposing &&
//                reconnectionJobs.containsKey(accountKey)) {
//
//                // CRÍTICO: Verificar si la corrutina fue cancelada
//                if (!coroutineContext.isActive) return
//
//                // CRÍTICO: Verificar estado de registro ANTES de cada intento
//                val registrationState = RegistrationStateManager.getAccountState(accountKey)
//                if (registrationState == RegistrationState.OK) {
//                    log.d(tag = TAG) { "Account $accountKey is now registered - STOPPING reconnection immediately" }
//                    markReconnectionSuccessful(accountKey, currentState.attempts)
//                    return
//                }
//
//                // Verificar estado actual de reconexión
//                currentState = getReconnectionState(accountKey) ?: break
//                if (!currentState.isReconnecting || currentState.shouldStop) {
//                    log.d(tag = TAG) { "Reconnection stopped externally for $accountKey" }
//                    break
//                }
//
//                // CRÍTICO: Verificar estado de red con NetworkMonitor
//                val networkAvailable = networkMonitor?.isNetworkAvailable() ?: false
//
//                if (!networkAvailable) {
//                    log.d(tag = TAG) { "Network not available for $accountKey, waiting..." }
//
//                    // Actualizar estado para indicar espera por red
//                    currentState = currentState.copy(
//                        isNetworkAvailable = false,
//                        timestamp = Clock.System.now().toEpochMilliseconds()
//                    )
//                    updateReconnectionState(accountKey, currentState)
//
//                    // Esperar con verificaciones periódicas (30 segundos total)
//                    var waitCount = 0
//                    while (waitCount < 6 && coroutineContext.isActive) {
//                        delay(5000)
//                        waitCount++
//
//                        if (isDisposing || !reconnectionJobs.containsKey(accountKey)) break
//
//                        // Verificar si la cuenta se conectó durante la espera
//                        val currentRegState = RegistrationStateManager.getAccountState(accountKey)
//                        if (currentRegState == RegistrationState.OK) {
//                            log.d(tag = TAG) { "Account $accountKey registered while waiting for network - SUCCESS" }
//                            markReconnectionSuccessful(accountKey, currentState.attempts)
//                            return
//                        }
//
//                        val networkNowAvailable = networkMonitor?.isNetworkAvailable() ?: false
//                        if (networkNowAvailable) {
//                            log.d(tag = TAG) { "Network became available for $accountKey during wait" }
//                            break
//                        }
//                    }
//
//                    // Continuar al siguiente ciclo para re-verificar
//                    continue
//                }
//
//                // Red disponible, proceder con intento de reconexión
//                val attemptNumber = currentState.attempts + 1
//                val delay = calculateBackoffDelay(attemptNumber)
//
//                log.d(tag = TAG) {
//                    "Reconnection attempt $attemptNumber/$maxReconnectionAttempts for $accountKey in ${delay}ms"
//                }
//
//                // CRÍTICO: Esperar con verificaciones cada segundo
//                val delaySeconds = (delay / 1000).toInt()
//                var delayCount = 0
//                while (delayCount < delaySeconds && coroutineContext.isActive) {
//                    delay(1000)
//                    delayCount++
//
//                    // Verificar si fue cancelado durante la espera
//                    if (isDisposing || !reconnectionJobs.containsKey(accountKey)) {
//                        log.d(tag = TAG) { "Reconnection cancelled during delay for $accountKey" }
//                        return
//                    }
//
//                    // CRÍTICO: Verificar si la conexión se recuperó durante la espera
//                    val regState = RegistrationStateManager.getAccountState(accountKey)
//                    if (regState == RegistrationState.OK) {
//                        log.d(tag = TAG) { "Connection recovered during delay for $accountKey - SUCCESS" }
//                        markReconnectionSuccessful(accountKey, attemptNumber)
//                        return
//                    }
//                }
//
//                // Verificar una vez más antes del intento
//                val latestState = getReconnectionState(accountKey)
//                if (latestState == null || !latestState.isReconnecting || latestState.shouldStop) {
//                    log.d(tag = TAG) { "Reconnection cancelled after delay for $accountKey" }
//                    break
//                }
//
//                // CRÍTICO: Verificar red nuevamente después del delay
//                if (networkMonitor?.isNetworkAvailable() != true) {
//                    log.d(tag = TAG) { "Network lost during reconnection delay for $accountKey" }
//                    continue
//                }
//
//                // Actualizar estado antes del intento
//                currentState = currentState.copy(
//                    attempts = attemptNumber,
//                    lastAttemptTime = Clock.System.now().toEpochMilliseconds(),
//                    backoffDelay = delay,
//                    isNetworkAvailable = true,
//                    timestamp = Clock.System.now().toEpochMilliseconds()
//                )
//                updateReconnectionState(accountKey, currentState)
//
//                // Ejecutar intento de reconexión
//                val success = executeReconnectionAttempt(accountInfo, reason)
//
//                if (success) {
//                    log.d(tag = TAG) { "Reconnection successful for $accountKey on attempt $attemptNumber" }
//                    markReconnectionSuccessful(accountKey, attemptNumber)
//                    return
//
//                } else {
//                    log.d(tag = TAG) { "Reconnection attempt $attemptNumber failed for $accountKey" }
//
//                    // Actualizar estado con error
//                    currentState = currentState.copy(
//                        lastError = "Reconnection attempt $attemptNumber failed",
//                        timestamp = Clock.System.now().toEpochMilliseconds()
//                    )
//                    updateReconnectionState(accountKey, currentState)
//                }
//
//                // Actualizar estado actual para la siguiente iteración
//                currentState = getReconnectionState(accountKey) ?: break
//            }
//
//            // Verificar por qué terminó el bucle
//            val finalState = getReconnectionState(accountKey)
//            if (finalState?.isReconnecting == true && !finalState.shouldStop) {
//                if (finalState.attempts >= maxReconnectionAttempts) {
//                    log.w(tag = TAG) { "Max reconnection attempts reached for $accountKey" }
//                    markReconnectionFailed(accountKey, "Max reconnection attempts (${maxReconnectionAttempts}) reached")
//                } else {
//                    log.d(tag = TAG) { "Reconnection loop ended for other reason for $accountKey" }
//                    markReconnectionFailed(accountKey, "Reconnection loop ended unexpectedly")
//                }
//            }
//
//        } catch (e: Exception) {
//            if (e is CancellationException) {
//                log.d(tag = TAG) { "Reconnection loop cancelled for $accountKey" }
//            } else {
//                log.e(tag = TAG) { "Error in reconnection loop for $accountKey: ${e.message}" }
//                markReconnectionFailed(accountKey, "Exception: ${e.message}")
//            }
//        } finally {
//            // Limpiar job
//            reconnectionJobs.remove(accountKey)
//            log.d(tag = TAG) { "Reconnection loop finished for $accountKey" }
//        }
//    }

    /**
     * CORREGIDO: Ejecuta un intento de reconexión con verificación final
     */
    private suspend fun executeReconnectionAttempt(
        accountInfo: AccountInfo,
        reason: ReconnectionReason
    ): Boolean {
        return try {
            val accountKey = accountInfo.getAccountIdentity()

            log.d(tag = TAG) { "Executing reconnection attempt for $accountKey" }

            // CRÍTICO: Verificar una última vez antes de llamar al callback
            val preCallbackState = RegistrationStateManager.getAccountState(accountKey)
            if (preCallbackState == RegistrationState.OK) {
                log.d(tag = TAG) { "Account $accountKey already registered before callback - SUCCESS" }
                return true
            }

            // Llamar al callback de reconexión
            onReconnectionRequiredCallback?.invoke(accountInfo, reason)

            // CORREGIDO: Aumentar tiempo inicial de espera para registro SIP
            delay(5000) // Aumentado a 5 segundos para dar tiempo al registro SIP

            // CRÍTICO: Verificar múltiples veces con intervalos más largos
            repeat(5) { attempt ->
                // Verificar si fue cancelado durante la verificación
                if (!coroutineContext.isActive || isDisposing) {
                    return false
                }

                val registrationState = RegistrationStateManager.getAccountState(accountKey)
                log.d(tag = TAG) { "Registration check ${attempt + 1}/5 for $accountKey: state=$registrationState" }

                if (registrationState == RegistrationState.OK) {
                    log.d(tag = TAG) { "Reconnection successful for $accountKey after ${attempt + 1} checks" }

                    // CRÍTICO: Actualizar cache de éxito inmediatamente
                    connectionSuccessCache[accountKey] = Clock.System.now().toEpochMilliseconds()

                    // CRÍTICO: Notificar explícitamente que la cuenta está conectada
                    markAccountConnected(accountKey)

                    return true
                }

                // Esperar más tiempo entre verificaciones para registros SIP lentos
                if (attempt < 4) {
                    delay(3000) // 3 segundos entre verificaciones
                }
            }

            // ÚLTIMO INTENTO: Verificar una vez más después de todas las verificaciones
            val finalState = RegistrationStateManager.getAccountState(accountKey)
            log.d(tag = TAG) { "Final reconnection check for $accountKey - state: $finalState" }

            if (finalState == RegistrationState.OK) {
                connectionSuccessCache[accountKey] = Clock.System.now().toEpochMilliseconds()
                markAccountConnected(accountKey)
                return true
            }

            log.d(tag = TAG) { "Reconnection attempt failed for $accountKey - final state: $finalState" }
            false

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in reconnection attempt: ${e.message}" }
            false
        }
    }

    /**
     * CORREGIDO: Marca una reconexión como exitosa con limpieza
     */
    private suspend fun markReconnectionSuccessful(accountKey: String, attempts: Int) {
        log.d(tag = TAG) { "Marking reconnection as successful for $accountKey after $attempts attempts" }

        // CRÍTICO: Detener inmediatamente cualquier job activo
        val job = reconnectionJobs.remove(accountKey)
        job?.cancel()

        // Actualizar cache de éxito inmediatamente
        connectionSuccessCache[accountKey] = Clock.System.now().toEpochMilliseconds()

        // Crear estado de éxito y limpiar inmediatamente
        val successState = ReconnectionState(
            accountKey = accountKey,
            isReconnecting = false,
            shouldStop = true,
            attempts = attempts,
            lastError = null,
            timestamp = Clock.System.now().toEpochMilliseconds()
        )
        updateReconnectionState(accountKey, successState)

        // CRÍTICO: Limpiar estado inmediatamente, no esperar
        clearReconnectionState(accountKey)

        log.d(tag = TAG) { "Successfully marked and cleaned reconnection state for $accountKey" }
    }

    /**
     * CORREGIDO: Marca una reconexión como fallida
     */
    private fun markReconnectionFailed(accountKey: String, error: String) {
        val currentState = getReconnectionState(accountKey)
        if (currentState != null) {
            val failedState = currentState.copy(
                isReconnecting = false,
                shouldStop = true,
                lastError = error,
                timestamp = Clock.System.now().toEpochMilliseconds()
            )
            updateReconnectionState(accountKey, failedState)
        }
    }

    /**
     * CORREGIDO: Detiene la reconexión para una cuenta de forma segura y completa
     */
    fun stopReconnection(accountKey: String) {
        log.d(tag = TAG) { "Stopping reconnection for $accountKey" }

        // Marcar el estado como que debe detenerse
        val currentState = getReconnectionState(accountKey)
        if (currentState != null && currentState.isReconnecting) {
            val stoppedState = currentState.copy(
                shouldStop = true,
                timestamp = Clock.System.now().toEpochMilliseconds()
            )
            updateReconnectionState(accountKey, stoppedState)
        }

        // Cancelar job de manera segura
        val job = reconnectionJobs.remove(accountKey)
        if (job != null) {
            try {
                job.cancel()
                log.d(tag = TAG) { "Reconnection job cancelled for $accountKey" }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error cancelling reconnection job for $accountKey: ${e.message}" }
            }
        }

        // Limpiar estado después de un delay
        scope.launch {
            delay(2000)
            clearReconnectionState(accountKey)
        }
    }

    /**
     * CORREGIDO: Detiene todas las reconexiones
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
     * CORREGIDO: Maneja cambio de red con verificación de estados
     */
    fun onNetworkChanged(accounts: List<AccountInfo>) {
        if (isDisposing) return

        log.d(tag = TAG) { "Network changed, checking reconnection for ${accounts.size} accounts" }

        scope.launch {
            // Esperar que la red se estabilice
            delay(networkChangeDelay)
            if (isDisposing) return@launch

            val accountsNeedingReconnection = accounts.filter { accountInfo ->
                val accountKey = accountInfo.getAccountIdentity()
                val registrationState = RegistrationStateManager.getAccountState(accountKey)
                val needsReconnection = registrationState != RegistrationState.OK

                log.d(tag = TAG) { "Network change - Account $accountKey: state=$registrationState, needs reconnection=$needsReconnection" }
                needsReconnection
            }

            if (accountsNeedingReconnection.isNotEmpty()) {
                log.d(tag = TAG) { "Network changed - reconnecting ${accountsNeedingReconnection.size} accounts" }

                accountsNeedingReconnection.forEach { accountInfo ->
                    val accountKey = accountInfo.getAccountIdentity()

                    // Detener reconexión actual si existe
                    stopReconnection(accountKey)

                    // Dar un poco de tiempo antes de iniciar nueva reconexión
                    delay(1000)

                    if (!isDisposing) {
                        startReconnection(
                            accountInfo = accountInfo,
                            reason = ReconnectionReason.NETWORK_CHANGED,
                            isNetworkAvailable = true
                        )
                    }
                }
            } else {
                log.d(tag = TAG) { "All accounts properly registered after network change" }
            }
        }
    }

    /**
     * CORREGIDO: Maneja pérdida de red
     */
    fun onNetworkLost(accounts: List<AccountInfo>) {
        if (isDisposing) return

        log.d(tag = TAG) { "Network lost, preparing reconnection for ${accounts.size} accounts" }

        accounts.forEach { accountInfo ->
            val accountKey = accountInfo.getAccountIdentity()

            // Detener reconexión actual si existe
            stopReconnection(accountKey)

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
     * CORREGIDO: Maneja recuperación de red con verificaciones estrictas
     */
    fun onNetworkRecovered(accounts: List<AccountInfo>) {
        if (isDisposing) return

        log.d(tag = TAG) { "Network recovered, starting reconnection for ${accounts.size} accounts" }

        scope.launch {
            // Esperar que la red se estabilice completamente
            delay(5000) // Aumentado a 5 segundos
            if (isDisposing) return@launch

            // Verificar que la red realmente está disponible
            if (networkMonitor?.isNetworkAvailable() != true) {
                log.d(tag = TAG) { "Network not actually available after recovery signal, skipping" }
                return@launch
            }

            val accountsNeedingReconnection = accounts.filter { accountInfo ->
                val accountKey = accountInfo.getAccountIdentity()
                val registrationState = RegistrationStateManager.getAccountState(accountKey)
                val needsReconnection = registrationState != RegistrationState.OK

                log.d(tag = TAG) { "Network recovery - Account $accountKey: state=$registrationState, needs reconnection=$needsReconnection" }
                needsReconnection
            }

            if (accountsNeedingReconnection.isNotEmpty()) {
                log.d(tag = TAG) { "Network recovered - reconnecting ${accountsNeedingReconnection.size} accounts" }

                accountsNeedingReconnection.forEach { accountInfo ->
                    val accountKey = accountInfo.getAccountIdentity()

                    // Detener cualquier proceso de reconexión anterior
                    stopReconnection(accountKey)

                    // Dar tiempo entre reconexiones
                    delay(2000)

                    if (!isDisposing && networkMonitor?.isNetworkAvailable() == true) {
                        startReconnection(
                            accountInfo = accountInfo,
                            reason = ReconnectionReason.NETWORK_CHANGED,
                            isNetworkAvailable = true
                        )
                    }
                }
            } else {
                log.d(tag = TAG) { "All accounts properly registered after network recovery" }

                // Limpiar estados de reconexión para cuentas ya conectadas
                accounts.forEach { accountInfo ->
                    clearReconnectionState(accountInfo.getAccountIdentity())
                }
            }
        }
    }

    /**
     * Maneja fallo de registro
     */
    fun onRegistrationFailed(accountInfo: AccountInfo, error: String?) {
        if (isDisposing) return

        val accountKey = accountInfo.getAccountIdentity()
        log.d(tag = TAG) { "Registration failed for $accountKey: $error" }

        // CRÍTICO: Si el error indica problema de red, no iniciar reconexión inmediata
        val networkRelatedErrors = listOf(
            "Unable to resolve host",
            "No address associated with hostname",
            "Network is unreachable",
            "Connection timed out",
            "Connection refused"
        )

        val isNetworkError = error?.let { errorMsg ->
            networkRelatedErrors.any { networkError ->
                errorMsg.contains(networkError, ignoreCase = true)
            }
        } ?: false

        if (isNetworkError) {
            log.d(tag = TAG) { "Registration failed due to network error for $accountKey - checking network availability" }

            // Verificar si realmente hay red disponible
            val networkAvailable = networkMonitor?.isNetworkAvailable() ?: false

            if (!networkAvailable) {
                log.d(tag = TAG) { "Network not available for $accountKey - creating pending state instead of reconnecting" }

                // Crear estado de espera por red, NO reconexión activa
                val waitingState = ReconnectionState(
                    accountKey = accountKey,
                    isReconnecting = false, // NO reconectar sin red
                    attempts = 0,
                    reason = ReconnectionReason.REGISTRATION_FAILED,
                    isNetworkAvailable = false,
                    shouldStop = true,
                    lastError = error,
                    backoffDelay = baseReconnectionDelay
                )
                updateReconnectionState(accountKey, waitingState)
                return
            }
        }

        // Si no es error de red, proceder con reconexión normal
        startReconnection(
            accountInfo = accountInfo,
            reason = ReconnectionReason.REGISTRATION_FAILED,
            isNetworkAvailable = networkMonitor?.isNetworkAvailable() ?: false
        )
    }

    /**
     * Maneja desconexión de WebSocket
     */
    fun onWebSocketDisconnected(accountInfo: AccountInfo) {
        if (isDisposing) return

        val accountKey = accountInfo.getAccountIdentity()
        log.d(tag = TAG) { "WebSocket disconnected for $accountKey" }

        startReconnection(
            accountInfo = accountInfo,
            reason = ReconnectionReason.WEBSOCKET_DISCONNECTED,
            isNetworkAvailable = networkMonitor?.isNetworkAvailable() ?: false
        )
    }

    /**
     * Reconexión manual
     */
    fun manualReconnection(accountInfo: AccountInfo) {
        val accountKey = accountInfo.getAccountIdentity()
        log.d(tag = TAG) { "Manual reconnection triggered for $accountKey" }

        // Detener cualquier reconexión automática
        stopReconnection(accountKey)

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
     * CORREGIDO: Limpia el estado de reconexión
     */
    private fun clearReconnectionState(accountKey: String) {
        val currentStates = _reconnectionStateFlow.value.toMutableMap()
        if (currentStates.remove(accountKey) != null) {
            _reconnectionStateFlow.value = currentStates
            log.d(tag = TAG) { "Cleared reconnection state for $accountKey" }
        }

        // También limpiar del cache de éxito si es muy antiguo
        val successTime = connectionSuccessCache[accountKey]
        if (successTime != null && (Clock.System.now().toEpochMilliseconds() - successTime) > 300000) {
            connectionSuccessCache.remove(accountKey)
        }
    }

    // === MÉTODOS PÚBLICOS ===

    fun getReconnectionStates(): Map<String, ReconnectionState> = _reconnectionStateFlow.value

    fun isReconnecting(accountKey: String): Boolean {
        val state = getReconnectionState(accountKey)
        return state?.isReconnecting == true && state.shouldStop != true
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
                shouldStop = false,
                timestamp = Clock.System.now().toEpochMilliseconds()
            )
            updateReconnectionState(accountKey, resetState)
            log.d(tag = TAG) { "Reset reconnection attempts for $accountKey" }
        }
    }

    /**
     * NUEVO: Método para marcar una cuenta como conectada exitosamente
     */
    fun markAccountConnected(accountKey: String) {
        log.d(tag = TAG) { "Marking account $accountKey as connected - stopping any reconnection" }

        // Actualizar cache de éxito
        connectionSuccessCache[accountKey] = Clock.System.now().toEpochMilliseconds()

        // Detener reconexión si está activa
        stopReconnection(accountKey)
    }

    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        val states = _reconnectionStateFlow.value
        val activeJobs = reconnectionJobs.size

        return buildString {
            appendLine("=== RECONNECTION MANAGER DIAGNOSTIC ===")
            appendLine("Is Disposing: $isDisposing")
            appendLine("Active Reconnection Jobs: $activeJobs")
            appendLine("Tracked Accounts: ${states.size}")
            appendLine("Connection Success Cache: ${connectionSuccessCache.size}")
            appendLine("Max Attempts: $maxReconnectionAttempts")
            appendLine("Base Delay: ${baseReconnectionDelay}ms")
            appendLine("Max Delay: ${maxReconnectionDelay}ms")
            appendLine("Network Change Delay: ${networkChangeDelay}ms")
            appendLine("Network Monitor Available: ${networkMonitor != null}")

            if (states.isNotEmpty()) {
                appendLine("\n--- Reconnection States ---")
                states.forEach { (accountKey, state) ->
                    appendLine("$accountKey:")
                    appendLine("  Reconnecting: ${state.isReconnecting}")
                    appendLine("  Should Stop: ${state.shouldStop}")
                    appendLine("  Attempts: ${state.attempts}/${state.maxAttempts}")
                    appendLine("  Reason: ${state.reason}")
                    appendLine("  Network Available: ${state.isNetworkAvailable}")
                    appendLine("  Last Error: ${state.lastError ?: "None"}")
                    appendLine("  Backoff Delay: ${state.backoffDelay}ms")
                    appendLine("  Last Success: ${connectionSuccessCache[accountKey] ?: "Never"}")
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
     * NUEVO: Maneja pérdida específica de internet (diferente a pérdida de red)
     */
    fun onInternetLost(accounts: List<AccountInfo>) {
        if (isDisposing) return

        log.d(tag = TAG) { "Internet lost, stopping active reconnections and marking accounts for recovery" }

        accounts.forEach { accountInfo ->
            val accountKey = accountInfo.getAccountIdentity()

            // CRÍTICO: Detener inmediatamente cualquier reconexión activa que no tenga internet
            stopReconnection(accountKey)

            // Crear estado de espera específico para pérdida de internet
            val waitingForInternetState = ReconnectionState(
                accountKey = accountKey,
                isReconnecting = false, // NO reconectar sin internet
                attempts = 0,
                reason = ReconnectionReason.NETWORK_LOST,
                isNetworkAvailable = false,
                backoffDelay = baseReconnectionDelay,
                lastError = "Internet connection lost - waiting for recovery",
                shouldStop = true // Detener hasta que regrese internet
            )

            updateReconnectionState(accountKey, waitingForInternetState)
            log.d(tag = TAG) { "Account $accountKey marked as waiting for internet recovery" }
        }
    }

    /**
     * NUEVO: Maneja recuperación específica de internet
     */
    /**
     * NUEVO: Maneja recuperación específica de internet
     */
    fun onInternetRecovered(accounts: List<AccountInfo>) {
        if (isDisposing) return

        log.d(tag = TAG) { "Internet recovered, starting reconnection for ${accounts.size} accounts" }

        scope.launch {
            // Esperar que la conexión de internet se estabilice completamente
            delay(6000) // 6 segundos para asegurar estabilidad real
            if (isDisposing) return@launch

            // CRÍTICO: Verificar que realmente hay internet disponible
            if (networkMonitor?.hasInternet() != true) {
                log.d(tag = TAG) { "Internet not actually available after recovery signal, retrying in 10s" }
                delay(10000)
                if (networkMonitor?.hasInternet() != true) {
                    log.d(tag = TAG) { "Internet still not available, skipping reconnection" }
                    return@launch
                }
            }

            log.d(tag = TAG) { "Internet confirmed available, proceeding with reconnections" }

            // Verificar TODAS las cuentas una vez más
            val accountsStillNeedingReconnection = accounts.filter { accountInfo ->
                val accountKey = accountInfo.getAccountIdentity()
                val registrationState = RegistrationStateManager.getAccountState(accountKey)
                val needsReconnection = registrationState != RegistrationState.OK

                log.d(tag = TAG) { "Internet recovery - Account $accountKey: state=$registrationState, needs reconnection=$needsReconnection" }
                needsReconnection
            }

            if (accountsStillNeedingReconnection.isNotEmpty()) {
                log.d(tag = TAG) { "Starting reconnection for ${accountsStillNeedingReconnection.size} accounts after internet recovery" }

                // CORREGIDO: Usar for loop en lugar de forEach para permitir break
                for (index in accountsStillNeedingReconnection.indices) {
                    val accountInfo = accountsStillNeedingReconnection[index]
                    val accountKey = accountInfo.getAccountIdentity()

                    log.d(tag = TAG) { "Reconnecting account ${index + 1}/${accountsStillNeedingReconnection.size}: $accountKey" }

                    // Limpiar cualquier estado previo
                    stopReconnection(accountKey)

                    // Delay escalonado entre cuentas
                    delay(2000L * (index + 1))

                    if (!isDisposing && networkMonitor?.hasInternet() == true) {
                        startReconnection(
                            accountInfo = accountInfo,
                            reason = ReconnectionReason.NETWORK_CHANGED,
                            isNetworkAvailable = true
                        )

                        log.d(tag = TAG) { "Reconnection started for $accountKey" }
                    } else {
                        log.d(tag = TAG) { "Skipping remaining reconnections - internet lost during process" }
                        break // Ahora funciona porque estamos en un for loop
                    }
                }
            } else {
                log.d(tag = TAG) { "All accounts properly registered after internet recovery" }

                // Limpiar estados de reconexión para cuentas ya conectadas
                accounts.forEach { accountInfo ->
                    clearReconnectionState(accountInfo.getAccountIdentity())
                }
            }
        }
    }

    /**
     * CORREGIDO: Limpieza de recursos completa
     */
    fun dispose() {
        log.d(tag = TAG) { "Disposing ReconnectionManager..." }

        isDisposing = true

        stopAllReconnections()

        // Limpiar estados y cachés
        _reconnectionStateFlow.value = emptyMap()
        connectionSuccessCache.clear()

        // Cancelar scope
        scope.cancel()

        log.d(tag = TAG) { "ReconnectionManager disposed completely" }
    }
}