package com.eddyslarez.siplibrary.core

import com.eddyslarez.siplibrary.data.database.DatabaseManager
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.models.RegistrationState
import com.eddyslarez.siplibrary.data.services.sip.SipMessageHandler
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SipReconnectionManager(
    private val networkManager: NetworkManager,
    private val messageHandler: SipMessageHandler,
    private val sipCoreManager: SipCoreManager
) {
    companion object {
        private const val TAG = "SipReconnectionManager"
        private const val MAX_RECONNECTION_ATTEMPTS = 5
        private const val RECONNECTION_BASE_DELAY = 2000L // 2 segundos base
        private const val RECONNECTION_MAX_DELAY = 30000L // 30 segundos máximo
        private const val NETWORK_STABILITY_CHECK_DELAY = 3000L // Esperar estabilidad de red
        private const val ACCOUNT_RECOVERY_TIMEOUT = 10000L // Timeout para recuperar cuentas
    }

    // Estado de reconexión
    private var reconnectionJob: Job? = null
    private var networkStabilityJob: Job? = null
    private var isNetworkAvailable = false
    private var wasDisconnectedDueToNetwork = false
    private val reconnectionAttempts = mutableMapOf<String, Int>()
    private val accountRecoveryAttempts = AtomicInteger(0)

    // Cache de cuentas para reconexión
    private val cachedAccounts = ConcurrentHashMap<String, AccountInfo>()
    private var lastAccountSync = 0L

    // Listeners
    private var reconnectionListener: ReconnectionListener? = null

    /**
     * Inicializar el manager de reconexión
     */
    fun initialize() {
        log.d(tag = TAG) { "Initializing SipReconnectionManager" }
        setupNetworkListener()
        checkInitialNetworkState()
        syncAccountsFromCoreManager()
        startPeriodicAccountSync()
    }

    /**
     * Sincronizar cuentas desde SipCoreManager
     */
    private fun syncAccountsFromCoreManager() {
        try {
            val activeAccounts = sipCoreManager.activeAccounts
            cachedAccounts.clear()
            cachedAccounts.putAll(activeAccounts)
            lastAccountSync = System.currentTimeMillis()
            log.d(tag = TAG) { "Synced ${cachedAccounts.size} accounts from SipCoreManager" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error syncing accounts from SipCoreManager: ${e.message}" }
        }
    }

    /**
     * Sincronización periódica de cuentas
     */
    private fun startPeriodicAccountSync() {
        CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    delay(30000L) // Cada 30 segundos
                    syncAccountsFromCoreManager()
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in periodic account sync: ${e.message}" }
                }
            }
        }
    }

    /**
     * Configurar el listener de conectividad de red
     */
    private fun setupNetworkListener() {
        networkManager.setConnectivityListener(object : NetworkConnectivityListener {
            override fun onNetworkLost() {
                handleNetworkLost()
            }

            override fun onNetworkRestored() {
                handleNetworkRestored()
            }

            override fun onReconnectionStarted() {
                log.d(tag = TAG) { "NetworkManager: Reconnection started" }
                reconnectionListener?.onReconnectionStarted()
            }

            override fun onReconnectionCompleted(successful: Boolean) {
                log.d(tag = TAG) { "NetworkManager: Reconnection completed - Success: $successful" }
                reconnectionListener?.onReconnectionCompleted(successful)
            }
        })

        // Obtener estado inicial de red
        isNetworkAvailable = networkManager.isNetworkAvailable()
    }

    /**
     * Verificar estado inicial de la red
     */
    private fun checkInitialNetworkState() {
        try {
            isNetworkAvailable = networkManager.isNetworkAvailable()
            log.d(tag = TAG) { "Initial network state: $isNetworkAvailable" }

            // Si no hay conectividad inicial, preparar para reconexión
            if (!isNetworkAvailable) {
                wasDisconnectedDueToNetwork = true
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error checking network state: ${e.message}" }
            isNetworkAvailable = false
            wasDisconnectedDueToNetwork = true
        }
    }

    /**
     * Manejar pérdida de red
     */
    private fun handleNetworkLost() {
        // Cancelar job de estabilidad de red anterior
        networkStabilityJob?.cancel()

        val networkInfo = networkManager.getNetworkInfo()
        val isStillConnected = networkInfo["isAvailable"] as? Boolean ?: false

        if (isStillConnected) {
            log.w(tag = TAG) { "NetworkManager reported loss but network is still available - double checking" }

            // Verificar nuevamente en 1 segundo
            networkStabilityJob = CoroutineScope(Dispatchers.IO).launch {
                delay(1000L)
                val recheckInfo = networkManager.getNetworkInfo()
                val stillConnected = recheckInfo["isAvailable"] as? Boolean ?: false

                if (stillConnected) {
                    log.d(tag = TAG) { "Network is actually still available - ignoring false alarm" }
                    return@launch
                }

                // Proceder con pérdida de red real
                processNetworkLoss()
            }
            return
        }

        processNetworkLoss()
    }

    /**
     * Procesar pérdida real de red
     */
    private fun processNetworkLoss() {
        isNetworkAvailable = false
        wasDisconnectedDueToNetwork = true

        log.w(tag = TAG) { "🚨 NETWORK LOST - Preparing for reconnection. Cached accounts: ${cachedAccounts.size}" }

        // Cancelar trabajos de reconexión en curso
        reconnectionJob?.cancel()

        // Sincronizar cuentas antes de la pérdida
        syncAccountsFromCoreManager()

        // Marcar todas las cuentas como desconectadas
        cachedAccounts.values.forEach { account ->
            account.isRegistered = false
            val accountKey = "${account.username}@${account.domain}"
            try {
                sipCoreManager.updateRegistrationState(accountKey, RegistrationState.NONE)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error updating registration state for $accountKey: ${e.message}" }
            }
        }

        reconnectionListener?.onNetworkLost()
    }

    /**
     * Manejar restauración de red
     */
    private fun handleNetworkRestored() {
        // Cancelar job anterior de estabilidad
        networkStabilityJob?.cancel()

        val wasDisconnected = !isNetworkAvailable || wasDisconnectedDueToNetwork

        log.d(tag = TAG) { "🌐 NETWORK RESTORED - Was disconnected: $wasDisconnected, Cached accounts: ${cachedAccounts.size}" }

        isNetworkAvailable = true

        if (wasDisconnected) {
            wasDisconnectedDueToNetwork = false
            reconnectionListener?.onNetworkRestored()

            // Esperar a que la red se estabilice antes de reconectar
            networkStabilityJob = CoroutineScope(Dispatchers.IO).launch {
                log.d(tag = TAG) { "⏳ Waiting for network stability..." }
                delay(NETWORK_STABILITY_CHECK_DELAY)

                // Verificar que la red sigue siendo estable
                if (networkManager.isNetworkAvailable()) {
                    log.d(tag = TAG) { "✅ Network is stable, starting reconnection process" }
                    startReconnectionProcess()
                } else {
                    log.w(tag = TAG) { "❌ Network became unstable during stabilization wait" }
                    handleNetworkLost()
                }
            }
        }
    }

    /**
     * Iniciar proceso de reconexión automática
     */
    fun startReconnectionProcess(accountsToReconnect: List<AccountInfo>? = null) {
        // Cancelar reconexión anterior si está en curso
        reconnectionJob?.cancel()

        reconnectionJob = CoroutineScope(Dispatchers.IO).launch {
            log.d(tag = TAG) { "🔄 Starting automatic reconnection process" }

            try {
                // Obtener cuentas para reconectar
                val accounts = getAccountsForReconnection(accountsToReconnect)

                if (accounts.isEmpty()) {
                    log.w(tag = TAG) { "⚠️ No accounts available for reconnection - attempting recovery" }
                    val recoveredAccounts = recoverAccountsFromDatabase()

                    if (recoveredAccounts.isEmpty()) {
                        log.e(tag = TAG) { "❌ No accounts could be recovered for reconnection" }
                        return@launch
                    }

                    log.d(tag = TAG) { "✅ Recovered ${recoveredAccounts.size} accounts from database" }
                    reconnectAccounts(recoveredAccounts)
                } else {
                    log.d(tag = TAG) { "🎯 Reconnecting ${accounts.size} accounts" }
                    reconnectAccounts(accounts)
                }

            } catch (e: Exception) {
                log.e(tag = TAG) { "❌ Error in reconnection process: ${e.message}" }
                reconnectionListener?.onReconnectionCompleted(false)
            }
        }
    }

    /**
     * Obtener cuentas para reconexión con fallbacks
     */
    private suspend fun getAccountsForReconnection(providedAccounts: List<AccountInfo>?): List<AccountInfo> {
        return when {
            // Usar cuentas proporcionadas si están disponibles
            !providedAccounts.isNullOrEmpty() -> {
                log.d(tag = TAG) { "Using provided accounts: ${providedAccounts.size}" }
                providedAccounts
            }

            // Usar cuentas en caché
            cachedAccounts.isNotEmpty() -> {
                log.d(tag = TAG) { "Using cached accounts: ${cachedAccounts.size}" }
                cachedAccounts.values.toList()
            }

            // Sincronizar desde SipCoreManager
            else -> {
                log.d(tag = TAG) { "Attempting to sync accounts from SipCoreManager" }
                syncAccountsFromCoreManager()

                if (cachedAccounts.isNotEmpty()) {
                    cachedAccounts.values.toList()
                } else {
                    // Último recurso: recuperar desde base de datos
                    log.w(tag = TAG) { "No accounts in cache, attempting database recovery" }
                    recoverAccountsFromDatabase()
                }
            }
        }
    }

    /**
     * Recuperar cuentas desde la base de datos
     */
    private suspend fun recoverAccountsFromDatabase(): List<AccountInfo> {
        return try {
            val recoveryAttempt = accountRecoveryAttempts.incrementAndGet()
            log.d(tag = TAG) { "🔍 Database recovery attempt #$recoveryAttempt" }

            withTimeout(ACCOUNT_RECOVERY_TIMEOUT) {
                val databaseManager = DatabaseManager.getInstance(sipCoreManager.application)
                val registeredAccounts = databaseManager.getRegisteredSipAccounts().first()

                val recoveredAccounts = registeredAccounts.mapNotNull { dbAccount ->
                    try {
                        val accountInfo = AccountInfo(
                            username = dbAccount.username,
                            password = dbAccount.password,
                            domain = dbAccount.domain
                        ).apply {
                            token = dbAccount.pushToken ?: ""
                            provider = dbAccount.pushProvider ?: "fcm"
                            userAgent = sipCoreManager.userAgent()
                            isRegistered = false // Will be set during registration
                        }

                        // Actualizar cache
                        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
                        cachedAccounts[accountKey] = accountInfo

                        // Actualizar SipCoreManager
                        sipCoreManager.activeAccounts[accountKey] = accountInfo

                        log.d(tag = TAG) { "✅ Recovered account: $accountKey" }
                        accountInfo

                    } catch (e: Exception) {
                        log.e(tag = TAG) { "❌ Error recovering account ${dbAccount.username}@${dbAccount.domain}: ${e.message}" }
                        null
                    }
                }

                log.d(tag = TAG) { "📊 Database recovery completed: ${recoveredAccounts.size}/${registeredAccounts.size} accounts recovered" }
                recoveredAccounts
            }
        } catch (e: TimeoutCancellationException) {
            log.e(tag = TAG) { "⏰ Database recovery timed out" }
            emptyList()
        } catch (e: Exception) {
            log.e(tag = TAG) { "💥 Database recovery failed: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Reconectar lista de cuentas
     */
    private suspend fun reconnectAccounts(accounts: List<AccountInfo>) = coroutineScope {
        reconnectionListener?.onReconnectionStarted()
        var successfulReconnections = 0

        // Reconectar cada cuenta en paralelo pero controlado
        val reconnectionJobs = accounts.map { accountInfo ->
            async(Dispatchers.IO) {
                if (reconnectAccountWithRetry(accountInfo)) {
                    successfulReconnections++
                    true
                } else {
                    false
                }
            }
        }

        // Esperar a que todas las reconexiones terminen
        val results = reconnectionJobs.awaitAll()
        val successful = successfulReconnections > 0

        log.d(tag = TAG) { "🏁 Reconnection process completed: $successfulReconnections/${accounts.size} successful" }
        reconnectionListener?.onReconnectionCompleted(successful)
    }


    /**
     * Reconectar una cuenta específica con lógica de retry mejorada
     */
    suspend fun reconnectAccountWithRetry(accountInfo: AccountInfo): Boolean {
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"

        if (!networkManager.isNetworkAvailable()) {
            log.w(tag = TAG) { "🌐 No connectivity, skipping reconnection for: $accountKey" }
            return false
        }

        var attempts = reconnectionAttempts[accountKey] ?: 0
        var lastError: Exception? = null

        while (attempts < MAX_RECONNECTION_ATTEMPTS &&
            networkManager.isNetworkAvailable() &&
            !accountInfo.isRegistered) {

            attempts++
            reconnectionAttempts[accountKey] = attempts

            log.d(tag = TAG) { "🔄 Reconnection attempt $attempts/$MAX_RECONNECTION_ATTEMPTS for: $accountKey" }

            try {
                reconnectionListener?.onReconnectionAttempt(accountKey, attempts)

                // Actualizar estado de reconexión
                sipCoreManager.updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)

                // Cerrar WebSocket existente si está abierto pero no funcional
                cleanupExistingConnection(accountInfo)

                // Intentar reconectar
                val success = reconnectionListener?.onReconnectAccount(accountInfo) ?: false

                if (success) {
                    // Esperar resultado de la conexión con timeout más largo
                    val reconnected = waitForReconnectionResult(accountInfo, 15000L)

                    if (reconnected) {
                        log.d(tag = TAG) { "✅ Successfully reconnected: $accountKey" }
                        reconnectionAttempts.remove(accountKey)
                        reconnectionListener?.onAccountReconnected(accountKey, true)

                        // Actualizar cache
                        cachedAccounts[accountKey] = accountInfo
                        return true

                    } else {
                        log.w(tag = TAG) { "⚠️ Reconnection failed for: $accountKey (attempt $attempts)" }
                        sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)
                        reconnectionListener?.onAccountReconnected(accountKey, false)
                    }
                } else {
                    log.e(tag = TAG) { "❌ Failed to initiate reconnection for: $accountKey" }
                    sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)
                    reconnectionListener?.onAccountReconnected(accountKey, false)
                }

                // Esperar antes del siguiente intento con backoff exponencial
                if (attempts < MAX_RECONNECTION_ATTEMPTS) {
                    val delayMs = calculateReconnectionDelay(attempts)
                    log.d(tag = TAG) { "⏳ Waiting ${delayMs}ms before next reconnection attempt for: $accountKey" }
                    delay(delayMs)
                }

            } catch (e: Exception) {
                lastError = e
                log.e(tag = TAG) { "💥 Error during reconnection attempt $attempts for $accountKey: ${e.message}" }
                sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)
                reconnectionListener?.onAccountReconnected(accountKey, false)

                if (attempts < MAX_RECONNECTION_ATTEMPTS) {
                    val delayMs = calculateReconnectionDelay(attempts)
                    delay(delayMs)
                }
            }
        }

        // Todas las reconexiones fallaron
        log.e(tag = TAG) { "❌ Max reconnection attempts reached for: $accountKey. Last error: ${lastError?.message}" }
        reconnectionAttempts.remove(accountKey)
        sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)
        reconnectionListener?.onReconnectionFailed(accountKey)

        return false
    }

    /**
     * Limpiar conexión existente
     */
    private suspend fun cleanupExistingConnection(accountInfo: AccountInfo) {
        try {
            accountInfo.webSocketClient?.let { webSocket ->
                if (webSocket.isConnected()) {
                    log.d(tag = TAG) { "🧹 Cleaning up existing WebSocket connection" }
                    webSocket.close()
                    delay(1500) // Esperar más tiempo para cierre completo
                }
            }
        } catch (e: Exception) {
            log.w(tag = TAG) { "⚠️ Error cleaning up existing WebSocket: ${e.message}" }
        }
    }

    /**
     * Esperar el resultado de la reconexión con timeout y checks más frecuentes
     */
    private suspend fun waitForReconnectionResult(accountInfo: AccountInfo, timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        val checkInterval = 250L // Verificar cada 250ms

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // Verificar si la cuenta está registrada
            if (accountInfo.isRegistered) {
                log.d(tag = TAG) { "✅ Account registration confirmed: ${accountInfo.username}@${accountInfo.domain}" }
                return true
            }

            // Verificar si perdimos conectividad
            if (!networkManager.isNetworkAvailable()) {
                log.w(tag = TAG) { "🌐 Network lost during reconnection wait" }
                return false
            }

            delay(checkInterval)
        }

        log.w(tag = TAG) { "⏰ Reconnection result timeout for: ${accountInfo.username}@${accountInfo.domain}" }
        return false
    }

    /**
     * Calcular el delay para el siguiente intento de reconexión (backoff exponencial con jitter)
     */
    private fun calculateReconnectionDelay(attempt: Int): Long {
        val exponentialDelay = RECONNECTION_BASE_DELAY * (1 shl (attempt - 1))
        val cappedDelay = minOf(exponentialDelay, RECONNECTION_MAX_DELAY)

        // Añadir jitter aleatorio del 10% para evitar thundering herd
        val jitter = (cappedDelay * 0.1 * kotlin.random.Random.nextDouble()).toLong()
        return cappedDelay + jitter
    }

    /**
     * Forzar reconexión manual
     */
    fun forceReconnection(accounts: List<AccountInfo>) {
        log.d(tag = TAG) { "🔧 FORCING MANUAL RECONNECTION" }

        // Verificar y forzar estado actual en NetworkManager
        networkManager.forceNetworkCheck()
        isNetworkAvailable = networkManager.isNetworkAvailable()

        if (!isNetworkAvailable) {
            log.w(tag = TAG) { "🌐 Cannot force reconnection - no connectivity" }
            return
        }

        // Resetear contadores de intentos
        reconnectionAttempts.clear()
        accountRecoveryAttempts.set(0)

        // Sincronizar cuentas
        syncAccountsFromCoreManager()

        // Marcar como desconectado para forzar reconexión
        wasDisconnectedDueToNetwork = true

        startReconnectionProcess(accounts)
    }

    /**
     * Verificar y corregir el estado de conectividad
     */
    fun verifyAndFixConnectivity(accounts: List<AccountInfo>) {
        log.d(tag = TAG) { "🔍 VERIFYING AND FIXING CONNECTIVITY" }

        // Forzar verificación en NetworkManager
        networkManager.forceNetworkCheck()
        isNetworkAvailable = networkManager.isNetworkAvailable()

        if (!isNetworkAvailable) {
            log.d(tag = TAG) { "🌐 No connectivity available during verification" }
            return
        }

        val accountsToCheck = if (accounts.isNotEmpty()) accounts else cachedAccounts.values.toList()
        log.d(tag = TAG) { "🔍 Verifying connectivity for ${accountsToCheck.size} accounts" }

        CoroutineScope(Dispatchers.IO).launch {
            accountsToCheck.forEach { accountInfo ->
                val accountKey = "${accountInfo.username}@${accountInfo.domain}"

                try {
                    val webSocketConnected = accountInfo.webSocketClient?.isConnected() == true
                    val isRegistered = accountInfo.isRegistered

                    log.d(tag = TAG) {
                        "📊 Account $accountKey - WebSocket: $webSocketConnected, Registered: $isRegistered"
                    }

                    // Si debería estar registrada pero no lo está, intentar reconectar
                    if (!isRegistered || !webSocketConnected) {
                        log.d(tag = TAG) { "🔧 Account $accountKey needs reconnection" }
                        reconnectAccountWithRetry(accountInfo)
                    }

                } catch (e: Exception) {
                    log.e(tag = TAG) { "💥 Error verifying connectivity for $accountKey: ${e.message}" }
                }
            }
        }
    }

    /**
     * Obtener información del estado de conectividad (mejorada)
     */
    fun getConnectivityStatus(): Map<String, Any> {
        val networkManagerInfo = try {
            networkManager.getNetworkInfo()
        } catch (e: Exception) {
            emptyMap<String, Any>()
        }

        return mapOf(
            "networkAvailable" to isNetworkAvailable,
            "wasDisconnectedDueToNetwork" to wasDisconnectedDueToNetwork,
            "reconnectionInProgress" to (reconnectionJob?.isActive == true),
            "networkStabilityJobActive" to (networkStabilityJob?.isActive == true),
            "reconnectionAttempts" to reconnectionAttempts.toMap(),
            "cachedAccountsCount" to cachedAccounts.size,
            "accountRecoveryAttempts" to accountRecoveryAttempts.get(),
            "lastAccountSync" to lastAccountSync,
            "networkManagerInfo" to networkManagerInfo,
            "diagnosticInfo" to getDiagnosticInfo()
        )
    }

    /**
     * Información de diagnóstico
     */
    private fun getDiagnosticInfo(): Map<String, Any> {
        return mapOf(
            "cachedAccounts" to cachedAccounts.keys,
            "activeReconnectionAttempts" to reconnectionAttempts.size,
            "timeSinceLastAccountSync" to (System.currentTimeMillis() - lastAccountSync),
            "reconnectionJobActive" to (reconnectionJob?.isActive == true)
        )
    }

    /**
     * Configurar listener de eventos de reconexión
     */
    fun setReconnectionListener(listener: ReconnectionListener?) {
        this.reconnectionListener = listener
        log.d(tag = TAG) { "Reconnection listener ${if (listener != null) "set" else "removed"}" }
    }

    /**
     * Verificar si la red está disponible
     */
    fun isNetworkAvailable(): Boolean = isNetworkAvailable

    /**
     * Obtener cuentas en caché
     */
    fun getCachedAccounts(): Map<String, AccountInfo> = cachedAccounts.toMap()

    /**
     * Limpiar recursos
     */
    fun dispose() {
        log.d(tag = TAG) { "Disposing SipReconnectionManager" }

        reconnectionJob?.cancel()
        networkStabilityJob?.cancel()

        reconnectionAttempts.clear()
        cachedAccounts.clear()

        reconnectionListener = null
        isNetworkAvailable = false
        wasDisconnectedDueToNetwork = false
        lastAccountSync = 0L
        accountRecoveryAttempts.set(0)

        log.d(tag = TAG) { "SipReconnectionManager disposed" }
    }
}

/**
 * Interface para escuchar eventos de reconexión (mejorada)
 */
interface ReconnectionListener {
    fun onNetworkLost()
    fun onNetworkRestored()
    fun onReconnectionStarted()
    fun onReconnectionCompleted(successful: Boolean)
    fun onReconnectionAttempt(accountKey: String, attempt: Int)
    fun onReconnectAccount(accountInfo: AccountInfo): Boolean
    fun onAccountReconnected(accountKey: String, successful: Boolean)
    fun onReconnectionFailed(accountKey: String)
}