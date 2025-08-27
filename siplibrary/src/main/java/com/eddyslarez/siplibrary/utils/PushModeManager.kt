package com.eddyslarez.siplibrary.utils

import com.eddyslarez.siplibrary.data.models.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Gestor de modo push con transiciones automáticas y manuales
 * 
 * @author Eddys Larez
 */
class PushModeManager(
    private val config: PushModeConfig = PushModeConfig()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val TAG = "PushModeManager"

    // Estados
    private val _pushModeStateFlow = MutableStateFlow(
        PushModeState(
            currentMode = PushMode.FOREGROUND,
            previousMode = null,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            reason = "Initial state"
        )
    )
    val pushModeStateFlow: StateFlow<PushModeState> = _pushModeStateFlow.asStateFlow()

    // Jobs para transiciones automáticas
    private var transitionJob: Job? = null
    private var callEndTransitionJob: Job? = null

    // Callbacks
    private var onModeChangeCallback: ((PushModeState) -> Unit)? = null
    private var onRegistrationRequiredCallback: ((Set<String>, PushMode) -> Unit)? = null

    // Estado interno
    private var isCallActive = false
    private var wasInPushBeforeCall = false

    /**
     * Configura callbacks para notificaciones de cambios
     */
    fun setCallbacks(
        onModeChange: ((PushModeState) -> Unit)? = null,
        onRegistrationRequired: ((Set<String>, PushMode) -> Unit)? = null
    ) {
        this.onModeChangeCallback = onModeChange
        this.onRegistrationRequiredCallback = onRegistrationRequired
    }
    /**
     * Notifica que la aplicación pasó a segundo plano
     */
    fun onAppBackgrounded(registeredAccounts: Set<String>) {
        log.d(tag = TAG) {
            "=== APP BACKGROUNDED EVENT ===" +
                    "\nRegistered accounts: ${registeredAccounts.size}" +
                    "\nAccounts: $registeredAccounts" +
                    "\nCurrent mode: ${getCurrentMode()}" +
                    "\nStrategy: ${config.strategy}" +
                    "\nIs call active: $isCallActive"
        }

        if (config.strategy == PushModeStrategy.AUTOMATIC && !isCallActive && registeredAccounts.isNotEmpty()) {
            log.d(tag = TAG) { "Conditions met for transition to push mode" }
            scheduleTransitionToPush(registeredAccounts, PushModeReasons.APP_BACKGROUNDED)
        } else {
            log.w(tag = TAG) {
                "Transition to push NOT scheduled:" +
                        "\n- Strategy: ${config.strategy}" +
                        "\n- Call active: $isCallActive" +
                        "\n- Accounts: ${registeredAccounts.size}"
            }
        }
    }
//    /**
//     * Notifica que la aplicación pasó a segundo plano
//     */
//    fun onAppBackgrounded(registeredAccounts: Set<String>) {
//        log.d(tag = TAG) { "App backgrounded with ${registeredAccounts.size} accounts" }
//
//        if (config.strategy == PushModeStrategy.AUTOMATIC && !isCallActive) {
//            scheduleTransitionToPush(registeredAccounts, PushModeReasons.APP_BACKGROUNDED)
//        }
//    }
    /**
     * Notifica que la aplicación pasó a primer plano
     */
    fun onAppForegrounded(registeredAccounts: Set<String>) {
        log.d(tag = TAG) {
            "=== APP FOREGROUNDED EVENT ===" +
                    "\nRegistered accounts: ${registeredAccounts.size}" +
                    "\nAccounts: $registeredAccounts" +
                    "\nCurrent mode: ${getCurrentMode()}" +
                    "\nStrategy: ${config.strategy}"
        }

        // Cancelar transición pendiente a push
        cancelPendingTransition()

        if (config.strategy == PushModeStrategy.AUTOMATIC && registeredAccounts.isNotEmpty()) {
            log.d(tag = TAG) { "Transitioning to foreground mode" }
            transitionToForeground(registeredAccounts, PushModeReasons.APP_FOREGROUNDED)
        } else {
            log.w(tag = TAG) { "Transition to foreground NOT executed - strategy: ${config.strategy}" }
        }
    }

//    /**
//     * Notifica que la aplicación pasó a primer plano
//     */
//    fun onAppForegrounded(registeredAccounts: Set<String>) {
//        log.d(tag = TAG) { "App foregrounded with ${registeredAccounts.size} accounts" }
//
//        // Cancelar transición pendiente a push
//        cancelPendingTransition()
//
//        if (config.strategy == PushModeStrategy.AUTOMATIC) {
//            transitionToForeground(registeredAccounts, PushModeReasons.APP_FOREGROUNDED)
//        }
//    }

    /**
     * Notifica que se recibió una llamada entrante
     */
    fun onIncomingCallReceived(registeredAccounts: Set<String>) {
        log.d(tag = TAG) { "Incoming call received, current mode: ${getCurrentMode()}" }

        val currentState = _pushModeStateFlow.value
        
        // Recordar si estábamos en modo push antes de la llamada
        if (currentState.currentMode == PushMode.PUSH) {
            wasInPushBeforeCall = true
        }

        isCallActive = true

        // Cancelar cualquier transición pendiente
        cancelPendingTransition()
        cancelCallEndTransition()

        // Si estamos en modo push y se requiere reregistro automático
        if (currentState.currentMode == PushMode.PUSH && config.forceReregisterOnIncomingCall) {
            transitionToForeground(registeredAccounts, PushModeReasons.INCOMING_CALL_RECEIVED)
        }
    }

    /**
     * Notifica que una llamada terminó
     */
    fun onCallEnded(registeredAccounts: Set<String>) {
        log.d(tag = TAG) { "Call ended, was in push before call: $wasInPushBeforeCall" }

        isCallActive = false

        // Si estábamos en modo push antes de la llamada y está configurado para volver
        if (wasInPushBeforeCall && config.returnToPushAfterCallEnd) {
            scheduleReturnToPushAfterCall(registeredAccounts)
        }

        // Reset del flag
        wasInPushBeforeCall = false
    }

    /**
     * Transición manual a modo push
     */
    fun switchToPushMode(accountsToSwitch: Set<String>) {
        log.d(tag = TAG) { "Manual switch to push mode for accounts: $accountsToSwitch" }

        cancelPendingTransition()
        transitionToPush(accountsToSwitch, PushModeReasons.MANUAL_SWITCH)
    }

    /**
     * Transición manual a modo foreground
     */
    fun switchToForegroundMode(accountsToSwitch: Set<String>) {
        log.d(tag = TAG) { "Manual switch to foreground mode for accounts: $accountsToSwitch" }

        cancelPendingTransition()
        transitionToForeground(accountsToSwitch, PushModeReasons.MANUAL_SWITCH)
    }

    /**
     * Notifica que se recibió una notificación push
     */
    fun onPushNotificationReceived(specificAccount: String? = null, allRegisteredAccounts: Set<String> = emptySet()) {
        log.d(tag = TAG) {
            "=== PUSH NOTIFICATION RECEIVED ===" +
                    "\nSpecific account: $specificAccount" +
                    "\nAll registered accounts: $allRegisteredAccounts" +
                    "\nCurrent mode: ${getCurrentMode()}"
        }

        val currentState = _pushModeStateFlow.value

        // Si estamos en modo push, cambiar a foreground solo la cuenta específica o todas
        if (currentState.currentMode == PushMode.PUSH) {
            log.d(tag = TAG) { "Currently in PUSH mode, switching to FOREGROUND" }
            wasInPushBeforeCall = true

            if (specificAccount != null) {
                // Solo cambiar la cuenta específica a foreground
                log.d(tag = TAG) { "Switching SPECIFIC account to foreground: $specificAccount" }
                transitionSpecificAccountToForeground(specificAccount, PushModeReasons.PUSH_NOTIFICATION_RECEIVED)
            } else {
                // Cambiar todas las cuentas (comportamiento anterior)
                log.d(tag = TAG) { "Switching ALL accounts to foreground: $allRegisteredAccounts" }
                transitionToForeground(allRegisteredAccounts, PushModeReasons.PUSH_NOTIFICATION_RECEIVED)
            }
        } else {
            log.d(tag = TAG) { "Not in PUSH mode, ignoring push notification. Current mode: ${currentState.currentMode}" }
        }
    }

    /**
     * Transición de una cuenta específica a modo foreground
     */
    private fun transitionSpecificAccountToForeground(accountKey: String, reason: String) {
        val currentState = _pushModeStateFlow.value
        
        log.d(tag = TAG) { "Transitioning specific account to FOREGROUND: $accountKey, reason: $reason" }

        // Crear nuevo estado manteniendo las otras cuentas en push
        val updatedAccountsInPush = currentState.accountsInPushMode.toMutableSet()
        updatedAccountsInPush.remove(accountKey)
        
        val newState = PushModeState(
            currentMode = if (updatedAccountsInPush.isEmpty()) PushMode.FOREGROUND else PushMode.PUSH,
            previousMode = currentState.currentMode,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            reason = reason,
            accountsInPushMode = updatedAccountsInPush,
            wasInPushBeforeCall = currentState.wasInPushBeforeCall,
            specificAccountInForeground = accountKey
        )

        _pushModeStateFlow.value = newState

        // Notificar que se requiere reregistro solo para la cuenta específica
        onRegistrationRequiredCallback?.invoke(setOf(accountKey), PushMode.FOREGROUND)
        onModeChangeCallback?.invoke(newState)
    }

    /**
     * Notifica que una llamada terminó para una cuenta específica
     */
    fun onCallEndedForAccount(accountKey: String, allRegisteredAccounts: Set<String>) {
        log.d(tag = TAG) { "Call ended for specific account: $accountKey, was in push before call: $wasInPushBeforeCall" }

        isCallActive = false

        // Si estábamos en modo push antes de la llamada y está configurado para volver
        if (wasInPushBeforeCall && config.returnToPushAfterCallEnd) {
            log.d(tag = TAG) { "Scheduling return to push mode for account: $accountKey" }
            scheduleReturnToPushForSpecificAccount(accountKey)
        } else {
            log.d(tag = TAG) { "Not returning to push mode - wasInPushBeforeCall: $wasInPushBeforeCall, returnToPushAfterCallEnd: ${config.returnToPushAfterCallEnd}" }
        }

        // Reset del flag
        wasInPushBeforeCall = false
    }


    /**
     * Programa retorno a modo push para una cuenta específica después de que termine una llamada
     */
    private fun scheduleReturnToPushForSpecificAccount(accountKey: String) {
        cancelCallEndTransition()

        callEndTransitionJob = scope.launch {
            try {
                // Delay más corto para retorno después de llamada
                val returnDelay = 2000L
                log.d(tag = TAG) { "Scheduling return to push for account $accountKey in ${returnDelay}ms after call end" }
                delay(returnDelay)

                // Verificar que no hay nueva llamada activa
                if (!isCallActive) {
                    log.d(tag = TAG) { "Executing return to push mode for account: $accountKey" }
                    transitionSpecificAccountToPush(accountKey, PushModeReasons.CALL_ENDED)
                } else {
                    log.d(tag = TAG) { "Return to push cancelled for $accountKey - new call is active" }
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in call end transition for $accountKey: ${e.message}" }
            }
        }
    }

    /**
     * Transición de una cuenta específica a modo push
     */
    private fun transitionSpecificAccountToPush(accountKey: String, reason: String) {
        val currentState = _pushModeStateFlow.value

        log.d(tag = TAG) { "Transitioning specific account to PUSH: $accountKey, reason: $reason" }

        // Agregar la cuenta específica al conjunto de cuentas en push
        val updatedAccountsInPush = currentState.accountsInPushMode.toMutableSet()
        updatedAccountsInPush.add(accountKey)

        val newState = PushModeState(
            currentMode = PushMode.PUSH,
            previousMode = currentState.currentMode,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            reason = reason,
            accountsInPushMode = updatedAccountsInPush,
            wasInPushBeforeCall = currentState.wasInPushBeforeCall,
            specificAccountInForeground = null
        )

        _pushModeStateFlow.value = newState

        // Notificar que se requiere reregistro en modo push para la cuenta específica
        onRegistrationRequiredCallback?.invoke(setOf(accountKey), PushMode.PUSH)
        onModeChangeCallback?.invoke(newState)

        log.d(tag = TAG) { "Account $accountKey successfully transitioned to push mode" }
    }

    /**
     * Programa transición a modo push con delay
     */
//    private fun scheduleTransitionToPush(accounts: Set<String>, reason: String) {
//        cancelPendingTransition()
//
//        transitionJob = scope.launch {
//            try {
//                log.d(tag = TAG) { "Scheduling transition to push in ${config.autoTransitionDelay}ms" }
//                delay(config.autoTransitionDelay)
//
//                // Verificar que aún no hay llamada activa
//                if (!isCallActive) {
//                    transitionToPush(accounts, reason)
//                } else {
//                    log.d(tag = TAG) { "Transition to push cancelled - call is active" }
//                }
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error in scheduled transition: ${e.message}" }
//            }
//        }
//    }
    /**
     * Programa transición a modo push con delay y logging mejorado
     */
    private fun scheduleTransitionToPush(accounts: Set<String>, reason: String) {
        log.d(tag = TAG) { "Scheduling transition to push mode..." }

        cancelPendingTransition()

        transitionJob = scope.launch {
            try {
                log.d(tag = TAG) {
                    "Starting transition delay of ${config.autoTransitionDelay}ms" +
                            "\nReason: $reason" +
                            "\nAccounts to switch: $accounts"
                }

                delay(config.autoTransitionDelay)

                // Verificar que aún no hay llamada activa
                if (!isCallActive) {
                    log.d(tag = TAG) { "Delay completed, executing transition to push" }
                    transitionToPush(accounts, reason)
                } else {
                    log.d(tag = TAG) { "Transition to push cancelled - call became active during delay" }
                }
            } catch (e: CancellationException) {
                log.d(tag = TAG) { "Transition to push was cancelled" }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in scheduled transition: ${e.message}" }
            }
        }
    }

    /**
     * Programa retorno a modo push después de que termine una llamada
     */
    private fun scheduleReturnToPushAfterCall(accounts: Set<String>) {
        cancelCallEndTransition()

        callEndTransitionJob = scope.launch {
            try {
                // Delay más corto para retorno después de llamada
                val returnDelay = 2000L
                log.d(tag = TAG) { "Scheduling return to push in ${returnDelay}ms after call end" }
                delay(returnDelay)
                
                // Verificar que no hay nueva llamada activa
                if (!isCallActive) {
                    transitionToPush(accounts, PushModeReasons.CALL_ENDED)
                } else {
                    log.d(tag = TAG) { "Return to push cancelled - new call is active" }
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in call end transition: ${e.message}" }
            }
        }
    }


    /**
     * Transición inmediata a modo push con logging detallado
     */
    private fun transitionToPush(accounts: Set<String>, reason: String) {
        val currentState = _pushModeStateFlow.value

        log.d(tag = TAG) {
            "=== EXECUTING TRANSITION TO PUSH ===" +
                    "\nFrom mode: ${currentState.currentMode}" +
                    "\nReason: $reason" +
                    "\nAccounts: $accounts" +
                    "\nCallback set: ${onRegistrationRequiredCallback != null}" +
                    "\nMode change callback set: ${onModeChangeCallback != null}"
        }

        if (currentState.currentMode == PushMode.PUSH) {
            log.d(tag = TAG) { "Already in push mode, ignoring transition" }
            return
        }

        val newState = PushModeState(
            currentMode = PushMode.PUSH,
            previousMode = currentState.currentMode,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            reason = reason,
            accountsInPushMode = accounts,
            wasInPushBeforeCall = currentState.wasInPushBeforeCall
        )

        _pushModeStateFlow.value = newState

        log.d(tag = TAG) { "Push mode state updated successfully" }

        // Notificar callbacks
        try {
            log.d(tag = TAG) { "Calling registration required callback for ${accounts.size} accounts" }
            onRegistrationRequiredCallback?.invoke(accounts, PushMode.PUSH)

            log.d(tag = TAG) { "Calling mode change callback" }
            onModeChangeCallback?.invoke(newState)

            log.d(tag = TAG) { "=== TRANSITION TO PUSH COMPLETED ===" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in transition callbacks: ${e.message}" }
        }
    }

//    private fun transitionToPush(accounts: Set<String>, reason: String) {
//        val currentState = _pushModeStateFlow.value
//
//        if (currentState.currentMode == PushMode.PUSH) {
//            log.d(tag = TAG) { "Already in push mode, ignoring transition" }
//            return
//        }
//
//        log.d(tag = TAG) { "Transitioning to PUSH mode: $reason" }
//
//        val newState = PushModeState(
//            currentMode = PushMode.PUSH,
//            previousMode = currentState.currentMode,
//            timestamp = Clock.System.now().toEpochMilliseconds(),
//            reason = reason,
//            accountsInPushMode = accounts,
//            wasInPushBeforeCall = currentState.wasInPushBeforeCall
//        )
//
//        _pushModeStateFlow.value = newState
//
//        // Notificar que se requiere reregistro en modo push
//        onRegistrationRequiredCallback?.invoke(accounts, PushMode.PUSH)
//        onModeChangeCallback?.invoke(newState)
//    }

    /**
     * Transición inmediata a modo foreground con logging detallado
     */
    private fun transitionToForeground(accounts: Set<String>, reason: String) {
        val currentState = _pushModeStateFlow.value

        log.d(tag = TAG) {
            "=== EXECUTING TRANSITION TO FOREGROUND ===" +
                    "\nFrom mode: ${currentState.currentMode}" +
                    "\nReason: $reason" +
                    "\nAccounts: $accounts"
        }

        if (currentState.currentMode == PushMode.FOREGROUND) {
            log.d(tag = TAG) { "Already in foreground mode, ignoring transition" }
            return
        }

        val newState = PushModeState(
            currentMode = PushMode.FOREGROUND,
            previousMode = currentState.currentMode,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            reason = reason,
            accountsInPushMode = emptySet(),
            wasInPushBeforeCall = currentState.wasInPushBeforeCall
        )

        _pushModeStateFlow.value = newState

        log.d(tag = TAG) { "Foreground mode state updated successfully" }

        // Notificar callbacks
        try {
            log.d(tag = TAG) { "Calling registration required callback for ${accounts.size} accounts" }
            onRegistrationRequiredCallback?.invoke(accounts, PushMode.FOREGROUND)

            log.d(tag = TAG) { "Calling mode change callback" }
            onModeChangeCallback?.invoke(newState)

            log.d(tag = TAG) { "=== TRANSITION TO FOREGROUND COMPLETED ===" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in transition callbacks: ${e.message}" }
        }
    }

    /**
     * Transición inmediata a modo foreground
     */
//    private fun transitionToForeground(accounts: Set<String>, reason: String) {
//        val currentState = _pushModeStateFlow.value
//
//        if (currentState.currentMode == PushMode.FOREGROUND) {
//            log.d(tag = TAG) { "Already in foreground mode, ignoring transition" }
//            return
//        }
//
//        log.d(tag = TAG) { "Transitioning to FOREGROUND mode: $reason" }
//
//        val newState = PushModeState(
//            currentMode = PushMode.FOREGROUND,
//            previousMode = currentState.currentMode,
//            timestamp = Clock.System.now().toEpochMilliseconds(),
//            reason = reason,
//            accountsInPushMode = emptySet(),
//            wasInPushBeforeCall = currentState.wasInPushBeforeCall
//        )
//
//        _pushModeStateFlow.value = newState
//
//        // Notificar que se requiere reregistro en modo foreground
//        onRegistrationRequiredCallback?.invoke(accounts, PushMode.FOREGROUND)
//        onModeChangeCallback?.invoke(newState)
//    }

    /**
     * Cancela transición pendiente
     */
    private fun cancelPendingTransition() {
        transitionJob?.cancel()
        transitionJob = null
    }

    /**
     * Cancela transición de fin de llamada
     */
    private fun cancelCallEndTransition() {
        callEndTransitionJob?.cancel()
        callEndTransitionJob = null
    }

    // === MÉTODOS DE CONSULTA ===

    fun getCurrentMode(): PushMode = _pushModeStateFlow.value.currentMode
    fun getCurrentState(): PushModeState = _pushModeStateFlow.value
    fun isInPushMode(): Boolean = getCurrentMode() == PushMode.PUSH
    fun isInForegroundMode(): Boolean = getCurrentMode() == PushMode.FOREGROUND
    fun getConfig(): PushModeConfig = config

    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        val state = getCurrentState()
        
        return buildString {
            appendLine("=== PUSH MODE MANAGER DIAGNOSTIC ===")
            appendLine("Current Mode: ${state.currentMode}")
            appendLine("Previous Mode: ${state.previousMode}")
            appendLine("Strategy: ${config.strategy}")
            appendLine("Reason: ${state.reason}")
            appendLine("Timestamp: ${state.timestamp}")
            appendLine("Is Call Active: $isCallActive")
            appendLine("Was In Push Before Call: $wasInPushBeforeCall")
            appendLine("Accounts In Push Mode: ${state.accountsInPushMode}")
            appendLine("Specific Account In Foreground: ${state.specificAccountInForeground}")
            appendLine("Transition Job Active: ${transitionJob?.isActive}")
            appendLine("Call End Job Active: ${callEndTransitionJob?.isActive}")
            appendLine("Auto Transition Delay: ${config.autoTransitionDelay}ms")
            appendLine("Force Reregister On Call: ${config.forceReregisterOnIncomingCall}")
            appendLine("Return To Push After Call: ${config.returnToPushAfterCallEnd}")
        }
    }

    /**
     * Limpieza de recursos
     */
    fun dispose() {
        cancelPendingTransition()
        cancelCallEndTransition()
        log.d(tag = TAG) { "PushModeManager disposed" }
    }
}