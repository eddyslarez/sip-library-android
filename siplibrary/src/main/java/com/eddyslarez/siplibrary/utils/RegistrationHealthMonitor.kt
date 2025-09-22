package com.eddyslarez.siplibrary.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock

/**
 * Monitor de salud de registros SIP con verificaciones periódicas
 * 
 * @author Eddys Larez
 */
class RegistrationHealthMonitor {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val TAG = "RegistrationHealthMonitor"
    
    // Estados de salud
    private val _healthStatusFlow = MutableStateFlow<Map<String, HealthStatus>>(emptyMap())
    val healthStatusFlow: StateFlow<Map<String, HealthStatus>> = _healthStatusFlow.asStateFlow()
    
    private val _overallHealthFlow = MutableStateFlow(OverallHealth.UNKNOWN)
    val overallHealthFlow: StateFlow<OverallHealth> = _overallHealthFlow.asStateFlow()
    
    // Jobs de monitoreo
    private var healthCheckJob: Job? = null
    private var pingJob: Job? = null
    
    // Configuración
    private var healthCheckIntervalMs = 30000L // 30 segundos
    private var pingIntervalMs = 60000L // 60 segundos
    private var isMonitoring = false
    
    // Callbacks
    private var onHealthChangeCallback: ((String, HealthStatus) -> Unit)? = null
    private var onUnhealthyAccountCallback: ((String, String) -> Unit)? = null
    
    data class HealthStatus(
        val accountKey: String,
        val isHealthy: Boolean,
        val lastCheckTime: Long,
        val lastSuccessfulPing: Long,
        val consecutiveFailures: Int,
        val issues: List<HealthIssue>,
        val score: Int // 0-100
    ) {
        fun getHealthDescription(): String {
            return when {
                score >= 90 -> "Excellent"
                score >= 70 -> "Good"
                score >= 50 -> "Fair"
                score >= 30 -> "Poor"
                else -> "Critical"
            }
        }
    }
    
    data class HealthIssue(
        val type: IssueType,
        val description: String,
        val severity: IssueSeverity,
        val timestamp: Long
    )
    
    enum class IssueType {
        REGISTRATION_EXPIRED,
        NETWORK_DISCONNECTED,
        CONSECUTIVE_FAILURES,
        PING_TIMEOUT,
        AUTHENTICATION_ERROR,
        SERVER_ERROR,
        UNKNOWN_ERROR
    }
    
    enum class IssueSeverity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }
    
    enum class OverallHealth {
        UNKNOWN,
        CRITICAL,
        POOR,
        FAIR,
        GOOD,
        EXCELLENT
    }
    
    /**
     * Configura callbacks para notificaciones
     */
    fun setCallbacks(
        onHealthChange: ((String, HealthStatus) -> Unit)? = null,
        onUnhealthyAccount: ((String, String) -> Unit)? = null
    ) {
        this.onHealthChangeCallback = onHealthChange
        this.onUnhealthyAccountCallback = onUnhealthyAccount
    }
    
    /**
     * Configura intervalos de monitoreo
     */
    fun setMonitoringIntervals(
        healthCheckIntervalMs: Long = 30000L,
        pingIntervalMs: Long = 60000L
    ) {
        this.healthCheckIntervalMs = healthCheckIntervalMs
        this.pingIntervalMs = pingIntervalMs
        
        // Reiniciar monitoreo si está activo
        if (isMonitoring) {
            stopMonitoring()
            startMonitoring()
        }
    }
    
    /**
     * Inicia el monitoreo de salud
     */
    fun startMonitoring() {
        if (isMonitoring) {
            log.d(tag = TAG) { "Health monitoring already started" }
            return
        }
        
        log.d(tag = TAG) { "Starting registration health monitoring" }
        
        isMonitoring = true
        
        // Verificación inicial
        performHealthCheck()
        
        // Programar verificaciones periódicas
        startPeriodicHealthChecks()
        startPeriodicPings()
    }
    
    /**
     * Detiene el monitoreo de salud
     */
    fun stopMonitoring() {
        if (!isMonitoring) {
            return
        }
        
        log.d(tag = TAG) { "Stopping registration health monitoring" }
        
        healthCheckJob?.cancel()
        pingJob?.cancel()
        
        healthCheckJob = null
        pingJob = null
        isMonitoring = false
    }
    
    /**
     * Inicia verificaciones periódicas de salud
     */
    private fun startPeriodicHealthChecks() {
        healthCheckJob = scope.launch {
            while (isMonitoring) {
                try {
                    delay(healthCheckIntervalMs)
                    if (isMonitoring) {
                        performHealthCheck()
                    }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in periodic health check: ${e.message}" }
                }
            }
        }
    }
    
    /**
     * Inicia pings periódicos
     */
    private fun startPeriodicPings() {
        pingJob = scope.launch {
            while (isMonitoring) {
                try {
                    delay(pingIntervalMs)
                    if (isMonitoring) {
                        performPingCheck()
                    }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in periodic ping: ${e.message}" }
                }
            }
        }
    }
    
    /**
     * Realiza verificación completa de salud
     */
    fun performHealthCheck() {
        val currentTime = Clock.System.now().toEpochMilliseconds()
        val accountStates = RegistrationStateManager.getAllAccountStateInfos()
        val networkConnected = RegistrationStateManager.getNetworkState()
        
        val healthStatuses = mutableMapOf<String, HealthStatus>()
        
        accountStates.forEach { (accountKey, stateInfo) ->
            val issues = mutableListOf<HealthIssue>()
            var score = 100
            var consecutiveFailures = stateInfo.consecutiveFailures
            
            // Verificar estado de registro
            if (stateInfo.state != com.eddyslarez.siplibrary.data.models.RegistrationState.OK) {
                issues.add(HealthIssue(
                    type = IssueType.REGISTRATION_EXPIRED,
                    description = "Registration state is ${stateInfo.state}",
                    severity = IssueSeverity.HIGH,
                    timestamp = currentTime
                ))
                score -= 30
            }
            
            // Verificar expiración
            if (stateInfo.isExpired()) {
                issues.add(HealthIssue(
                    type = IssueType.REGISTRATION_EXPIRED,
                    description = "Registration expired",
                    severity = IssueSeverity.CRITICAL,
                    timestamp = currentTime
                ))
                score -= 40
            }
            
            // Verificar conectividad de red
            if (!networkConnected) {
                issues.add(HealthIssue(
                    type = IssueType.NETWORK_DISCONNECTED,
                    description = "Network disconnected",
                    severity = IssueSeverity.HIGH,
                    timestamp = currentTime
                ))
                score -= 25
            }
            
            // Verificar fallos consecutivos
            if (stateInfo.consecutiveFailures > 0) {
                val severity = when {
                    stateInfo.consecutiveFailures >= 5 -> IssueSeverity.CRITICAL
                    stateInfo.consecutiveFailures >= 3 -> IssueSeverity.HIGH
                    else -> IssueSeverity.MEDIUM
                }
                
                issues.add(HealthIssue(
                    type = IssueType.CONSECUTIVE_FAILURES,
                    description = "${stateInfo.consecutiveFailures} consecutive failures",
                    severity = severity,
                    timestamp = currentTime
                ))
                score -= (stateInfo.consecutiveFailures * 5)
            }
            
            // Verificar errores de autenticación
            if (stateInfo.errorMessage?.contains("auth", ignoreCase = true) == true) {
                issues.add(HealthIssue(
                    type = IssueType.AUTHENTICATION_ERROR,
                    description = stateInfo.errorMessage,
                    severity = IssueSeverity.HIGH,
                    timestamp = currentTime
                ))
                score -= 20
            }
            
            // Obtener estado de salud anterior para ping
            val previousHealth = _healthStatusFlow.value[accountKey]
            val lastSuccessfulPing = previousHealth?.lastSuccessfulPing ?: currentTime
            
            // Verificar timeout de ping
            if (currentTime - lastSuccessfulPing > (pingIntervalMs * 2)) {
                issues.add(HealthIssue(
                    type = IssueType.PING_TIMEOUT,
                    description = "No successful ping in ${(currentTime - lastSuccessfulPing) / 1000} seconds",
                    severity = IssueSeverity.MEDIUM,
                    timestamp = currentTime
                ))
                score -= 15
            }
            
            // Asegurar que el score esté en rango válido
            score = score.coerceIn(0, 100)
            
            val healthStatus = HealthStatus(
                accountKey = accountKey,
                isHealthy = score >= 70 && issues.none { it.severity == IssueSeverity.CRITICAL },
                lastCheckTime = currentTime,
                lastSuccessfulPing = lastSuccessfulPing,
                consecutiveFailures = consecutiveFailures,
                issues = issues,
                score = score
            )
            
            healthStatuses[accountKey] = healthStatus
            
            // Notificar cambios de salud
            val previousHealthStatus = _healthStatusFlow.value[accountKey]
            if (previousHealthStatus?.isHealthy != healthStatus.isHealthy) {
                onHealthChangeCallback?.invoke(accountKey, healthStatus)
                
                if (!healthStatus.isHealthy) {
                    val issueDescription = issues.joinToString(", ") { it.description }
                    onUnhealthyAccountCallback?.invoke(accountKey, issueDescription)
                }
            }
        }
        
        // Actualizar estados
        _healthStatusFlow.value = healthStatuses
        updateOverallHealth(healthStatuses)
        
        log.d(tag = TAG) { "Health check completed for ${healthStatuses.size} accounts" }
    }
    
    /**
     * Realiza verificación de ping (simulada)
     */
    private fun performPingCheck() {
        val currentTime = Clock.System.now().toEpochMilliseconds()
        val currentHealthStatuses = _healthStatusFlow.value.toMutableMap()
        val registeredAccounts = RegistrationStateManager.getRegisteredAccounts()
        
        registeredAccounts.forEach { accountKey ->
            val currentHealth = currentHealthStatuses[accountKey]
            if (currentHealth != null) {
                // Simular ping exitoso para cuentas registradas
                val updatedHealth = currentHealth.copy(
                    lastSuccessfulPing = currentTime,
                    lastCheckTime = currentTime
                )
                currentHealthStatuses[accountKey] = updatedHealth
            }
        }
        
        _healthStatusFlow.value = currentHealthStatuses
        log.d(tag = TAG) { "Ping check completed for ${registeredAccounts.size} accounts" }
    }
    
    /**
     * Actualiza la salud general basada en las cuentas individuales
     */
    private fun updateOverallHealth(healthStatuses: Map<String, HealthStatus>) {
        val overallHealth = when {
            healthStatuses.isEmpty() -> OverallHealth.UNKNOWN
            healthStatuses.values.all { it.score >= 90 } -> OverallHealth.EXCELLENT
            healthStatuses.values.all { it.score >= 70 } -> OverallHealth.GOOD
            healthStatuses.values.any { it.score < 30 } -> OverallHealth.CRITICAL
            healthStatuses.values.any { it.score < 50 } -> OverallHealth.POOR
            else -> OverallHealth.FAIR
        }
        
        val previousOverallHealth = _overallHealthFlow.value
        if (previousOverallHealth != overallHealth) {
            _overallHealthFlow.value = overallHealth
            log.d(tag = TAG) { "Overall health changed: $previousOverallHealth -> $overallHealth" }
        }
    }
    
    /**
     * Fuerza verificación inmediata de una cuenta específica
     */
    fun checkAccountHealth(accountKey: String) {
        scope.launch {
            log.d(tag = TAG) { "Performing immediate health check for $accountKey" }
            performHealthCheck()
        }
    }
    
    /**
     * Marca una cuenta como que tuvo un ping exitoso
     */
    fun markSuccessfulPing(accountKey: String) {
        val currentTime = Clock.System.now().toEpochMilliseconds()
        val currentHealthStatuses = _healthStatusFlow.value.toMutableMap()
        
        currentHealthStatuses[accountKey]?.let { currentHealth ->
            val updatedHealth = currentHealth.copy(
                lastSuccessfulPing = currentTime,
                lastCheckTime = currentTime
            )
            currentHealthStatuses[accountKey] = updatedHealth
            _healthStatusFlow.value = currentHealthStatuses
        }
    }
    
    /**
     * Marca una cuenta como que tuvo un fallo de ping
     */
    fun markFailedPing(accountKey: String, reason: String) {
        val currentTime = Clock.System.now().toEpochMilliseconds()
        val currentHealthStatuses = _healthStatusFlow.value.toMutableMap()
        
        currentHealthStatuses[accountKey]?.let { currentHealth ->
            val newIssues = currentHealth.issues.toMutableList()
            newIssues.add(HealthIssue(
                type = IssueType.PING_TIMEOUT,
                description = "Ping failed: $reason",
                severity = IssueSeverity.MEDIUM,
                timestamp = currentTime
            ))
            
            val updatedHealth = currentHealth.copy(
                issues = newIssues,
                lastCheckTime = currentTime,
                consecutiveFailures = currentHealth.consecutiveFailures + 1,
                score = (currentHealth.score - 10).coerceAtLeast(0)
            )
            currentHealthStatuses[accountKey] = updatedHealth
            _healthStatusFlow.value = currentHealthStatuses
        }
    }
    
    // === MÉTODOS DE CONSULTA ===
    
    fun getAccountHealth(accountKey: String): HealthStatus? {
        return _healthStatusFlow.value[accountKey]
    }
    
    fun getAllHealthStatuses(): Map<String, HealthStatus> {
        return _healthStatusFlow.value
    }
    
    fun getOverallHealth(): OverallHealth {
        return _overallHealthFlow.value
    }
    
    fun getHealthyAccounts(): List<String> {
        return _healthStatusFlow.value.filter { it.value.isHealthy }.keys.toList()
    }
    
    fun getUnhealthyAccounts(): List<String> {
        return _healthStatusFlow.value.filter { !it.value.isHealthy }.keys.toList()
    }
    
    fun getCriticalAccounts(): List<String> {
        return _healthStatusFlow.value.filter { 
            it.value.issues.any { issue -> issue.severity == IssueSeverity.CRITICAL }
        }.keys.toList()
    }
    
    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        val healthStatuses = _healthStatusFlow.value
        val overallHealth = _overallHealthFlow.value
        
        return buildString {
            appendLine("=== REGISTRATION HEALTH MONITOR DIAGNOSTIC ===")
            appendLine("Is Monitoring: $isMonitoring")
            appendLine("Overall Health: $overallHealth")
            appendLine("Health Check Interval: ${healthCheckIntervalMs}ms")
            appendLine("Ping Interval: ${pingIntervalMs}ms")
            appendLine("Total Accounts: ${healthStatuses.size}")
            appendLine("Healthy Accounts: ${getHealthyAccounts().size}")
            appendLine("Unhealthy Accounts: ${getUnhealthyAccounts().size}")
            appendLine("Critical Accounts: ${getCriticalAccounts().size}")
            
            appendLine("\n--- Account Health Details ---")
            healthStatuses.forEach { (accountKey, health) ->
                appendLine("$accountKey:")
                appendLine("  Health: ${health.getHealthDescription()} (${health.score}/100)")
                appendLine("  Is Healthy: ${health.isHealthy}")
                appendLine("  Last Check: ${health.lastCheckTime}")
                appendLine("  Last Ping: ${health.lastSuccessfulPing}")
                appendLine("  Consecutive Failures: ${health.consecutiveFailures}")
                appendLine("  Issues: ${health.issues.size}")
                
                health.issues.forEach { issue ->
                    appendLine("    - ${issue.type}: ${issue.description} (${issue.severity})")
                }
                appendLine()
            }
        }
    }
    
    /**
     * Limpieza de recursos
     */
    fun dispose() {
        stopMonitoring()
        onHealthChangeCallback = null
        onUnhealthyAccountCallback = null
    }
}