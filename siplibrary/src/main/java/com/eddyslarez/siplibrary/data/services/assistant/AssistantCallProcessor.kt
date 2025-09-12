package com.eddyslarez.siplibrary.data.services.assistant

import com.eddyslarez.siplibrary.data.models.*
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Procesador especializado para llamadas del asistente
 * Maneja la lógica de decisión y ejecución de acciones
 *
 * @author Eddys Larez
 */
class AssistantCallProcessor(
    private val assistantManager: AssistantManager // Add this parameter
) {

    private val TAG = "AssistantCallProcessor"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isInitialized = false

    // Callbacks para acciones
    private var onRejectCallCallback: ((String, String) -> Unit)? = null
    private var onDeflectCallCallback: ((String, String, String) -> Unit)? = null

    // Estadísticas de procesamiento
    private var totalProcessedCalls = 0L
    private var successfulActions = 0L
    private var failedActions = 0L

    /**
     * Configura callbacks para acciones del asistente
     */
    fun setCallbacks(
        onRejectCall: ((callId: String, reason: String) -> Unit)? = null,
        onDeflectCall: ((callId: String, assistantNumber: String, reason: String) -> Unit)? = null
    ) {
        this.onRejectCallCallback = onRejectCall
        this.onDeflectCallCallback = onDeflectCall
    }

    /**
     * Procesa una llamada entrante según las reglas del asistente
     */
    suspend fun processCall(
        callId: String,
        callerNumber: String,
        callerDisplayName: String?,
        config: AssistantConfig,
        contactChecker: suspend (String) -> Boolean,
        blacklistChecker: suspend (String) -> Boolean
    ): AssistantProcessingResult = withContext(Dispatchers.IO) {

        val startTime = System.currentTimeMillis()
        totalProcessedCalls++

        try {
            log.d(tag = TAG) {
                "Processing call $callId from $callerNumber for account ${config.accountKey}"
            }

            // Verificar si el asistente está activo
            if (!config.isActive()) {
                return@withContext AssistantProcessingResult(
                    shouldProcess = false,
                    action = AssistantAction.REJECT_IMMEDIATELY,
                    reason = "assistant_disabled",
                    config = config
                )
            }

            // Procesar según el modo configurado
            val result = when (config.mode) {
                AssistantMode.CONTACTS_ONLY -> {
                    processContactsOnlyMode(callerNumber, config, contactChecker)
                }

                AssistantMode.BLACKLIST_FILTER -> {
                    processBlacklistMode(callerNumber, config, blacklistChecker)
                }

                AssistantMode.DISABLED -> {
                    AssistantProcessingResult(
                        shouldProcess = false,
                        action = AssistantAction.REJECT_IMMEDIATELY,
                        reason = "mode_disabled",
                        config = config
                    )
                }
            }

            // Ejecutar acción si es necesario
            if (result.shouldProcess) {
                executeAction(callId, callerNumber, result)
            }

            val processingTime = System.currentTimeMillis() - startTime
            log.d(tag = TAG) {
                "Call processing completed in ${processingTime}ms: shouldProcess=${result.shouldProcess}, action=${result.action}"
            }

            return@withContext result

        } catch (e: Exception) {
            failedActions++
            log.e(tag = TAG) { "Error processing call: ${e.message}" }

            return@withContext AssistantProcessingResult(
                shouldProcess = false,
                action = AssistantAction.REJECT_IMMEDIATELY,
                reason = "processing_error: ${e.message}",
                config = config
            )
        }
    }
    /**
     * Initialize the processor
     */
    fun initialize() {
        isInitialized = true
        log.d(tag = TAG) { "AssistantCallProcessor initialized" }
    }
    /**
     * Procesa llamada en modo "solo contactos"
     */
    private suspend fun processContactsOnlyMode(
        callerNumber: String,
        config: AssistantConfig,
        contactChecker: suspend (String) -> Boolean
    ): AssistantProcessingResult {

        val isInContacts = contactChecker(callerNumber)

        log.d(tag = TAG) {
            "Contacts-only mode: $callerNumber is ${if (isInContacts) "IN" else "NOT IN"} contacts"
        }

        return if (isInContacts) {
            // Permitir llamada - está en contactos
            AssistantProcessingResult(
                shouldProcess = false,
                action = config.action,
                reason = "in_contacts",
                config = config
            )
        } else {
            // Procesar llamada - NO está en contactos
            AssistantProcessingResult(
                shouldProcess = true,
                action = config.action,
                reason = "not_in_contacts",
                assistantNumber = config.assistantNumber.takeIf { it.isNotEmpty() },
                config = config
            )
        }
    }

    /**
     * Procesa llamada en modo lista negra
     */
    private suspend fun processBlacklistMode(
        callerNumber: String,
        config: AssistantConfig,
        blacklistChecker: suspend (String) -> Boolean
    ): AssistantProcessingResult {

        val isBlacklisted = blacklistChecker(callerNumber)

        log.d(tag = TAG) {
            "Blacklist mode: $callerNumber is ${if (isBlacklisted) "BLACKLISTED" else "NOT BLACKLISTED"}"
        }

        return if (isBlacklisted) {
            // Procesar llamada - está en lista negra
            AssistantProcessingResult(
                shouldProcess = true,
                action = config.action,
                reason = "blacklisted",
                assistantNumber = config.assistantNumber.takeIf { it.isNotEmpty() },
                config = config
            )
        } else {
            // Permitir llamada - NO está en lista negra
            AssistantProcessingResult(
                shouldProcess = false,
                action = config.action,
                reason = "not_blacklisted",
                config = config
            )
        }
    }

    /**
     * Ejecuta la acción determinada por el asistente
     */
    private fun executeAction(
        callId: String,
        callerNumber: String,
        result: AssistantProcessingResult
    ) {
        scope.launch {
            try {
                when (result.action) {
                    AssistantAction.REJECT_IMMEDIATELY -> {
                        log.d(tag = TAG) {
                            "Executing REJECT_IMMEDIATELY for $callId from $callerNumber"
                        }

                        onRejectCallCallback?.invoke(callId, result.reason)
                        successfulActions++
                    }

                    AssistantAction.SEND_TO_ASSISTANT -> {
                        val assistantNumber = result.assistantNumber

                        if (assistantNumber.isNullOrEmpty()) {
                            log.e(tag = TAG) {
                                "Cannot deflect call $callId - no assistant number configured"
                            }
                            failedActions++
                            return@launch
                        }

                        log.d(tag = TAG) {
                            "Executing SEND_TO_ASSISTANT for $callId from $callerNumber to $assistantNumber"
                        }

                        onDeflectCallCallback?.invoke(callId, assistantNumber, result.reason)
                        successfulActions++
                    }
                }

            } catch (e: Exception) {
                failedActions++
                log.e(tag = TAG) { "Error executing assistant action: ${e.message}" }
            }
        }
    }

    /**
     * Notifica resultado de deflección
     */
    suspend fun notifyDeflectionResult(
        callId: String,
        callerNumber: String,
        success: Boolean,
        errorMessage: String? = null
    ) {
        assistantManager.notifyDeflectionResult(callId, callerNumber, success, errorMessage)
    }

    // === MÉTODOS PÚBLICOS PARA LA LIBRERÍA ===

    /**
     * Obtiene el gestor del asistente
     */
    fun getAssistantManager(): AssistantManager {
        return assistantManager
    }

    /**
     * Establece contactos manualmente
     */
    fun setManualContacts(contacts: List<Contact>) {
        assistantManager.setManualContacts(contacts)
    }

    /**
     * Cambia a usar contactos del dispositivo
     */
    fun useDeviceContacts() {
        assistantManager.useDeviceContacts()
    }

    /**
     * Verifica si el asistente está activo para una cuenta
     */
    fun isAssistantActive(accountKey: String): Boolean {
        return assistantManager.isAssistantActive(accountKey)
    }

    /**
     * Obtiene estadísticas de procesamiento
     */
    fun getProcessingStatistics(): ProcessingStatistics {
        return ProcessingStatistics(
            totalProcessedCalls = totalProcessedCalls,
            successfulActions = successfulActions,
            failedActions = failedActions,
            successRate = if (totalProcessedCalls > 0) {
                (successfulActions.toDouble() / totalProcessedCalls.toDouble()) * 100.0
            } else 0.0
        )
    }

    data class ProcessingStatistics(
        val totalProcessedCalls: Long,
        val successfulActions: Long,
        val failedActions: Long,
        val successRate: Double
    )

    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        val stats = getProcessingStatistics()

        return buildString {
            appendLine("=== ASSISTANT INTEGRATION DIAGNOSTIC ===")
            appendLine("Is Initialized: $isInitialized")
            appendLine("Total Processed Calls: ${stats.totalProcessedCalls}")
            appendLine("Successful Actions: ${stats.successfulActions}")
            appendLine("Failed Actions: ${stats.failedActions}")
            appendLine("Success Rate: ${"%.1f".format(stats.successRate)}%")
            appendLine("Callbacks Set: reject=${onRejectCallCallback != null}, deflect=${onDeflectCallCallback != null}")

            if (isInitialized) {
                appendLine("\n${assistantManager.getDiagnosticInfo()}")
            }
        }
    }

    /**
     * Limpia recursos
     */
    fun dispose() {
        assistantManager.dispose()
        isInitialized = false

        // Reset estadísticas
        totalProcessedCalls = 0L
        successfulActions = 0L
        failedActions = 0L

        log.d(tag = TAG) { "AssistantIntegration disposed" }
    }
}