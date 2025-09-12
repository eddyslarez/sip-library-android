package com.eddyslarez.siplibrary.data.services.assistant

import android.app.Application
import com.eddyslarez.siplibrary.core.SipCoreManager
import com.eddyslarez.siplibrary.data.database.DatabaseManager
import com.eddyslarez.siplibrary.data.models.CallData
import com.eddyslarez.siplibrary.data.models.CallDirections
import com.eddyslarez.siplibrary.data.models.Contact
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Integración del sistema de asistente con SipCoreManager
 * Maneja el procesamiento automático de llamadas entrantes
 * 
 * @author Eddys Larez
 */
class AssistantIntegration(
    private val application: Application,
    private val sipCoreManager: SipCoreManager,
    private val databaseManager: DatabaseManager
) {
    
    private val TAG = "AssistantIntegration"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Gestor del asistente
    private val assistantManager = AssistantManager(application, databaseManager)
    
    // Estado de inicialización
    private var isInitialized = false
    
    /**
     * Inicializa la integración del asistente
     */
    fun initialize() {
        if (isInitialized) {
            log.d(tag = TAG) { "Assistant integration already initialized" }
            return
        }
        
        log.d(tag = TAG) { "Initializing assistant integration..." }
        
        // Configurar callbacks del asistente
        setupAssistantCallbacks()
        
        // Configurar listener de llamadas entrantes en SipCoreManager
        setupIncomingCallListener()
        
        isInitialized = true
        log.d(tag = TAG) { "Assistant integration initialized successfully" }
    }
    
    /**
     * Configura callbacks del asistente
     */
    private fun setupAssistantCallbacks() {
        assistantManager.setCallbacks(
            onCallShouldBeRejected = { callId, reason ->
                handleCallRejection(callId, reason)
            },
            onCallShouldBeDeflected = { callId, assistantNumber, reason ->
                handleCallDeflection(callId, assistantNumber, reason)
            }
        )
    }
    
    /**
     * Configura listener para llamadas entrantes
     */
    private fun setupIncomingCallListener() {
        // Este método debe ser llamado desde SipCoreManager cuando se recibe una llamada
        // Por ahora, documentamos la integración necesaria
        log.d(tag = TAG) { "Incoming call listener setup completed" }
    }
    
    /**
     * MÉTODO PRINCIPAL: Procesa llamada entrante
     * Este método debe ser llamado desde SipCoreManager al recibir un INVITE
     */
    suspend fun processIncomingCall(
        accountKey: String,
        callId: String,
        callData: CallData
    ): Boolean {
        
        if (!isInitialized) {
            log.w(tag = TAG) { "Assistant integration not initialized" }
            return false
        }
        
        try {
            log.d(tag = TAG) { 
                "Processing incoming call: $callId from ${callData.from} for account $accountKey" 
            }
            
            val result = assistantManager.processIncomingCall(
                accountKey = accountKey,
                callId = callId,
                callerNumber = callData.from,
                callerDisplayName = callData.remoteDisplayName.ifEmpty { null }
            )
            
            log.d(tag = TAG) { 
                "Assistant processing result: shouldProcess=${result.shouldProcess}, action=${result.action}, reason=${result.reason}" 
            }
            
            return result.shouldProcess
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing incoming call: ${e.message}" }
            return false
        }
    }
    
    /**
     * Maneja rechazo de llamada
     */
    private fun handleCallRejection(callId: String, reason: String) {
        scope.launch {
            try {
                log.d(tag = TAG) { "Handling call rejection: $callId, reason: $reason" }
                
                sipCoreManager.rejectCall(callId)
                
                log.d(tag = TAG) { "Call rejected silently: $callId" }
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error handling call rejection: ${e.message}" }
            }
        }
    }
    
    /**
     * Maneja deflección de llamada al asistente
     */
    private fun handleCallDeflection(callId: String, assistantNumber: String, reason: String) {
        scope.launch {
            try {
                log.d(tag = TAG) { 
                    "Handling call deflection: $callId to $assistantNumber, reason: $reason" 
                }
                
                // Llamar al método de deflección de SipCoreManager
                val success = sipCoreManager.deflectIncomingCall(callId, assistantNumber)
                
                // Notificar resultado al asistente
                val callData = sipCoreManager.getCallData(callId)
                if (callData != null) {
                    assistantManager.notifyDeflectionResult(
                        callId = callId,
                        callerNumber = callData.from,
                        success = success,
                        errorMessage = if (!success) "Deflection failed" else null
                    )
                }
                
                log.d(tag = TAG) { "Call deflection result: $callId -> success=$success" }
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error handling call deflection: ${e.message}" }
                
                // Notificar fallo
                val callData = sipCoreManager.getCallData(callId)
                if (callData != null) {
                    assistantManager.notifyDeflectionResult(
                        callId = callId,
                        callerNumber = callData.from,
                        success = false,
                        errorMessage = e.message
                    )
                }
            }
        }
    }
    
    // === MÉTODOS PÚBLICOS PARA LA LIBRERÍA ===
    
    /**
     * Permite establecer contactos manualmente
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
     * Obtiene el gestor del asistente
     */
    fun getAssistantManager(): AssistantManager {
        return assistantManager
    }
    
    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== ASSISTANT INTEGRATION DIAGNOSTIC ===")
            appendLine("Is Initialized: $isInitialized")
            appendLine("SipCoreManager Available: ${sipCoreManager != null}")
            appendLine("DatabaseManager Available: ${databaseManager != null}")
            
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
        log.d(tag = TAG) { "AssistantIntegration disposed" }
    }
}