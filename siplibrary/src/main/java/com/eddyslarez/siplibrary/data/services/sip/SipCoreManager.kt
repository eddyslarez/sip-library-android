@@ .. @@
 package com.eddyslarez.siplibrary.core
 
 import android.app.Application
+import com.eddyslarez.siplibrary.data.services.network.NetworkAwareReconnectionService
+import com.eddyslarez.siplibrary.data.services.network.ReconnectionManager
 import com.eddyslarez.siplibrary.data.models.*
 import com.eddyslarez.siplibrary.data.services.audio.AudioManager
 import com.eddyslarez.siplibrary.data.services.audio.CallHoldManager
@@ .. @@
     // Gestores principales
     private val webRtcManager: WebRtcManager = WebRtcManagerFactory.createWebRtcManager(application)
     private val audioManager = AudioManager(application)
+    private val networkReconnectionService = NetworkAwareReconnectionService(application)
     private val callHoldManager = CallHoldManager(webRtcManager)
     private val callHistoryManager = CallHistoryManager()
     private val dtmfManager = DtmfManager()
@@ .. @@
     fun initialize(config: EddysSipLibrary.SipConfig) {
         if (isInitialized) {
             log.d(tag = TAG) { "SipCoreManager already initialized" }
             return
         }
 
         try {
             log.d(tag = TAG) { "Initializing SipCoreManager..." }
             
             this.config = config
             
             // Inicializar CallStateManager
             CallStateManager.initialize()
             
             // Inicializar WebRTC
             webRtcManager.initialize()
             webRtcManager.setListener(createWebRtcEventListener())
             
+            // Inicializar servicio de reconexión de red
+            setupNetworkReconnectionService()
+            
             // Configurar platform registration
             setupPlatformRegistration()
             
             isInitialized = true
             log.d(tag = TAG) { "SipCoreManager initialized successfully" }
             
         } catch (e: Exception) {
             log.e(tag = TAG) { "Error initializing SipCoreManager: ${e.message}" }
             throw e
         }
     }
+    
+    /**
+     * Configura el servicio de reconexión de red
+     */
+    private fun setupNetworkReconnectionService() {
+        networkReconnectionService.setCallbacks(
+            onReconnectionRequired = { accountInfo, reason ->
+                log.d(tag = TAG) { 
+                    "Network reconnection required for ${accountInfo.getAccountIdentity()}: $reason" 
+                }
+                
+                scope.launch {
+                    handleNetworkReconnection(accountInfo, reason)
+                }
+            },
+            onNetworkStatusChanged = { networkInfo ->
+                log.d(tag = TAG) { 
+                    "Network status changed: ${networkInfo.networkType}, connected: ${networkInfo.isConnected}" 
+                }
+                
+                // Notificar a listeners si es necesario
+                notifyNetworkStatusChanged(networkInfo.isConnected)
+            }
+        )
+        
+        networkReconnectionService.start()
+    }
+    
+    /**
+     * Maneja reconexión de red
+     */
+    private suspend fun handleNetworkReconnection(
+        accountInfo: AccountInfo, 
+        reason: ReconnectionManager.ReconnectionReason
+    ) {
+        try {
+            val accountKey = accountInfo.getAccountIdentity()
+            
+            log.d(tag = TAG) { "Handling network reconnection for $accountKey, reason: $reason" }
+            
+            // Verificar si la cuenta aún existe
+            if (!accounts.containsKey(accountKey)) {
+                log.w(tag = TAG) { "Account $accountKey no longer exists, skipping reconnection" }
+                return
+            }
+            
+            // Cerrar WebSocket existente si está abierto
+            accountInfo.webSocketClient?.let { webSocket ->
+                if (webSocket.isConnected()) {
+                    log.d(tag = TAG) { "Closing existing WebSocket for $accountKey" }
+                    webSocket.close()
+                }
+            }
+            
+            // Resetear estado de autenticación
+            accountInfo.resetAuthState()
+            
+            // Actualizar estado de registro
+            RegistrationStateManager.updateAccountState(
+                accountKey, 
+                RegistrationState.IN_PROGRESS,
+                "Network reconnection in progress"
+            )
+            
+            // Esperar un poco para que la red se estabilice
+            delay(1000)
+            
+            // Intentar reconectar
+            val success = attemptAccountReconnection(accountInfo)
+            
+            if (success) {
+                log.d(tag = TAG) { "Network reconnection successful for $accountKey" }
+                RegistrationStateManager.updateAccountState(accountKey, RegistrationState.OK)
+            } else {
+                log.e(tag = TAG) { "Network reconnection failed for $accountKey" }
+                RegistrationStateManager.updateAccountState(
+                    accountKey, 
+                    RegistrationState.FAILED,
+                    "Network reconnection failed"
+                )
+            }
+            
+        } catch (e: Exception) {
+            log.e(tag = TAG) { "Error in network reconnection: ${e.message}" }
+        }
+    }
+    
+    /**
+     * Intenta reconectar una cuenta
+     */
+    private suspend fun attemptAccountReconnection(accountInfo: AccountInfo): Boolean {
+        return try {
+            val accountKey = accountInfo.getAccountIdentity()
+            
+            log.d(tag = TAG) { "Attempting reconnection for $accountKey" }
+            
+            // Crear nueva conexión WebSocket
+            val webSocket = createWebSocketConnection(accountInfo)
+            accountInfo.webSocketClient = webSocket
+            
+            // Conectar WebSocket
+            webSocket.connect()
+            
+            // Esperar conexión
+            var attempts = 0
+            while (!webSocket.isConnected() && attempts < 10) {
+                delay(500)
+                attempts++
+            }
+            
+            if (!webSocket.isConnected()) {
+                log.e(tag = TAG) { "WebSocket connection failed for $accountKey" }
+                return false
+            }
+            
+            log.d(tag = TAG) { "WebSocket connected for $accountKey, attempting registration" }
+            
+            // Intentar registro
+            val registrationSuccess = performRegistration(accountInfo)
+            
+            if (registrationSuccess) {
+                log.d(tag = TAG) { "Registration successful for $accountKey" }
+                return true
+            } else {
+                log.e(tag = TAG) { "Registration failed for $accountKey" }
+                return false
+            }
+            
+        } catch (e: Exception) {
+            log.e(tag = TAG) { "Error in account reconnection: ${e.message}" }
+            false
+        }
+    }
+    
+    /**
+     * Realiza el registro de una cuenta
+     */
+    private suspend fun performRegistration(accountInfo: AccountInfo): Boolean {
+        return try {
+            val accountKey = accountInfo.getAccountIdentity()
+            
+            // Determinar si estamos en modo push
+            val isInPushMode = pushModeManager.isInPushMode()
+            val accountsInPush = pushModeManager.getCurrentState().accountsInPushMode
+            val isAccountInPush = accountsInPush.contains(accountKey)
+            
+            log.d(tag = TAG) { 
+                "Performing registration for $accountKey, push mode: $isInPushMode, account in push: $isAccountInPush" 
+            }
+            
+            // Construir mensaje de registro
+            val callId = accountInfo.generateCallId()
+            val fromTag = generateSipTag()
+            
+            accountInfo.callId = callId
+            accountInfo.fromTag = fromTag
+            
+            val registerMessage = SipMessageBuilder.buildRegisterMessage(
+                accountInfo = accountInfo,
+                callId = callId,
+                fromTag = fromTag,
+                isAppInBackground = isAccountInPush
+            )
+            
+            // Enviar mensaje
+            accountInfo.webSocketClient?.send(registerMessage)
+            
+            // Esperar respuesta (simplificado para el ejemplo)
+            delay(3000)
+            
+            // Verificar estado de registro
+            val registrationState = RegistrationStateManager.getAccountState(accountKey)
+            return registrationState == RegistrationState.OK
+            
+        } catch (e: Exception) {
+            log.e(tag = TAG) { "Error in registration: ${e.message}" }
+            false
+        }
+    }
+    
+    /**
+     * Notifica cambio de estado de red a listeners
+     */
+    private fun notifyNetworkStatusChanged(isConnected: Boolean) {
+        // Notificar a través del listener principal si es necesario
+        // Esto se puede expandir según las necesidades
+    }

@@ .. @@
     fun registerAccount(
         username: String,
         password: String,
         domain: String,
         pushToken: String?,
         pushProvider: String?
     ) {
         scope.launch {
             try {
                 val accountKey = "$username@$domain"
                 log.d(tag = TAG) { "Registering account: $accountKey" }
                 
                 // Crear AccountInfo
                 val accountInfo = AccountInfo(username, password, domain).apply {
                     userAgent = config.userAgent
                     token = pushToken ?: ""
                     provider = pushProvider ?: ""
                 }
                 
                 // Almacenar cuenta
                 accounts[accountKey] = accountInfo
                 
+                // Registrar cuenta en el servicio de reconexión de red
+                networkReconnectionService.registerAccount(accountInfo)
+                
                 // Actualizar estado inicial
                 RegistrationStateManager.updateAccountState(accountKey, RegistrationState.IN_PROGRESS)
                 
                 // Crear conexión WebSocket
                 val webSocket = createWebSocketConnection(accountInfo)
                 accountInfo.webSocketClient = webSocket
                 
                 // Conectar
                 webSocket.connect()
                 
             } catch (e: Exception) {
                 log.e(tag = TAG) { "Error registering account: ${e.message}" }
                 val accountKey = "$username@$domain"
                 RegistrationStateManager.updateAccountState(
                     accountKey, 
                     RegistrationState.FAILED, 
                     e.message
                 )
             }
         }
     }

@@ .. @@
     fun unregisterAccount(username: String, domain: String) {
         scope.launch {
             try {
                 val accountKey = "$username@$domain"
                 log.d(tag = TAG) { "Unregistering account: $accountKey" }
                 
                 val accountInfo = accounts[accountKey]
                 if (accountInfo != null) {
+                    // Desregistrar del servicio de reconexión
+                    networkReconnectionService.unregisterAccount(accountKey)
+                    
                     // Enviar UNREGISTER si está conectado
                     accountInfo.webSocketClient?.let { webSocket ->
                         if (webSocket.isConnected()) {
                             val unregisterMessage = SipMessageBuilder.buildUnregisterMessage(
                                 accountInfo,
                                 accountInfo.callId ?: generateId(),
                                 accountInfo.fromTag ?: generateId()
                             )
                             webSocket.send(unregisterMessage)
                         }
                         webSocket.close()
                     }
                     
                     // Remover cuenta
                     accounts.remove(accountKey)
                     RegistrationStateManager.removeAccount(accountKey)
                 }
                 
             } catch (e: Exception) {
                 log.e(tag = TAG) { "Error unregistering account: ${e.message}" }
             }
         }
     }

@@ .. @@
     fun unregisterAllAccounts() {
         scope.launch {
             try {
                 log.d(tag = TAG) { "Unregistering all accounts" }
                 
+                // Desregistrar todas las cuentas del servicio de reconexión
+                networkReconnectionService.unregisterAllAccounts()
+                
                 val accountKeys = accounts.keys.toList()
                 accountKeys.forEach { accountKey ->
                     val parts = accountKey.split("@")
                     if (parts.size == 2) {
                         unregisterAccount(parts[0], parts[1])
                     }
                 }
                 
                 accounts.clear()
                 RegistrationStateManager.clearAllStates()
                 
             } catch (e: Exception) {
                 log.e(tag = TAG) { "Error unregistering all accounts: ${e.message}" }
             }
         }
     }

@@ .. @@
         private fun handleWebSocketError(accountInfo: AccountInfo, error: Exception) {
             val accountKey = accountInfo.getAccountIdentity()
             log.e(tag = TAG) { "WebSocket error for $accountKey: ${error.message}" }
             
+            // Notificar al servicio de reconexión
+            networkReconnectionService.notifyWebSocketDisconnected(accountKey)
+            
             RegistrationStateManager.updateAccountState(
                 accountKey, 
                 RegistrationState.FAILED, 
                 "WebSocket error: ${error.message}"
             )
         }
         
         private fun handleWebSocketClose(accountInfo: AccountInfo, code: Int, reason: String) {
             val accountKey = accountInfo.getAccountIdentity()
             log.d(tag = TAG) { "WebSocket closed for $accountKey: $code - $reason" }
             
+            // Notificar al servicio de reconexión si no fue un cierre normal
+            if (code != 1000) { // 1000 = cierre normal
+                networkReconnectionService.notifyWebSocketDisconnected(accountKey)
+            }
+            
             RegistrationStateManager.updateAccountState(
                 accountKey, 
                 RegistrationState.FAILED, 
                 "WebSocket closed: $reason"
             )
         }

@@ .. @@
                     RegistrationState.FAILED -> {
                         log.e(tag = TAG) { "Registration failed for $accountKey: $statusReason" }
                         
+                        // Notificar fallo de registro al servicio de reconexión
+                        networkReconnectionService.notifyRegistrationFailed(accountKey, statusReason)
+                        
                         RegistrationStateManager.updateAccountState(
                             accountKey, 
                             RegistrationState.FAILED, 
                             statusReason
                         )
                     }

@@ .. @@
     fun dispose() {
         try {
             log.d(tag = TAG) { "Disposing SipCoreManager..." }
             
             // Detener todos los servicios
             unregisterAllAccounts()
             audioManager.stopAllRingtones()
             webRtcManager.dispose()
+            networkReconnectionService.dispose()
             
             // Limpiar estados
             CallStateManager.resetToIdle()
             RegistrationStateManager.clearAllStates()
             
             isInitialized = false
             log.d(tag = TAG) { "SipCoreManager disposed successfully" }
             
         } catch (e: Exception) {
             log.e(tag = TAG) { "Error disposing SipCoreManager: ${e.message}" }
         }
     }

@@ .. @@
     fun getDiagnosticInfo(): String {
         return buildString {
             appendLine("=== SIP CORE MANAGER DIAGNOSTIC ===")
             appendLine("Initialized: $isInitialized")
             appendLine("Accounts: ${accounts.size}")
             appendLine("WebRTC Initialized: ${webRtcManager.isInitialized()}")
             appendLine("Push Mode: ${pushModeManager.getCurrentMode()}")
             
             appendLine("\n${CallStateManager.getDiagnosticInfo()}")
             appendLine("\n${RegistrationStateManager.getDiagnosticInfo()}")
             appendLine("\n${pushModeManager.getDiagnosticInfo()}")
+            appendLine("\n${networkReconnectionService.getDiagnosticInfo()}")
             appendLine("\n${MultiCallManager.getDiagnosticInfo()}")
             appendLine("\n${audioManager.getDiagnosticInfo()}")
         }
     }