package com.eddyslarez.siplibrary.core

import android.content.ContentValues.TAG
import com.eddyslarez.siplibrary.data.models.RegistrationState
import kotlinx.coroutines.*
import kotlin.math.*
import com.eddyslarez.siplibrary.data.models.*
import com.eddyslarez.siplibrary.utils.*

import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlin.math.*

/**
 * ReconnectionManager - Gestiona la reconexión inteligente de cuentas SIP
 * Trabaja en conjunto con NetworkManager para reconectar de manera eficiente
 */
class ReconnectionManager(
    private val sipCoreManager: SipCoreManagerInterface
) {
    companion object {
        private const val TAG = "ReconnectionManager"
        private const val MAX_RECONNECTION_ATTEMPTS = 5
        private const val RECONNECTION_BASE_DELAY = 2000L // 2 segundos base
        private const val RECONNECTION_MAX_DELAY = 30000L // 30 segundos máximo
        private const val NETWORK_STABILITY_WAIT = 2000L // Esperar estabilidad de red
        private const val REGISTRATION_TIMEOUT = 15000L // Timeout para esperar registro
    }

    // Estado interno
    private val reconnectionAttempts = mutableMapOf<String, Int>()
    private var reconnectionJob: Job? = null
    private var isReconnectionInProgress = false
    private val activeReconnectionJobs = mutableMapOf<String, Job>()

    // Scope para operaciones async
    private val reconnectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Obtiene información de estado de reconexión
     */
    fun getReconnectionStatus(): Map<String, Any> {
        return mapOf(
            "isReconnectionInProgress" to isReconnectionInProgress,
            "activeReconnectionJobs" to activeReconnectionJobs.size,
            "reconnectionAttempts" to reconnectionAttempts.toMap(),
            "accountsNeedingReconnection" to getAccountsNeedingReconnection().size
        )
    }

    /**
     * Verifica y corrige el estado de conectividad de todas las cuentas
     */
    suspend fun verifyAndFixConnectivity() {
        if (!sipCoreManager.isNetworkAvailable()) {
            log.d(tag = TAG) { "Network not available, cannot verify connectivity" }
            return
        }

        log.d(tag = TAG) { "🔍 Verifying and fixing connectivity for all accounts" }

        sipCoreManager.getActiveAccounts().values.forEach { accountInfo ->
            val accountKey = "${accountInfo.username}@${accountInfo.domain}"

            try {
                val webSocketConnected = accountInfo.webSocketClient?.isConnected() == true
                val isRegistered = accountInfo.isRegistered
                val registrationState = sipCoreManager.getRegistrationState(accountKey)

                log.d(tag = TAG) {
                    "Account $accountKey - WebSocket: $webSocketConnected, Registered: $isRegistered, State: $registrationState"
                }

                // Si debería estar registrada pero no lo está, intentar reconectar
                if (!isRegistered && registrationState != RegistrationState.IN_PROGRESS) {
                    log.d(tag = TAG) { "Account $accountKey needs connectivity fix" }

                    reconnectionScope.launch {
                        reconnectAccountWithRetry(accountInfo)
                    }
                }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error verifying connectivity for $accountKey: ${e.message}" }
            }
        }
    }

    /**
     * Cleanup y dispose
     */
    fun dispose() {
        try {
            // Cancelar todas las operaciones
            reconnectionJob?.cancel()
            activeReconnectionJobs.values.forEach { it.cancel() }
            reconnectionScope.cancel()

            // Limpiar estado
            reconnectionAttempts.clear()
            activeReconnectionJobs.clear()
            isReconnectionInProgress = false

            log.d(tag = TAG) { "ReconnectionManager disposed" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error disposing ReconnectionManager: ${e.message}" }
        }
    }


    /**
     * Interface para integración con SipCoreManager
     * Define los métodos que ReconnectionManager necesita del SipCoreManager
     */
    interface SipCoreManagerInterface {
        fun isNetworkAvailable(): Boolean
        fun getActiveAccounts(): Map<String, AccountInfo>
        fun connectWebSocketAndRegister(accountInfo: AccountInfo)
        fun updateRegistrationState(accountKey: String, state: RegistrationState)
        fun getRegistrationState(accountKey: String): RegistrationState
        fun getNetworkConnectivityListener(): NetworkConnectivityListener?
    }

    suspend fun startReconnectionProcess() {
        // Evitar múltiples procesos de reconexión simultáneos
        if (isReconnectionInProgress) {
            log.d(tag = TAG) { "Reconnection already in progress, skipping" }
            return
        }

        // Cancelar reconexión anterior si existe
        reconnectionJob?.cancel()

        reconnectionJob = reconnectionScope.launch {
            try {
                isReconnectionInProgress = true
                log.d(tag = TAG) { "🔄 Starting reconnection process" }

                // Esperar a que la red se estabilice
                delay(NETWORK_STABILITY_WAIT)

                // Obtener cuentas que necesitan reconexión
                val accountsToReconnect = getAccountsNeedingReconnection()

                if (accountsToReconnect.isEmpty()) {
                    log.d(tag = TAG) { "No accounts need reconnection" }
                    return@launch
                }

                log.d(tag = TAG) { "🔄 Reconnecting ${accountsToReconnect.size} accounts" }

                // Notificar inicio de reconexión
                sipCoreManager.getNetworkConnectivityListener()?.onReconnectionStarted()

                // Reconectar cuentas en paralelo
                val reconnectionJobs = accountsToReconnect.map { accountInfo ->
                    async {
                        reconnectAccountWithRetry(accountInfo)
                    }
                }

                // Esperar que todas las reconexiones terminen
                val results = reconnectionJobs.awaitAll()
                val successfulReconnections = results.count { it }

                log.d(tag = TAG) {
                    "🔄 Reconnection process completed: $successfulReconnections/${results.size} successful"
                }

                // Notificar resultado
                sipCoreManager.getNetworkConnectivityListener()?.onReconnectionCompleted(
                    successful = successfulReconnections > 0
                )

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in reconnection process: ${e.message}" }
                sipCoreManager.getNetworkConnectivityListener()?.onReconnectionCompleted(false)
            } finally {
                isReconnectionInProgress = false
            }
        }
    }

    /**
     * Obtiene las cuentas que necesitan reconectarse
     */
    private fun getAccountsNeedingReconnection(): List<AccountInfo> {
        return sipCoreManager.getActiveAccounts().values.filter { accountInfo ->
            val accountKey = "${accountInfo.username}@${accountInfo.domain}"

            // Criterios para reconexión:
            // 1. No está registrada actualmente
            // 2. Tenía registro previamente (está en la lista de intentos o debería estar registrada)
            // 3. WebSocket no está conectado o está en estado inválido
            val needsReconnection = !accountInfo.isRegistered &&
                    (reconnectionAttempts.containsKey(accountKey) ||
                            accountInfo.webSocketClient?.isConnected() != true)

            if (needsReconnection) {
                log.d(tag = TAG) { "Account needs reconnection: $accountKey" }
            }

            needsReconnection
        }
    }

    /**
     * Reconecta una cuenta específica con lógica de retry inteligente
     */
    private suspend fun reconnectAccountWithRetry(accountInfo: AccountInfo): Boolean {
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"

        // Cancelar job anterior para esta cuenta si existe
        activeReconnectionJobs[accountKey]?.cancel()

        return try {
            activeReconnectionJobs[accountKey] = coroutineContext[Job] as Job

            var attempts = reconnectionAttempts[accountKey] ?: 0
            var lastError: Exception? = null

            while (attempts < MAX_RECONNECTION_ATTEMPTS &&
                sipCoreManager.isNetworkAvailable() &&
                !accountInfo.isRegistered
            ) {

                attempts++
                reconnectionAttempts[accountKey] = attempts

                log.d(tag = TAG) { "🔄 Reconnection attempt $attempts/$MAX_RECONNECTION_ATTEMPTS for: $accountKey" }

                try {
                    // Actualizar estado de registro
                    sipCoreManager.updateRegistrationState(
                        accountKey,
                        RegistrationState.IN_PROGRESS
                    )

                    // Limpiar conexión WebSocket anterior si es necesario
                    if (shouldResetWebSocket(accountInfo)) {
                        resetWebSocketConnection(accountInfo)
                        delay(1000) // Esperar cierre completo
                    }

                    // Intentar reconectar
                    sipCoreManager.connectWebSocketAndRegister(accountInfo)

                    // Esperar resultado de la conexión con timeout
                    val success = waitForReconnectionResult(accountInfo, REGISTRATION_TIMEOUT)

                    if (success) {
                        log.d(tag = TAG) { "✅ Successfully reconnected: $accountKey" }
                        reconnectionAttempts.remove(accountKey) // Limpiar intentos
                        return true
                    } else {
                        log.w(tag = TAG) { "❌ Reconnection failed for: $accountKey (attempt $attempts)" }

                        if (attempts < MAX_RECONNECTION_ATTEMPTS) {
                            val delayMs = calculateReconnectionDelay(attempts)
                            log.d(tag = TAG) { "⏳ Waiting ${delayMs}ms before next attempt for: $accountKey" }
                            delay(delayMs)
                        }
                    }

                } catch (e: Exception) {
                    lastError = e
                    log.e(tag = TAG) { "💥 Error during reconnection attempt $attempts for $accountKey: ${e.message}" }

                    if (attempts < MAX_RECONNECTION_ATTEMPTS) {
                        val delayMs = calculateReconnectionDelay(attempts)
                        delay(delayMs)
                    }
                }
            }

            // Si llegamos aquí, falló la reconexión
            if (attempts >= MAX_RECONNECTION_ATTEMPTS) {
                log.e(tag = TAG) { "🚫 Max reconnection attempts reached for: $accountKey" }
                sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)
                reconnectionAttempts.remove(accountKey)
            }

            false

        } catch (e: Exception) {
            log.e(tag = TAG) { "💥 Critical error in reconnection for $accountKey: ${e.message}" }
            sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)
            false
        } finally {
            activeReconnectionJobs.remove(accountKey)
        }
    }

    /**
     * Determina si se debe resetear la conexión WebSocket
     */
    private fun shouldResetWebSocket(accountInfo: AccountInfo): Boolean {
        val webSocketClient = accountInfo.webSocketClient
        return when {
            webSocketClient == null -> false // No hay WebSocket para resetear
            webSocketClient.isConnected() -> {
                // WebSocket reporta estar conectado, pero verificar si realmente funciona
                // Esto es útil cuando la conexión está "zombi"
                log.d(tag = TAG) { "WebSocket reports connected, checking if reset needed" }
                true // Por seguridad, resetear para asegurar estado limpio
            }

            else -> false // WebSocket ya está desconectado
        }
    }

    /**
     * Resetea la conexión WebSocket de manera segura
     */
    private suspend fun resetWebSocketConnection(accountInfo: AccountInfo) {
        try {
            log.d(tag = TAG) { "🔄 Resetting WebSocket connection for ${accountInfo.username}@${accountInfo.domain}" }

            accountInfo.webSocketClient?.let { webSocket ->
                // Detener timers primero
                webSocket.stopPingTimer()
                webSocket.stopRegistrationRenewalTimer()

                // Cerrar conexión
                webSocket.close(1000, "Reconnection reset")

                // Limpiar referencia
                accountInfo.webSocketClient = null
            }

        } catch (e: Exception) {
            log.w(tag = TAG) { "⚠️ Error resetting WebSocket: ${e.message}" }
            // Forzar limpieza
            accountInfo.webSocketClient = null
        }
    }

    /**
     * Espera el resultado de la reconexión con timeout
     */
    private suspend fun waitForReconnectionResult(
        accountInfo: AccountInfo,
        timeoutMs: Long
    ): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (accountInfo.isRegistered) {
                log.d(tag = TAG) { "✅ Registration confirmed for ${accountInfo.username}@${accountInfo.domain}" }
                return true
            }

            // Verificar también si hubo un error definitivo
            val accountKey = "${accountInfo.username}@${accountInfo.domain}"
            val currentState = sipCoreManager.getRegistrationState(accountKey)
            if (currentState == RegistrationState.FAILED) {
                log.d(tag = TAG) { "❌ Registration failed definitively for $accountKey" }
                return false
            }

            delay(500) // Verificar cada 500ms
        }

        log.w(tag = TAG) { "⏰ Registration timeout for ${accountInfo.username}@${accountInfo.domain}" }
        return false
    }

    /**
     * Calcula el delay para el siguiente intento usando backoff exponencial
     */
    private fun calculateReconnectionDelay(attempt: Int): Long {
        // Backoff exponencial con jitter para evitar thundering herd
        val baseDelay = RECONNECTION_BASE_DELAY * (1 shl (attempt - 1)) // 2^(attempt-1) * base
        val jitter = (0..1000).random() // Jitter de 0-1 segundo
        val delay = min(baseDelay + jitter, RECONNECTION_MAX_DELAY)

        return delay
    }

    /**
     * Fuerza reconexión manual de todas las cuentas
     */
    fun forceReconnection() {
        if (!sipCoreManager.isNetworkAvailable()) {
            log.w(tag = TAG) { "Cannot force reconnection - network not available" }
            return
        }

        log.d(tag = TAG) { "🔧 Forcing manual reconnection" }

        reconnectionScope.launch {
            // Resetear contadores de intentos para dar nueva oportunidad
            reconnectionAttempts.clear()

            // Iniciar proceso de reconexión
            startReconnectionProcess()
        }
    }

    /**
     * Fuerza reconexión de una cuenta específica
     */
    fun forceAccountReconnection(username: String, domain: String) {
        val accountKey = "$username@$domain"
        val accountInfo = sipCoreManager.getActiveAccounts()[accountKey]

        if (accountInfo == null) {
            log.w(tag = TAG) { "Account not found for forced reconnection: $accountKey" }
            return
        }

        if (!sipCoreManager.isNetworkAvailable()) {
            log.w(tag = TAG) { "Cannot force account reconnection - network not available" }
            return
        }

        log.d(tag = TAG) { "🔧 Forcing manual reconnection for: $accountKey" }

        reconnectionScope.launch {
            // Resetear intentos para esta cuenta
            reconnectionAttempts.remove(accountKey)

            // Reconectar
            val success = reconnectAccountWithRetry(accountInfo)
            log.d(tag = TAG) { "Force reconnection result for $accountKey: $success" }
        }
    }

    /**
     * Limpia el estado de reconexión para una cuenta específica
     */
    fun clearReconnectionState(username: String, domain: String) {
        val accountKey = "$username@$domain"
        reconnectionAttempts.remove(accountKey)
        activeReconnectionJobs[accountKey]?.cancel()
        activeReconnectionJobs.remove(accountKey)

        log.d(tag = TAG) { "🧹 Cleared reconnection state for: $accountKey" }
    }
}