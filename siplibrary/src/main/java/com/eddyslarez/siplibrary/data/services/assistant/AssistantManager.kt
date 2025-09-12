package com.eddyslarez.siplibrary.data.services.assistant

import android.app.Application
import com.eddyslarez.siplibrary.data.database.DatabaseManager
import com.eddyslarez.siplibrary.data.database.entities.AssistantConfigEntity
import com.eddyslarez.siplibrary.data.database.entities.BlacklistEntity
import com.eddyslarez.siplibrary.data.database.entities.AssistantCallLogEntity
import com.eddyslarez.siplibrary.data.models.*
import com.eddyslarez.siplibrary.data.services.contacts.ContactManager
import com.eddyslarez.siplibrary.utils.generateId
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Gestor principal del sistema de asistente SIP
 * Maneja configuraciones individuales por cuenta y procesamiento de llamadas
 * 
 * @author Eddys Larez
 */
class AssistantManager(
    private val application: Application,
    private val databaseManager: DatabaseManager
) {
    
    private val TAG = "AssistantManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Gestor de contactos
    private val contactManager = ContactManager(application)
    
    // Estados del asistente
    private val _activeConfigsFlow = MutableStateFlow<Map<String, AssistantConfig>>(emptyMap())
    val activeConfigsFlow: StateFlow<Map<String, AssistantConfig>> = _activeConfigsFlow.asStateFlow()
    
    private val _isProcessingFlow = MutableStateFlow(false)
    val isProcessingFlow: StateFlow<Boolean> = _isProcessingFlow.asStateFlow()
    
    // Callbacks para integración con SipCoreManager
    private var onCallShouldBeRejectedCallback: ((String, String) -> Unit)? = null
    private var onCallShouldBeDeflectedCallback: ((String, String, String) -> Unit)? = null
    
    // Cache de listas negras por configuración
    private val blacklistCache = mutableMapOf<String, List<BlacklistEntry>>()
    
    init {
        loadActiveConfigurations()
    }
    
    /**
     * Configura callbacks para integración con el sistema SIP
     */
    fun setCallbacks(
        onCallShouldBeRejected: ((callId: String, reason: String) -> Unit)? = null,
        onCallShouldBeDeflected: ((callId: String, assistantNumber: String, reason: String) -> Unit)? = null
    ) {
        this.onCallShouldBeRejectedCallback = onCallShouldBeRejected
        this.onCallShouldBeDeflectedCallback = onCallShouldBeDeflected
    }
    
    /**
     * Permite al usuario establecer contactos manualmente
     */
    fun setManualContacts(contacts: List<Contact>) {
        contactManager.setManualContacts(contacts)
        log.d(tag = TAG) { "Manual contacts set: ${contacts.size}" }
    }
    
    /**
     * Cambia a usar contactos del dispositivo
     */
    fun useDeviceContacts(config: ContactExtractionConfig = ContactExtractionConfig()) {
        contactManager.useDeviceContacts(config)
        log.d(tag = TAG) { "Switched to device contacts extraction" }
    }
    
    // === GESTIÓN DE CONFIGURACIONES ===
    
    /**
     * Activa el asistente para una cuenta específica
     */
    suspend fun enableAssistant(
        accountKey: String,
        mode: AssistantMode,
        action: AssistantAction,
        assistantNumber: String = ""
    ): AssistantConfig {
        
        log.d(tag = TAG) { "Enabling assistant for $accountKey: mode=$mode, action=$action" }
        
        // Validar parámetros
        if (action == AssistantAction.SEND_TO_ASSISTANT && assistantNumber.isBlank()) {
            throw IllegalArgumentException("Assistant number is required for SEND_TO_ASSISTANT action")
        }
        
        // Obtener cuenta SIP para validar que existe
        val sipAccount = databaseManager.getSipAccountByCredentials(
            accountKey.split("@")[0],
            accountKey.split("@")[1]
        ) ?: throw IllegalArgumentException("SIP account not found: $accountKey")
        
        // Crear o actualizar configuración
        val config = databaseManager.createOrUpdateAssistantConfig(
            accountId = sipAccount.id,
            accountKey = accountKey,
            isEnabled = true,
            mode = mode,
            action = action,
            assistantNumber = assistantNumber
        )
        
        // Actualizar cache
        updateActiveConfigsCache()
        
        log.d(tag = TAG) { "Assistant enabled for $accountKey" }
        
        return config.toAssistantConfig()
    }
    
    /**
     * Desactiva el asistente para una cuenta específica
     */
    suspend fun disableAssistant(accountKey: String) {
        log.d(tag = TAG) { "Disabling assistant for $accountKey" }
        
        databaseManager.disableAssistant(accountKey)
        updateActiveConfigsCache()
        
        log.d(tag = TAG) { "Assistant disabled for $accountKey" }
    }
    
    /**
     * Obtiene configuración del asistente para una cuenta
     */
    suspend fun getAssistantConfig(accountKey: String): AssistantConfig? {
        return databaseManager.getAssistantConfig(accountKey)?.toAssistantConfig()
    }
    
    /**
     * Obtiene todas las configuraciones activas
     */
    fun getActiveConfigurations(): Flow<List<AssistantConfig>> {
        return databaseManager.getActiveAssistantConfigs()
    }
    
    // === GESTIÓN DE LISTA NEGRA ===
    
    /**
     * Añade número a la lista negra
     */
    suspend fun addToBlacklist(
        accountKey: String,
        phoneNumber: String,
        displayName: String? = null,
        reason: String? = null
    ): BlacklistEntry {
        
        log.d(tag = TAG) { "Adding to blacklist for $accountKey: $phoneNumber" }
        
        val config = getAssistantConfigEntity(accountKey)
            ?: throw IllegalStateException("Assistant not configured for account: $accountKey")
        
        val entry = databaseManager.addToBlacklist(
            assistantConfigId = config.id,
            phoneNumber = phoneNumber,
            displayName = displayName,
            reason = reason
        )
        
        // Actualizar cache
        updateBlacklistCacheByAccountKey(config.accountKey)
        
        log.d(tag = TAG) { "Added to blacklist: $phoneNumber for $accountKey" }
        
        return entry.toBlacklistEntry()
    }
    
    /**
     * Remueve número de la lista negra
     */
    suspend fun removeFromBlacklist(accountKey: String, phoneNumber: String) {
        log.d(tag = TAG) { "Removing from blacklist for $accountKey: $phoneNumber" }
        
        val config = getAssistantConfigEntity(accountKey)
            ?: throw IllegalStateException("Assistant not configured for account: $accountKey")
        
        databaseManager.removeFromBlacklist(config.id, phoneNumber)
        updateBlacklistCacheByAccountKey(config.accountKey)
        
        log.d(tag = TAG) { "Removed from blacklist: $phoneNumber for $accountKey" }
    }
    
    /**
     * Obtiene lista negra para una cuenta
     */
    fun getBlacklist(accountKey: String): Flow<List<BlacklistEntry>> {
        return databaseManager.getBlacklistForAccount(accountKey)
    }
    
    /**
     * Verifica si un número está en la lista negra
     */
    suspend fun isPhoneNumberBlacklisted(accountKey: String, phoneNumber: String): Boolean {
        val config = getAssistantConfigEntity(accountKey) ?: return false
        return databaseManager.isPhoneNumberBlacklisted(config.id, phoneNumber)
    }
    
    // === PROCESAMIENTO DE LLAMADAS ===
    
    /**
     * Procesa una llamada entrante según la configuración del asistente
     * MÉTODO PRINCIPAL que debe ser llamado desde SipCoreManager
     */
    suspend fun processIncomingCall(
        accountKey: String,
        callId: String,
        callerNumber: String,
        callerDisplayName: String? = null
    ): AssistantProcessingResult {
        
        val startTime = System.currentTimeMillis()
        _isProcessingFlow.value = true
        
        try {
            log.d(tag = TAG) { "Processing incoming call for $accountKey from $callerNumber" }
            
            // Obtener configuración del asistente
            val config = getAssistantConfigEntity(accountKey)
            
            if (config == null || !config.shouldProcessCall()) {
                log.d(tag = TAG) { "Assistant not active for $accountKey, allowing call" }
                return AssistantProcessingResult(
                    shouldProcess = false,
                    action = AssistantAction.REJECT_IMMEDIATELY,
                    reason = "assistant_disabled"
                )
            }
            
            // Determinar acción según el modo
            val result = when (config.mode) {
                AssistantMode.CONTACTS_ONLY -> processContactsOnlyMode(config, callerNumber)
                AssistantMode.BLACKLIST_FILTER -> processBlacklistMode(config, callerNumber)
                AssistantMode.DISABLED -> AssistantProcessingResult(
                    shouldProcess = false,
                    action = AssistantAction.REJECT_IMMEDIATELY,
                    reason = "assistant_disabled"
                )
            }
            
            // Si debe procesar la llamada, ejecutar la acción
            if (result.shouldProcess) {
                executeAssistantAction(
                    config = config,
                    callId = callId,
                    callerNumber = callerNumber,
                    callerDisplayName = callerDisplayName,
                    action = result.action,
                    reason = result.reason,
                    processingTime = System.currentTimeMillis() - startTime
                )
            }
            
            log.d(tag = TAG) { 
                "Call processing completed for $accountKey: shouldProcess=${result.shouldProcess}, action=${result.action}, reason=${result.reason}" 
            }
            
            return result.copy(config = config.toAssistantConfig())
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing incoming call: ${e.message}" }
            return AssistantProcessingResult(
                shouldProcess = false,
                action = AssistantAction.REJECT_IMMEDIATELY,
                reason = "processing_error: ${e.message}"
            )
        } finally {
            _isProcessingFlow.value = false
        }
    }
    
    /**
     * Procesa llamada en modo "solo contactos"
     */
    private suspend fun processContactsOnlyMode(
        config: AssistantConfigEntity,
        callerNumber: String
    ): AssistantProcessingResult {
        
        val isInContacts = contactManager.isPhoneNumberInContacts(callerNumber)
        
        return if (isInContacts) {
            // Número está en contactos, permitir llamada
            AssistantProcessingResult(
                shouldProcess = false,
                action = config.action,
                reason = "in_contacts"
            )
        } else {
            // Número NO está en contactos, procesar según configuración
            AssistantProcessingResult(
                shouldProcess = true,
                action = config.action,
                reason = "not_in_contacts",
                assistantNumber = config.assistantNumber.takeIf { it.isNotEmpty() }
            )
        }
    }
    
    /**
     * Procesa llamada en modo lista negra
     */
    private suspend fun processBlacklistMode(
        config: AssistantConfigEntity,
        callerNumber: String
    ): AssistantProcessingResult {
        
        val isBlacklisted = databaseManager.isPhoneNumberBlacklisted(config.id, callerNumber)
        
        return if (isBlacklisted) {
            // Número está en lista negra, procesar según configuración
            AssistantProcessingResult(
                shouldProcess = true,
                action = config.action,
                reason = "blacklisted",
                assistantNumber = config.assistantNumber.takeIf { it.isNotEmpty() }
            )
        } else {
            // Número NO está en lista negra, permitir llamada
            AssistantProcessingResult(
                shouldProcess = false,
                action = config.action,
                reason = "not_blacklisted"
            )
        }
    }
    
    /**
     * Ejecuta la acción del asistente
     */
    private suspend fun executeAssistantAction(
        config: AssistantConfigEntity,
        callId: String,
        callerNumber: String,
        callerDisplayName: String?,
        action: AssistantAction,
        reason: String,
        processingTime: Long
    ) {
        
        when (action) {
            AssistantAction.REJECT_IMMEDIATELY -> {
                log.d(tag = TAG) { "Rejecting call immediately: $callId from $callerNumber" }
                
                // Notificar al SipCoreManager para rechazar sin que suene
                onCallShouldBeRejectedCallback?.invoke(callId, reason)
                
                // Registrar en historial
                logAssistantAction(
                    config = config,
                    callerNumber = callerNumber,
                    callerDisplayName = callerDisplayName,
                    action = action,
                    reason = reason,
                    processingTime = processingTime,
                    wasDeflected = false,
                    deflectionSuccess = false
                )
            }
            
            AssistantAction.SEND_TO_ASSISTANT -> {
                log.d(tag = TAG) { "Deflecting call to assistant: $callId from $callerNumber to ${config.assistantNumber}" }
                
                // Notificar al SipCoreManager para hacer deflection
                onCallShouldBeDeflectedCallback?.invoke(callId, config.assistantNumber, reason)
                
                // Registrar en historial (el resultado de deflection se actualizará después)
                logAssistantAction(
                    config = config,
                    callerNumber = callerNumber,
                    callerDisplayName = callerDisplayName,
                    action = action,
                    reason = reason,
                    processingTime = processingTime,
                    wasDeflected = true,
                    deflectionSuccess = false, // Se actualizará cuando sepamos el resultado
                    assistantNumber = config.assistantNumber
                )
            }
        }
    }
    
    /**
     * Notifica el resultado de una deflección (para actualizar el historial)
     */
    suspend fun notifyDeflectionResult(
        callId: String,
        callerNumber: String,
        success: Boolean,
        errorMessage: String? = null
    ) {
        try {
            // Buscar el log más reciente para este número
            val recentLog = databaseManager.getRecentAssistantCallLogForNumber(callerNumber)
            
            if (recentLog != null && recentLog.wasDeflected && !recentLog.deflectionSuccess) {
                // Actualizar el resultado
                databaseManager.updateDeflectionResult(recentLog.id, success, errorMessage)
                
                log.d(tag = TAG) { 
                    "Deflection result updated for $callerNumber: success=$success" +
                    if (errorMessage != null) ", error=$errorMessage" else ""
                }
            }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error updating deflection result: ${e.message}" }
        }
    }
    
    /**
     * Registra acción del asistente en el historial
     */
    private suspend fun logAssistantAction(
        config: AssistantConfigEntity,
        callerNumber: String,
        callerDisplayName: String?,
        action: AssistantAction,
        reason: String,
        processingTime: Long,
        wasDeflected: Boolean,
        deflectionSuccess: Boolean,
        assistantNumber: String? = null
    ) {
        try {
            val callLog = AssistantCallLogEntity(
                id = generateId(),
                assistantConfigId = config.id,
                accountKey = config.accountKey,
                callerNumber = callerNumber,
                callerDisplayName = callerDisplayName,
                action = action,
                reason = reason,
                assistantNumber = assistantNumber,
                wasDeflected = wasDeflected,
                deflectionSuccess = deflectionSuccess,
                processingTimeMs = processingTime
            )
            
            databaseManager.insertAssistantCallLog(callLog)
            
            log.d(tag = TAG) { "Assistant action logged: $action for $callerNumber" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error logging assistant action: ${e.message}" }
        }
    }
    
    // === MÉTODOS DE CONFIGURACIÓN ===
    
    /**
     * Actualiza modo del asistente
     */
    suspend fun updateAssistantMode(accountKey: String, mode: AssistantMode) {
        databaseManager.updateAssistantMode(accountKey, mode)
        updateActiveConfigsCache()
        log.d(tag = TAG) { "Assistant mode updated for $accountKey: $mode" }
    }
    
    /**
     * Actualiza acción del asistente
     */
    suspend fun updateAssistantAction(accountKey: String, action: AssistantAction) {
        databaseManager.updateAssistantAction(accountKey, action)
        updateActiveConfigsCache()
        log.d(tag = TAG) { "Assistant action updated for $accountKey: $action" }
    }
    
    /**
     * Actualiza número del asistente
     */
    suspend fun updateAssistantNumber(accountKey: String, assistantNumber: String) {
        databaseManager.updateAssistantNumber(accountKey, assistantNumber)
        updateActiveConfigsCache()
        log.d(tag = TAG) { "Assistant number updated for $accountKey: $assistantNumber" }
    }
    
    // === MÉTODOS DE CONSULTA ===
    
    /**
     * Verifica si el asistente está activo para una cuenta
     */
    fun isAssistantActive(accountKey: String): Boolean {
        return _activeConfigsFlow.value[accountKey]?.isActive() ?: false
    }
    
    /**
     * Obtiene estadísticas del asistente para una cuenta
     */
    suspend fun getAssistantStatistics(accountKey: String): AssistantStatistics? {
        return databaseManager.getAssistantStatistics(accountKey)
    }
    
    /**
     * Obtiene historial de llamadas procesadas
     */
    fun getAssistantCallLogs(accountKey: String): Flow<List<AssistantCallLog>> {
        return databaseManager.getAssistantCallLogsForAccount(accountKey)
    }
    
    // === MÉTODOS PRIVADOS ===
    
    /**
     * Carga configuraciones activas al inicializar
     */
    private fun loadActiveConfigurations() {
        scope.launch {
            try {
                databaseManager.getActiveAssistantConfigs().collect { configs ->
                    val configMap = configs.associate { it.accountKey to it }
                    _activeConfigsFlow.value = configMap

                    // Use accountKey instead of id
                    configs.forEach { config ->
                        if (config.mode == AssistantMode.BLACKLIST_FILTER) {
                            updateBlacklistCacheByAccountKey(config.accountKey)
                        }
                    }
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error loading active configurations: ${e.message}" }
            }
        }
    }
    
    /**
     * Actualiza cache de configuraciones activas
     */
    private suspend fun updateActiveConfigsCache() {
        try {
            val configs = databaseManager.getActiveAssistantConfigsList()
            val configMap = configs.associate { it.accountKey to it.toAssistantConfig() }
            _activeConfigsFlow.value = configMap
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error updating active configs cache: ${e.message}" }
        }
    }
//
//    /**
//     * Actualiza cache de lista negra
//     */
//    private suspend fun updateBlacklistCache(configId: String) {
//        try {
//            val blacklist = databaseManager.getBlacklistForConfigList(configId)
//            blacklistCache[configId] = blacklist.map { it.toBlacklistEntry() }
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error updating blacklist cache: ${e.message}" }
//        }
//    }
    /**
     * Updates blacklist cache by account key
     */
    private suspend fun updateBlacklistCacheByAccountKey(accountKey: String) {
        try {
            val config = getAssistantConfigEntity(accountKey)
            if (config != null) {
                val blacklist = databaseManager.getBlacklistForConfigList(config.id)
                blacklistCache[config.id] = blacklist.map { it.toBlacklistEntry() }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error updating blacklist cache: ${e.message}" }
        }
    }
    /**
     * Obtiene entidad de configuración del asistente
     */
    private suspend fun getAssistantConfigEntity(accountKey: String): AssistantConfigEntity? {
        return databaseManager.getAssistantConfigEntity(accountKey)
    }
    
    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        val activeConfigs = _activeConfigsFlow.value
        
        return buildString {
            appendLine("=== ASSISTANT MANAGER DIAGNOSTIC ===")
            appendLine("Active Configurations: ${activeConfigs.size}")
            appendLine("Is Processing: ${_isProcessingFlow.value}")
            appendLine("Blacklist Cache Size: ${blacklistCache.size}")
            appendLine("Callbacks Set: reject=${onCallShouldBeRejectedCallback != null}, deflect=${onCallShouldBeDeflectedCallback != null}")
            
            appendLine("\n--- Active Configurations ---")
            activeConfigs.forEach { (accountKey, config) ->
                appendLine("$accountKey:")
                appendLine("  Mode: ${config.mode}")
                appendLine("  Action: ${config.action}")
                appendLine("  Assistant Number: ${config.assistantNumber}")
                appendLine("  Enabled At: ${config.enabledAt}")
            }
            
            appendLine("\n--- Blacklist Cache ---")
            blacklistCache.forEach { (configId, entries) ->
                appendLine("Config $configId: ${entries.size} entries")
            }
            
            appendLine("\n${contactManager.getDiagnosticInfo()}")
        }
    }
    
    /**
     * Limpia recursos
     */
    fun dispose() {
        contactManager.dispose()
        blacklistCache.clear()
        _activeConfigsFlow.value = emptyMap()
        log.d(tag = TAG) { "AssistantManager disposed" }
    }
}