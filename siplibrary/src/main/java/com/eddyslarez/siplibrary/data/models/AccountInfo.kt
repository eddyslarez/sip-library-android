package com.eddyslarez.siplibrary.data.models

import com.eddyslarez.siplibrary.data.services.websocket.MultiplatformWebSocket
import com.eddyslarez.siplibrary.utils.generateId
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.random.Random

/**
 * Información de cuenta SIP y contenedor de estado
 *
 * @author Eddys Larez
 */
/**
 * Información de cuenta mejorada con thread safety y validaciones
 *
 * Mejoras principales:
 * - CSeq thread-safe con validaciones automáticas
 * - Estados sincronizados con locks
 * - Auto-validación y corrección de inconsistencias
 * - Mejor manejo de WebSocket y auth state
 *
 * @author Eddys Larez - Version 2.0 Enhanced
 */
class AccountInfo(
    val username: String,
    var password: String, // Ahora mutable para actualizaciones
    val domain: String
) {
    private val TAG = "AccountInfo"
    private val accountMutex = Mutex()
    private val cseqMutex = Mutex()

    // Connection state con thread safety
    @Volatile
    var webSocketClient: MultiplatformWebSocket? = null
    var reconnectionJob: Job? = null
    val reconnectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    var reconnectCount = 0

    // SIP headers and identifiers con protección
    @Volatile
    var callId: String? = null
    @Volatile
    var fromTag: String? = null
    @Volatile
    var toTag: String? = null

    // CRÍTICO: CSeq thread-safe con validaciones automáticas
    @Volatile
    private var _cseq: Int = 1
    val cseq: Int get() = _cseq

    @Volatile
    var fromHeader: String? = null
    @Volatile
    var toHeader: String? = null
    @Volatile
    var viaHeader: String? = null
    @Volatile
    var fromUri: String? = null
    @Volatile
    var toUri: String? = null
    @Volatile
    var remoteContact: String? = null
    @Volatile
    var userAgent: String? = null

    // Authentication con thread safety
    @Volatile
    var authorizationHeader: String? = null
    @Volatile
    var challengeNonce: String? = null
    @Volatile
    var realm: String? = null
    @Volatile
    var authRetryCount: Int = 0
    @Volatile
    var method: String? = null

    // WebRTC data
    @Volatile
    var useWebRTCFormat = false
    @Volatile
    var remoteSdp: String? = null
    @Volatile
    var iceUfrag: String? = null
    @Volatile
    var icePwd: String? = null
    @Volatile
    var dtlsFingerprint: String? = null
    @Volatile
    var setupRole: String? = null

    // Call state con protección concurrente
    @Volatile
    var currentCallData: CallData? = null
    @Volatile
    var isRegistered: Boolean = false
    @Volatile
    var isCallConnected: Boolean = false
    @Volatile
    var hasIncomingCall: Boolean = false
    @Volatile
    var callStartTime: Long = 0L

    // Push notification data
    @Volatile
    var token: String = ""
    @Volatile
    var provider: String = ""

    // NUEVO: Timestamps para tracking
    @Volatile
    private var lastCseqUpdate: Long = 0L
    @Volatile
    private var lastAuthReset: Long = 0L
    @Volatile
    private var lastCallReset: Long = 0L

    // NUEVO: Validación y state tracking
    private var stateInconsistencyDetected = false
    private var lastValidationTime = 0L

    companion object {
        private const val MAX_CSEQ_VALUE = 2147483647
        private const val MIN_CSEQ_VALUE = 1
        private const val CSEQ_RESET_THRESHOLD = MAX_CSEQ_VALUE - 1000
    }

    /**
     * NUEVO: Incrementa CSeq de forma thread-safe con validaciones
     */
    suspend fun incrementCSeq(): Int = cseqMutex.withLock {
        try {
            // Validar valor actual
            if (_cseq >= CSEQ_RESET_THRESHOLD) {
                log.w(tag = TAG) { "CSeq approaching limit for ${getAccountIdentity()}, resetting" }
                resetCSeq()
                return@withLock _cseq
            }

            if (_cseq <= 0) {
                log.w(tag = TAG) { "Invalid CSeq detected for ${getAccountIdentity()}, fixing" }
                _cseq = MIN_CSEQ_VALUE
            }

            _cseq++
            lastCseqUpdate = Clock.System.now().toEpochMilliseconds()

            log.d(tag = TAG) { "CSeq incremented to $_cseq for ${getAccountIdentity()}" }
            return@withLock _cseq

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error incrementing CSeq for ${getAccountIdentity()}: ${e.message}" }
            // En caso de error, resetear y devolver 1
            _cseq = MIN_CSEQ_VALUE
            return@withLock _cseq
        }
    }

    /**
     * NUEVO: Resetea CSeq de forma thread-safe
     */
    suspend fun resetCSeq() = cseqMutex.withLock {
        _cseq = MIN_CSEQ_VALUE
        lastCseqUpdate = Clock.System.now().toEpochMilliseconds()
        log.d(tag = TAG) { "CSeq reset to $_cseq for ${getAccountIdentity()}" }
    }

    /**
     * NUEVO: Valida y corrige CSeq si es necesario
     */
    suspend fun validateAndFixCSeq(): Boolean = cseqMutex.withLock {
        val wasValid = _cseq in MIN_CSEQ_VALUE..MAX_CSEQ_VALUE

        if (!wasValid) {
            log.w(tag = TAG) { "Invalid CSeq detected: $_cseq for ${getAccountIdentity()}" }
            _cseq = MIN_CSEQ_VALUE
            lastCseqUpdate = Clock.System.now().toEpochMilliseconds()
            log.d(tag = TAG) { "CSeq corrected to $_cseq for ${getAccountIdentity()}" }
        }

        return@withLock wasValid
    }

    /**
     * NUEVO: Obtiene el próximo CSeq sin incrementar (para preview)
     */
    fun getNextCSeq(): Int {
        return if (_cseq >= CSEQ_RESET_THRESHOLD) 1 else _cseq + 1
    }

    /**
     * Genera un identificador único para requests SIP con mejor randomness
     */
    fun generateCallId(): String {
        val timestamp = Clock.System.now().toEpochMilliseconds()
        val randomPart = (1..32).joinToString("") {
            Random.nextInt(16).toString(16)
        }
        return "${randomPart}_${timestamp}@$domain"
    }
    /**
     * NUEVO: Actualiza CSeq desde fuente externa (ej. mensaje SIP recibido)
     * Solo actualiza si el nuevo valor es mayor que el actual
     */
    suspend fun updateCSeqFromExternal(newCSeq: Int, source: String = "external") = cseqMutex.withLock {
        try {
            // Validar que el nuevo valor es válido
            if (newCSeq !in MIN_CSEQ_VALUE..MAX_CSEQ_VALUE) {
                log.w(tag = TAG) { "Invalid external CSeq $newCSeq from $source for ${getAccountIdentity()}" }
                return@withLock false
            }

            // Solo actualizar si es mayor (para mantener secuencia correcta)
            if (newCSeq > _cseq) {
                val oldCSeq = _cseq
                _cseq = newCSeq
                lastCseqUpdate = Clock.System.now().toEpochMilliseconds()

                log.d(tag = TAG) { "CSeq updated from $source: $oldCSeq -> $_cseq for ${getAccountIdentity()}" }
                return@withLock true
            } else {
                log.d(tag = TAG) { "External CSeq $newCSeq from $source <= current $_cseq, not updating" }
                return@withLock false
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error updating CSeq from $source: ${e.message}" }
            return@withLock false
        }
    }
    /**
     * Parsea URI de contacto desde header Contact con mejor handling
     */
    fun parseContactUri(contactHeader: String): String {
        return try {
            val uriMatch = Regex("<([^>]+)>").find(contactHeader)
            if (uriMatch != null) {
                uriMatch.groupValues[1]
            } else {
                val uriPart = contactHeader
                    .substringAfter(":", "")
                    .substringBefore(";", "")
                    .trim()

                if (uriPart.isNotEmpty()) uriPart else contactHeader
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error parsing contact URI: ${e.message}" }
            contactHeader
        }
    }

    /**
     * Resetea estado de llamada con thread safety mejorado
     */
    suspend fun resetCallState() = accountMutex.withLock {
        try {
            isCallConnected = false
            hasIncomingCall = false
            callStartTime = 0L
            currentCallData = null
            lastCallReset = Clock.System.now().toEpochMilliseconds()

            log.d(tag = TAG) { "Call state reset for ${getAccountIdentity()}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error resetting call state for ${getAccountIdentity()}: ${e.message}" }
        }
    }

    /**
     * Resetea estado de autenticación con thread safety mejorado
     */
    suspend fun resetAuthState() = accountMutex.withLock {
        try {
            authRetryCount = 0
            challengeNonce = null
            authorizationHeader = null
            realm = null
            method = null
            lastAuthReset = Clock.System.now().toEpochMilliseconds()

            log.d(tag = TAG) { "Auth state reset for ${getAccountIdentity()}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error resetting auth state for ${getAccountIdentity()}: ${e.message}" }
        }
    }

    /**
     * NUEVO: Resetea todo el estado de la cuenta de forma segura
     */
    suspend fun resetAllState() = accountMutex.withLock {
        try {
            resetCallState()
            resetAuthState()
            resetCSeq()

            // Reset flags
            isRegistered = false
            isCallConnected = false
            hasIncomingCall = false
            reconnectCount = 0

            log.d(tag = TAG) { "Complete state reset for ${getAccountIdentity()}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in complete state reset: ${e.message}" }
        }
    }

    /**
     * Obtiene string de identidad de cuenta
     */
    fun getAccountIdentity(): String {
        return "$username@$domain"
    }

    /**
     * NUEVO: Valida la consistencia del estado interno
     */
    fun validateInternalState(): StateValidationResult {
        val issues = mutableListOf<String>()
        val timestamp = Clock.System.now().toEpochMilliseconds()

        // Validar CSeq
        if (_cseq <= 0 || _cseq > MAX_CSEQ_VALUE) {
            issues.add("Invalid CSeq: $_cseq")
        }

        // Validar estado de registro
        val hasWebSocket = webSocketClient != null
        val isWebSocketConnected = webSocketClient?.isConnected() == true

        if (isRegistered && !hasWebSocket) {
            issues.add("Marked as registered but no WebSocket")
        }

        if (isRegistered && !isWebSocketConnected) {
            issues.add("Marked as registered but WebSocket disconnected")
        }

        // Validar estado de llamada
        val hasCallData = currentCallData != null

        if (isCallConnected && !hasCallData) {
            issues.add("Marked as call connected but no call data")
        }

        if (hasIncomingCall && !hasCallData) {
            issues.add("Has incoming call flag but no call data")
        }

        // Validar timestamps
        val timeSinceLastCseqUpdate = timestamp - lastCseqUpdate
        if (lastCseqUpdate > 0 && timeSinceLastCseqUpdate > 300000L) { // 5 minutos
            issues.add("CSeq not updated in ${timeSinceLastCseqUpdate}ms")
        }

        return StateValidationResult(
            isValid = issues.isEmpty(),
            issues = issues,
            timestamp = timestamp,
            cseqValue = _cseq,
            isRegistered = isRegistered,
            hasWebSocket = hasWebSocket,
            isWebSocketConnected = isWebSocketConnected
        )
    }

    /**
     * NUEVO: Corrige automáticamente inconsistencias detectadas
     */
    suspend fun autoFixInconsistencies(): Boolean = accountMutex.withLock {
        try {
            val validation = validateInternalState()

            if (validation.isValid) {
                log.d(tag = TAG) { "No inconsistencies found for ${getAccountIdentity()}" }
                return@withLock true
            }

            log.w(tag = TAG) { "Fixing inconsistencies for ${getAccountIdentity()}: ${validation.issues}" }

            var allFixed = true

            validation.issues.forEach { issue ->
                try {
                    when {
                        issue.contains("Invalid CSeq") -> {
                            resetCSeq()
                        }

                        issue.contains("registered but no WebSocket") -> {
                            isRegistered = false
                            log.d(tag = TAG) { "Fixed: Unmarked registration due to no WebSocket" }
                        }

                        issue.contains("registered but WebSocket disconnected") -> {
                            isRegistered = false
                            log.d(tag = TAG) { "Fixed: Unmarked registration due to disconnected WebSocket" }
                        }

                        issue.contains("call connected but no call data") -> {
                            isCallConnected = false
                            log.d(tag = TAG) { "Fixed: Unmarked call connected due to no call data" }
                        }

                        issue.contains("incoming call flag but no call data") -> {
                            hasIncomingCall = false
                            log.d(tag = TAG) { "Fixed: Cleared incoming call flag due to no call data" }
                        }

                        issue.contains("CSeq not updated") -> {
                            // CSeq stale, pero no necesariamente un error crítico
                            log.d(tag = TAG) { "Note: CSeq hasn't been updated recently (not critical)" }
                        }

                        else -> {
                            log.w(tag = TAG) { "Unknown issue cannot be auto-fixed: $issue" }
                            allFixed = false
                        }
                    }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error fixing issue '$issue': ${e.message}" }
                    allFixed = false
                }
            }

            if (allFixed) {
                log.d(tag = TAG) { "All inconsistencies fixed for ${getAccountIdentity()}" }
            } else {
                log.w(tag = TAG) { "Some inconsistencies could not be fixed for ${getAccountIdentity()}" }
            }

            return@withLock allFixed

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in auto-fix for ${getAccountIdentity()}: ${e.message}" }
            return@withLock false
        }
    }

    /**
     * NUEVO: Actualiza credenciales de forma thread-safe
     */
    suspend fun updateCredentials(
        newPassword: String? = null,
        newToken: String? = null,
        newProvider: String? = null
    ) = accountMutex.withLock {
        try {
            var credentialsChanged = false

            newPassword?.let {
                if (password != it) {
                    password = it
                    credentialsChanged = true
                }
            }

            newToken?.let {
                if (token != it) {
                    token = it
                    credentialsChanged = true
                }
            }

            newProvider?.let {
                if (provider != it) {
                    provider = it
                    credentialsChanged = true
                }
            }

            if (credentialsChanged) {
                // Reset auth cuando cambian credenciales
                resetAuthState()
                log.d(tag = TAG) { "Credentials updated for ${getAccountIdentity()}" }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error updating credentials: ${e.message}" }
        }
    }

    /**
     * NUEVO: Preparación completa para reconexión
     */
    suspend fun prepareForReconnection() = accountMutex.withLock {
        try {
            // 1. Limpiar estado de autenticación
            resetAuthState()

            // 2. Validar y corregir CSeq
            validateAndFixCSeq()

            // 3. Marcar como no registrado
            isRegistered = false

            // 4. Limpiar datos de conexión obsoletos
            remoteContact = null

            // 5. Reset retry count
            authRetryCount = 0
            reconnectCount = 0

            log.d(tag = TAG) { "Account prepared for reconnection: ${getAccountIdentity()}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error preparing for reconnection: ${e.message}" }
        }
    }

    /**
     * Información de diagnóstico mejorada
     */
    fun getDiagnosticInfo(): String {
        val validation = runBlocking {
            try {
                validateInternalState()
            } catch (e: Exception) {
                StateValidationResult(
                    isValid = false,
                    issues = listOf("Validation error: ${e.message}"),
                    timestamp = System.currentTimeMillis(),
                    cseqValue = _cseq,
                    isRegistered = isRegistered,
                    hasWebSocket = webSocketClient != null,
                    isWebSocketConnected = webSocketClient?.isConnected() == true
                )
            }
        }

        return buildString {
            appendLine("=== ACCOUNT INFO DIAGNOSTIC v2.0 ===")
            appendLine("Username: $username")
            appendLine("Domain: $domain")
            appendLine("Identity: ${getAccountIdentity()}")

            appendLine("\n--- Registration State ---")
            appendLine("Is Registered: $isRegistered")
            appendLine("Auth Retry Count: $authRetryCount")
            appendLine("Reconnect Count: $reconnectCount")
            appendLine("Has Auth Header: ${authorizationHeader != null}")
            appendLine("Token: ${token.take(10)}${if (token.length > 10) "..." else ""}")

            appendLine("\n--- Connection State ---")
            appendLine("WebSocket Present: ${webSocketClient != null}")
            appendLine("WebSocket Connected: ${webSocketClient?.isConnected() == true}")
            appendLine("Remote Contact: ${remoteContact ?: "None"}")

            appendLine("\n--- Call State ---")
            appendLine("Is Call Connected: $isCallConnected")
            appendLine("Has Incoming Call: $hasIncomingCall")
            appendLine("Current Call Data: ${currentCallData?.callId ?: "None"}")
            appendLine("Call Start Time: $callStartTime")

            appendLine("\n--- SIP State ---")
            appendLine("Current CSeq: $_cseq")
            appendLine("From Tag: ${fromTag ?: "None"}")
            appendLine("To Tag: ${toTag ?: "None"}")
            appendLine("Call ID: ${callId ?: "None"}")

            appendLine("\n--- State Validation ---")
            appendLine("State Valid: ${validation.isValid}")
            if (!validation.isValid) {
                appendLine("Issues Found:")
                validation.issues.forEach { issue ->
                    appendLine("  - $issue")
                }
            }

            appendLine("\n--- Timestamps ---")
            appendLine("Last CSeq Update: ${if (lastCseqUpdate > 0) lastCseqUpdate else "Never"}")
            appendLine("Last Auth Reset: ${if (lastAuthReset > 0) lastAuthReset else "Never"}")
            appendLine("Last Call Reset: ${if (lastCallReset > 0) lastCallReset else "Never"}")
            appendLine("Last Validation: ${if (lastValidationTime > 0) lastValidationTime else "Never"}")
        }
    }

    /**
     * NUEVO: Verifica la salud general de la cuenta
     */
    fun isHealthy(): Boolean {
        return try {
            val validation = runBlocking { validateInternalState() }

            validation.isValid &&
                    _cseq in MIN_CSEQ_VALUE..CSEQ_RESET_THRESHOLD &&
                    authRetryCount < 5 &&
                    reconnectCount < 10 &&
                    (webSocketClient?.isConnected() == true || !isRegistered)

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error checking health for ${getAccountIdentity()}: ${e.message}" }
            false
        }
    }

    /**
     * NUEVO: Data class para resultado de validación de estado
     */
    data class StateValidationResult(
        val isValid: Boolean,
        val issues: List<String>,
        val timestamp: Long,
        val cseqValue: Int,
        val isRegistered: Boolean,
        val hasWebSocket: Boolean,
        val isWebSocketConnected: Boolean
    )

    /**
     * NUEVO: Información resumida del estado para logging
     */
    fun getStateShort(): String {
        return "Account(${getAccountIdentity()}, reg=$isRegistered, cseq=$_cseq, " +
                "ws=${webSocketClient?.isConnected()}, call=${currentCallData?.callId})"
    }

    /**
     * NUEVO: Limpieza completa de recursos
     */
    suspend fun cleanup() = accountMutex.withLock {
        try {
            // Cancelar job de reconexión si existe
            reconnectionJob?.cancel()

            // Parar y cerrar WebSocket
            webSocketClient?.let { ws ->
                try {
                    ws.stopPingTimer()
                    ws.stopRegistrationRenewalTimer()
                    ws.close()
                } catch (e: Exception) {
                    log.w(tag = TAG) { "Error closing WebSocket during cleanup: ${e.message}" }
                }
            }
            webSocketClient = null

            // Reset todos los estados
            resetAllState()

            log.d(tag = TAG) { "Account cleanup completed for ${getAccountIdentity()}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error during account cleanup: ${e.message}" }
        }
    }

    /**
     * NUEVO: toString mejorado para debugging
     */
    override fun toString(): String {
        return "AccountInfo(${getAccountIdentity()}, " +
                "registered=$isRegistered, " +
                "cseq=$_cseq, " +
                "wsConnected=${webSocketClient?.isConnected()}, " +
                "callConnected=$isCallConnected, " +
                "hasCall=${currentCallData != null}, " +
                "authRetries=$authRetryCount, " +
                "reconnects=$reconnectCount)"
    }
}